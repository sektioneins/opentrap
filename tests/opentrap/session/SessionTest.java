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

        Harness.test("a Domain the responding host does not fall under is not stored", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=b.test\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 0, "nothing stored");
            Expect.isNull(jar.headerFor(B, get("/", "b.test"), null), "not sent to b.test");
        });

        Harness.test("a Domain that is a public suffix is not stored", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("shop.example.co.uk", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=co.uk\nContent-Length: 0\n\n"));
            jar.updateFrom(new HttpService("a.github.io", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=github.io\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 0, "nothing stored");
        });

        Harness.test("a Domain equal to the host that is a public suffix leaves the cookie host-only", () -> {
            CookieJar jar = new CookieJar();
            HttpService lh = new HttpService("localhost", 80, false);
            jar.updateFrom(lh, resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=localhost\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "one cookie");
            Cookie c = jar.all().get(0);
            Expect.isTrue(c.hostOnly, "host-only");
            Expect.equal(c.domain, "localhost", "domain");
            Expect.notNull(jar.headerFor(lh, get("/", "localhost"), null), "sent to localhost");
            Expect.isNull(jar.headerFor(new HttpService("x.localhost", 80, false), get("/", "x.localhost"), null),
                    "not sent to x.localhost");
        });

        Harness.test("a parent Domain from a subdomain is stored lower case without its dot and reaches sibling subdomains", () -> {
            CookieJar jar = new CookieJar();
            HttpService www = new HttpService("www.example.com", 443, true);
            jar.updateFrom(www, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=1; Domain=.EXAMPLE.com\n"
                    + "Set-Cookie: sid=2; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(names(jar.all()), List.of("sid@example.com/"), "one cookie");
            Expect.isTrue(!jar.all().get(0).hostOnly, "not host-only");
            Expect.equal(jar.headerFor(new HttpService("api.example.com", 443, true),
                    get("/", "api.example.com"), null), "sid=2", "sent to api.example.com");
            CookieJar j2 = new CookieJar();
            j2.updateFrom(new HttpService("app.corp.internal", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: k=1; Domain=corp.internal\nContent-Length: 0\n\n"));
            Expect.equal(j2.size(), 1, "corp.internal stored");
        });

        Harness.test("a Domain is never matched by suffix against an IP address", () -> {
            CookieJar jar = new CookieJar();
            HttpService ip = new HttpService("10.0.0.1", 80, false);
            jar.updateFrom(ip, resp("HTTP/1.1 200 OK\nSet-Cookie: a=1; Domain=0.0.1\n"
                    + "Set-Cookie: b=1; Domain=10.0.0.1\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "only b stored");
            Expect.equal(jar.headerFor(ip, get("/", "10.0.0.1"), null), "b=1", "b sent");
            CookieJar j2 = new CookieJar();
            j2.set(new Cookie("m", "1", "0.1", "/", false, 0));
            Expect.isNull(j2.headerFor(ip, get("/", "10.0.0.1"), null), "manual suffix cookie not sent to an IP");
            Expect.isTrue(!Cookie.domainMatch("::ffff:10.0.0.1", "0.0.1"), "IPv6 is never suffix matched");
        });

        Harness.test("a Domain that is only a string suffix of the host is not stored", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("bada.test", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=ada.test\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 0, "nothing stored");
        });

        Harness.test("a host's own cookie wins over a sibling's parent-domain cookie of the same name", () -> {
            CookieJar jar = new CookieJar();
            HttpService www = new HttpService("www.example.com", 443, true);
            jar.updateFrom(www, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=T\nContent-Length: 0\n\n"));
            jar.updateFrom(new HttpService("uploads.example.com", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: sid=S; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(jar.headerFor(www, get("/", "www.example.com"), "sid=old; x=1"), "sid=T; x=1", "own cookie");
            Expect.equal(jar.headerFor(new HttpService("api.example.com", 443, true), get("/", "api.example.com"), null),
                    "sid=S", "sibling's cookie where no own one exists");
        });

        Harness.test("of two domain cookies of one name the longer domain wins", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("x.example.com", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: d=1; Domain=example.com\nContent-Length: 0\n\n"));
            jar.updateFrom(new HttpService("b.a.example.com", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: d=2; Domain=a.example.com\nContent-Length: 0\n\n"));
            HttpService c = new HttpService("c.a.example.com", 443, true);
            Expect.equal(jar.headerFor(c, get("/", "c.a.example.com"), null), "d=2", "longer domain");
        });

        Harness.test("of two cookies of one name the longer path wins, and other names follow in RFC order", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: p=1; Path=/\nSet-Cookie: p=2; Path=/app\n"
                    + "Set-Cookie: q=3; Path=/\nContent-Length: 0\n\n"));
            Expect.equal(jar.headerFor(A, get("/app/x", "a.test"), null), "p=2; q=3", "longer path");
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: p=9; Path=/\nContent-Length: 0\n\n"));
            Expect.equal(jar.headerFor(A, get("/", "a.test"), null), "p=9; q=3", "replaced cookie keeps its place");
        });

        Harness.test("a jar cookie replaces the request's own cookie of its name in place", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "fresh", "example.com", "/", false, 0));
            Expect.equal(jar.headerFor(new HttpService("example.com", 80, false), get("/", "example.com"),
                    "a=1; sid=stale; b=2; sid=old2"), "a=1; sid=fresh; b=2", "order");
        });

        Harness.test("a hand-added cookie wins over learned cookies of its name", () -> {
            CookieJar jar = new CookieJar();
            HttpService www = new HttpService("www.example.com", 443, true);
            Cookie m = new Cookie("sid", "M", "example.com", "/", false, 0);
            m.manual = true;
            jar.set(m);
            jar.updateFrom(www, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=L\nSet-Cookie: other=1\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 3, "learned and manual sid both in the jar");
            Expect.equal(jar.headerFor(www, get("/", "www.example.com"), null), "sid=M; other=1", "manual wins");
        });

        Harness.test("the last Domain attribute in a header decides", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(A, resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=b.test; Domain=a.test\n"
                    + "Set-Cookie: y=1; Domain=a.test; Domain=b.test\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "only x stored");
            Expect.equal(jar.all().get(0).domain, "a.test", "domain of x");
            CookieJar jar2 = new CookieJar();
            HttpService www = new HttpService("www.example.com", 443, true);
            jar2.updateFrom(www, resp("HTTP/1.1 200 OK\nSet-Cookie: z=1; Domain=example.com; Domain=.\nContent-Length: 0\n\n"));
            Expect.equal(jar2.size(), 1, "z stored");
            Expect.equal(jar2.all().get(0).domain, "www.example.com", "host domain");
            Expect.isTrue(jar2.all().get(0).hostOnly, "host-only");
        });

        Harness.test("an empty Domain after a Domain leaves the cookie host-only", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("www.a.test", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: sid=1; Domain=a.test; Domain=\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "stored");
            Expect.isTrue(jar.all().get(0).hostOnly, "host-only");
            Expect.equal(jar.headerFor(new HttpService("www.a.test", 443, true), get("/", "www.a.test"), null), "sid=1", "sent to www.a.test");
            Expect.isNull(jar.headerFor(A, get("/", "a.test"), null), "not sent to the parent");
        });

        Harness.test("an IPv6 host that names itself as Domain gets the cookie back, and nothing else does", () -> {
            CookieJar jar = new CookieJar();
            HttpService v6 = new HttpService("[::1]", 443, true);
            jar.updateFrom(v6, resp("HTTP/1.1 200 OK\nSet-Cookie: a=1; Domain=[::1]\nSet-Cookie: b=1; Domain=[::2]\n"
                    + "Set-Cookie: c=1; Domain=1\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "only a stored");
            Expect.equal(jar.headerFor(v6, get("/", "[::1]"), null), "a=1", "sent back");
            Expect.isNull(jar.headerFor(new HttpService("[::2]", 443, true), get("/", "[::2]"), null), "not sent to another address");
            Expect.isNull(jar.headerFor(A, get("/", "a.test"), null), "not sent to a name");
        });

        Harness.test("a Domain or host with a trailing dot cannot reach a public suffix", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("shop.example.co.uk.", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: x=1; Domain=co.uk.\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 0, "nothing stored");
            HttpService suffix = new HttpService("co.uk.", 443, true);
            jar.updateFrom(suffix, resp("HTTP/1.1 200 OK\nSet-Cookie: y=1; Domain=co.uk.\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "the suffix host's own cookie stored");
            Expect.isTrue(jar.all().get(0).hostOnly, "host-only");
            Expect.equal(jar.headerFor(suffix, get("/", "co.uk."), null), "y=1", "sent to co.uk.");
            Expect.isNull(jar.headerFor(new HttpService("x.co.uk.", 443, true), get("/", "x.co.uk."), null),
                    "not sent to x.co.uk.");
        });

        Harness.test("a cookie stored with a leading dot is replaced by the same cookie without it", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "old", ".example.com", "/", false, 0));
            jar.updateFrom(new HttpService("www.example.com", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: sid=new; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "one cookie");
            Expect.equal(jar.headerFor(new HttpService("api.example.com", 443, true),
                    get("/", "api.example.com"), null), "sid=new", "new value sent");
        });

        Harness.test("a host name in upper case is compared in lower case", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("WWW.Example.com", 443, true),
                    resp("HTTP/1.1 200 OK\nSet-Cookie: sid=1; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 1, "stored");
            Expect.equal(jar.all().get(0).domain, "example.com", "domain");
            Expect.equal(jar.headerFor(new HttpService("api.example.com", 443, true), get("/", "api.example.com"), null), "sid=1", "sent");
        });

        Harness.test("a response with no host sets no cookie", () -> {
            CookieJar jar = new CookieJar();
            jar.updateFrom(new HttpService("", 443, true), resp("HTTP/1.1 200 OK\nSet-Cookie: sid=1\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 0, "nothing stored");
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
            Expect.isFalse(h.contains("sid=stale"), "request's own value dropped");
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
