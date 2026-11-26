package opentrap.proxy;

import opentrap.core.LogEntry;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.TestTls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A listener without "Support invisible proxying" forwards only requests that
 * carry an absolute URL, over real loopback sockets.
 */
public final class InvisibleProxyTest {

    private static final String OK_REPLY = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) >= 0; ) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    /** Send a raw request to the proxy on loopback and read the reply until EOF. */
    private static String exchange(int port, String request) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readAll(s.getInputStream());
        }
    }

    /**
     * A loopback server. With {@code answer} it answers one request with "ok"
     * and reports the request line; without it, it only binds and never
     * accepts, so a connection from the proxy waits in the backlog.
     */
    private static final class Backend implements AutoCloseable {
        final ServerSocket socket;
        final CompletableFuture<String> requestLine = new CompletableFuture<>();

        Backend(boolean answer, boolean tls) throws Exception {
            socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            if (!answer) return;
            SSLContext sslCtx = tls ? TestTls.serverContext("127.0.0.1") : null;
            Thread.ofVirtual().start(() -> {
                try (Socket c = socket.accept()) {
                    c.setSoTimeout(2000);
                    Socket s = c;
                    if (tls) {
                        SSLSocket t = (SSLSocket) sslCtx.getSocketFactory().createSocket(c, null, c.getPort(), false);
                        t.setUseClientMode(false);
                        s = t;
                    }
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    while (!head.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
                        int b = in.read();
                        if (b < 0) break;
                        head.write(b);
                    }
                    String h = head.toString(StandardCharsets.ISO_8859_1);
                    requestLine.complete(h.substring(0, Math.max(0, h.indexOf("\r\n"))));
                    s.getOutputStream().write(OK_REPLY.getBytes(StandardCharsets.ISO_8859_1));
                    s.getOutputStream().flush();
                } catch (Exception e) {
                    requestLine.completeExceptionally(e);
                }
            });
        }

        int port() { return socket.getLocalPort(); }

        String requestLine() throws Exception { return requestLine.get(2, TimeUnit.SECONDS); }

        /** True when the proxy opened a connection to this server before the call. */
        boolean hasConnectionWaiting() throws Exception {
            socket.setSoTimeout(1);
            try {
                socket.accept().close();
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            }
        }

        public void close() throws Exception { socket.close(); }
    }

    /** Trusts every certificate: the proxy presents one made by its own CA. */
    private static SSLContext trustAll() throws Exception {
        TrustManager tm = new X509ExtendedTrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{ tm }, null);
        return ctx;
    }

    public void run() {
        Harness.suite("invisible proxying, needs loopback sockets");

        // One server for the suite: its constructor generates an RSA key, which
        // would push every case past the slow threshold. Each case starts and
        // stops its own listener on its own free port.
        Path dir = null;
        SuiteContext ctx = new SuiteContext();
        ProxyServer server = null;
        try {
            dir = Files.createTempDirectory("opentrap-invisible");
            server = new ProxyServer(ctx, dir);
            ProxyServer srv = server;
            AtomicInteger logged = new AtomicInteger();
            srv.setObserver(new ProxyServer.Observer() {
                public void exchangeLogged(LogEntry entry) { logged.incrementAndGet(); }
                public void statusChanged(String text) {}
            });

            Harness.test("with invisible proxying off a request without an absolute URL is refused with 400 and not forwarded", () -> {
                try (Backend b = new Backend(false, false)) {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String reply = exchange(port, "GET /x HTTP/1.1\r\nHost: 127.0.0.1:" + b.port() + "\r\n\r\n");
                        Expect.isTrue(reply.startsWith("HTTP/1.1 400 Bad Request"), "reply: " + reply);
                        Expect.isTrue(reply.contains("Support invisible proxying"), "names the setting: " + reply);
                        Expect.isTrue(reply.contains("port " + port), "names the port: " + reply);
                        Expect.isFalse(b.hasConnectionWaiting(), "the backend was contacted");
                        String post = exchange(port, "POST /x HTTP/1.1\r\nHost: 127.0.0.1:" + b.port()
                                + "\r\nContent-Length: 5\r\n\r\nhello");
                        Expect.isTrue(post.startsWith("HTTP/1.1 400 Bad Request"), "post: " + post);
                        Expect.isTrue(post.contains("Support invisible proxying"), "post names the setting: " + post);
                        Expect.isFalse(b.hasConnectionWaiting(), "the backend was contacted by the POST");
                        String noHost = exchange(port, "GET /x HTTP/1.0\r\n\r\n");
                        Expect.isTrue(noHost.startsWith("HTTP/1.1 400 Bad Request"), "no Host: " + noHost);
                        Expect.equal(logged.get(), 0, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("with invisible proxying off an absolute-form request is forwarded as before", () -> {
                try (Backend b = new Backend(true, false)) {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String reply = exchange(port, "GET http://127.0.0.1:" + b.port()
                                + "/x HTTP/1.1\r\nHost: 127.0.0.1:" + b.port() + "\r\n\r\n");
                        Expect.isTrue(reply.endsWith("ok"), "reply: " + reply);
                        Expect.equal(b.requestLine(), "GET /x HTTP/1.1", "backend request line");
                        Expect.equal(logged.get(), 1, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("with invisible proxying off the listener's own page is still served to a browser that opens it directly", () -> {
                try {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String page = exchange(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
                        Expect.isTrue(page.startsWith("HTTP/1.1 200"), "page: " + page);
                        Expect.isTrue(page.contains("OpenTRAP"), "page names the proxy: " + page);
                        String named = exchange(port, "GET / HTTP/1.1\r\nHost: opentrap\r\n\r\n");
                        Expect.isTrue(named.startsWith("HTTP/1.1 200"), "named: " + named);
                        Expect.isTrue(named.contains("OpenTRAP"), "named page: " + named);
                        String der = exchange(port, "GET /cert/der HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n");
                        Expect.isTrue(der.contains("Content-Type: application/x-x509-ca-cert"), "der: " + der);
                        Expect.equal(logged.get(), 0, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("with invisible proxying off a request inside a CONNECT tunnel is forwarded", () -> {
                try (Backend b = new Backend(true, true)) {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), port)) {
                        raw.setSoTimeout(2000);
                        OutputStream out = raw.getOutputStream();
                        InputStream in = raw.getInputStream();
                        out.write(("CONNECT 127.0.0.1:" + b.port() + " HTTP/1.1\r\nHost: 127.0.0.1:" + b.port()
                                + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        ByteArrayOutputStream head = new ByteArrayOutputStream();
                        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                            int c = in.read();
                            Expect.isTrue(c >= 0, "the CONNECT answer ended early");
                            head.write(c);
                        }
                        Expect.isTrue(head.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200"),
                                "CONNECT answer: " + head);
                        try (SSLSocket tls = (SSLSocket) trustAll().getSocketFactory()
                                .createSocket(raw, "127.0.0.1", b.port(), true)) {
                            tls.setUseClientMode(true);
                            tls.getOutputStream().write(("GET /t HTTP/1.1\r\nHost: 127.0.0.1:" + b.port()
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            tls.getOutputStream().flush();
                            String reply = readAll(tls.getInputStream());
                            Expect.isTrue(reply.endsWith("ok"), "reply: " + reply);
                        }
                        Expect.equal(b.requestLine(), "GET /t HTTP/1.1", "backend request line");
                        Expect.equal(logged.get(), 1, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("with invisible proxying on a request without an absolute URL is forwarded by its Host header", () -> {
                try (Backend b = new Backend(true, false)) {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    l.invisible = true;
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String reply = exchange(port, "GET /x HTTP/1.1\r\nHost: 127.0.0.1:" + b.port() + "\r\n\r\n");
                        Expect.isTrue(reply.endsWith("ok"), "reply: " + reply);
                        Expect.equal(b.requestLine(), "GET /x HTTP/1.1", "backend request line");
                        Expect.equal(logged.get(), 1, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("with invisible proxying off a listener with a redirect forwards a request without an absolute URL to its redirect target", () -> {
                try (Backend b = new Backend(true, false)) {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    l.redirectHost = "127.0.0.1";
                    l.redirectPort = b.port();
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String reply = exchange(port, "GET /x HTTP/1.1\r\nHost: example.invalid\r\n\r\n");
                        Expect.isTrue(reply.endsWith("ok"), "reply: " + reply);
                        Expect.equal(b.requestLine(), "GET /x HTTP/1.1", "backend request line");
                        Expect.equal(logged.get(), 1, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            Harness.test("the invisible proxying suite sets up", () -> { throw new RuntimeException(e); });
        } finally {
            if (server != null) server.stopAll();
            ctx.store().closeStore();
            ctx.wsStore().closeStore();
            try { if (dir != null) deleteTree(dir); } catch (Exception _) {}
        }
    }
}
