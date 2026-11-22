package opentrap.core;

import opentrap.organizer.OrganizerItem;
import opentrap.proxy.HistoryFilter;
import opentrap.session.Cookie;
import opentrap.session.Extraction;
import opentrap.session.Macro;
import opentrap.session.SessionManager;
import opentrap.session.SessionRule;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The allowlist filter on project-file object streams (VULN-001): everything a
 * saved state carries reads back, and anything else, or anything oversized or
 * too deeply nested, is refused before it is built.
 */
public final class ProjectSerialTest {

    private static final HttpService SVC = new HttpService("example.com", 443, true);

    private static byte[] serialize(Object... objs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            for (Object o : objs) oos.writeObject(o);
        }
        return baos.toByteArray();
    }

    /** True when an InvalidClassException is thrown, or sits anywhere in the cause chain. */
    private static boolean refused(Callable<?> c) {
        try {
            c.call();
            return false;
        } catch (Throwable t) {
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof InvalidClassException) return true;
            }
            return false;
        }
    }

    private static Object tabState(byte[] req, byte[] resp) throws Exception {
        Class<?> k = Class.forName("opentrap.repeater.RepeaterTool$TabState");
        Constructor<?> ctor = k.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object t = ctor.newInstance();
        java.lang.reflect.Field f = k.getDeclaredField("req");
        f.setAccessible(true);
        f.set(t, req);
        f = k.getDeclaredField("resp");
        f.setAccessible(true);
        f.set(t, resp);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static <T> T next(ObjectInputStream in) throws Exception { return (T) in.readObject(); }

    public void run() {
        Harness.suite("project file deserialization filter");

        Harness.test("a snapshot holding every kind of saved state reads back through the filter", () -> {
            try {
                HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"));
                HttpMessage resp = new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nok"));

                Scope scope = new Scope();
                Scope.Rule rule = new Scope.Rule();
                rule.host = "example.com";
                scope.include().add(rule);

                LinkedHashMap<String, String> settings = new LinkedHashMap<>();
                settings.put("proxy.logOutOfScope", "true");

                SessionManager.State state = new SessionManager.State();
                state.jar = new ArrayList<>(List.of(new Cookie()));
                SessionRule sr = new SessionRule();
                sr.tools.add("repeater");
                sr.trigger = SessionRule.Trigger.values()[0];
                state.rules = new ArrayList<>(List.of(sr));
                Macro m = new Macro("login");
                m.items.add(new Macro.Item(SVC, req));
                m.extractions.add(new Extraction("tok", Extraction.From.values()[0], "x"));
                state.macros = new ArrayList<>(List.of(m));

                ArrayList<OrganizerItem> organizer = new ArrayList<>(List.of(new OrganizerItem(SVC, req, resp, "cap", "proxy")));

                Issue issue = Issue.builder("t").service(SVC).path("/a").tags("OWASP A01", "CWE-79").build();
                issue.addLocation("/b", "q");
                issue.addFoundBy("zap");
                ArrayList<Issue> issues = new ArrayList<>(List.of(issue));

                LinkedHashMap<String, java.io.Serializable> tools = new LinkedHashMap<>();
                tools.put("proxy", new HistoryFilter());
                tools.put("decoder", "text");
                tools.put("comparer", new ArrayList<>(List.of(new byte[] {1, 2})));
                tools.put("repeater", new ArrayList<>(List.of((java.io.Serializable) tabState(req.raw(), resp.raw()))));
                TreeMap<String, Object> ext = new TreeMap<>();
                ext.put("n", 1L);
                ext.put("b", true);
                tools.put("extension", ext);

                ScanTask.Snapshot snap = new ScanTask.Snapshot(1, ScanTask.Type.AUDIT, "t", "cfg", "scope",
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10L, new byte[] {1}, new CopyOnWriteArrayList<>(List.of("line")));
                ArrayList<ScanTask.Snapshot> tasks = new ArrayList<>(List.of(snap));

                byte[] bytes = serialize(scope, settings, state, organizer, issues, tools, tasks);
                try (ObjectInputStream in = ProjectSerial.recordReader(bytes)) {
                    Scope s2 = next(in);
                    Object settings2 = in.readObject();
                    SessionManager.State st2 = next(in);
                    in.readObject();
                    List<Issue> issues2 = next(in);
                    LinkedHashMap<String, ?> tools2 = next(in);
                    List<ScanTask.Snapshot> tasks2 = next(in);
                    Expect.equal(s2.include().size(), 1, "scope include rule survives");
                    Expect.equal(((LinkedHashMap<?, ?>) settings2).get("proxy.logOutOfScope"), "true", "settings survive");
                    Expect.equal(st2.macros.get(0).items.size(), 1, "macro item survives");
                    Expect.equal(st2.rules.get(0).tools, Set.of("repeater"), "rule tools survive");
                    Expect.equal(issues2.get(0).tags(), List.of("OWASP A01", "CWE-79"), "issue tags survive");
                    Expect.equal(new ArrayList<>(tools2.keySet()), List.of("proxy", "decoder", "comparer", "repeater", "extension"), "tool states survive in order");
                    Expect.equal(tasks2.get(0).log(), List.of("line"), "task log survives");
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a history record and a WebSocket record read back through the filter", () -> {
            try {
                HttpMessage req = new HttpMessage(Bytes.bytes("GET /p?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n"));
                LogEntry e = new LogEntry("proxy", SVC, req);
                e.setResponse(new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nok")));
                LogEntry back = (LogEntry) ProjectSerial.recordReader(e.indexBytes()).readObject();
                Expect.equal(back.pathAndQuery(), e.pathAndQuery(), "history entry path");

                WebSocketMessage w = new WebSocketMessage(1, "example.com", WebSocketMessage.Direction.values()[0],
                        WebSocketMessage.Op.TEXT, Bytes.bytes("hello"), false);
                WebSocketMessage wb = (WebSocketMessage) ProjectSerial.recordReader(w.indexBytes()).readObject();
                Expect.equal(wb.text(), w.text(), "websocket text");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a record holding a class the filter does not list is refused", () -> {
            try {
                byte[] s = serialize(new ArrayList<>(List.of(new java.awt.Point(1, 2))));
                Expect.isTrue(refused(() -> ProjectSerial.recordReader(s).readObject()), "Point is refused");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an array longer than its record is refused before it is allocated", () -> {
            try {
                byte[] s = serialize(new byte[] {1, 2, 3});
                ByteBuffer.wrap(s).putInt(s.length - 7, Integer.MAX_VALUE - 8);   // the array length field
                Expect.isTrue(refused(() -> ProjectSerial.recordReader(s).readObject()), "oversized array is refused");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("nesting deeper than any saved state is refused", () -> {
            try {
                Object o = new ArrayList<>();
                for (int i = 0; i < 50; i++) { ArrayList<Object> outer = new ArrayList<>(); outer.add(o); o = outer; }
                byte[] s = serialize(o);
                Expect.isTrue(refused(() -> ProjectSerial.recordReader(s).readObject()), "deep nesting is refused");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an old-format stream refuses a reference array larger than any history", () -> {
            try {
                byte[] s = serialize((Object) new Object[] {null});
                ByteBuffer.wrap(s).putInt(s.length - 5, ProjectSerial.LEGACY_MAX_REFERENCES + 1);
                Expect.isTrue(refused(() -> ProjectSerial.legacyReader(new ByteArrayInputStream(s)).readObject()), "huge reference array is refused");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an old-format stream refuses a wide primitive array larger than any history", () -> {
            try {
                byte[] s = serialize(new long[] {0});
                ByteBuffer.wrap(s).putInt(s.length - 12, ProjectSerial.LEGACY_MAX_REFERENCES + 1);   // the length, before the 8 data bytes
                Expect.isTrue(refused(() -> ProjectSerial.legacyReader(new ByteArrayInputStream(s)).readObject()), "huge long array is refused");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("every class the filter names exists", () -> {
            for (String entry : ProjectSerial.ALLOWED.split(";")) {
                if (entry.endsWith("*") || entry.startsWith("!")) continue;
                try {
                    Class.forName(entry, false, ProjectSerial.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new AssertionError("allowlist names a class that does not exist: " + entry);
                }
            }
        });
    }
}
