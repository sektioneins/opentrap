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
    }

    /** Answer one connection with a fixed response; completes with the request as received. */
    private static CompletableFuture<String> serveOnce(ServerSocket server, String response) {
        CompletableFuture<String> got = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
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
                got.complete(buf.toString(StandardCharsets.ISO_8859_1));
            } catch (Exception e) { got.completeExceptionally(e); }
        });
        return got;
    }
}
