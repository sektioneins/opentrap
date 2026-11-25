package opentrap.core;

import opentrap.testkit.ConnectProxy;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.TestTls;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests upstream-proxy routing: destination host matching, and that an HTTP
 * proxy rule makes the client open a CONNECT tunnel to the proxy (verified with
 * a local fake proxy, no external network).
 */
public final class UpstreamProxyTest {

    public void run() {
        Harness.suite("upstream proxy");

        Harness.test("destination host matching honours globs and the enabled flag", () -> {
            UpstreamProxy p = new UpstreamProxy();
            p.host = "127.0.0.1"; p.port = 8080; p.hostPattern = "*.example.com";
            Expect.isTrue(p.matches(new HttpService("api.example.com", 443, true)), "subdomain matches");
            Expect.isFalse(p.matches(new HttpService("example.org", 443, true)), "other host does not");
            p.enabled = false;
            Expect.isFalse(p.matches(new HttpService("api.example.com", 443, true)), "disabled never matches");
        });

        Harness.test("an HTTP proxy rule routes the request through a CONNECT tunnel", () -> {
            AtomicReference<String> connectLine = new AtomicReference<>("");
            ServerSocket fakeProxy;
            try { fakeProxy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()); }
            catch (Exception e) { throw new RuntimeException(e); }

            Thread.ofVirtual().name("fake-proxy").start(() -> {
                try (Socket s = fakeProxy.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    connectLine.set(readHead(in));                 // CONNECT target:80 ...
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    readHead(in);                                  // the tunnelled GET
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (Exception _) {}
            });

            UpstreamProxy rule = new UpstreamProxy();
            rule.type = UpstreamProxy.Type.HTTP;
            rule.host = "127.0.0.1";
            rule.port = fakeProxy.getLocalPort();
            HttpClient.setProxyProvider(svc -> rule);
            try {
                HttpService target = new HttpService("target.test", 80, false);
                HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: target.test\r\n\r\n"));
                HttpClient.Result r = new HttpClient().send(target, req, new HttpClient.Options());
                Expect.isTrue(r.ok(), "response received through the proxy");
                Expect.equal(r.response.bodyText(), "hi", "body came back through the tunnel");
                Expect.isTrue(connectLine.get().startsWith("CONNECT target.test:80"), "proxy saw a CONNECT to the target");
            } finally {
                HttpClient.setProxyProvider(null);
                try { fakeProxy.close(); } catch (Exception _) {}
            }
        });

        Harness.test("every mode the JDK sends but HTTP/3 goes through the HTTP upstream rule that covers the target", guard(() -> {
            HttpsServer server = httpsServer(new AtomicReference<>(), new AtomicInteger());
            try {
                InetSocketAddress addr = server.getAddress();
                for (HttpMode mode : List.of(HttpMode.HTTP_2, HttpMode.HTTP_2_IGNORE_ALPN, HttpMode.AUTO)) {
                    try (ConnectProxy proxy = new ConnectProxy(null, addr)) {
                        HttpClient.setProxyProvider(svc -> ruleOn(proxy, "", ""));
                        HttpClient.Result r = send(new HttpService("h2.invalid", 443, true), mode);
                        Expect.isTrue(r.ok(), mode + " send ok: " + r.error);
                        Expect.equal(r.response.bodyText(), "hello", mode + " body");
                        Expect.equal(proxy.targets, List.of("h2.invalid:443"), mode + " CONNECT target");
                        Expect.equal(r.remoteIp, "127.0.0.1", mode + " remote address is the proxy");
                    } finally {
                        HttpClient.setProxyProvider(null);
                    }
                }
                try (ConnectProxy proxy = new ConnectProxy(null)) {
                    UpstreamProxy rule = ruleOn(proxy, "", "");
                    rule.hostPattern = "*.invalid";
                    HttpClient.setProxyProvider(svc -> rule.matches(svc) ? rule : null);
                    HttpClient.Result r = send(new HttpService("127.0.0.1", addr.getPort(), true), HttpMode.HTTP_2);
                    Expect.isTrue(r.ok(), "a target the rule does not cover goes direct: " + r.error);
                    Expect.isTrue(proxy.targets.isEmpty(), "the proxy saw nothing");
                } finally {
                    HttpClient.setProxyProvider(null);
                }
            } finally {
                server.stop(0);
            }
        }));

        Harness.test("an upstream login goes to the proxy on the CONNECT and never to the server", guard(() -> {
            AtomicReference<Headers> seen = new AtomicReference<>();
            HttpsServer server = httpsServer(seen, new AtomicInteger());
            try (ConnectProxy proxy = new ConnectProxy("u:p", server.getAddress())) {
                HttpClient.setProxyProvider(svc -> ruleOn(proxy, "u", "p"));
                HttpClient.Result r = send(new HttpService("h2.invalid", 443, true), HttpMode.HTTP_2);
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.isTrue(proxy.logins.contains("Basic dTpw"), "the proxy got the Basic login: " + proxy.logins);
                Expect.isFalse(seen.get().containsKey("Proxy-Authorization"), "the server saw no Proxy-Authorization");
                Expect.isFalse(seen.get().containsKey("Authorization"), "the server saw no Authorization");
            } finally {
                HttpClient.setProxyProvider(null);
                server.stop(0);
            }
        }));

        Harness.test("a 401 from the server comes back through an upstream rule with a login", guard(() -> {
            AtomicInteger hits = new AtomicInteger();
            HttpsServer server = statusServer(401, "WWW-Authenticate", "Basic realm=\"t\"", hits);
            try (ConnectProxy proxy = new ConnectProxy("u:p", server.getAddress())) {
                HttpClient.setProxyProvider(svc -> ruleOn(proxy, "u", "p"));
                HttpClient.Result r = send(new HttpService("h2.invalid", 443, true), HttpMode.HTTP_2);
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.equal(r.response.statusCode(), 401, "status");
                Expect.equal(hits.get(), 1, "the server was asked once");
            } finally {
                HttpClient.setProxyProvider(null);
                server.stop(0);
            }
        }));

        Harness.test("a 407 from the server is returned, not taken for the proxy's", guard(() -> {
            AtomicInteger hits = new AtomicInteger();
            HttpsServer server = statusServer(407, "Proxy-Authenticate", "Basic realm=\"t\"", hits);
            try (ConnectProxy proxy = new ConnectProxy("u:p", server.getAddress())) {
                HttpClient.setProxyProvider(svc -> ruleOn(proxy, "u", "p"));
                HttpClient.Result r = send(new HttpService("h2.invalid", 443, true), HttpMode.HTTP_2);
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.equal(r.response.statusCode(), 407, "status");
                Expect.equal(hits.get(), 1, "the server was asked once");
            } finally {
                HttpClient.setProxyProvider(null);
                server.stop(0);
            }
        }));

        Harness.test("a mode the JDK sends is refused under a SOCKS upstream rule before any connection", guard(() -> {
            try (ServerSocket target = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ServerSocket socks = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
                UpstreamProxy rule = new UpstreamProxy();
                rule.type = UpstreamProxy.Type.SOCKS;
                rule.host = "127.0.0.1";
                rule.port = socks.getLocalPort();
                HttpClient.setProxyProvider(svc -> rule);
                try {
                    for (HttpMode mode : List.of(HttpMode.HTTP_2, HttpMode.AUTO)) {
                        HttpClient.Result r = send(new HttpService("127.0.0.1", target.getLocalPort(), true), mode);
                        Expect.isFalse(r.ok(), mode + " is refused");
                        Expect.equal(r.error, mode + " cannot go through the SOCKS upstream proxy set for 127.0.0.1;"
                                + " choose HTTP/1, or make the rule an HTTP proxy", mode + " error");
                    }
                } finally {
                    HttpClient.setProxyProvider(null);
                }
                for (ServerSocket server : List.of(target, socks)) {
                    server.setSoTimeout(2000);
                    try (Socket mine = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                         Socket first = server.accept()) {
                        Expect.equal(first.getPort(), mine.getLocalPort(), "the first connection is ours, none came before");
                    }
                }
            }
        }));

        Harness.test("an upstream that cannot be reached fails the send and never goes direct", guard(() -> {
            AtomicInteger hits = new AtomicInteger();
            HttpsServer server = httpsServer(new AtomicReference<>(), hits);
            try {
                int closed;
                try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closed = s.getLocalPort(); }
                UpstreamProxy rule = new UpstreamProxy();
                rule.host = "127.0.0.1";
                rule.port = closed;
                HttpService svc = new HttpService("127.0.0.1", server.getAddress().getPort(), true);
                HttpClient.setProxyProvider(s -> rule);
                try {
                    HttpClient.Result r = send(svc, HttpMode.HTTP_2);
                    Expect.isFalse(r.ok(), "send through a dead upstream fails");
                    Expect.isTrue(r.error != null && !r.error.isEmpty(), "an error message is set");
                } finally {
                    HttpClient.setProxyProvider(null);
                }
                HttpClient.Result direct = send(svc, HttpMode.HTTP_2);
                Expect.isTrue(direct.ok(), "the same send direct works: " + direct.error);
                Expect.equal(hits.get(), 1, "the server saw only the direct request");
            } finally {
                server.stop(0);
            }
        }));

