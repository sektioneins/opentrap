package opentrap.extensions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import opentrap.core.Alert;
import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.engine.EngineState;
import opentrap.engine.FakeEngines;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Store;
import opentrap.extensions.store.Version;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.StoreFixtures;

/**
 * What the user does with the add-on store beyond its lists: restart the
 * engine, which stops the scans running on it, and be told which add-ons a
 * feature is missing.
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
                ScanTask crawl = task(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.RUNNING);
                ScanTask audit = task(ctx, ScanTask.Type.AUDIT, ScanTask.State.PAUSED);
                ScanTask done = task(ctx, ScanTask.Type.LIVE_ACTIVE, ScanTask.State.FINISHED);
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

        Harness.test("the shared store is null until it is set up", () -> {
            Expect.isNull(Store.sharedIfSet(), "no shared store in the tests");
        });
    }

    private static ScanTask task(SuiteContext ctx, ScanTask.Type type, ScanTask.State state) {
        ScanTask t = new ScanTask(type, type + " " + state, "test");
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
