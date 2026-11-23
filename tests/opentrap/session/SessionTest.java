package opentrap.session;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.LogEntry;
import opentrap.core.SaveFilter;
import opentrap.core.Scope;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the session-handling engine headless: the cookie jar's parsing and
 * matching, value extraction, rule scoping and triggers, and the manager's
 * learn-cookies / re-authenticate-and-retry decision.
 */
public final class SessionTest {

    private static HttpMessage resp(String raw) { return new HttpMessage(Bytes.bytes(raw.replace("\n", "\r\n"))); }
    private static HttpMessage get(String path, String host) {
        return new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n"));
    }

    private static final HttpService A = new HttpService("a.test", 443, true);
    private static final HttpService B = new HttpService("b.test", 443, true);

    private static LogEntry entry(HttpService svc, String path) {
        LogEntry e = new LogEntry("proxy", svc, get(path, svc.host()));
        e.setResponse(resp("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
        return e;
    }

    private static SaveFilter filterFor(HttpService svc, String path) {
        Scope scope = new Scope();
        scope.includeUrl(svc, path, path.equals("/"));
        return SaveFilter.inScope(scope);
    }

    private static List<String> names(List<Cookie> cookies) {
        List<String> out = new ArrayList<>();
        for (Cookie c : cookies) out.add(c.name + "@" + c.domain + c.path);
        return out;
    }

    private static Macro macro(String name, HttpService... targets) {
        Macro m = new Macro(name);
        for (HttpService t : targets) m.items.add(new Macro.Item(t, get("/login", t.host())));
        return m;
    }

    public void run() {
        Harness.suite("session handling");

        Harness.test("Set-Cookie headers are learned and offered back on matching requests", () -> {
            CookieJar jar = new CookieJar();
            HttpService svc = new HttpService("example.com", 443, true);
            jar.updateFrom(svc, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=abc; Path=/\n"
                    + "Set-Cookie: theme=dark; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 2, "cookies learned");
            String h = jar.headerFor(svc, get("/", "example.com"), null);
            Expect.isTrue(h.contains("sid=abc"), "sid offered");
            Expect.isTrue(h.contains("theme=dark"), "theme offered");
        });

        Harness.test("a cookie set without Domain goes back only to the host that set it", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=abc; Path=/\nContent-Length: 0\n\n"));
            Expect.notNull(jar.headerFor(A, get("/", "a.test"), null), "sent to a.test");
            Expect.isNull(jar.headerFor(new HttpService("x.a.test", 443, true), get("/", "x.a.test"), null),
                    "not sent to x.a.test");
        });

        Harness.test("a cookie set with Domain also goes to that domain's subdomains", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=abc; Domain=.a.test\nContent-Length: 0\n\n"));
            Expect.notNull(jar.headerFor(A, get("/", "a.test"), null), "sent to a.test");
            Expect.notNull(jar.headerFor(new HttpService("x.a.test", 443, true), get("/", "x.a.test"), null),
                    "sent to x.a.test");
        });

        Harness.test("an empty Domain attribute leaves the cookie host-only", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=abc; Domain=\nContent-Length: 0\n\n"));
            Expect.notNull(jar.headerFor(A, get("/", "a.test"), null), "sent to a.test");
            Expect.isNull(jar.headerFor(B, get("/", "b.test"), null), "not sent to b.test");
            Expect.isNull(jar.headerFor(new HttpService("x.a.test", 443, true), get("/", "x.a.test"), null),
                    "not sent to x.a.test");
        });

        Harness.test("secure cookies are withheld from plain http", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "abc", "example.com", "/", true, 0));
            Expect.isNull(jar.headerFor(new HttpService("example.com", 80, false), get("/", "example.com"), null),
                    "no cookie over http");
            Expect.notNull(jar.headerFor(new HttpService("example.com", 443, true), get("/", "example.com"), null),
                    "cookie over https");
        });

        Harness.test("path-scoped cookies only match under their path", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("adm", "1", "example.com", "/admin", false, 0));
            HttpService svc = new HttpService("example.com", 80, false);
            Expect.isNull(jar.headerFor(svc, get("/public", "example.com"), null), "not on /public");
            Expect.notNull(jar.headerFor(svc, get("/admin/panel", "example.com"), null), "on /admin/panel");
        });

        Harness.test("the jar wins over a cookie already on the request", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "fresh", "example.com", "/", false, 0));
            String h = jar.headerFor(new HttpService("example.com", 80, false), get("/", "example.com"), "sid=stale; other=keep");
            Expect.isTrue(h.contains("sid=fresh"), "jar value used");
            Expect.isTrue(h.contains("other=keep"), "unrelated cookie preserved");
        });

        Harness.test("extraction reads regex, header and cookie sources", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("auth", "tok123", "example.com", "/", false, 0));
            HttpMessage r = resp("HTTP/1.1 200 OK\nX-CSRF: hdrtok\nContent-Length: 34\n\n<input name=csrf value=\"body-tok\">");
            Expect.equal(new Extraction("c", Extraction.From.REGEX, "value=\"([^\"]+)\"").extract(r, jar), "body-tok", "regex group");
            Expect.equal(new Extraction("h", Extraction.From.HEADER, "X-CSRF").extract(r, jar), "hdrtok", "header");
            Expect.equal(new Extraction("k", Extraction.From.COOKIE, "auth").extract(r, jar), "tok123", "cookie");
        });

        Harness.test("rule scoping matches host globs and tools", () -> {
            SessionRule r = new SessionRule();
            r.hostPattern = "*.example.com";
            r.tools.add("repeater");
            Expect.isTrue(r.appliesTo("repeater", new HttpService("app.example.com", 443, true)), "subdomain + tool");
            Expect.isFalse(r.appliesTo("intruder", new HttpService("app.example.com", 443, true)), "wrong tool");
            Expect.isFalse(r.appliesTo("repeater", new HttpService("other.org", 443, true)), "wrong host");
        });

        Harness.test("status and content triggers fire on the right responses", () -> {
            SessionRule r = new SessionRule();
            r.macroName = "login";
            r.trigger = SessionRule.Trigger.STATUS;
            r.triggerValue = "401,403";
            Expect.isTrue(r.triggered(resp("HTTP/1.1 401 Unauthorized\nContent-Length: 0\n\n")), "401 fires");
            Expect.isFalse(r.triggered(resp("HTTP/1.1 200 OK\nContent-Length: 0\n\n")), "200 does not");
            r.trigger = SessionRule.Trigger.CONTAINS;
            r.triggerValue = "Please log in";
            Expect.isTrue(r.triggered(resp("HTTP/1.1 200 OK\nContent-Length: 11\n\nPlease log in")), "body text fires");
        });

        Harness.test("the manager learns cookies and asks for a retry only when a rule triggers", () -> {
            SessionManager sm = new SessionManager(null);
            SessionRule r = new SessionRule();
            r.name = "reauth";
            r.hostPattern = "example.com";
            r.macroName = "login";
            r.trigger = SessionRule.Trigger.STATUS;
            r.triggerValue = "401";
            sm.rules().add(r);
            sm.macros().add(new Macro("login")); // empty: re-run does no network in the test

            HttpService svc = new HttpService("example.com", 443, true);
            HttpMessage req = get("/", "example.com");
            boolean retry = sm.afterResponse("repeater", svc, req,
                    resp("HTTP/1.1 401 Unauthorized\nSet-Cookie: sid=learned; Path=/\nContent-Length: 0\n\n"));
            Expect.isTrue(retry, "401 asks for a retry");
            Expect.isTrue(sm.jar().size() >= 1, "cookie learned from the response");

            boolean noRetry = sm.afterResponse("repeater", svc, req, resp("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
            Expect.isFalse(noRetry, "200 does not retry");
        });

        Harness.test("beforeSend applies jar cookies when a rule governs the request", () -> {
            SessionManager sm = new SessionManager(null);
            SessionRule r = new SessionRule();
            r.hostPattern = "example.com";
            r.useCookieJar = true;
            sm.rules().add(r);
            sm.jar().set(new Cookie("sid", "abc", "example.com", "/", false, 0));

            HttpMessage req = get("/", "example.com");
            sm.beforeSend("repeater", new HttpService("example.com", 80, false), req);
            Expect.isTrue(req.header("Cookie") != null && req.header("Cookie").contains("sid=abc"), "cookie applied");

            HttpMessage untouched = get("/", "other.org");
            sm.beforeSend("repeater", new HttpService("other.org", 80, false), untouched);
            Expect.isNull(untouched.header("Cookie"), "no cookie for an unmatched host");
        });

        Harness.test("an in-scope snapshot keeps a cookie only when it would be sent with an in-scope request", () -> {
            SessionManager sm = new SessionManager(null);
            sm.jar().set(new Cookie("sid", "1", "a.test", "/", false, 0));
            sm.jar().set(new Cookie("sid", "2", "b.test", "/", false, 0));
            SessionManager.State s = sm.snapshot(filterFor(A, "/"), List.of(entry(A, "/"), entry(B, "/")));
            Expect.equal(names(s.jar), List.of("sid@a.test/"), "only the a.test cookie");
        });

        Harness.test("a parent-domain cookie sent to an in-scope host is kept", () -> {
            SessionManager sm = new SessionManager(null);
            sm.jar().set(new Cookie("sid", "1", "example.com", "/", false, 0));
            HttpService app = new HttpService("app.example.com", 443, true);
            SessionManager.State s = sm.snapshot(filterFor(app, "/"), List.of(entry(app, "/")));
            Expect.equal(names(s.jar), List.of("sid@example.com/"), "parent-domain cookie kept");
        });

        Harness.test("a cookie whose path never meets in-scope traffic is left out", () -> {
            SessionManager sm = new SessionManager(null);
            sm.jar().set(new Cookie("adm", "1", "a.test", "/admin", false, 0));
            sm.jar().set(new Cookie("sid", "2", "a.test", "/", false, 0));
            SessionManager.State s = sm.snapshot(filterFor(A, "/app"), List.of(entry(A, "/app/x")));
            Expect.equal(names(s.jar), List.of("sid@a.test/"), "only the root-path cookie");
        });

        Harness.test("a cookie with no domain is left out of an in-scope snapshot", () -> {
            SessionManager sm = new SessionManager(null);
            sm.jar().set(new Cookie("anon", "1", "", "/", false, 0));
            SessionManager.State s = sm.snapshot(filterFor(A, "/"), List.of(entry(A, "/")));
            Expect.equal(s.jar.size(), 0, "no cookies");
        });

        Harness.test("a macro is kept only when every request in it is aimed at the scope", () -> {
            SessionManager sm = new SessionManager(null);
            sm.macros().add(macro("in", A));
            sm.macros().add(macro("mixed", A, B));
            SessionRule r = new SessionRule();
            r.macroName = "mixed";
            sm.rules().add(r);
            SessionManager.State s = sm.snapshot(filterFor(A, "/"), List.of(entry(A, "/")));
            List<String> kept = new ArrayList<>();
            for (Macro m : s.macros) kept.add(m.name);
            Expect.equal(kept, List.of("in"), "only the macro aimed wholly at the scope");
            Expect.equal(s.rules.size(), 1, "session rules unchanged");
        });

        Harness.test("an unfiltered snapshot keeps the whole jar and every macro", () -> {
            SessionManager sm = new SessionManager(null);
            sm.jar().set(new Cookie("sid", "1", "b.test", "/", false, 0));
            sm.macros().add(macro("mixed", A, B));
            SessionManager.State s = sm.snapshot(SaveFilter.ALL, List.of());
            Expect.equal(s.jar.size(), 1, "cookie kept");
            Expect.equal(s.macros.size(), 1, "macro kept");
        });
    }
}
