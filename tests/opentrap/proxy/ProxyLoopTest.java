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

    public void run() {
        Harness.suite("proxy loop, needs loopback sockets");

        Harness.test("a request that resolves to the proxy's own listener is refused once and logged once", () -> {
            Path dir = null;
            SuiteContext ctx = new SuiteContext();
            ProxyServer server = null;
            try {
                dir = Files.createTempDirectory("opentrap-loop");
                server = new ProxyServer(ctx, dir);
                AtomicInteger logged = new AtomicInteger();
                server.setObserver(new ProxyServer.Observer() {
                    public void exchangeLogged(LogEntry entry) { logged.incrementAndGet(); }
                    public void statusChanged(String text) {}
                });
                int port = freePort();
                ProxyListener l = new ProxyListener(port);
                l.bind = ProxyListener.Bind.ALL;
                server.listeners().add(l);
                server.start(l);

                try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    s.setSoTimeout(2000);
                    OutputStream out = s.getOutputStream();
                    out.write(("GET http://0.0.0.0:" + port + "/ HTTP/1.1\r\nHost: 0.0.0.0:" + port
                            + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    InputStream in = s.getInputStream();
                    byte[] chunk = new byte[4096];
                    for (int n; (n = in.read(chunk)) >= 0; ) buf.write(chunk, 0, n);
                    String reply = buf.toString(StandardCharsets.ISO_8859_1);
                    String status = reply.substring(0, reply.indexOf("\r\n"));
                    Expect.isTrue(status.contains("502"), "status line: " + status);
                    Expect.isTrue(reply.contains("loop refused: 0.0.0.0:" + port + " is a listener of this proxy"),
                            "body names the refusal: " + reply);
                }
                Expect.equal(logged.get(), 1, "exchanges logged");
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (server != null) server.stopAll();
                ctx.store().closeStore();
                ctx.wsStore().closeStore();
                try { if (dir != null) deleteTree(dir); } catch (Exception _) {}
            }
        });

        Harness.test("a stopped listener's port is no longer refused", () -> {
            Path dir = null;
            SuiteContext ctx = new SuiteContext();
            ProxyServer server = null;
            try {
                dir = Files.createTempDirectory("opentrap-loop");
                server = new ProxyServer(ctx, dir);
                int port = freePort();
                ProxyListener l = new ProxyListener(port);
                server.listeners().add(l);
                server.start(l);
                Expect.isTrue(server.listening().stream().anyMatch(a -> a.getPort() == port),
                        "the running listener is listed");
                server.stop(l);
                Expect.isFalse(server.listening().stream().anyMatch(a -> a.getPort() == port),
                        "the stopped listener is not listed");
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (server != null) server.stopAll();
                ctx.store().closeStore();
                ctx.wsStore().closeStore();
                try { if (dir != null) deleteTree(dir); } catch (Exception _) {}
            }
        });
    }
}