        Harness.test("a plain HTTP target in HTTP/2 mode reaches the upstream as an absolute-form request", guard(() -> {
            try (ConnectProxy proxy = new ConnectProxy(null)) {
                HttpClient.setProxyProvider(svc -> ruleOn(proxy, "", ""));
                HttpClient.Result r = send(new HttpService("h1.invalid", 80, false), HttpMode.HTTP_2);
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.equal(r.response.bodyText(), "proxy", "the proxy answered");
                Expect.equal(proxy.targets, List.of("http://h1.invalid/"), "absolute-form target");
            } finally {
                HttpClient.setProxyProvider(null);
            }
        }));

        Harness.test("sends through one rule share a client, and a changed rule gets a new one", guard(() -> {
            HttpClient.Options opt = new HttpClient.Options();
            UpstreamProxy a = new UpstreamProxy();
            a.host = "up.invalid"; a.port = 3128; a.user = "u"; a.pass = "p";
            UpstreamProxy b = new UpstreamProxy();
            b.host = "up.invalid"; b.port = 3128; b.user = "u"; b.pass = "p";
            java.net.http.HttpClient ca = Http2Client.clientFor(a, opt);
            Expect.isTrue(ca == Http2Client.clientFor(b, opt), "equal rules share a client");
            b.pass = "q";
            Expect.isFalse(ca == Http2Client.clientFor(b, opt), "another password gets another client");
            b.pass = "p"; b.port = 3129;
            Expect.isFalse(ca == Http2Client.clientFor(b, opt), "another port gets another client");
            java.net.http.HttpClient direct = Http2Client.clientFor(null, opt);
            Expect.isFalse(direct == ca || direct == Http2Client.clientFor(b, opt), "direct is its own client");
            Expect.equal(direct.proxy(), java.util.Optional.of(java.net.http.HttpClient.Builder.NO_PROXY), "direct names no proxy");
            List<Proxy> chosen = ca.proxy().orElseThrow().select(URI.create("https://x.test/"));
            Expect.equal(chosen.size(), 1, "one proxy chosen");
            Expect.equal(chosen.get(0).type(), Proxy.Type.HTTP, "an HTTP proxy");
            InetSocketAddress at = (InetSocketAddress) chosen.get(0).address();
            Expect.isTrue(at.isUnresolved(), "the address stays unresolved");
            Expect.equal(at.getHostString() + ":" + at.getPort(), "up.invalid:3128", "the rule's host and port");
        }));

