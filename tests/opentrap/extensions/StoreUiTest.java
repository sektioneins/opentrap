package opentrap.extensions;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import opentrap.core.Alert;
import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.engine.EngineState;
import opentrap.engine.FakeEngines;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Entry;
import opentrap.extensions.store.Fetcher;
import opentrap.extensions.store.Installed;
import opentrap.extensions.store.Kind;
import opentrap.extensions.store.Source;
import opentrap.extensions.store.SourceList;
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
                    store.open(Version.parse("2.17.0"), engine);
                    StorePanel panel = onEdt(() -> new StorePanel(new SuiteContext(), store));
                    onEdt(() -> panel.model().getRowCount());
                    Expect.equal(requests.get(), 0, "no source is contacted by opening the tab");
                    store.refresh(() -> false);
                    Expect.equal(requests.get(), 1, "a refresh does contact the source");
                });
            } finally {
                https.stop(0);
            }
        });

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
    }

    private static final String IP = "127.0.0.1";

    /** A loopback HTTPS server that serves an empty feed and counts the requests it gets. */
    private static HttpsServer feedServer(TestTls.Pair tls, AtomicInteger requests) {
        try {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(IP, 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            https.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            https.createContext("/", ex -> {
                requests.incrementAndGet();
                byte[] b = ZapFixtures.feed().getBytes();
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            });
            https.start();
            return https;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
