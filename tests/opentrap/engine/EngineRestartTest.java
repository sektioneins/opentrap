package opentrap.engine;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
        volatile String refuse;   // a query part whose action calls the servers refuse

        FakeLauncher(int limit) { this.limit = limit; }

        @Override
        public Process launch(List<String> launch) throws IOException {
            if (servers.size() >= limit) throw new IOException("launch refused by the test");
            int at = launch.indexOf("-port");
            FakeServer s = FakeServer.start(refuse, Integer.parseInt(launch.get(at + 1)));
            servers.add(s);
            return new FakeProcess(s);
        }
    }

    /** Stands in for ZAP's API: answers the version, records every request. */
    static final class FakeServer {
        final HttpServer http;
        final String base;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile boolean alive = true;

        private FakeServer(HttpServer http, String refuse) {
            this.http = http;
            this.base = "http://127.0.0.1:" + http.getAddress().getPort();
            http.createContext("/", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String req = ex.getRequestURI().getPath() + "?" + (q == null ? "" : q);
                requests.add(req);
                String body;
                if (req.startsWith("/JSON/core/view/version/")) body = "{\"version\":\"0.0.0\"}";
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

        void stop() { alive = false; http.stop(0); }
    }

    /** The launched "process": alive until destroyed, which stops its server. */
    static final class FakeProcess extends Process {
        private final FakeServer server;

        FakeProcess(FakeServer server) { this.server = server; }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !server.alive; }
        @Override public int exitValue() {
            if (server.alive) throw new IllegalThreadStateException("running");
            return 0;
        }
        @Override public boolean isAlive() { return server.alive; }
        @Override public void destroy() { server.stop(); }
        @Override public Process destroyForcibly() { server.stop(); return this; }
    }
}
