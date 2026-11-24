package opentrap.dashboard;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import opentrap.core.Alert;
import opentrap.core.HttpService;
import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.engine.FakeEngines;
import opentrap.engine.LivePassiveScanner;
import opentrap.engine.ScanConfig;
import opentrap.engine.ZapEngine;
import opentrap.engine.ZapScanner;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Store;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.StoreFixtures;

/**
 * A crawl, an audit or the live audit whose add-ons are not active says so
 * where it is started, from the store's record, and does not start. The record
 * is only trusted once an engine start has read the add-ons into it.
 */
public final class ScanAddOnCheckTest {

    private static final HttpService SVC = new HttpService("app.test", 80, false);
    private static final List<ZapScanner.Seed> SEEDS = List.of(new ZapScanner.Seed(SVC, "http://app.test/"));
    private static final ScanConfig NO_AJAX = withAjax(ScanConfig.crawlDefault(), false);
    private static final ScanConfig AJAX = withAjax(ScanConfig.crawlDefault(), true);
    private static final String ENGINE_READY = "Engine ready.";

    public void run() {
        Harness.suite("Scan add-on checks");

        Harness.test("a crawl with spider disabled is refused and its task names it", () ->
                StoreFixtures.started(ids(CoreAddOns.Feature.CRAWL), List.of("spider"), (store, engine) -> {
                    ScanTask task = scan(store, false, NO_AJAX);
                    Expect.isTrue(task.logLines().contains(
                            "Crawl needs the add-on(s) spider, which are not active. Enable them in Extensions > Installed."),
                            "the task names spider: " + task.logLines());
                    Expect.isFalse(logged(task, ENGINE_READY), "the crawl does not start: " + task.logLines());
                    Expect.equal(task.state(), ScanTask.State.FINISHED, "the task is finished");
                }));

        Harness.test("a crawl runs unchecked when the add-on store is not set up", () -> {
            ScanTask task = scan(null, true, AJAX);
            Expect.isTrue(logged(task, ENGINE_READY), "the crawl starts: " + task.logLines());
            Expect.isFalse(logged(task, "needs the add-on(s)"), "nothing is named missing: " + task.logLines());
        });

        Harness.test("a crawl and audit names the add-ons the audit is missing", () ->
                StoreFixtures.started(ids(CoreAddOns.Feature.CRAWL), List.of(), (store, engine) -> {
                    ScanTask task = scan(store, true, NO_AJAX);
                    Expect.isTrue(task.logLines().contains(
                            "Audit needs the add-on(s) ascanrules, oast, pscan, which are not active. Enable them in Extensions > Installed."),
                            "the task names the audit's add-ons: " + task.logLines());
                    Expect.isFalse(logged(task, "Crawl needs"), "the crawl has its add-ons: " + task.logLines());
                    Expect.isFalse(logged(task, ENGINE_READY), "the job does not start: " + task.logLines());
                }));

        Harness.test("a crawl without the AJAX crawl's add-ons runs without the AJAX crawl and says why", () ->
                StoreFixtures.started(ids(CoreAddOns.Feature.CRAWL), List.of(), (store, engine) -> {
                    ScanTask task = scan(store, false, AJAX);
                    Expect.isTrue(logged(task, "AJAX crawl needs the add-on(s) spiderAjax, selenium, which are not active."),
                            "the task names the AJAX crawl's add-ons: " + task.logLines());
                    Expect.isTrue(logged(task, ENGINE_READY), "the crawl itself starts: " + task.logLines());
                }));

        Harness.test("the live audit with pscan disabled says so and does not route through the engine", () ->
                StoreFixtures.started(ids(CoreAddOns.Feature.LIVE_AUDIT), List.of("pscan"), (store, engine) -> {
                    ZapEngine ze = FakeEngines.engine();
                    SuiteContext ctx = new SuiteContext();
                    LivePassiveScanner scanner = FakeEngines.liveAudit(ctx, ze,
                            () -> store.notActiveMessage(CoreAddOns.Feature.LIVE_AUDIT));
                    try {
                        scanner.setEnabled(true);
                        String msg = "Live audit needs the add-on(s) pscan, which are not active. "
                                + "Enable them in Extensions > Installed.";
                        await(() -> hasAlert(ctx, msg), () -> "the event log names pscan: " + ctx.alerts());
                        Expect.isFalse(scanner.isEnabled(), "the live audit is off");
                        Expect.isNull(scanner.routeFor(SVC), "traffic goes direct");
                        Expect.isFalse(hasAlert(ctx, "Live passive scanning is active"), "it is not reported active");
                    } finally {
                        scanner.setEnabled(false);
                        ze.shutdown();
                    }
                }));

        Harness.test("the live audit is checked again when it resumes after a restart", () ->
                StoreFixtures.started(ids(CoreAddOns.Feature.LIVE_AUDIT), List.of("pscan"), (store, engine) -> {
                    ZapEngine ze = FakeEngines.engine();
                    SuiteContext ctx = new SuiteContext();
                    AtomicReference<Store> seen = new AtomicReference<>();   // no store until the restart
                    LivePassiveScanner scanner = FakeEngines.liveAudit(ctx, ze, () -> {
                        Store s = seen.get();
                        return s == null ? null : s.notActiveMessage(CoreAddOns.Feature.LIVE_AUDIT);
                    });
                    try {
                        scanner.setEnabled(true);
                        await(() -> hasAlert(ctx, "Live passive scanning is active"), () -> "active: " + ctx.alerts());
                        seen.set(store);
                        ze.restart();
                        Expect.isTrue(hasAlert(ctx, "Live audit needs the add-on(s) pscan"),
                                "the event log names pscan: " + ctx.alerts());
                        Expect.isFalse(hasAlert(ctx, "Live audit resumed"), "it does not resume: " + ctx.alerts());
                        Expect.isNull(scanner.routeFor(SVC), "traffic goes direct");
                    } finally {
                        scanner.setEnabled(false);
                        ze.shutdown();
                    }
                }));

        Harness.test("a refused live audit shows paused on the dashboard with the reason", () -> {
            ZapEngine ze = FakeEngines.engine();
            SuiteContext ctx = new SuiteContext();
            String why = "Live audit needs the add-on(s) pscan, which are not active. "
                    + "Enable them in Extensions > Installed.";
            LivePassiveScanner scanner = FakeEngines.liveAudit(ctx, ze, () -> why);
            ScanTask task = new ScanTask(ScanTask.Type.LIVE_PASSIVE, "Live audit from Proxy (all traffic)", "test");
            ctx.addTask(task);
            DashboardTool.followRefusals(ctx, scanner, task);
            try {
                scanner.setEnabled(true);
                await(() -> task.state() == ScanTask.State.PAUSED, () -> "the task is paused: " + task.stateLabel());
                Expect.isTrue(task.logLines().contains(why), "the task says why: " + task.logLines());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                scanner.setEnabled(false);
                ze.shutdown();
            }
        });

        Harness.test("a crawl stopped while the engine starts does not crawl", () -> {
            ScanTask task = new ScanTask(ScanTask.Type.LIVE_ACTIVE, "Crawl of app.test", "Crawl - Fast");
            task.cancel();   // as an engine restart does while the crawl waits for the engine
            task.log("Stopped by engine restart");
            ScanTask done = scan(null, false, NO_AJAX, task);
            Expect.isFalse(logged(done, ENGINE_READY), "the crawl does not start: " + done.logLines());
            Expect.equal(done.logLines().get(done.logLines().size() - 1), "Stopped by engine restart",
                    "the reason stays the last line");
            Expect.equal(done.state(), ScanTask.State.FINISHED, "the task is finished");
        });
    }

