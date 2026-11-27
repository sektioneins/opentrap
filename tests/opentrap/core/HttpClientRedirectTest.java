package opentrap.core;

import opentrap.session.CookieJar;
import opentrap.session.Macro;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests how the client follows redirects: the next hop's target is the
 * server's choice, so a hop to another origin must not carry the first
 * origin's cookies, credentials or body, and https never redirects to http.
 */
public final class HttpClientRedirectTest {

    private static final HttpService A = new HttpService("a.test", 443, true);

    /** The tests address the servers as 127.0.0.1, so they must not land on ::1. */
    private static final InetAddress LOOPBACK = loopback();

    private static InetAddress loopback() {
        try { return InetAddress.getByName("127.0.0.1"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Macro loginMacro(HttpService svc) {
        Macro m = new Macro("login");
        m.items.add(new Macro.Item(svc, new HttpMessage(Bytes.bytes("POST /login HTTP/1.1\r\nHost: 127.0.0.1:"
                + svc.port() + "\r\nContent-Length: 3\r\n\r\nu=1"))));
        return m;
    }

    private static HttpMessage post() {
        return new HttpMessage(Bytes.bytes("POST /login HTTP/1.1\r\nHost: a.test\r\n"
                + "Cookie: sid=secret\r\nAuthorization: Basic dTpw\r\nProxy-Authorization: Basic cDpx\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 13\r\n\r\nuser=u&pass=p"));
    }

    private static HttpMessage redirect(int status, String location) {
        return new HttpMessage(Bytes.bytes("HTTP/1.1 " + status + " Moved\r\nLocation: " + location
                + "\r\nContent-Length: 0\r\n\r\n"));
    }

    public void run() {
        Harness.suite("HttpClient redirects");

        Harness.test("a 307 to another host drops cookies, credentials and the body but keeps the method", () -> {
            HttpClient.Hop h = HttpClient.nextHop(A, post(), redirect(307, "https://b.test/next"));
            Expect.notNull(h, "redirect followed");
            Expect.isTrue(h.crossOrigin(), "cross-origin");
            HttpMessage m = h.request();
            Expect.equal(m.method(), "POST", "method kept");
            Expect.isNull(m.header("Cookie"), "no Cookie");
            Expect.isNull(m.header("Authorization"), "no Authorization");
            Expect.isNull(m.header("Proxy-Authorization"), "no Proxy-Authorization");
            Expect.isNull(m.header("Content-Type"), "no Content-Type");
            Expect.equal(m.body().length, 0, "no body");
            Expect.equal(m.header("Content-Length"), "0", "empty body announced");
            Expect.equal(m.header("Host"), "b.test", "Host is the new host");
            Expect.equal(m.requestTarget(), "/next", "target");
        });

        Harness.test("a 308 on the same origin resends cookies, credentials and the body", () -> {
            HttpClient.Hop h = HttpClient.nextHop(A, post(), redirect(308, "/login2"));
            Expect.notNull(h, "redirect followed");
            Expect.isFalse(h.crossOrigin(), "same origin");
            HttpMessage m = h.request();
            Expect.equal(m.method(), "POST", "method kept");
            Expect.equal(m.header("Cookie"), "sid=secret", "Cookie kept");
            Expect.equal(m.header("Authorization"), "Basic dTpw", "Authorization kept");
            Expect.equal(m.bodyText(), "user=u&pass=p", "body kept");
        });

        Harness.test("another port on the same host counts as another origin", () -> {
            HttpClient.Hop h = HttpClient.nextHop(A, post(), redirect(302, "https://a.test:8443/"));
            Expect.isTrue(h.crossOrigin(), "cross-origin");
            Expect.isNull(h.request().header("Cookie"), "no Cookie");
            Expect.equal(h.request().header("Host"), "a.test:8443", "Host carries the port");
        });

        Harness.test("a 303 to another host becomes a GET with no body and no credentials", () -> {
            HttpMessage m = HttpClient.nextHop(A, post(), redirect(303, "https://b.test/")).request();
            Expect.equal(m.method(), "GET", "GET");
            Expect.equal(m.body().length, 0, "no body");
            Expect.isNull(m.header("Content-Length"), "no Content-Length");
            Expect.isNull(m.header("Authorization"), "no Authorization");
        });

        Harness.test("a redirect from https to plain http is not followed", () -> {
            Expect.isNull(HttpClient.nextHop(A, post(), redirect(302, "http://a.test/")), "same host, http");
            Expect.isNull(HttpClient.nextHop(A, post(), redirect(307, "http://b.test/")), "other host, http");
        });

        Harness.test("a redirect to a scheme other than http or https is not followed", () -> {
            Expect.isNull(HttpClient.nextHop(A, post(), redirect(302, "ftp://a.test/f")), "ftp");
            Expect.isNull(HttpClient.nextHop(A, post(), redirect(302, "javascript:alert(1)")), "javascript");
        });

        Harness.test("a macro step answered with a 307 to another origin reaches it with no Cookie, Authorization or body", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                CompletableFuture<String> atB = serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/landing\r\nContent-Length: 0\r\n\r\n");
                HttpService svcA = new HttpService("127.0.0.1", a.getLocalPort(), false);
                Macro m = new Macro("login");
                m.items.add(new Macro.Item(svcA, new HttpMessage(Bytes.bytes("POST /login HTTP/1.1\r\nHost: 127.0.0.1:"
                        + a.getLocalPort() + "\r\nCookie: sid=secret\r\nAuthorization: Basic dTpw\r\n"
                        + "Content-Length: 13\r\n\r\nuser=u&pass=p"))));
                m.run(new CookieJar(), null);
                String seen = atB.get(5, TimeUnit.SECONDS);
                String head = seen.substring(0, seen.indexOf("\r\n\r\n")).toLowerCase(java.util.Locale.ROOT);
                Expect.isTrue(seen.startsWith("POST /landing "), "method kept: " + seen);
                Expect.isFalse(head.contains("\ncookie:"), "no Cookie");
                Expect.isFalse(head.contains("\nauthorization:"), "no Authorization");
                Expect.isFalse(seen.contains("user=u"), "no body");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a macro stores a redirect target's cookie under the target's host only", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serve(b, "HTTP/1.1 200 OK\r\nSet-Cookie: z=1\r\nContent-Length: 2\r\n\r\nok");
                serve(a, "HTTP/1.1 302 Found\r\nLocation: http://localhost:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                HttpService svcA = new HttpService("127.0.0.1", a.getLocalPort(), false);
                CookieJar jar = new CookieJar();
                loginMacro(svcA).run(jar, null);
                List<opentrap.session.Cookie> all = jar.all();
                Expect.equal(all.size(), 1, "one cookie: " + all);
                Expect.equal(all.get(0).name, "z", "name");
                Expect.equal(all.get(0).domain, "localhost", "domain");
                Expect.isNull(jar.headerFor(svcA, new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: x\r\n\r\n")), null),
                        "nothing for the original host");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a macro logs each redirect hop under the host that answered it", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serve(b, "HTTP/1.1 200 OK\r\nSet-Cookie: z=1\r\nContent-Length: 2\r\n\r\nok");
                serve(a, "HTTP/1.1 302 Found\r\nLocation: http://localhost:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                List<String> log = new ArrayList<>();
                loginMacro(new HttpService("127.0.0.1", a.getLocalPort(), false)).run(new CookieJar(),
                        (svc, req, resp) -> log.add(svc.host() + " " + req.method() + " " + req.path() + " " + resp.statusCode()));
                Expect.equal(log, List.of("127.0.0.1 POST /login 302", "localhost GET / 200"), "recorded hops");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a macro step without a redirect stores its cookie and extracts from its response as before", () -> {
            try (ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serve(a, "HTTP/1.1 200 OK\r\nSet-Cookie: sid=1\r\nContent-Length: 9\r\n\r\ntoken=abc");
                Macro m = loginMacro(new HttpService("127.0.0.1", a.getLocalPort(), false));
                m.extractions.add(new opentrap.session.Extraction("t", opentrap.session.Extraction.From.REGEX, "token=(\\w+)"));
                CookieJar jar = new CookieJar();
                List<String> log = new ArrayList<>();
                java.util.Map<String, String> out = m.run(jar,
                        (svc, req, resp) -> log.add(svc.host() + " " + req.method() + " " + req.path() + " " + resp.statusCode()));
                Expect.equal(out, java.util.Map.of("t", "abc"), "extracted");
                Expect.equal(jar.all().size(), 1, "one cookie");
                Expect.equal(jar.all().get(0).name, "sid", "name");
                Expect.equal(jar.all().get(0).domain, "127.0.0.1", "domain");
                Expect.equal(log, List.of("127.0.0.1 POST /login 200"), "one entry");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("session handling adds the new origin's cookies on a cross-origin hop", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                CompletableFuture<String> atB = serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                int portB = b.getLocalPort();
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.session = new SessionHook() {
                    public void beforeSend(String tool, HttpService svc, HttpMessage req) {
                        req.setHeader("Cookie", svc.port() == portB ? "b=2" : "a=1");
                    }
                    public boolean afterResponse(String tool, HttpService svc, HttpMessage req, HttpMessage resp) {
                        return false;
                    }
                };
                HttpService svcA = new HttpService("127.0.0.1", a.getLocalPort(), false);
                HttpClient.Result r = new HttpClient().send(svcA, new HttpMessage(Bytes.bytes(
                        "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + a.getLocalPort() + "\r\n\r\n")), o);
                Expect.isTrue(r.ok(), "final response: " + r.error);
                Expect.equal(r.redirectsFollowed, 1, "one redirect");
                Expect.equal(r.finalService.port(), portB, "final service is B");
                String seen = atB.get(5, TimeUnit.SECONDS);
                Expect.isTrue(seen.contains("\r\nCookie: b=2\r\n"), "B's cookie: " + seen);
                Expect.isFalse(seen.contains("a=1"), "not A's cookie");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("session handling learns each followed redirect from the hop that answered it and the last response once", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serve(b, "HTTP/1.1 302 Found\r\nLocation: /two\r\nContent-Length: 0\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                List<String> log = new ArrayList<>();
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.session = logging(log, a.getLocalPort(), false);
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(log, List.of("before A /", "redirect A 302", "before B /",
                        "redirect B 302", "before B /two", "after B 200"), "hook calls");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("session handling sends the cookie a redirect set on the next hop in place of the stale one", () -> {
            try (ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                CompletableFuture<List<String>> seen = serve(a,
                        "HTTP/1.1 302 Found\r\nSet-Cookie: sid=new\r\nLocation: /home\r\nContent-Length: 0\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                opentrap.session.SessionManager sm = new opentrap.session.SessionManager(null);
                sm.rules().add(new opentrap.session.SessionRule());
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.session = sm;
                o.sessionTool = "repeater";
                HttpService svcA = new HttpService("127.0.0.1", a.getLocalPort(), false);
                HttpClient.Result r = new HttpClient().send(svcA, new HttpMessage(Bytes.bytes(
                        "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + a.getLocalPort() + "\r\nCookie: sid=old\r\n\r\n")), o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                String second = seen.get(5, TimeUnit.SECONDS).get(1);
                Expect.isTrue(second.contains("\r\nCookie: sid=new\r\n"), "new cookie: " + second);
                Expect.isFalse(second.contains("sid=old"), "no stale cookie: " + second);
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("session handling stores a redirect target's cookie under the target's host", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serve(b, "HTTP/1.1 302 Found\r\nSet-Cookie: z=1\r\nLocation: /end\r\nContent-Length: 0\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://localhost:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                opentrap.session.SessionManager sm = new opentrap.session.SessionManager(null);
                sm.rules().add(new opentrap.session.SessionRule());
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.session = sm;
                o.sessionTool = "repeater";
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                List<opentrap.session.Cookie> all = sm.jar().all();
                Expect.equal(all.size(), 1, "one cookie: " + all);
                Expect.equal(all.get(0).name, "z", "name");
                Expect.equal(all.get(0).domain, "localhost", "domain");
                Expect.isTrue(all.get(0).hostOnly, "host only");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a redirect the policy refuses reaches session handling only as the last response", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                List<String> log = new ArrayList<>();
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.redirectPolicy = HttpClient.RedirectPolicy.sameOrigin();
                o.session = logging(log, a.getLocalPort(), false);
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(log, List.of("before A /", "after A 302"), "hook calls");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a failing onRedirect does not stop the redirect", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.session = logging(new ArrayList<>(), a.getLocalPort(), true);
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.response.statusCode(), 200, "followed to B");
                Expect.equal(r.redirectsFollowed, 1, "one redirect");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("the on-site policy refuses another host, port or scheme and allows the same origin", () -> {
            HttpClient.RedirectPolicy p = HttpClient.RedirectPolicy.sameOrigin();
            Expect.isNull(p.refusal(A, A, post()), "same origin");
            Expect.equal(p.refusal(A, new HttpService("b.test", 443, true), post()), "off-site", "other host");
            Expect.equal(p.refusal(A, new HttpService("a.test", 8443, true), post()), "off-site", "other port");
            Expect.equal(p.refusal(A, new HttpService("a.test", 80, false), post()), "off-site", "other scheme");
        });

        Harness.test("the in-scope policy follows only targets the scope covers", () -> {
            Scope s = new Scope();
            s.includeUrl(A, "/", true);
            HttpClient.RedirectPolicy p = HttpClient.RedirectPolicy.inScope(s);
            HttpClient.Hop home = HttpClient.nextHop(A, post(), redirect(302, "/home"));
            HttpClient.Hop other = HttpClient.nextHop(A, post(), redirect(302, "https://b.test/"));
            HttpClient.Hop logout = HttpClient.nextHop(A, post(), redirect(302, "/logout"));
            Expect.isNull(p.refusal(A, home.service(), home.request()), "in scope");
            Expect.equal(p.refusal(A, other.service(), other.request()), "out of scope", "other host");
            Expect.equal(p.refusal(A, logout.service(), logout.request()), "out of scope", "default exclude");
            HttpClient.RedirectPolicy none = HttpClient.RedirectPolicy.inScope(new Scope());
            Expect.equal(none.refusal(A, home.service(), home.request()), "out of scope", "empty scope");
        });

        Harness.test("an off-site redirect is not followed under the on-site policy and the 302 is returned", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/landing\r\nContent-Length: 0\r\n\r\n");
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.redirectPolicy = HttpClient.RedirectPolicy.sameOrigin();
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.response.statusCode(), 302, "the 302 is returned");
                Expect.equal(r.redirectsFollowed, 0, "nothing followed");
                Expect.equal(r.finalService.port(), a.getLocalPort(), "final service is A");
                Expect.equal(r.redirectRefusal, "off-site", "reason");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a chain stops at the first hop the policy refuses", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket c = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                CompletableFuture<String> atC = serveOnce(c, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"
                        + b.getLocalPort() + "/end\r\nContent-Length: 0\r\n\r\n");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + c.getLocalPort()
                        + "/\r\nContent-Length: 0\r\n\r\n");
                Scope scope = new Scope();
                scope.includeUrl(new HttpService("127.0.0.1", a.getLocalPort(), false), "/", true);
                scope.includeUrl(new HttpService("127.0.0.1", c.getLocalPort(), false), "/", true);
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.redirectPolicy = HttpClient.RedirectPolicy.inScope(scope);
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.response.statusCode(), 302, "C's 302 is returned");
                Expect.equal(r.redirectsFollowed, 1, "A to C followed");
                Expect.equal(r.finalService.port(), c.getLocalPort(), "final service is C");
                Expect.equal(r.redirectRefusal, "out of scope", "reason");
                Expect.isTrue(atC.get(5, TimeUnit.SECONDS).startsWith("GET /"), "C was asked");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("without a policy every redirect is followed as before", () -> {
            try (ServerSocket b = new ServerSocket(0, 1, LOOPBACK);
                 ServerSocket a = new ServerSocket(0, 1, LOOPBACK)) {
                serveOnce(b, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                serveOnce(a, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + b.getLocalPort()
                        + "/landing\r\nContent-Length: 0\r\n\r\n");
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                HttpClient.Result r = sendGet(a, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.response.statusCode(), 200, "followed to B");
                Expect.equal(r.redirectsFollowed, 1, "one redirect");
                Expect.equal(r.redirectRefusal, "", "no refusal");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static HttpClient.Result sendGet(ServerSocket a, HttpClient.Options o) {
        HttpService svcA = new HttpService("127.0.0.1", a.getLocalPort(), false);
        return new HttpClient().send(svcA, new HttpMessage(Bytes.bytes(
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + a.getLocalPort() + "\r\n\r\n")), o);
    }

    /** Answer one connection with a fixed response; completes with the request as received. */
    private static CompletableFuture<String> serveOnce(ServerSocket server, String response) {
        return serve(server, response).thenApply(l -> l.get(0));
    }

    /** Answer one connection per response, in turn; completes with the requests as received. */
    private static CompletableFuture<List<String>> serve(ServerSocket server, String... responses) {
        CompletableFuture<List<String>> got = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            List<String> seen = new ArrayList<>();
            try {
                for (String response : responses) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(5000);
                        InputStream in = s.getInputStream();
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        int b, state = 0;
                        while (state < 4 && (b = in.read()) >= 0) {
                            buf.write(b);
                            if ((state == 0 || state == 2) && b == 13) state++;
                            else if ((state == 1 || state == 3) && b == 10) state++;
                            else state = 0;
                        }
                        HttpMessage req = new HttpMessage(buf.toByteArray());
                        String cl = req.header("Content-Length");
                        int n = cl == null ? 0 : Integer.parseInt(cl.trim());
                        for (int i = 0; i < n && (b = in.read()) >= 0; i++) buf.write(b);
                        OutputStream out = s.getOutputStream();
                        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        seen.add(buf.toString(StandardCharsets.ISO_8859_1));
                    }
                }
                got.complete(seen);
            } catch (Exception e) { got.completeExceptionally(e); }
        });
        return got;
    }

    /** A hook that appends what it is called with to a log, and can fail in onRedirect. */
    private static SessionHook logging(List<String> log, int portA, boolean failOnRedirect) {
        return new SessionHook() {
            public void beforeSend(String tool, HttpService svc, HttpMessage req) {
                log.add("before " + name(svc) + " " + req.path());
            }
            @Override
            public void onRedirect(String tool, HttpService svc, HttpMessage req, HttpMessage resp) {
                log.add("redirect " + name(svc) + " " + resp.statusCode());
                if (failOnRedirect) throw new IllegalStateException("boom");
            }
            public boolean afterResponse(String tool, HttpService svc, HttpMessage req, HttpMessage resp) {
                log.add("after " + name(svc) + " " + resp.statusCode());
                return false;
            }
            private String name(HttpService svc) { return svc.port() == portA ? "A" : "B"; }
        };
    }
}