        Harness.test("a live-scan route that cannot be reached falls back to the target's upstream rule, not to a direct connection", guard(() -> {
            HttpServer server = plainServer();
            try (ConnectProxy proxy = new ConnectProxy(null, server.getAddress())) {
                HttpClient.setProxyProvider(svc -> ruleOn(proxy, "", ""));
                HttpClient.Result r = sendLive(new HttpService("live.invalid", 80, false));
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.equal(r.response.bodyText(), "hello", "the server answered");
                Expect.equal(proxy.targets, List.of("live.invalid:80"), "the rule's proxy got the CONNECT");
            } finally {
                HttpClient.setProxyProvider(null);
                server.stop(0);
            }
        }));

        Harness.test("with no upstream rule a live-scan route that cannot be reached still falls back to a direct connection", guard(() -> {
            HttpServer server = plainServer();
            try {
                HttpClient.Result r = sendLive(new HttpService("127.0.0.1", server.getAddress().getPort(), false));
                Expect.isTrue(r.ok(), "send ok: " + r.error);
                Expect.equal(r.response.bodyText(), "hello", "the server answered");
            } finally {
                server.stop(0);
            }
        }));
    }

    private interface Body { void run() throws Exception; }

    /** A case body that may throw: a checked exception fails the case. */
    private static Runnable guard(Body body) {
        return () -> {
            try { body.run(); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        };
    }

    private static UpstreamProxy ruleOn(ConnectProxy proxy, String user, String pass) {
        UpstreamProxy rule = new UpstreamProxy();
        rule.host = "127.0.0.1";
        rule.port = proxy.port();
        rule.user = user;
        rule.pass = pass;
        return rule;
    }

    private static HttpClient.Result send(HttpService svc, HttpMode mode) {
        HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: " + svc.host() + "\r\n\r\n"));
        HttpClient.Options opt = new HttpClient.Options();
        opt.mode = mode;
        opt.connectTimeoutMs = 2000;
        opt.readTimeoutMs = 2000;
        return new HttpClient().send(svc, req, opt);
    }

    /** A send in HTTP/1 mode whose live-scan override points at a closed loopback port. */
    private static HttpClient.Result sendLive(HttpService svc) throws Exception {
        UpstreamProxy dead = new UpstreamProxy();
        dead.host = "127.0.0.1";
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { dead.port = s.getLocalPort(); }
        HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: " + svc.host() + "\r\n\r\n"));
        HttpClient.Options opt = new HttpClient.Options();
        opt.mode = HttpMode.HTTP_1;
        opt.upstream = dead;
        opt.connectTimeoutMs = 2000;
        opt.readTimeoutMs = 2000;
        return new HttpClient().send(svc, req, opt);
    }

    /** A plain HTTP server on loopback answering 200 "hello". */
    private static HttpServer plainServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = Bytes.bytes("hello");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
        return server;
    }

    /** An HTTPS server on loopback answering 200 "hello"; it keeps the last request headers and counts requests. */
    private static HttpsServer httpsServer(AtomicReference<Headers> seen, AtomicInteger hits) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(TestTls.serverContext("127.0.0.1")));
        server.createContext("/", ex -> {
            seen.set(ex.getRequestHeaders());
            hits.incrementAndGet();
            byte[] b = Bytes.bytes("hello");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
        return server;
    }

    /** An HTTPS server on loopback answering every request with a status, one header and no body; it counts requests. */
    private static HttpsServer statusServer(int status, String header, String value, AtomicInteger hits) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(TestTls.serverContext("127.0.0.1")));
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add(header, value);
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        return server;
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b, state = 0;
        while (state < 4 && (b = in.read()) >= 0) {
            sb.append((char) b);
            if ((state == 0 || state == 2) && b == 13) state++;
            else if ((state == 1 || state == 3) && b == 10) state++;
            else state = 0;
        }
        return sb.toString();
    }
}