    /** Runs a crawl job, as its thread does, on a fresh fake engine; returns its task. */
    private static ScanTask scan(Store store, boolean thenAudit, ScanConfig cfg) {
        return scan(store, thenAudit, cfg,
                new ScanTask(ScanTask.Type.LIVE_ACTIVE, "Crawl of app.test", "Crawl - " + cfg.name()));
    }

    private static ScanTask scan(Store store, boolean thenAudit, ScanConfig cfg, ScanTask task) {
        ZapEngine ze = FakeEngines.engine();
        SuiteContext ctx = new SuiteContext();
        ctx.addTask(task);
        try {
            DashboardTool.runScan(ctx, ze, store, SEEDS, thenAudit, cfg, 2, task);
        } finally {
            ze.shutdown();
        }
        return task;
    }

    private static List<String> ids(CoreAddOns.Feature f) { return List.copyOf(CoreAddOns.needs(f)); }

    private static boolean logged(ScanTask t, String start) {
        for (String l : t.logLines()) if (l.startsWith(start)) return true;
        return false;
    }

    private static boolean hasAlert(SuiteContext ctx, String start) {
        for (Alert a : ctx.alerts()) if (a.message.startsWith(start)) return true;
        return false;
    }

    private static ScanConfig withAjax(ScanConfig c, boolean ajax) {
        return new ScanConfig(c.name(), c.crawlDepth(), c.crawlDurationMin(), c.submitForms(), ajax,
                c.ajaxMaxDurationMin(), c.ajaxMaxDepth(), c.ajaxMaxStates(), c.attackStrength(), c.alertThreshold(),
                c.injectableMask(), c.rpcMask(), c.delayMs(), c.scanDurationMin());
    }

    interface Condition { boolean holds(); }

    /** Wait, up to a deadline, for {@code c} to hold. */
    private static void await(Condition c, Supplier<String> what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!c.holds() && System.currentTimeMillis() < deadline) Thread.sleep(2);
        Expect.isTrue(c.holds(), what.get());
    }
}
