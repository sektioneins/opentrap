package opentrap.engine;

import opentrap.core.Alert;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Restarting the engine stops the running one and starts a fresh one without
 * ending the session: listeners hear every state, rule edits are sent to the
 * new engine, a start that fails is reported, and shutdown stays final.
 */
public final class EngineRestartTest {

    public void run() {
        Harness.suite("Scan engine restart");

        Harness.test("a restart reports stopped then running", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.addStateListener(seen::add);
                Expect.equal(engine.state(), EngineState.NOT_STARTED, "a new engine is not started");
                engine.start();
                String first = engine.base();
                engine.restart();
                Expect.equal(seen, List.of(EngineState.STARTING, EngineState.RUNNING, EngineState.STOPPED,
                        EngineState.STARTING, EngineState.RUNNING), "the states listeners heard");
                Expect.equal(engine.state(), EngineState.RUNNING, "the engine runs after the restart");
                Expect.isFalse(first.equals(engine.base()), "the restarted engine has a new address");
                Expect.isFalse(launcher.servers.get(0).alive, "the first engine was stopped");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a restart of an engine never started simply starts it", body(() -> {
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", new FakeLauncher(Integer.MAX_VALUE));
            try {
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.addStateListener(seen::add);
                engine.restart();
                Expect.equal(seen, List.of(EngineState.STARTING, EngineState.RUNNING), "the states listeners heard");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("rule edits made before a restart are sent again after it", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                engine.start();
                engine.ruleEdits().passiveEnabled("10010", false);
                engine.restart();
                Expect.equal(launcher.servers.size(), 2, "two engines were launched");
                Expect.isTrue(launcher.servers.get(1).saw("/JSON/pscan/action/disableScanners/", "ids=10010"),
                        "the new engine was told to disable rule 10010: " + launcher.servers.get(1).requests);
                Expect.isNull(engine.lastError(), "every edit was applied");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a rule the new engine refuses is named in the last error", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            launcher.refuse = "id=40012";
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                engine.start();
                engine.ruleEdits().activeThreshold("40012", "HIGH");
                engine.ruleEdits().passiveEnabled("10010", false);
                engine.restart();
                Expect.equal(engine.state(), EngineState.RUNNING, "a refused edit does not fail the start");
                Expect.notNull(engine.lastError(), "the refused edit is reported");
                Expect.isTrue(engine.lastError().contains("40012"), "the refused rule is named");
                Expect.isFalse(engine.lastError().contains("10010"), "an applied rule is not named");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("an engine that does not come back is reported as failed", body(() -> {
            FakeLauncher launcher = new FakeLauncher(1);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.start();
                engine.addStateListener(seen::add);
                Throwable failure = null;
                try { engine.restart(); } catch (Exception e) { failure = e; }
                Expect.notNull(failure, "restart() reports the failure");
                Expect.equal(engine.state(), EngineState.FAILED, "the engine is failed");
                Expect.equal(seen, List.of(EngineState.STOPPED, EngineState.STARTING, EngineState.FAILED),
                        "the states listeners heard");
                Expect.notNull(engine.lastError(), "the failure is kept");
                Expect.isTrue(engine.lastError().contains("launch refused"), "the failure says why");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a failed start whose engine answers later is reported running by the next start", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            launcher.nextExits = true;   // start-up gives up, yet the engine's API answers
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                Throwable failure = null;
                try { engine.start(); } catch (Exception e) { failure = e; }
                Expect.notNull(failure, "the first start fails");
                Expect.equal(engine.state(), EngineState.FAILED, "the engine is failed");
                List<EngineState> seen = new CopyOnWriteArrayList<>();
                engine.addStateListener(seen::add);
                engine.start();
                Expect.equal(seen, List.of(EngineState.RUNNING), "the states listeners heard");
                Expect.isNull(engine.lastError(), "the old failure is cleared");
                Expect.equal(launcher.servers.size(), 1, "no second engine was launched");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a start after a failed one cleans up the engine that never answered", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            launcher.nextExits = true;
            launcher.nextSilent = true;
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                try { engine.start(); } catch (Exception e) { /* the failure this case is about */ }
                Expect.equal(engine.state(), EngineState.FAILED, "the first start failed");
                List<String> errorsSeen = new CopyOnWriteArrayList<>();
                engine.addStateListener(s -> {
                    if (s == EngineState.STARTING) errorsSeen.add(String.valueOf(engine.lastError()));
                });
                engine.start();
                Expect.equal(engine.state(), EngineState.RUNNING, "the second start runs");
                Expect.isFalse(launcher.servers.get(0).alive, "the failed engine was stopped");
                Expect.isFalse(Files.exists(launcher.homes.get(0)), "the failed engine's temp home is gone");
                Expect.equal(errorsSeen, List.of("null"), "STARTING listeners do not see the old error");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("shutdown stays final after a restart", body(() -> {
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", new FakeLauncher(Integer.MAX_VALUE));
            engine.start();
            engine.restart();
            engine.shutdown();
            Throwable failure = null;
            try { engine.start(); } catch (Exception e) { failure = e; }
            Expect.notNull(failure, "start() after shutdown() throws");
            failure = null;
            try { engine.restart(); } catch (Exception e) { failure = e; }
            Expect.notNull(failure, "restart() after shutdown() throws");
        }));

        Harness.test("an engine with no start hook starts with today's arguments", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            try {
                engine.start();
                List<String> cmd = launcher.launches.get(0);
                Expect.equal(cmd.get(cmd.indexOf("-jar") + 1), "zap-0.0.0.jar", "the jar is run where it is");
                Expect.isFalse(cmd.contains("-installdir"), "no install folder is named: " + cmd);
                Expect.isFalse(cmd.stream().anyMatch(a -> a.startsWith("start.addonDirs=")), "no add-on folder is named");
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("a start hook that fails still lets the engine start", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            ZapEngine.setStartHook((install, version) -> { throw new IOException("the store is broken"); });
            try {
                engine.start();
                Expect.equal(engine.state(), EngineState.RUNNING, "the engine runs");
                List<String> cmd = launcher.launches.get(0);
                Expect.equal(cmd.get(cmd.indexOf("-jar") + 1), "zap-0.0.0.jar", "the jar is run where it is");
                Expect.isFalse(cmd.contains("-installdir"), "no install folder is named: " + cmd);
            } finally {
                ZapEngine.setStartHook(null);
                engine.shutdown();
            }
        }));

        Harness.test("a restart asks the start hook again", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            List<String> asked = new CopyOnWriteArrayList<>();
            ZapEngine.setStartHook((install, version) -> {
                asked.add(version);
                return List.of("-config", "start.addonDirs=/store/enabled-" + asked.size());
            });
            try {
                engine.start();
                engine.restart();
                Expect.equal(asked, List.of("0.0.0", "0.0.0"), "the hook was asked for each start");
                Expect.isTrue(launcher.launches.get(1).contains("start.addonDirs=/store/enabled-2"),
                        "the new engine got the second answer: " + launcher.launches.get(1));
            } finally {
                ZapEngine.setStartHook(null);
                engine.shutdown();
            }
        }));

        Harness.test("a start hook's install folder is where the engine jar is run from", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            List<String> asked = new CopyOnWriteArrayList<>();
            ZapEngine.setStartHook((install, version) -> {
                asked.add(install + " " + version);
                return List.of("-installdir", "/rt/abc", "-config", "start.addonDirs=/store/enabled");
            });
            try {
                engine.start();
                Expect.equal(asked, List.of(Path.of("zap-0.0.0.jar").toAbsolutePath().getParent() + " 0.0.0"),
                        "the hook is told the install and the ZAP version");
                List<String> cmd = launcher.launches.get(0);
                Expect.equal(cmd.get(cmd.indexOf("-jar") + 1), Path.of("/rt/abc/zap-0.0.0.jar").toString(),
                        "the jar is run from the install folder");
                int dir = cmd.indexOf("-dir");
                Expect.equal(cmd.subList(dir + 2, dir + 6),
                        List.of("-installdir", "/rt/abc", "-config", "start.addonDirs=/store/enabled"),
                        "the hook's arguments follow -dir");
            } finally {
                ZapEngine.setStartHook(null);
                engine.shutdown();
            }
        }));

        Harness.test("the live audit routes through the new engine after a restart", body(() -> {
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", new FakeLauncher(Integer.MAX_VALUE));
            SuiteContext ctx = new SuiteContext();
            LivePassiveScanner scanner = new LivePassiveScanner(ctx, engine);
            try {
                scanner.setEnabled(true);
                awaitAlert(ctx, "Live passive scanning is active");
                Expect.equal(scanner.routeFor(SVC).port, port(engine), "the live audit routes through the engine");
                engine.restart();
                UpstreamProxy p = scanner.routeFor(SVC);
                Expect.notNull(p, "the live audit still routes after the restart");
                Expect.equal(p.port, port(engine), "through the restarted engine's port");
                Expect.isTrue(hasAlert(ctx, "Live audit paused: the engine is restarting"), "the pause is logged: " + ctx.alerts());
                Expect.isTrue(hasAlert(ctx, "Live audit resumed"), "the resume is logged: " + ctx.alerts());
            } finally {
                scanner.setEnabled(false);
                engine.shutdown();
            }
        }));

        Harness.test("the live audit stops routing when the engine fails", body(() -> {
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", new FakeLauncher(1));
            SuiteContext ctx = new SuiteContext();
            LivePassiveScanner scanner = new LivePassiveScanner(ctx, engine);
            try {
                scanner.setEnabled(true);
                awaitAlert(ctx, "Live passive scanning is active");
                Expect.notNull(scanner.routeFor(SVC), "the live audit routes through the engine");
                try { engine.restart(); } catch (Exception e) { /* the failure this case is about */ }
                Expect.isNull(scanner.routeFor(SVC), "traffic goes direct, not to the dead port");
                Expect.isTrue(hasAlert(ctx, "Live audit paused: the engine failed: launch refused by the test"),
                        "the failure is logged: " + ctx.alerts());
            } finally {
                scanner.setEnabled(false);
                engine.shutdown();
            }
        }));

        Harness.test("the live audit polls again after it is switched off and on", body(() -> {
            FakeLauncher launcher = new FakeLauncher(Integer.MAX_VALUE);
            CountDownLatch answer = new CountDownLatch(1);
            launcher.nextHold = answer;   // the engine's start blocks until the test lets it answer
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", launcher);
            SuiteContext ctx = new SuiteContext();
            LivePassiveScanner scanner = new LivePassiveScanner(ctx, engine);
            try {
                scanner.setEnabled(true);
                long deadline = System.currentTimeMillis() + 2000;
                while (!engine.awaitingAnswer() && System.currentTimeMillis() < deadline) Thread.sleep(2);
                Expect.isTrue(engine.awaitingAnswer(), "the engine is starting");
                scanner.setEnabled(false);   // switched off while the engine starts
                answer.countDown();
                joinStartThreads();
                Expect.isFalse(hasAlert(ctx, "Live passive scanning is active"),
                        "a live audit switched off during the start does not report itself active: " + ctx.alerts());
                Expect.isFalse(scanner.polling(), "nor polls");

                scanner.setEnabled(true);
                awaitAlert(ctx, "Live passive scanning is active");
                awaitPolling(scanner);

                scanner.setEnabled(false);
                Expect.isFalse(scanner.polling(), "switching off stops the poller");
                scanner.setEnabled(true);
                awaitPolling(scanner);
            } finally {
                answer.countDown();
                scanner.setEnabled(false);
                engine.shutdown();
            }
        }));

        Harness.test("alert ids seen before a restart are mapped again after it", body(() -> {
            ZapEngine engine = ZapEngine.forLauncher("zap-0.0.0.jar", new FakeLauncher(Integer.MAX_VALUE));
            SuiteContext ctx = new SuiteContext();
            LivePassiveScanner scanner = new LivePassiveScanner(ctx, engine);
            try {
                engine.start();
                scanner.pollOnce();
                Expect.equal(ctx.issues().size(), 1, "the first engine's alert 1 is an issue");
                engine.restart();
                scanner.pollOnce();
                Expect.equal(ctx.issues().size(), 2, "the new engine's alert 1 is an issue too: " + ctx.issues());
            } finally {
                engine.shutdown();
            }
        }));

        Harness.test("rule edits keep the latest value per rule and setting, in first-edit order", body(() -> {
            FakeServer server = FakeServer.start(null);
            try {
                RuleEdits edits = new RuleEdits();
                edits.activeStrength("40012", "LOW");
                edits.passiveThreshold("10010", "LOW");
                edits.activeStrength("40012", "HIGH");
                edits.activeEnabled("40012", false);
                List<String> failed = edits.replay(new ZapApi(server.base, "key"));
                Expect.equal(failed, List.of(), "nothing failed");
                List<String> paths = new ArrayList<>();
                for (String r : server.requests) if (!r.contains("/core/")) paths.add(r);
                Expect.equal(paths.size(), 3, "one call per rule and setting: " + paths);
                Expect.isTrue(paths.get(0).contains("/JSON/ascan/action/setScannerAttackStrength/"), "first the strength");
                Expect.isTrue(paths.get(0).contains("attackStrength=HIGH"), "with its latest value");
                Expect.isTrue(paths.get(1).contains("/JSON/pscan/action/setScannerAlertThreshold/"), "then the passive threshold");
                Expect.isTrue(paths.get(2).contains("/JSON/ascan/action/disableScanners/"), "then the enabled flag");
            } finally {
                server.stop();
            }
        }));
    }

    private static final HttpService SVC = new HttpService("app.test", 80, false);

    private static int port(ZapEngine engine) { return URI.create(engine.base()).getPort(); }

    private static boolean hasAlert(SuiteContext ctx, String start) {
        for (Alert a : ctx.alerts()) if (a.message.startsWith(start)) return true;
        return false;
    }

    /** Wait, up to a deadline, for an event log entry starting with {@code start}. */
    private static void awaitAlert(SuiteContext ctx, String start) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!hasAlert(ctx, start) && System.currentTimeMillis() < deadline) Thread.sleep(2);
        Expect.isTrue(hasAlert(ctx, start), "the event log says \"" + start + "\": " + ctx.alerts());
    }

