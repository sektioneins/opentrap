package opentrap.core;

import opentrap.oast.Collaborator;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferencesFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Pins which settings travel with a project and which belong to the user: a
 * project key lives in memory and in the project file, a user key in the
 * preferences store, and opening a project never writes the user's store.
 */
public final class ProjectSettingsTest {

    private static final String RULE = "true\tHTTP\tevil.invalid\t8080\t\t\t\n";

    private static Preferences node() { return Preferences.userRoot().node("opentrap.v1"); }

    private static Map<String, String> hostile() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope.advanced", "true");
        m.put("upstream.proxies", RULE);
        m.put("oast.server", "https://example.invalid");
        m.put("oast.token", "evil");
        m.put("browser.relaxSecurity", "true");
        m.put("ui.theme", "dark");
        return m;
    }

    public void run() {
        Harness.suite("project settings");

        Harness.test("a project key set in a session never reaches the preferences store", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            s.set(ProjectOptions.LOG_OUT_OF_SCOPE, "false");
            Expect.isNull(node().get(ProjectOptions.LOG_OUT_OF_SCOPE, null), "stored value");
            Expect.equal(s.get(ProjectOptions.LOG_OUT_OF_SCOPE, "true"), "false", "value in the session");
        });

        Harness.test("a user key set in a session is still kept in the preferences store", () -> {
            MemoryPreferencesFactory.reset();
            new Settings().set("ui.zebra", "false");
            Expect.equal(new Settings().get("ui.zebra", "true"), "false", "value after a restart");
        });

        Harness.test("the WebSocket frame cap is 16 MB unless set", () -> {
            MemoryPreferencesFactory.reset();
            try { Expect.equal(ProjectOptions.wsFrameBytes(new Settings()), 16 << 20, "default cap"); }
            finally { node().remove(ProjectOptions.WS_FRAME_MB); }
        });

        Harness.test("the WebSocket frame cap stays between 1 and 256 MB", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            try {
                s.setInt(ProjectOptions.WS_FRAME_MB, 0);
                Expect.equal(ProjectOptions.wsFrameBytes(s), 1 << 20, "zero");
                s.setInt(ProjectOptions.WS_FRAME_MB, -5);
                Expect.equal(ProjectOptions.wsFrameBytes(s), 1 << 20, "negative");
                s.setInt(ProjectOptions.WS_FRAME_MB, 100000);
                Expect.equal(ProjectOptions.wsFrameBytes(s), 256 << 20, "huge");
                s.setInt(ProjectOptions.WS_FRAME_MB, 64);
                Expect.equal(ProjectOptions.wsFrameBytes(s), 64 << 20, "in range");
            } finally { node().remove(ProjectOptions.WS_FRAME_MB); }
        });

        Harness.test("the WebSocket frame cap is the user's, not the project's", () -> {
            MemoryPreferencesFactory.reset();
            try {
                Expect.isFalse(ProjectOptions.isProjectKey(ProjectOptions.WS_FRAME_MB), "project key");
                new Settings().setInt(ProjectOptions.WS_FRAME_MB, 32);
                Expect.equal(node().get(ProjectOptions.WS_FRAME_MB, null), "32", "stored value");
            } finally { node().remove(ProjectOptions.WS_FRAME_MB); }
        });

        Harness.test("the unpack limit is 16 MB unless set", () -> {
            MemoryPreferencesFactory.reset();
            try { Expect.equal(ProjectOptions.unpackBytes(new Settings()), 16 << 20, "default limit"); }
            finally { node().remove(ProjectOptions.UNPACK_MB); }
        });

        Harness.test("the unpack limit stays between 1 and 256 MB", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            try {
                s.setInt(ProjectOptions.UNPACK_MB, 0);
                Expect.equal(ProjectOptions.unpackBytes(s), 1 << 20, "zero");
                s.setInt(ProjectOptions.UNPACK_MB, -5);
                Expect.equal(ProjectOptions.unpackBytes(s), 1 << 20, "negative");
                s.setInt(ProjectOptions.UNPACK_MB, 100000);
                Expect.equal(ProjectOptions.unpackBytes(s), 256 << 20, "huge");
                s.setInt(ProjectOptions.UNPACK_MB, 64);
                Expect.equal(ProjectOptions.unpackBytes(s), 64 << 20, "in range");
            } finally { node().remove(ProjectOptions.UNPACK_MB); }
        });

        Harness.test("the unpack limit is the user's, not the project's", () -> {
            MemoryPreferencesFactory.reset();
            try {
                Expect.isFalse(ProjectOptions.isProjectKey(ProjectOptions.UNPACK_MB), "project key");
                new Settings().setInt(ProjectOptions.UNPACK_MB, 32);
                Expect.equal(node().get(ProjectOptions.UNPACK_MB, null), "32", "stored value");
            } finally { node().remove(ProjectOptions.UNPACK_MB); }
        });

        Harness.test("a project key an older build stored in the preferences store is not read back", () -> {
            MemoryPreferencesFactory.reset();
            node().put("proxy.logOutOfScope", "false");
            Expect.equal(new Settings().get("proxy.logOutOfScope", "true"), "true", "value");
        });

        Harness.test("a project key an older build left in the preferences store is not listed by keys", () -> {
            MemoryPreferencesFactory.reset();
            node().put("proxy.logOutOfScope", "false");
            node().put("ui.zebra", "false");
            Set<String> keys = new Settings().keys();
            Expect.isFalse(keys.contains("proxy.logOutOfScope"), "project key listed");
            Expect.isTrue(keys.contains("ui.zebra"), "user key listed");
        });

        Harness.test("a project snapshot holds the project's keys and none of the user's", () -> {
            MemoryPreferencesFactory.reset();
            node().put("upstream.proxies", RULE);
            node().put("oast.token", "tok");
            Settings s = new Settings();
            s.get("upstream.proxies", "");
            s.get("oast.token", "");
            s.set("scope.advanced", "true");
            Expect.equal(s.projectSnapshot(), Map.of("scope.advanced", "true"), "project snapshot");
        });

        Harness.test("opening a project sets its project keys and nothing else", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            s.restoreProject(hostile());
            Expect.equal(s.get("scope.advanced", "<sentinel>"), "true", "project key");
            for (String k : List.of("upstream.proxies", "oast.server", "oast.token", "browser.relaxSecurity", "ui.theme"))
                Expect.equal(s.get(k, "<sentinel>"), "<sentinel>", k);
            try { Expect.equal(node().keys().length, 0, "keys in the preferences store"); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("after a restart, a restored project has left the upstream proxies and the Collaborator server as they were", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            UpstreamProxyManager m = new UpstreamProxyManager(s);
            UpstreamProxy mine = new UpstreamProxy();
            mine.host = "mine.example"; mine.port = 3128;
            m.rules().add(mine);
            m.save();
            s.set(Collaborator.TOKEN_KEY, "mine");
            s.restoreProject(hostile());

            Settings s2 = new Settings();
            List<UpstreamProxy> rules = new UpstreamProxyManager(s2).rules();
            Expect.equal(rules.size(), 1, "rule count");
            Expect.equal(rules.get(0).host, "mine.example", "rule host");
            Expect.equal(rules.get(0).port, 3128, "rule port");
            Expect.equal(new Collaborator(s2).serverUrl(), Collaborator.DEFAULT_SERVER, "Collaborator server");
            Expect.equal(s2.get(Collaborator.TOKEN_KEY, ""), "mine", "Collaborator token");
        });

        Harness.test("a restored project key reaches the settings listeners", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            List<String> seen = new ArrayList<>();
            s.addListener(seen::add);
            s.restoreProject(Map.of(ProjectOptions.LOG_OUT_OF_SCOPE, "false"));
            Expect.isTrue(seen.contains("proxy.logOutOfScope"), "listener saw " + seen);
        });

        Harness.test("a project map entry that is not a string is skipped, and the rest restores", () -> {
            MemoryPreferencesFactory.reset();
            Settings s = new Settings();
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put("scope.advanced", 42);
            m.put(null, "x");
            m.put(7, "y");
            m.put("tasks.autostart", "pause");
            s.restoreProject(m);
            Expect.equal(s.get("tasks.autostart", "d"), "pause", "later key");
            Expect.equal(s.get("scope.advanced", "d"), "d", "key with a non-string value");
        });

        Harness.test("no project key routes traffic, names a server or holds a credential or a path", () -> {
            List<String> forbidden = List.of("upstream.", "oast.", "socks.", "auth.", "browser.", "tls.",
                "dns.overrides", "logging.", "rest.", "ext.", "backup.", "temp.", "perf.", "render.",
                "http.streaming.", "tasks.schedule", "updates.", "startup.");
            for (String k : ProjectOptions.KEYS)
                for (String f : forbidden)
                    Expect.isFalse(k.startsWith(f), k + " starts with " + f);
            Expect.equal(ProjectOptions.KEYS, Set.of(
                "project.keepAuditBodies", "project.bodyCapKb",
                "proxy.logOutOfScope", "proxy.outOfScopeChoice",
                "scope.advanced", "scope.outOfScope.mode", "scope.dropOutOfScope",
                "tasks.autostart",
                "timeout.connect", "timeout.read", "timeout.openEnded", "timeout.dns", "timeout.dnsFailed",
                "dns.mode",
                "http.redirect.3xx", "http.redirect.refreshHeader", "http.redirect.metaRefresh",
                "http.redirect.javascript", "http.redirect.anyLocation",
                "http.100.understand", "http.100.remove", "http.keepAlive", "http.http2", "http.unpackGzip"),
                "the project keys are exactly the reviewed list");
        });
    }
}
