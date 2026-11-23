package opentrap.proxy;

import opentrap.core.LogEntry;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/** The proxy against its own listener, over real loopback sockets. */
public final class ProxyLoopTest {

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

    /** Send a raw request to the proxy on loopback and read the reply until EOF. */
    private static String exchange(int port, String request) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) >= 0; ) buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    public void run() {
        Harness.suite("proxy loop, needs loopback sockets");

        // One server for the suite: its constructor generates an RSA key, which
        // would push every case past the slow threshold. Each case starts and
        // stops its own listener on its own free port.
        Path dir = null;
        SuiteContext ctx = new SuiteContext();
        ProxyServer server = null;
        try {
            dir = Files.createTempDirectory("opentrap-loop");
            server = new ProxyServer(ctx, dir);
            ProxyServer srv = server;
            AtomicInteger logged = new AtomicInteger();
            srv.setObserver(new ProxyServer.Observer() {
                public void exchangeLogged(LogEntry entry) { logged.incrementAndGet(); }
                public void statusChanged(String text) {}
            });

            Harness.test("a request that resolves to the proxy's own listener is refused once and logged once", () -> {
                try {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        String reply = exchange(port, "GET http://0.0.0.0:" + port + "/ HTTP/1.1\r\nHost: 0.0.0.0:"
                                + port + "\r\n\r\n");
                        Expect.isTrue(reply.startsWith("HTTP/1.1 502"), "reply: " + reply);
                        Expect.isTrue(reply.contains("loop refused: 0.0.0.0:" + port + " is a listener of this proxy"),
                                "body names the refusal: " + reply);
                        Expect.equal(logged.get(), 1, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("a stopped listener's port is no longer refused", () -> {
                try {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    InetSocketAddress self = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
                    try {
                        Expect.isTrue(srv.listening().stream().anyMatch(a -> a.getPort() == port),
                                "the running listener is listed");
                        Expect.isTrue(LoopGuard.reaches(self, srv.listening(), a -> false),
                                "the running listener is reached");
                    } finally {
                        srv.stop(l);
                    }
                    Expect.isFalse(srv.listening().stream().anyMatch(a -> a.getPort() == port),
                            "the stopped listener is not listed");
                    Expect.isFalse(LoopGuard.reaches(self, srv.listening(), a -> false),
                            "the stopped listener is not reached");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Harness.test("a WebSocket upgrade that resolves to the proxy's own listener is refused without looping", () -> {
                try {
                    int port = freePort();
                    ProxyListener l = new ProxyListener(port);
                    srv.listeners().add(l);
                    srv.start(l);
                    logged.set(0);
                    try {
                        // A SocketTimeoutException here means the upgrade looped
                        // instead of being refused and the connection closed.
                        exchange(port, "GET http://0.0.0.0:" + port + "/ HTTP/1.1\r\nHost: 0.0.0.0:" + port
                                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
                        Expect.equal(logged.get(), 0, "exchanges logged");
                    } finally {
                        srv.stop(l);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            Harness.test("the proxy loop suite sets up", () -> { throw new RuntimeException(e); });
        } finally {
            if (server != null) server.stopAll();
            ctx.store().closeStore();
            ctx.wsStore().closeStore();
            try { if (dir != null) deleteTree(dir); } catch (Exception _) {}
        }
    }
}