    /** Wait, up to a deadline, for the scanner's poller to run. */
    private static void awaitPolling(LivePassiveScanner scanner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!scanner.polling() && System.currentTimeMillis() < deadline) Thread.sleep(2);
        Expect.isTrue(scanner.polling(), "the live audit polls");
    }

    /** Wait for every live audit start thread to finish. */
    private static void joinStartThreads() throws InterruptedException {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("live-passive-start")) t.join(2000);
        }
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

    /** Launches a {@link FakeServer} on the command's {@code -port}; launches past {@code limit} throw. */
    static final class FakeLauncher implements ZapEngine.Launcher {
        final int limit;
        final List<FakeServer> servers = new CopyOnWriteArrayList<>();
        final List<Path> homes = new CopyOnWriteArrayList<>();   // each launch's -dir
        final List<List<String>> launches = new CopyOnWriteArrayList<>();   // each launch's command line
        volatile String refuse;       // a query part whose action calls the servers refuse
        volatile boolean nextExits;   // the next launched process reports it exited at once
        volatile boolean nextSilent;  // the next launched server does not answer the version
        volatile CountDownLatch nextHold;   // the next launched server answers the version once this opens

        FakeLauncher(int limit) { this.limit = limit; }

        @Override
        public Process launch(List<String> launch) throws IOException {
            if (servers.size() >= limit) throw new IOException("launch refused by the test");
            launches.add(List.copyOf(launch));
            int at = launch.indexOf("-port");
            FakeServer s = FakeServer.start(refuse, Integer.parseInt(launch.get(at + 1)));
            s.silent = nextSilent;
            s.hold = nextHold;
            homes.add(Path.of(launch.get(launch.indexOf("-dir") + 1)));
            servers.add(s);
            boolean exits = nextExits;
            nextExits = false;
            nextSilent = false;
            nextHold = null;
            return new FakeProcess(s, exits);
        }
    }

    /** Stands in for ZAP's API: answers the version, records every request. */
    static final class FakeServer {
        final HttpServer http;
        final String base;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile boolean alive = true;
        volatile boolean silent;   // answers the version without one, as an engine not yet up
        volatile CountDownLatch hold;   // when set, the version is answered only once it opens

        private FakeServer(HttpServer http, String refuse) {
            this.http = http;
            this.base = "http://127.0.0.1:" + http.getAddress().getPort();
            http.createContext("/", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String req = ex.getRequestURI().getPath() + "?" + (q == null ? "" : q);
                requests.add(req);
                String body;
                CountDownLatch h = hold;
                if (h != null && req.startsWith("/JSON/core/view/version/")) {
                    try { h.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (req.startsWith("/JSON/core/view/version/")) body = silent ? "{}" : "{\"version\":\"0.0.0\"}";
                // Every engine numbers its alerts from 1; the name tells the engines apart.
                else if (req.startsWith("/JSON/core/view/alerts/")) body = "{\"alerts\":[{\"id\":\"1\",\"name\":\"Alert of "
                        + base + "\",\"url\":\"http://app.test/\",\"risk\":\"Low\",\"confidence\":\"Medium\"}]}";
                else if (refuse != null && req.contains(refuse)) body = "{\"code\":\"does_not_exist\",\"message\":\"Does Not Exist\"}";
                else body = "{\"Result\":\"OK\"}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            });
            http.start();
        }

        static FakeServer start(String refuse) throws IOException { return start(refuse, 0); }

        static FakeServer start(String refuse, int port) throws IOException {
            return new FakeServer(HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0), refuse);
        }

        boolean saw(String path, String query) {
            for (String r : requests) if (r.startsWith(path + "?") && r.contains(query)) return true;
            return false;
        }

        synchronized void stop() {
            if (!alive) return;
            alive = false;
            http.stop(0);
        }
    }

    /**
     * The launched "process": alive until destroyed, which stops its server.
     * One that {@code exited} reports itself gone at once while its server
     * keeps running until it is destroyed, so a test can see the cleanup.
     */
    static final class FakeProcess extends Process {
        private final FakeServer server;
        private final boolean exited;

        FakeProcess(FakeServer server, boolean exited) { this.server = server; this.exited = exited; }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !isAlive(); }
        @Override public int exitValue() {
            if (isAlive()) throw new IllegalThreadStateException("running");
            return 0;
        }
        @Override public boolean isAlive() { return server.alive && !exited; }
        @Override public void destroy() { server.stop(); }
        @Override public Process destroyForcibly() { server.stop(); return this; }
    }
}
