package opentrap.extensions;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import opentrap.core.Alert;
import opentrap.core.ScanTask;
import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.engine.EngineState;
import opentrap.engine.FakeEngines;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Entry;
import opentrap.extensions.store.Fetcher;
import opentrap.extensions.store.Installed;
import opentrap.extensions.store.Kind;
import opentrap.extensions.store.Pending;
import opentrap.extensions.store.Source;
import opentrap.extensions.store.SourceList;
import opentrap.extensions.store.SourceStatus;
import opentrap.extensions.store.SourceType;
import opentrap.extensions.store.Status;
import opentrap.extensions.store.Version;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.StoreFixtures;
import opentrap.testkit.TestTls;
import opentrap.testkit.ZapFixtures;

/**
 * What the user does with the add-on store: the Store tab's rows and filters,
 * the strip of pending changes, restarting the engine, which stops the scans
 * running on it, and being told which add-ons a feature is missing.
 */
public final class StoreUiTest {

    private static final List<String> CRAWL = List.copyOf(CoreAddOns.needs(CoreAddOns.Feature.CRAWL));

    public void run() {
        Harness.suite("Extension store actions");

        Harness.test("the restart warning names a running crawl and the live audit", () -> {
            ScanTask crawl = new ScanTask(ScanTask.Type.LIVE_ACTIVE, "Crawl of app.test", "Crawl - Fast");
            String w = EngineRestart.warning(List.of(crawl), true);
            Expect.notNull(w, "there is a warning");
            Expect.isTrue(w.contains("Crawl of app.test"), "the crawl is named: " + w);
            Expect.isTrue(w.contains("Live audit"), "the live audit is named: " + w);
        });

        Harness.test("nothing running means no warning", () -> {
            Expect.isNull(EngineRestart.warning(List.of(), false), "no warning");
        });

        Harness.test("a restart stops running crawls and says why", body(() -> {
            ZapEngine engine = FakeEngines.engine();
            try {
                engine.start();
                SuiteContext ctx = new SuiteContext();
                ScanTask crawl = scan(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.RUNNING);
                ScanTask audit = scan(ctx, ScanTask.Type.AUDIT, ScanTask.State.PAUSED);
                ScanTask done = scan(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.FINISHED);
                ScanTask live = task(ctx, ScanTask.Type.LIVE_PASSIVE, ScanTask.State.RUNNING);
                List<ScanTask> changed = new CopyOnWriteArrayList<>();
                ctx.addTaskListener(changed::add);
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.addStateListener(seen::add);

                EngineRestart.run(ctx, engine);

                Expect.isTrue(crawl.cancelled(), "the running crawl is cancelled");
                Expect.equal(last(crawl), "Stopped by engine restart", "the crawl's log says why");
                Expect.isTrue(audit.cancelled(), "the paused audit is cancelled");
                Expect.equal(last(audit), "Stopped by engine restart", "the audit's log says why");
                Expect.isFalse(done.cancelled(), "a finished crawl is left alone");
                Expect.isFalse(live.cancelled(), "the live audit is only paused, by the engine");
                Expect.isTrue(changed.contains(crawl) && changed.contains(audit), "the task list hears both: " + changed);
                await(() -> seen.equals(List.of(EngineState.STOPPED, EngineState.STARTING, EngineState.RUNNING)),
                        () -> "the engine restarts off the calling thread: " + seen);
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a running live passive crawl is neither named nor stopped by a restart", body(() -> {
            ZapEngine engine = FakeEngines.engine();
            try {
                engine.start();
                SuiteContext ctx = new SuiteContext();
                ScanTask crawl = scan(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.RUNNING);
                // "New live task" with passive crawl: a LIVE_ACTIVE task over proxy traffic, not on the engine
                ScanTask passiveCrawl = task(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.RUNNING);
                Expect.equal(EngineRestart.stoppable(ctx), List.of(crawl), "only the engine's crawl would stop");
                String w = EngineRestart.warning(EngineRestart.stoppable(ctx), false);
                Expect.isFalse(w.contains(passiveCrawl.title()), "the live passive crawl is not named: " + w);
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.addStateListener(seen::add);

                EngineRestart.run(ctx, engine);

                Expect.isFalse(passiveCrawl.cancelled(), "the live passive crawl is not cancelled");
                Expect.equal(last(passiveCrawl), "Started", "nor told it stopped");
                Expect.equal(passiveCrawl.state(), ScanTask.State.RUNNING, "it goes on");
                await(() -> seen.contains(EngineState.RUNNING), () -> "the engine restarts: " + seen);
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a restart that fails is told in the event log", body(() -> {
            ZapEngine engine = FakeEngines.engine(1);
            try {
                engine.start();
                SuiteContext ctx = new SuiteContext();
                EngineRestart.run(ctx, engine);
                await(() -> hasAlert(ctx, "extensions", "The engine restart failed: "),
                        () -> "the failure is logged: " + ctx.alerts());
                Expect.isTrue(hasAlert(ctx, "extensions", "The engine restart failed: launch refused by the test"),
                        "and says why: " + ctx.alerts());
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a crawl with its add-on disabled says which one", () -> {
            Expect.equal(EngineRestart.missingMessage(CoreAddOns.Feature.CRAWL, List.of("spider")),
                    "Crawl needs the add-on(s) spider, which are not active. Enable them in Extensions > Installed.",
                    "the message");
        });

        Harness.test("several missing add-ons are named in the order the feature needs them", () -> {
            Expect.equal(EngineRestart.missingMessage(CoreAddOns.Feature.LIVE_AUDIT, List.of("pscan", "pscanrules")),
                    "Live audit needs the add-on(s) pscan, pscanrules, which are not active. Enable them in Extensions > Installed.",
                    "the message");
        });

        Harness.test("a feature is not checked before an engine start has read the add-ons", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    store.open(Version.parse("2.17.0"), engine);
                    Expect.isFalse(store.reflectsEngine(), "an opened store does not yet reflect the engine");
                    Expect.equal(store.notActive(CoreAddOns.Feature.CRAWL), List.of(),
                            "nothing is named missing from a record no engine start has read");
                }));

        Harness.test("after an engine start a disabled add-on is named for each feature that needs it", () ->
                StoreFixtures.started(CRAWL, List.of("spider"), (store, engine) -> {
                    Expect.isTrue(store.reflectsEngine(), "the record reflects the engine");
                    Expect.equal(store.notActive(CoreAddOns.Feature.CRAWL), List.of("spider"), "the crawl misses spider");
                    Expect.equal(store.notActive(CoreAddOns.Feature.AUDIT), List.of("ascanrules", "oast", "pscan"),
                            "the audit misses the add-ons not bundled");
                }));

        Harness.test("a store row offers the action that fits", () -> {
            Entry best = entry("zap", "foo", "2.0.0", "11");
            Expect.equal(StorePanel.rowAction(best, null, false), "Install", "not installed");
            Expect.equal(StorePanel.rowAction(best, held("zap", "foo", "1.0.0", "11"), false), "Update",
                    "an older version installed");
            Entry rebuilt = entry("src-1", "foo", "2.0.0", "22");
            Expect.equal(StorePanel.rowAction(rebuilt, held("src-1", "foo", "2.0.0", "11"), true), "New build",
                    "the same version with another sha256 from a local folder");
            Expect.equal(StorePanel.rowAction(rebuilt, held("zap", "foo", "1.0.0", "11"), false), "Switch source",
                    "installed from another source");
            Expect.equal(StorePanel.rowAction(best, held("bundled", "foo", "2.0.0", "11"), false), "Installed",
                    "current, bundled and ZAP's feed counting as one source");
        });

        Harness.test("the default status filter hides alpha add-ons", () ->
                StoreFixtures.inTemp(List.of("spider"), (store, engine) -> {
                    ZapFixtures.zap(engine.resolve("plugin"), "rough", "1.0.0", "alpha");
                    store.open(Version.parse("2.17.0"), engine);
                    Expect.equal(store.catalogue().ids().size(), 2, "both add-ons are offered");
                    StorePanel panel = onEdt(() -> new StorePanel(new SuiteContext(), store));
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), 1, "one row is listed");
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 0)), "Add-on spider",
                            "the release add-on is the one listed");
                }));

        Harness.test("the pending strip hides itself when nothing is pending", () ->
                StoreFixtures.started(CRAWL, List.of(), (store, engine) -> {
                    PendingStrip strip = onEdt(() -> new PendingStrip(new SuiteContext(), store));
                    Expect.isFalse(onEdt(strip::isVisible), "nothing pending, nothing shown");
                    store.disable("spider");
                    store.disable("network");
                    await(() -> onEdt(() -> strip.isVisible()
                                    && "2 changes take effect when the engine restarts".equals(strip.text())),
                            () -> "the strip counts both changes: " + onEdt(strip::text));
                }));

        Harness.test("opening the store tab makes no request", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicInteger requests = new AtomicInteger();
            HttpsServer https = feedServer(tls, requests);
            try {
                StoreFixtures.inTemp(CRAWL, new Fetcher(tls.client(), host -> null), (store, engine) -> {
                    Path root = engine.resolveSibling("addons");
                    SourceList sources = SourceList.load(root, "2.17");
                    sources.update(Source.zapFeed("2.17", false));
                    String base = "https://" + IP + ":" + https.getAddress().getPort() + "/";
                    sources.add(new Source("src-1", "Mirror", SourceType.FEED_URL, base + "feed.xml", base, true));
                    sources.save();
                    Expect.isFalse(store.isOpen(), "the store is not open before the tab");
                    StorePanel panel = onEdt(() -> new StorePanel(new SuiteContext(), store,
                            () -> new ZapEngine.Install(engine, "2.17.0")));
                    // the tab opens the store off the EDT, then lists the bundled add-ons
                    await(() -> store.isOpen() && onEdt(() -> panel.model().getRowCount()) == CRAWL.size(),
                            () -> "the tab opened the store and listed the bundled add-ons");
                    Expect.equal(requests.get(), 0, "no source is contacted by opening the tab");
                    store.refresh(() -> false);
                    Expect.equal(requests.get(), 1, "a refresh does contact the source");
                });
            } finally {
                https.stop(0);
            }
        });

        Harness.test("a local folder's rebuild of the installed version shows as a new build", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "commonlib", "");
                    store.refresh(() -> false);
                    StorePanel panel = panel(store);
                    Expect.equal(onEdt(() -> panel.actionOf("foo")), "New build", "the row's action");
                    Expect.equal(onEdt(() -> mark(panel, "Add-on foo")), "new build", "the row's mark");
                    Expect.isNull(onEdt(() -> panel.whyNot("foo")), "a local folder needs no network");
                }));

        Harness.test("an installed release with only a newer beta on its line shows as installed", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    Path old = ZapFixtures.zap(folder, "foo", "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Files.delete(old);
                    ZapFixtures.zap(folder, "foo", "2.0.0", "beta");
                    store.refresh(() -> false);
                    Expect.equal(store.updates(), List.of(), "the store counts no update");
                    StorePanel panel = panel(store);
                    Expect.equal(onEdt(() -> panel.actionOf("foo")), "Installed", "the row's action");
                    Expect.equal(onEdt(() -> mark(panel, "Add-on foo")), "", "no update mark");
                    Expect.equal(onEdt(panel::tabTitle), "Store", "no update in the tab title");
                }));

        Harness.test("an update from ZAP's feed shows on a bundled add-on's row and in the tab title", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    zapCache(engine, ZapFixtures.addon("spider", "2.0.0", "release", ZAP_URL + "spider.zap", HASH));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    StorePanel panel = panel(store);
                    Expect.equal(onEdt(() -> panel.actionOf("spider")), "Update", "the bundled spider's row");
                    Expect.equal(onEdt(() -> mark(panel, "Add-on spider")), "update", "the row's mark");
                    Expect.equal(onEdt(() -> panel.actionOf("network")), "Installed", "a bundled add-on without update");
                    Expect.equal(onEdt(panel::tabTitle), "Store (1)", "the tab title counts the update");
                }));

        Harness.test("an update queued already is not counted in the tab title", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    ZapFixtures.zap(folder, "foo", "2.0.0", "release");
                    store.refresh(() -> false);
                    StorePanel panel = panel(store);
                    Expect.equal(onEdt(panel::tabTitle), "Store (1)", "the update is counted");
                    // the store tells the panel before install returns, and the panel reloads on the EDT before this read
                    install(store, offer(store, "foo", "2.0.0"));
                    Expect.equal(onEdt(panel::tabTitle), "Store", "not once it is queued");
                }));

        Harness.test("an add-on offered by two sources comes from ZAP's feed unless installed from the other", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, true);
                    zapCache(engine, ZapFixtures.addon("retire", "1.0.0", "release", ZAP_URL + "retire.zap", HASH));
                    ZapFixtures.zap(folder, "retire", "2.0.0", "release");
                    ZapFixtures.zap(folder, "mine", "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    StorePanel panel = panel(store);
                    Expect.equal(onEdt(() -> panel.actionOf("retire")), "Install", "not installed");
                    Expect.equal(onEdt(() -> version(panel, "Add-on retire")), "1.0.0",
                            "offered from ZAP's feed, not the newer folder build");
                    onEdt(() -> { panel.showSource("src-1"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), 2, "the Source filter keeps the folder's two");
                    Expect.equal(onEdt(() -> version(panel, "Add-on retire")), "2.0.0", "the folder's retire");
                    onEdt(() -> { panel.showSource(null); return null; });
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), CRAWL.size() + 2, "every add-on again");
                    install(store, offer(store, "retire", "2.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    // the engine start tells its listeners on a thread of its own
                    await(() -> "Installed".equals(onEdt(() -> panel.actionOf("retire"))),
                            () -> "installed from the folder: " + onEdt(() -> panel.actionOf("retire")));
                    Expect.equal(onEdt(() -> version(panel, "Add-on retire")), "2.0.0", "offered from its own source");
                }));

        Harness.test("entries of a source that could not be reached cannot be installed", () ->
                StoreFixtures.inTemp(CRAWL, new Fetcher(defaultTls(), host -> null), (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "mine", "1.0.0", "release");
                    String base = "https://" + IP + ":" + closedPort() + "/";
                    SourceList sources = SourceList.load(engine.resolveSibling("addons"), "2.17");
                    sources.add(new Source("src-2", "Mirror", SourceType.FEED_URL, base + "feed.xml", base, true));
                    sources.save();
                    cache(engine, "src-2", base + "feed.xml", ZapFixtures.addon("far", "1.0.0", "release", base + "far.zap", HASH));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    StorePanel panel = panel(store);
                    Expect.isNull(onEdt(() -> panel.whyNot("far")), "the cached entry can be installed");
                    store.refresh(() -> false);
                    Expect.notNull(store.catalogue().status("src-2").error(), "the refresh failed");
                    await(() -> onEdt(() -> panel.whyNot("far")) != null, () -> "the row hears the refresh");
                    Expect.isTrue(onEdt(() -> panel.whyNot("far")).startsWith("could not reach Mirror"),
                            "its source is named: " + onEdt(() -> panel.whyNot("far")));
                    Expect.equal(onEdt(() -> panel.actionOf("far")), "Install", "still listed from the cache");
                    Expect.isNull(onEdt(() -> panel.whyNot("mine")), "a local folder's entry still installs");
                }));

        Harness.test("a source's status line says when it was fetched and why its last try failed", () -> {
            Instant now = Instant.parse("2026-11-24T12:00:00Z");
            Source feed = new Source("src-2", "Mirror", SourceType.FEED_URL, "https://a.example/f.xml",
                    "https://a.example/", true);
            Source folder = new Source("src-1", "Mine", SourceType.LOCAL_FOLDER, "/tmp/x", "", true);
            Expect.equal(StorePanel.statusText(feed, new SourceStatus(null, null, List.of()), now),
                    "not fetched yet", "never fetched");
            Expect.equal(StorePanel.statusText(feed, new SourceStatus(now.minusSeconds(7200), "connection refused",
                            List.of("x: bad hash", "y: bad id")), now),
                    "fetched 2 hours ago, last try failed: connection refused, 2 left out", "fetched, then failed");
            Expect.equal(StorePanel.statusText(folder, new SourceStatus(now.minusSeconds(20), null, List.of()), now),
                    "read just now", "a local folder is read");
        });

        Harness.test("update all leaves out add-ons with a change queued", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    for (String id : List.of("foo", "bar", "baz")) ZapFixtures.zap(folder, id, "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    for (String id : List.of("foo", "bar", "baz")) install(store, offer(store, id, "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    for (String id : List.of("foo", "bar", "baz")) ZapFixtures.zap(folder, id, "2.0.0", "release");
                    store.refresh(() -> false);
                    store.remove("foo");
                    install(store, offer(store, "bar", "2.0.0"));
                    StorePanel panel = panel(store);
                    StorePanel.UpdatePlan up = onEdt(panel::planUpdates);
                    Expect.equal(up.plan().stream().map(Entry::id).toList(), List.of("baz"), "only baz is updated");
                    Expect.equal(up.leftOut().size(), 2, "two are left out: " + up.leftOut());
                    Expect.isTrue(up.leftOut().get(0).startsWith("Add-on bar: already queued")
                            && up.leftOut().get(0).contains("update"), "bar's queued update: " + up.leftOut());
                    Expect.isTrue(up.leftOut().get(1).startsWith("Add-on foo: already queued")
                            && up.leftOut().get(1).contains("remove"), "foo's queued removal: " + up.leftOut());
                    Expect.equal(onEdt(() -> panel.actionOf("foo")), "Update", "foo still has its update");
                    Expect.isTrue(String.valueOf(onEdt(() -> panel.whyNot("foo"))).contains("remove"),
                            "but its button is off over the queued removal");
                    Expect.isNull(onEdt(() -> panel.whyNot("baz")), "baz's button is on");
                }));

        Harness.test("an install leaves out what it needs when that is queued already", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "lib", "1.0.0", "release");
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "lib", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "lib", "1.0.0"));
                    StorePanel panel = panel(store);
                    StorePanel.InstallPlan ip = onEdt(() -> {
                        try { return panel.planInstall(offer(store, "foo", "1.0.0")); }
                        catch (opentrap.extensions.store.Resolver.Unresolvable e) { throw new AssertionError(e); }
                    });
                    Expect.equal(ip.plan().stream().map(Entry::id).toList(), List.of("foo"), "only foo is downloaded");
                    Expect.equal(ip.queued(), List.of("Add-on lib (install)"), "lib is named as queued already");
                }));

        Harness.test("update all leaves out a dependency whose update is queued already", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "lib", "1.0.0", "release");
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "lib", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    ZapFixtures.zap(folder, "lib", "2.0.0", "release");
                    ZapFixtures.zap(folder, "foo", "2.0.0", "release", "lib", ">= 2.0.0");
                    store.refresh(() -> false);
                    install(store, offer(store, "lib", "2.0.0"));
                    StorePanel panel = panel(store);
                    StorePanel.UpdatePlan up = onEdt(panel::planUpdates);
                    Expect.equal(up.plan().stream().map(Entry::id).toList(), List.of("foo"),
                            "foo without lib, whose update is queued");
                }));

        Harness.test("an install enables what it needs that is disabled or queued to go", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "commonlib", "");
                    ZapFixtures.zap(folder, "bar", "1.0.0", "release", "network", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.disable("commonlib");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.remove("network");
                    StorePanel panel = panel(store);
                    for (String id : List.of("foo", "bar")) {
                        StorePanel.InstallPlan ip = onEdt(() -> {
                            try { return panel.planInstall(offer(store, id, "1.0.0")); }
                            catch (opentrap.extensions.store.Resolver.Unresolvable e) { throw new AssertionError(e); }
                        });
                        Expect.equal(ip.keepOn(), List.of(id.equals("foo") ? "commonlib" : "network"),
                                id + " names what it needs");
                        Expect.isTrue(onEdt(() -> panel.confirmText(ip)).contains("enabled at the next engine start as well: "
                                + ip.keepOn().get(0)), "the confirmation names it: " + onEdt(() -> panel.confirmText(ip)));
                        store.install(ip.plan(), (d, t) -> {}, () -> false);
                        ip.after(store).run();
                    }
                    Expect.equal(queued(store, Pending.Action.REMOVE), List.of(), "network's removal is dropped");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    for (String id : List.of("foo", "bar", "commonlib", "network")) {
                        Expect.isTrue(store.installed().stream().anyMatch(in -> in.id().equals(id) && in.active()),
                                id + " is active: " + store.installed());
                    }
                }));

        Harness.test("update all enables a disabled dependency it updates", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "lib", "1.0.0", "release");
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "lib", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.disable("lib");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    ZapFixtures.zap(folder, "lib", "2.0.0", "release");
                    ZapFixtures.zap(folder, "foo", "2.0.0", "release", "lib", ">= 2.0.0");
                    store.refresh(() -> false);
                    StorePanel panel = panel(store);
                    StorePanel.UpdatePlan up = onEdt(panel::planUpdates);
                    Expect.equal(up.keepOn(), List.of("lib"), "lib is named");
                    store.install(up.plan(), (d, t) -> {}, () -> false);
                    up.after(store).run();
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Expect.isTrue(store.installed().stream().anyMatch(in -> in.id().equals("lib") && in.active()
                            && in.version().toString().equals("2.0.0")), "lib 2.0.0 runs: " + store.installed());
                }));

        Harness.test("an engine start that cannot bring the record in line stops it being trusted", () ->
                StoreFixtures.started(CRAWL, List.of("spider"), (store, engine) -> {
                    Expect.isTrue(store.reflectsEngine(), "the first starts brought the record in line");
                    Files.writeString(engine.resolveSibling("addons").resolve("2.17").resolve("store"),
                            "installed.count=not a number\n");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Expect.notNull(store.lastStartError(), "the start failed to read the record");
                    Expect.isFalse(store.reflectsEngine(), "the record no longer reflects the engine");
                    Expect.equal(store.notActive(CoreAddOns.Feature.CRAWL), List.of(),
                            "nothing is named missing from the old record");
                }));

        Harness.test("a pending disable shows on its row", () -> {
            Expect.equal(InstalledPanel.stateText(held("bundled", "spider", "1.0.0", "11"),
                            new Pending(Pending.Action.DISABLE, "spider", null, null)),
                    "Disabled at next engine start", "the row's state");
        });

        Harness.test("disabling a core add-on names the features that stop", () -> {
            String w = InstalledPanel.disableWarning("spider", List.of(), List.of(CoreAddOns.Feature.CRAWL));
            Expect.isTrue(w.contains("Crawl"), "the crawl is named: " + w);
        });

        Harness.test("disabling an add-on names those that need it", () -> {
            String w = InstalledPanel.disableWarning("commonlib", List.of("retire"), List.of());
            Expect.isTrue(w.contains("retire"), "the dependent is named: " + w);
        });

        Harness.test("a row's state says what the engine runs and what is queued", () -> {
            Installed on = held("zap", "foo", "1.0.0", "11");
            Installed off = new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.0.0"), "foo-release-1.0.0.zap",
                    "zap", "zap", false, "11".repeat(32));
            Expect.equal(InstalledPanel.stateText(on, null), "Active", "active");
            Expect.equal(InstalledPanel.stateText(off, null), "Disabled", "disabled");
            Expect.equal(InstalledPanel.stateText(off, new Pending(Pending.Action.ENABLE, "foo", null, null)),
                    "Enabled at next engine start", "a queued enable");
            Expect.equal(InstalledPanel.stateText(on, new Pending(Pending.Action.REMOVE, "foo", null, null)),
                    "Removed at next engine start", "a queued removal");
            Expect.equal(InstalledPanel.stateText(on, new Pending(Pending.Action.UPDATE, "foo",
                            entry("zap", "foo", "2.0.0", "22"), "foo-release-2.0.0.zap")),
                    "Update to 2.0.0 at next engine start", "a queued update");
            Expect.equal(InstalledPanel.stateText(null, new Pending(Pending.Action.INSTALL, "foo",
                            entry("zap", "foo", "2.0.0", "22"), "foo-release-2.0.0.zap")),
                    "Install 2.0.0 at next engine start", "a queued install");
        });

        Harness.test("a queued change that failed at the engine start says why on its row", () -> {
            Expect.equal(InstalledPanel.stateText(held("zap", "foo", "1.0.0", "11"),
                            new Pending(Pending.Action.DISABLE, "foo", null, null), "disable: Permission denied"),
                    "Disabled at next engine start (failed: Permission denied)", "the row's state");
        });

        Harness.test("removing a core add-on warns with the features and asks a second time", () -> {
            String w = InstalledPanel.removeWarning("spider", List.of(), List.of(CoreAddOns.Feature.CRAWL));
            Expect.isTrue(w.startsWith("Remove spider"), "it is about removing: " + w);
            Expect.isTrue(w.contains("Crawl"), "the crawl is named: " + w);
            String again = InstalledPanel.stopsAgain("Remove", "spider",
                    List.of(CoreAddOns.Feature.CRAWL, CoreAddOns.Feature.AJAX_CRAWL));
            Expect.isTrue(again.contains("Crawl, AJAX crawl"), "the second question names both: " + again);
        });

        Harness.test("the dependents of an add-on are followed to those that need them in turn", () -> {
            java.util.Map<String, List<String>> needers = java.util.Map.of(
                    "commonlib", List.of("foo", "bar"), "foo", List.of("baz"), "baz", List.of("bar"));
            Expect.equal(InstalledPanel.closure("commonlib", id -> needers.getOrDefault(id, List.of())),
                    List.of("foo", "bar", "baz"), "each once, in the order found, without the add-on itself");
        });

        Harness.test("the previous ZAP version's add-ons are offered once for a new one", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path root = engine.resolveSibling("addons");
                    opentrap.extensions.store.StoreRecord old = opentrap.extensions.store.StoreRecord.load(
                            Files.createDirectories(root.resolve("2.16")));
                    old.installed().put("foo", held("src-1", "foo", "1.0.0", "11"));
                    old.installed().put("spider", held("bundled", "spider", "1.0.0", "11"));
                    old.save();
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Expect.equal(store.carryover().stream().map(Installed::id).toList(), List.of("foo"),
                            "the add-on not bundled is offered");
                    store.carryoverOffered();
                    Expect.equal(store.carryover(), List.of(), "not a second time");
                    opentrap.extensions.store.Store again = new opentrap.extensions.store.Store(root,
                            new Fetcher(null, host -> null));
                    again.open(Version.parse("2.17.0"), engine);
                    Expect.equal(again.carryover(), List.of(), "nor after OpenTRAP starts again");
                }));

        Harness.test("disabling a core add-on takes what needs it along after asking twice", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "commonlib", "");
                    ZapFixtures.zap(folder, "bar", "1.0.0", "release", "foo", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "bar", "1.0.0"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Answers answers = new Answers(true);
                    InstalledPanel panel = installed(store, answers);
                    onEdt(() -> { panel.disableAddOn("commonlib"); return null; });
                    await(() -> queued(store, Pending.Action.DISABLE).size() == 3,
                            () -> "three disables are queued: " + store.pending());
                    Expect.equal(queued(store, Pending.Action.DISABLE), List.of("foo", "bar", "commonlib"),
                            "what needs it first, then the add-on itself");
                    Expect.equal(answers.asked.size(), 2, "asked twice: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("foo, bar"), "the first names the dependents: "
                            + answers.asked.get(0));
                    Expect.isTrue(answers.asked.get(0).contains("Crawl"), "and the feature: " + answers.asked.get(0));
                    Expect.isTrue(answers.asked.get(1).contains("anyway"),
                            "the second asks again: " + answers.asked.get(1));
                    await(() -> "Disabled at next engine start".equals(onEdt(() -> panel.stateOf("Add-on foo"))),
                            () -> "the row shows its queued change: " + onEdt(() -> panel.stateOf("Add-on foo")));
                }));

        Harness.test("declining the second question about a feature queues nothing", () ->
                StoreFixtures.started(CRAWL, List.of(), (store, engine) -> {
                    Answers answers = new Answers(true, false);
                    InstalledPanel panel = installed(store, answers);
                    onEdt(() -> { panel.removeAddOn("spider"); return null; });
                    Expect.equal(answers.asked.size(), 2, "asked twice: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("bundled with ZAP"),
                            "removing a bundled add-on says it stays removed: " + answers.asked.get(0));
                    // a change queued after it would come after anything this click queued
                    store.disable("network");
                    await(() -> !store.pending().isEmpty(), () -> "the later change is queued");
                    Expect.equal(store.pending().stream().map(Pending::id).toList(), List.of("network"),
                            "spider's removal was not queued");
                }));

        Harness.test("removing a bundled add-on updated from ZAP's feed says it stays removed", () ->
                StoreFixtures.started(CRAWL, List.of(), (store, engine) -> {
                    Path minor = engine.resolveSibling("addons").resolve("2.17");
                    opentrap.extensions.store.StoreRecord rec = opentrap.extensions.store.StoreRecord.load(minor);
                    Installed spider = rec.installed().get("spider");
                    rec.installed().put("spider", new Installed(spider.kind(), "spider", spider.version(),
                            spider.file(), Source.ZAP_ID, Source.ZAP_ID, true, spider.sha256()));
                    rec.save();
                    store.open(Version.parse("2.17.0"), engine);
                    Answers answers = new Answers(false);
                    InstalledPanel panel = installed(store, answers);
                    onEdt(() -> { panel.removeAddOn("spider"); return null; });
                    Expect.isTrue(answers.asked.get(0).contains("bundled with ZAP and stays removed"),
                            "the warning: " + answers.asked.get(0));
                }));

        Harness.test("enabling an add-on enables what it needs and names what is not installed", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release", "commonlib", "", "absent", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.install(List.of(offer(store, "foo", "1.0.0")), (d, t) -> {}, () -> false);
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.disable("foo");
                    store.disable("commonlib");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Answers answers = new Answers(true);
                    InstalledPanel panel = installed(store, answers);
                    onEdt(() -> { panel.enableAddOn("foo"); return null; });
                    await(() -> queued(store, Pending.Action.ENABLE).size() == 2,
                            () -> "two enables are queued: " + store.pending());
                    Expect.equal(queued(store, Pending.Action.ENABLE), List.of("commonlib", "foo"),
                            "what it needs first");
                    Expect.equal(answers.asked.size(), 1, "asked once: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("enables what it needs: commonlib"),
                            "the need is named: " + answers.asked.get(0));
                    Expect.isTrue(answers.asked.get(0).contains("absent, which is not installed. Install it from Store"),
                            "the missing one is named: " + answers.asked.get(0));
                }));

        Harness.test("a bundled add-on whose engine file is gone is listed as missing", () ->
                StoreFixtures.started(CRAWL, List.of(), (store, engine) -> {
                    Files.delete(engine.resolve("plugin").resolve("spider-release-1.0.0.zap"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Expect.equal(store.missing(), List.of("spider"), "the store names it");
                    InstalledPanel panel = installed(store, new Answers());
                    Expect.equal(onEdt(() -> panel.stateOf("spider")), InstalledPanel.MISSING, "its row");
                    onEdt(() -> { panel.select("spider"); return null; });
                    Expect.equal(onEdt(panel::buttons), "----", "nothing to enable, disable, remove or undo");
                    onEdt(() -> { panel.filter("All", "Missing"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), 1, "the State filter finds it");
                }));

        Harness.test("a queued change that failed shows why on its row and can be undone", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    install(store, offer(store, "foo", "1.0.0"));
                    Files.delete(engine.resolveSibling("addons").resolve("2.17").resolve("staged")
                            .resolve("foo-release-1.0.0.zap"));
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    InstalledPanel panel = installed(store, new Answers());
                    String state = onEdt(() -> panel.stateOf("Add-on foo"));
                    Expect.equal(state, "Install 1.0.0 at next engine start"
                            + " (failed: Staged file foo-release-1.0.0.zap is missing)", "the row's state");
                    onEdt(() -> { panel.select("Add-on foo"); return null; });
                    Expect.equal(onEdt(panel::buttons), "---U", "only Undo");
                }));

        Harness.test("the Kind and State filters narrow the list", () ->
                StoreFixtures.started(CRAWL, List.of("spider"), (store, engine) -> {
                    store.disable("network");
                    InstalledPanel panel = installed(store, new Answers());
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), CRAWL.size(), "every add-on");
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 0)), "ZAP add-on", "the kind");
                    onEdt(() -> { panel.filter("All", "Disabled"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 1)), "Add-on spider", "the disabled one");
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), 1, "only it");
                    onEdt(() -> { panel.filter("All", "Pending"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 1)), "Add-on network", "the queued one");
                    onEdt(() -> { panel.filter(InstalledPanel.JAR_KIND, "All"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), 0, "no jar is loaded");
                    onEdt(() -> { panel.filter("ZAP add-on", "Active"); return null; });
                    Expect.equal(onEdt(() -> panel.model().getRowCount()), CRAWL.size() - 2, "the active ones");
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 5)), "core", "a crawl add-on is marked core");
                }));

        Harness.test("declining the carry-over is kept, so it is not asked again", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path root = engine.resolveSibling("addons");
                    opentrap.extensions.store.StoreRecord old = opentrap.extensions.store.StoreRecord.load(
                            Files.createDirectories(root.resolve("2.16")));
                    old.installed().put("foo", held("src-1", "foo", "1.0.0", "11"));
                    old.save();
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Answers answers = new Answers(false);
                    InstalledPanel panel = installed(store, answers);
                    onEdt(() -> { panel.offerCarryover(); return null; });
                    await(() -> {
                        try { return store.carryover().isEmpty(); }
                        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    }, () -> "the answer is kept");
                    Expect.equal(answers.asked.size(), 1, "asked once: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("foo"), "the add-on is named: " + answers.asked.get(0));
                    Expect.equal(store.pending(), List.of(), "nothing is queued");
                }));

        Harness.test("the carry-over takes each add-on from its own line and names what is not offered", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "foo", "2.0.0", "release");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Installed foo = new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.0.0"),
                            "foo-release-1.0.0.zap", "src-1", "Mine", false, "11".repeat(32));
                    Installed gone = held("src-1", "gone", "1.0.0", "11");
                    Installed elsewhere = held("src-9", "spider", "1.0.0", "11");
                    InstalledPanel.CarryPlan cp = InstalledPanel.carryPlan(store, List.of(foo, gone, elsewhere));
                    Expect.equal(cp.plan().stream().map(e -> e.id() + " " + e.version()).toList(), List.of("foo 2.0.0"),
                            "foo from its own folder");
                    Expect.equal(cp.keepOff(), List.of("foo"), "it stays disabled");
                    Expect.equal(cp.unavailable(), List.of("gone", "spider"),
                            "one nobody offers, one only another source offers");
                    String text = InstalledPanel.carryoverText(cp, "2.17");
                    Expect.isTrue(text.contains("Not available for ZAP 2.17:\n  gone\n  spider"), "the text: " + text);
                }));

        Harness.test("the carry-over keeps on what an active add-on needs", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = folderSource(engine, false);
                    ZapFixtures.zap(folder, "b", "1.0.0", "release");
                    ZapFixtures.zap(folder, "a", "1.0.0", "release", "b", "", "commonlib", "");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    store.disable("commonlib");
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    Installed a = held("src-1", "a", "0.9.0", "11");
                    Installed b = new Installed(Kind.ZAP_ADDON, "b", Version.parse("0.9.0"), "b-release-0.9.0.zap",
                            "src-1", "Mine", false, "11".repeat(32));
                    InstalledPanel.CarryPlan cp = InstalledPanel.carryPlan(store, List.of(a, b));
                    Expect.equal(cp.plan().stream().map(Entry::id).toList(), List.of("b", "a"), "both come");
                    Expect.equal(cp.keepOff(), List.of(), "b is not kept off: a needs it");
                    Expect.equal(cp.keepOn(), List.of("commonlib"), "the disabled bundled one a needs is named");
                    String text = InstalledPanel.carryoverText(cp, "2.17");
                    Expect.isTrue(text.contains("enabled at the next engine start as well: commonlib"), "the text: " + text);
                    store.install(cp.plan(), (d, t) -> {}, () -> false);
                    cp.after(store).run();
                    store.startHook().beforeLaunch(engine, "2.17.0");
                    for (String id : List.of("a", "b", "commonlib")) {
                        Expect.isTrue(store.installed().stream().anyMatch(in -> in.id().equals(id) && in.active()),
                                id + " is active: " + store.installed());
                    }
                }));

        Harness.test("a feed source must be HTTPS", () -> {
            refused(() -> SourcesPanel.fromForm("x", SourceType.FEED_URL, "http://a/x.xml", ""), "plain HTTP");
        });

        Harness.test("a feed source's prefix defaults to its host", () -> {
            Source s = SourcesPanel.fromForm("x", SourceType.FEED_URL, "https://a.example/f.xml", "");
            Expect.equal(s.prefix(), "https://a.example/", "scheme and host with a trailing slash");
            Expect.equal(SourcesPanel.fromForm("x", SourceType.FEED_URL, "https://a.example:8443/f.xml", "").prefix(),
                    "https://a.example:8443/", "a port stays with the host");
            Expect.equal(SourcesPanel.fromForm("x", SourceType.FEED_URL, "https://a.example/f.xml",
                    "https://cdn.example").prefix(), "https://cdn.example/", "a prefix without a path ends at the host");
        });

        Harness.test("a source form refuses an empty name, a missing folder and a prefix that is not HTTPS", () -> {
            refused(() -> SourcesPanel.fromForm(" ", SourceType.FEED_URL, "https://a.example/f.xml", ""), "no name");
            refused(() -> SourcesPanel.fromForm("x", SourceType.FEED_URL, "https://a.example/f.xml",
                            "http://a.example/"), "a plain HTTP prefix");
            refused(() -> SourcesPanel.fromForm("x", SourceType.FEED_URL, "https://a.example/f.xml", "https:///x/"),
                    "a prefix without a host");
            refused(() -> SourcesPanel.fromForm("x", SourceType.LOCAL_FOLDER, "relative/folder", ""),
                    "a relative folder path");
            refused(() -> SourcesPanel.fromForm("x", SourceType.LOCAL_FOLDER, "/no/such/folder/here", ""),
                    "a folder that is not there");
            refused(() -> SourcesPanel.fromForm("x", SourceType.ZAP_FEED, "https://a.example/f.xml", ""),
                    "a built-in type");
            String tmp = System.getProperty("java.io.tmpdir");
            Source folder = SourcesPanel.fromForm("Mine", SourceType.LOCAL_FOLDER, tmp, "https://ignored.example/");
            Expect.equal(folder.prefix(), "", "a local folder has no download prefix");
            Expect.isTrue(folder.enabled(), "a new source is on");
        });

        Harness.test("the trust question names the source", () -> {
            Source s = new Source("", "MyCorp mirror", SourceType.FEED_URL, "https://a.example/f.xml",
                    "https://a.example/", true);
            String t = SourcesPanel.trustText(s);
            Expect.isTrue(t.contains("MyCorp mirror"), "the name: " + t);
            Expect.isTrue(t.contains("run as code"), "what it means: " + t);
            Expect.isTrue(t.contains("https://a.example/"), "the download prefix is shown: " + t);
        });

        Harness.test("the startup update check is off by default", () -> {
            String key = CoreExtensionSettingsPanel.UPDATE_ON_STARTUP;
            Settings s = new Settings();
            try {
                s.remove(key);
                Expect.isFalse(CoreExtensionSettingsPanel.updateOnStartup(new Settings()), "never set");
                s.setBool(key, true);
                Expect.isTrue(CoreExtensionSettingsPanel.updateOnStartup(new Settings()), "an explicit choice is kept");
            } finally {
                s.remove(key);
            }
        });

        Harness.test("the startup check contacts the sources only when it is switched on", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicInteger requests = new AtomicInteger();
            HttpsServer https = feedServer(tls, requests);
            Settings s = new Settings();
            String key = CoreExtensionSettingsPanel.UPDATE_ON_STARTUP;
            try {
                StoreFixtures.inTemp(CRAWL, new Fetcher(tls.client(), host -> null), (store, engine) -> {
                    mirror(engine, https);
                    Supplier<ZapEngine.Install> install = () -> new ZapEngine.Install(engine, "2.17.0");
                    s.setBool(key, false);
                    Expect.isNull(ExtensionsTool.startupCheck(s, store, install), "switched off: no check runs");
                    Expect.isFalse(store.isOpen(), "nor is the store opened for it");
                    s.setBool(key, true);
                    Thread t = ExtensionsTool.startupCheck(s, store, install);
                    Expect.notNull(t, "switched on: the check runs");
                    t.join(2000);
                    Expect.isFalse(t.isAlive(), "the check finished");
                    Expect.isTrue(store.isOpen(), "it opened the store first");
                    Expect.equal(requests.get(), 1, "it fetched the enabled source");
                });
            } finally {
                s.remove(key);
                https.stop(0);
            }
        });

        Harness.test("the built-in ZAP feed can only be switched on or off", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    store.open(Version.parse("2.17.0"), engine);
                    SourceAnswers answers = new SourceAnswers().choose(0);
                    SourcesPanel panel = sources(store, answers);
                    Expect.equal(onEdt(() -> panel.model().getValueAt(0, 1)), "ZAP feed (built in)", "its type");
                    onEdt(() -> { panel.select(Source.ZAP_ID); return null; });
                    Expect.equal(onEdt(panel::buttons), "AE-", "it cannot be removed");
                    onEdt(() -> { panel.editSelected(); return null; });
                    Expect.equal(answers.forms.get(), 0, "no form to change it");
                    Expect.equal(answers.asked.size(), 1, "one question: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("built in"), "it says why: " + answers.asked.get(0));
                    await(() -> !store.catalogue().source(Source.ZAP_ID).orElseThrow().enabled(),
                            () -> "the store hears it switched off");
                    Expect.isFalse(SourceList.load(engine.resolveSibling("addons"), "2.17").all().get(0).enabled(),
                            "and it is saved");
                    onEdt(() -> { panel.removeSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "Remove asks nothing for it");
                    Expect.equal(store.catalogue().sources().size(), 1, "it stays");
                }));

        Harness.test("adding a source asks the trust question once and a decline adds nothing", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path folder = Files.createDirectories(engine.resolveSibling("mine"));
                    ZapFixtures.zap(folder, "foo", "1.0.0", "release");
                    store.open(Version.parse("2.17.0"), engine);
                    SourcesPanel.Form mine = new SourcesPanel.Form("Mine", SourceType.LOCAL_FOLDER, folder.toString(), "");
                    SourceAnswers answers = new SourceAnswers(
                            mine, new SourcesPanel.Form("", SourceType.LOCAL_FOLDER, folder.toString(), ""), mine)
                            .choose(1, 0);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.addSource(); return null; });
                    Expect.equal(answers.asked.size(), 1, "the trust question: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("Mine") && answers.asked.get(0).contains("run as code"),
                            "it names the source: " + answers.asked.get(0));
                    // the second add, whose form is wrong first, comes after anything the declined one did
                    onEdt(() -> { panel.addSource(); return null; });
                    Expect.equal(answers.told.size(), 1, "the wrong form is told: " + answers.told);
                    Expect.isTrue(answers.told.get(0).contains("name"), "with the reason: " + answers.told.get(0));
                    Expect.equal(answers.asked.size(), 2, "asked once for the second add: " + answers.asked);
                    await(() -> store.catalogue().sources().size() == 2, () -> "the store hears the added source");
                    Source added = store.catalogue().sources().get(1);
                    Expect.equal(added.id(), "src-1", "the declined add used no id");
                    Expect.equal(added.name(), "Mine", "the added source");
                    Expect.equal(SourceList.load(engine.resolveSibling("addons"), "2.17").all().size(), 2,
                            "only the accepted one is saved");
                    Expect.equal(store.catalogue().offers("foo").size(), 1, "its folder is read at once");
                }));

        Harness.test("a changed or switched source reaches the store without contacting it", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicInteger requests = new AtomicInteger();
            HttpsServer https = feedServer(tls, requests);
            try {
                StoreFixtures.inTemp(CRAWL, new Fetcher(tls.client(), host -> null), (store, engine) -> {
                    String base = mirror(engine, https);
                    cache(engine, "src-1", base + "feed.xml", ZapFixtures.addon("far", "1.0.0", "release", base + "far.zap", HASH));
                    store.open(Version.parse("2.17.0"), engine);
                    SourceAnswers answers = new SourceAnswers(
                            new SourcesPanel.Form("Mirror 2", SourceType.FEED_URL, base + "feed.xml", base));
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.editSelected(); return null; });
                    await(() -> "Mirror 2".equals(store.catalogue().source("src-1").orElseThrow().name()),
                            () -> "the store hears the new name");
                    Expect.equal(answers.asked.size(), 0, "a new name asks no trust question: " + answers.asked);
                    Expect.equal(store.catalogue().offers("far").size(), 1, "its list is kept from the cache");
                    onEdt(() -> { panel.setOn("src-1", false); return null; });
                    await(() -> store.catalogue().offers("far").isEmpty(), () -> "switched off, its entries go");
                    onEdt(() -> { panel.setOn("src-1", true); return null; });
                    await(() -> store.catalogue().offers("far").size() == 1, () -> "switched on, they are back");
                    await(() -> "Mirror 2".equals(onEdt(() -> panel.model().getValueAt(1, 0)))
                                    && Boolean.TRUE.equals(onEdt(() -> panel.model().getValueAt(1, 3))),
                            () -> "the row follows: " + onEdt(() -> panel.model().getValueAt(1, 0)));
                    Expect.equal(requests.get(), 0, "no change contacted the source");
                    store.refresh(() -> false);
                    Expect.equal(requests.get(), 1, "a refresh does");
                });
            } finally {
                https.stop(0);
            }
        });

        Harness.test("a feed moved to another URL asks again and lists nothing until it is refreshed", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicInteger requests = new AtomicInteger();
            HttpsServer https = feedServer(tls, requests);
            try {
                StoreFixtures.inTemp(CRAWL, new Fetcher(tls.client(), host -> null), (store, engine) -> {
                    String base = mirror(engine, https);
                    cache(engine, "src-1", base + "feed.xml", ZapFixtures.addon("far", "1.0.0", "release", base + "far.zap", HASH));
                    store.open(Version.parse("2.17.0"), engine);
                    SourceAnswers answers = new SourceAnswers(
                            new SourcesPanel.Form("Mirror", SourceType.FEED_URL, base + "other.xml", ""))
                            .choose(0);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.editSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "the trust question again: " + answers.asked);
                    await(() -> store.catalogue().source("src-1").orElseThrow().location().endsWith("other.xml"),
                            () -> "the store hears the new URL");
                    Expect.equal(store.catalogue().offers("far"), List.of(), "the old URL's list is not shown");
                    Expect.isFalse(Files.exists(engine.resolveSibling("addons").resolve("2.17").resolve("cache")
                            .resolve("src-1")), "nor kept");
                    Expect.equal(requests.get(), 0, "and nothing was fetched");
                });
            } finally {
                https.stop(0);
            }
        });

        Harness.test("removing a source can keep its installed add-ons", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    twoFolders(store, engine);
                    SourceAnswers answers = new SourceAnswers().choose(0);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    Expect.equal(onEdt(panel::buttons), "AER", "a user source can be removed");
                    onEdt(() -> { panel.removeSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "asked once: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("foo"), "its add-on is named: " + answers.asked.get(0));
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the removal ran to its end");
                    Expect.isTrue(store.catalogue().source("src-1").isEmpty(), "the store heard it removed");
                    Expect.equal(store.pending(), List.of(), "nothing of the source's was queued");
                    Expect.isTrue(store.installed().stream().anyMatch(i -> i.id().equals("foo")), "foo stays installed");
                }));

        Harness.test("removing a source with its add-ons queues their removal and what needs them", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    twoFolders(store, engine);
                    ZapFixtures.zap(engine.resolveSibling("mine"), "baz", "1.0.0", "release");
                    store.refresh(() -> false);
                    install(store, offer(store, "baz", "1.0.0"));
                    SourceAnswers answers = new SourceAnswers().choose(1);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.removeSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "asked once: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("bar"), "what needs foo is named: " + answers.asked.get(0));
                    Expect.isTrue(answers.asked.get(0).contains("baz (install 1.0.0)"),
                            "the queued install is named: " + answers.asked.get(0));
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the removal ran to its end");
                    Expect.equal(store.pending().stream().map(p -> p.action() + " " + p.id()).toList(),
                            List.of("REMOVE bar", "REMOVE foo"),
                            "what needs it first, then the source's own, and its queued install is dropped");
                    Expect.isTrue(store.catalogue().source("src-1").isEmpty(), "the source is gone");
                    Expect.isTrue(store.catalogue().source("src-2").isPresent(), "the other stays");
                }));

        Harness.test("removing a source with nothing installed from it drops what is queued from it", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    queuedInstall(store, engine);
                    SourceAnswers answers = new SourceAnswers().choose(0);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.removeSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "asked once: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("foo (install 1.0.0)"),
                            "the queued install is named: " + answers.asked.get(0));
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the removal ran to its end");
                    Expect.isTrue(store.catalogue().source("src-1").isEmpty(), "the source is gone");
                    Expect.equal(store.pending(), List.of(), "its queued install is dropped");
                    Expect.isFalse(Files.exists(staged(engine, "foo-release-1.0.0.zap")), "with its staged file");
                }));

        Harness.test("switching a source off drops what is queued from it after asking", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    queuedInstall(store, engine);
                    SourceAnswers answers = new SourceAnswers().choose(1, 0);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.setOn("src-1", false); return null; });
                    Expect.equal(answers.asked.size(), 1, "asked: " + answers.asked);
                    Expect.isTrue(answers.asked.get(0).contains("foo (install 1.0.0)"),
                            "the queued install is named: " + answers.asked.get(0));
                    Expect.equal(onEdt(() -> panel.model().getValueAt(1, 3)), true, "declined, it shows on");
                    onEdt(() -> { panel.setOn("src-1", false); return null; });
                    Expect.equal(answers.asked.size(), 2, "asked again: " + answers.asked);
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the switch ran to its end");
                    Expect.isFalse(store.catalogue().source("src-1").orElseThrow().enabled(), "it is off");
                    Expect.equal(store.pending(), List.of(), "its queued install is dropped");
                    Expect.equal(onEdt(panel::changesFinished), 1, "only the accepted switch ran a change");
                }));

        Harness.test("an edit made from a stale row is refused and the saved source is shown", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path mine = folderSource(engine, false);
                    Path theirs = Files.createDirectories(engine.resolveSibling("theirs"));
                    store.open(Version.parse("2.17.0"), engine);
                    // another OpenTRAP instance moves the source after this one listed it
                    Path root = engine.resolveSibling("addons");
                    SourceList other = SourceList.load(root, "2.17");
                    other.update(new Source("src-1", "Theirs", SourceType.LOCAL_FOLDER, theirs.toString(), "", true));
                    other.save();
                    SourceAnswers answers = new SourceAnswers(
                            new SourcesPanel.Form("Mine 2", SourceType.LOCAL_FOLDER, mine.toString(), ""));
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.editSelected(); return null; });
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the edit ran to its end");
                    await(() -> answers.told.size() == 1, () -> "the refusal is told: " + answers.told);
                    Expect.isTrue(answers.told.get(0).contains("changed elsewhere"), "why: " + answers.told.get(0));
                    Source saved = SourceList.load(root, "2.17").all().get(1);
                    Expect.equal(saved.name() + " " + saved.location(), "Theirs " + theirs, "the other change is kept");
                    Expect.equal(store.catalogue().source("src-1").orElseThrow().name(), "Theirs",
                            "and the list shows it");
                }));

        Harness.test("declining the trust question for an edit saves nothing", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path mine = folderSource(engine, false);
                    Path elsewhere = Files.createDirectories(engine.resolveSibling("elsewhere"));
                    store.open(Version.parse("2.17.0"), engine);
                    SourceAnswers answers = new SourceAnswers(
                            new SourcesPanel.Form("Mine", SourceType.LOCAL_FOLDER, elsewhere.toString(), ""))
                            .choose(1);
                    SourcesPanel panel = sources(store, answers);
                    onEdt(() -> { panel.select("src-1"); return null; });
                    onEdt(() -> { panel.editSelected(); return null; });
                    Expect.equal(answers.asked.size(), 1, "the trust question: " + answers.asked);
                    // a switch after it is the later event: had the edit run, two changes would finish
                    onEdt(() -> { panel.setOn("src-1", false); return null; });
                    await(() -> onEdt(panel::changesFinished) == 1, () -> "the switch ran to its end");
                    Source saved = SourceList.load(engine.resolveSibling("addons"), "2.17").all().get(1);
                    Expect.equal(saved.location(), mine.toString(), "the folder is unchanged");
                    Expect.isFalse(saved.enabled(), "only the switch was saved");
                }));

        Harness.test("a cached list fetched from another URL is not shown", () ->
                StoreFixtures.inTemp(CRAWL, (store, engine) -> {
                    Path root = engine.resolveSibling("addons");
                    String base = "https://mirror.example/";
                    SourceList sources = SourceList.load(root, "2.17");
                    sources.update(Source.zapFeed("2.17", false));
                    sources.add(new Source("src-1", "Mirror", SourceType.FEED_URL, base + "feed.xml", base, true));
                    sources.save();
                    cache(engine, "src-1", base + "feed.xml",
                            ZapFixtures.addon("far", "1.0.0", "release", base + "far.zap", HASH));
                    store.open(Version.parse("2.17.0"), engine);
                    Expect.equal(store.catalogue().offers("far").size(), 1, "the cache of its own URL is shown");
                    // another instance moves the feed, and this cache file stays behind
                    SourceList moved = SourceList.load(root, "2.17");
                    moved.update(new Source("src-1", "Mirror", SourceType.FEED_URL, base + "other.xml", base, true));
                    moved.save();
                    opentrap.extensions.store.Store again = new opentrap.extensions.store.Store(root,
                            new Fetcher(null, host -> null));
                    again.open(Version.parse("2.17.0"), engine);
                    Expect.equal(again.catalogue().offers("far"), List.of(), "the old URL's list is not shown");
                    Expect.isNull(again.catalogue().status("src-1").fetched(), "the moved feed is not fetched yet");
                }));

        Harness.test("a feed moved while a refresh fetches it keeps none of the old URL's list", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicReference<opentrap.extensions.store.Store> current = new AtomicReference<>();
            AtomicReference<String> baseOf = new AtomicReference<>();
            HttpsServer https = server(tls, ex -> {
                String base = baseOf.get();
                try {
                    // the move lands while the old URL's answer is on its way
                    Source was = current.get().catalogue().source("src-1").orElseThrow();
                    current.get().updateSource(was, new Source("src-1", "Mirror", SourceType.FEED_URL,
                            base + "other.xml", base, true));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                byte[] b = ZapFixtures.feed(ZapFixtures.addon("far", "1.0.0", "release", base + "far.zap", HASH))
                        .getBytes();
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            });
            try {
                StoreFixtures.inTemp(CRAWL, new Fetcher(tls.client(), host -> null), (store, engine) -> {
                    current.set(store);
                    baseOf.set(mirror(engine, https));
                    store.open(Version.parse("2.17.0"), engine);
                    store.refresh(() -> false);
                    Source now = store.catalogue().source("src-1").orElseThrow();
                    Expect.isTrue(now.location().endsWith("other.xml"), "the refresh kept the moved feed: " + now);
                    Expect.equal(store.catalogue().offers("far"), List.of(), "the old URL's list is not shown");
                    Expect.isFalse(Files.exists(engine.resolveSibling("addons").resolve("2.17").resolve("cache")
                            .resolve("src-1")), "nor cached");
                });
            } finally {
                https.stop(0);
            }
        });
    }

    /** Folder source "src-1" offering foo, whose install is queued and not applied yet. */
    private static void queuedInstall(opentrap.extensions.store.Store store, Path engine) throws Exception {
        Path folder = folderSource(engine, false);
        ZapFixtures.zap(folder, "foo", "1.0.0", "release");
        store.startHook().beforeLaunch(engine, "2.17.0");
        install(store, offer(store, "foo", "1.0.0"));
        Expect.equal(queued(store, Pending.Action.INSTALL), List.of("foo"), "foo's install is queued");
    }

    private static Path staged(Path engine, String file) {
        return engine.resolveSibling("addons").resolve("2.17").resolve("staged").resolve(file);
    }

    /** The answers a sources list gets, in turn, and what it asked and told; a form that runs out is Cancel. */
    private static final class SourceAnswers implements SourcesPanel.Dialogs {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<String> told = new CopyOnWriteArrayList<>();
        final AtomicInteger forms = new AtomicInteger();
        private final java.util.ArrayDeque<SourcesPanel.Form> nextForm = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<Integer> nextChoice = new java.util.ArrayDeque<>();
        SourceAnswers(SourcesPanel.Form... forms) { nextForm.addAll(List.of(forms)); }
        SourceAnswers choose(Integer... choices) { nextChoice.addAll(List.of(choices)); return this; }
        @Override public SourcesPanel.Form form(String title, SourcesPanel.Form start) {
            forms.incrementAndGet();
            return nextForm.poll();
        }
        @Override public int choose(String title, String text, String... options) {
            asked.add(text);
            Integer a = nextChoice.poll();
            return a == null ? -1 : a;
        }
        @Override public void tell(String title, String text) { told.add(text); }
    }

    /** Fails unless {@code r} throws an {@link IllegalArgumentException}. */
    private static void refused(Runnable r, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("not refused: " + what);
    }

    /** A sources list over an open store; it never looks for an engine. */
    private static SourcesPanel sources(opentrap.extensions.store.Store store, SourceAnswers answers) {
        return onEdt(() -> new SourcesPanel(store, () -> null, answers));
    }

    /** ZAP's feed off and a feed source "src-1" on {@code https}; returns the server's base URL. */
    private static String mirror(Path engine, HttpsServer https) throws java.io.IOException {
        SourceList sources = SourceList.load(engine.resolveSibling("addons"), "2.17");
        sources.update(Source.zapFeed("2.17", false));
        String base = "https://" + IP + ":" + https.getAddress().getPort() + "/";
        sources.add(new Source("src-1", "Mirror", SourceType.FEED_URL, base + "feed.xml", base, true));
        sources.save();
        return base;
    }

    /** Folder "src-1" with foo installed from it, and folder "src-2" with bar, which needs foo, installed. */
    private static void twoFolders(opentrap.extensions.store.Store store, Path engine) throws Exception {
        Path mine = folderSource(engine, false);
        Path other = Files.createDirectories(engine.resolveSibling("other"));
        SourceList sources = SourceList.load(engine.resolveSibling("addons"), "2.17");
        sources.add(new Source("src-2", "Other", SourceType.LOCAL_FOLDER, other.toString(), "", true));
        sources.save();
        ZapFixtures.zap(mine, "foo", "1.0.0", "release");
        ZapFixtures.zap(other, "bar", "1.0.0", "release", "foo", "");
        store.startHook().beforeLaunch(engine, "2.17.0");
        install(store, offer(store, "foo", "1.0.0"));
        store.startHook().beforeLaunch(engine, "2.17.0");
        install(store, offer(store, "bar", "1.0.0"));
        store.startHook().beforeLaunch(engine, "2.17.0");
    }

    /** The answers an Installed tab gets, in turn (yes when they run out), and what it asked and told. */
    private static final class Answers implements InstalledPanel.Dialogs {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<String> told = new CopyOnWriteArrayList<>();
        private final java.util.ArrayDeque<Boolean> next = new java.util.ArrayDeque<>();
        Answers(Boolean... answers) { next.addAll(List.of(answers)); }
        @Override public boolean confirm(String title, String text, String verb) {
            asked.add(text);
            Boolean a = next.poll();
            return a == null || a;
        }
        @Override public void tell(String title, String text) { told.add(text); }
    }

    /** An Installed tab over an open store; it never looks for an engine. */
    private static InstalledPanel installed(opentrap.extensions.store.Store store, Answers answers) {
        return onEdt(() -> new InstalledPanel(new SuiteContext(), store, () -> null, answers));
    }

    /** The ids queued for {@code action}, in queue order. */
    private static List<String> queued(opentrap.extensions.store.Store store, Pending.Action action) {
        return store.pending().stream().filter(p -> p.action() == action).map(Pending::id).toList();
    }

    private static final String IP = "127.0.0.1";

    /** A loopback HTTPS server that serves an empty feed and counts the requests it gets. */
    private static HttpsServer feedServer(TestTls.Pair tls, AtomicInteger requests) {
        return server(tls, ex -> {
            requests.incrementAndGet();
            byte[] b = ZapFixtures.feed().getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
    }

    /** A loopback HTTPS server answering every request with {@code handler}. */
    private static HttpsServer server(TestTls.Pair tls, com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(IP, 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            https.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            https.createContext("/", handler);
            https.start();
            return https;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final String ZAP_URL = "https://github.com/zaproxy/zap-extensions/releases/download/x/";
    private static final String HASH = "SHA-256:" + "a".repeat(64);

    /** A Store tab over an open store; it never looks for an engine. */
    private static StorePanel panel(opentrap.extensions.store.Store store) {
        return onEdt(() -> new StorePanel(new SuiteContext(), store, () -> null));
    }

    /** Adds a local folder source "src-1" next to the engine and returns its folder; ZAP's feed on or off. */
    private static Path folderSource(Path engine, boolean zapFeed) throws java.io.IOException {
        Path folder = Files.createDirectories(engine.resolveSibling("mine"));
        SourceList sources = SourceList.load(engine.resolveSibling("addons"), "2.17");
        sources.update(Source.zapFeed("2.17", zapFeed));
        sources.add(new Source("src-1", "Mine", SourceType.LOCAL_FOLDER, folder.toString(), "", true));
        sources.save();
        return folder;
    }

    /** ZAP's feed as if fetched just now, from the cache alone. */
    private static void zapCache(Path engine, String... addons) throws java.io.IOException {
        cache(engine, Source.ZAP_ID, Source.zapFeed("2.17", true).location(), addons);
    }

    /** {@code sourceId}'s cached list as if fetched just now from {@code url}. */
    private static void cache(Path engine, String sourceId, String url, String... addons) throws java.io.IOException {
        Path dir = Files.createDirectories(engine.resolveSibling("addons").resolve("2.17").resolve("cache"));
        Files.writeString(dir.resolve(sourceId), Instant.now() + "\n" + url + "\n" + ZapFixtures.feed(addons));
    }

    private static Entry offer(opentrap.extensions.store.Store store, String id, String version) {
        for (Entry e : store.catalogue().offers(id)) if (e.version().toString().equals(version)) return e;
        throw new AssertionError(id + " " + version + " is not offered");
    }

    private static void install(opentrap.extensions.store.Store store, Entry e) throws Exception {
        store.install(store.plan(e), (done, total) -> {}, () -> false);
    }

    /** The model's cell in column {@code col} of the row named {@code name}. */
    private static String cell(StorePanel panel, String name, int col) {
        javax.swing.table.TableModel m = panel.model();
        for (int r = 0; r < m.getRowCount(); r++) {
            if (name.equals(m.getValueAt(r, 0))) return String.valueOf(m.getValueAt(r, col));
        }
        throw new AssertionError(name + " is not listed");
    }

    private static String version(StorePanel panel, String name) { return cell(panel, name, 1); }

    private static String mark(StorePanel panel, String name) { return cell(panel, name, 3); }

    private static javax.net.ssl.SSLContext defaultTls() {
        try {
            return javax.net.ssl.SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A loopback port nothing listens on: bound once, then closed. */
    private static int closedPort() throws java.io.IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName(IP))) {
            return s.getLocalPort();
        }
    }

    private static Entry entry(String sourceId, String id, String version, String shaByte) {
        return new Entry(Kind.ZAP_ADDON, sourceId, id, "Add-on " + id, "", "", Version.parse(version),
                Status.RELEASE, "", "", "", 1, id + "-release-" + version + ".zap", "", shaByte.repeat(32),
                List.of());
    }

    private static Installed held(String sourceId, String id, String version, String shaByte) {
        return new Installed(Kind.ZAP_ADDON, id, Version.parse(version), id + "-release-" + version + ".zap",
                sourceId, sourceId, true, shaByte.repeat(32));
    }

    /** Runs {@code s} on the EDT and returns its result: Swing components are built and read there only. */
    private static <T> T onEdt(Supplier<T> s) {
        AtomicReference<T> out = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> out.set(s.get()));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return out.get();
    }

    /** A crawl or audit the engine runs, as the dashboard and the scanner make them. */
    private static ScanTask scan(SuiteContext ctx, ScanTask.Type type, ScanTask.State state) {
        ScanTask t = task(ctx, type, state);
        t.markEngineScan();
        return t;
    }

    private static ScanTask task(SuiteContext ctx, ScanTask.Type type, ScanTask.State state) {
        ScanTask t = new ScanTask(type, "test", "test");
        t.setTitle(type + " " + state + " #" + t.number);
        t.setState(state);
        t.log("Started");
        ctx.addTask(t);
        return t;
    }

    private static String last(ScanTask t) {
        List<String> log = t.logLines();
        return log.isEmpty() ? null : log.get(log.size() - 1);
    }

    private static boolean hasAlert(SuiteContext ctx, String tool, String start) {
        for (Alert a : ctx.alerts()) if (a.tool.equals(tool) && a.message.startsWith(start)) return true;
        return false;
    }

    interface Condition { boolean holds(); }

    /** Wait, up to a deadline, for {@code c} to hold. */
    private static void await(Condition c, Supplier<String> what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!c.holds() && System.currentTimeMillis() < deadline) Thread.sleep(2);
        Expect.isTrue(c.holds(), what.get());
    }

    interface Body { void run() throws Exception; }

    /** A case body that may throw; a checked exception fails the case. */
    private static Runnable body(Body b) {
        return () -> {
            try { b.run(); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        };
    }
}
