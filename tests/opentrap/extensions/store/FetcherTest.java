package opentrap.extensions.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import opentrap.core.UpstreamProxy;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.TestTls;
import opentrap.testkit.ZapFixtures;

/** Feed fetches and add-on downloads against a loopback HTTPS server: HTTPS only, caps, checksums, proxies. */
public final class FetcherTest {

    private static final String IP = "127.0.0.1";

    /** A loopback HTTPS server; {@code requests} counts what reached it. */
    private static final class Server implements AutoCloseable {
        final HttpsServer https;
        final AtomicInteger requests = new AtomicInteger();

        Server(TestTls.Pair tls, HttpHandler handler) throws IOException {
            https = HttpsServer.create(new InetSocketAddress(IP, 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            https.createContext("/", ex -> {
                requests.incrementAndGet();
                handler.handle(ex);
            });
            https.start();
        }

        String url(String path) { return "https://" + IP + ":" + https.getAddress().getPort() + path; }

        @Override public void close() { https.stop(0); }
    }

    private static void answer(HttpExchange ex, byte[] body) throws IOException {
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static Fetcher fetcher(TestTls.Pair tls) { return new Fetcher(tls.client(), host -> null); }

    private static Path tmp() {
        try { return Files.createTempDirectory("opentrap-fetch"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static List<String> names(Path dir) {
        try (Stream<Path> s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Entry entry(String id, String version, long size, String url, String sha256) {
        return new Entry(Kind.ZAP_ADDON, Source.ZAP_ID, id, "Add-on " + id, "", "", Version.parse(version),
                Status.RELEASE, "", "", "", size, id + "-release-" + version + ".zap", url, sha256, List.of());
    }

    /** Runs the body and returns the message of the IOException it must throw. */
    private static String failure(String what, IoAction body) {
        try {
            body.run();
        } catch (IOException e) {
            return String.valueOf(e.getMessage());
        }
        Expect.isTrue(false, what + ": should have thrown");
        return "";
    }

    private interface IoAction { void run() throws IOException; }

    private static final Fetcher.Progress NO_PROGRESS = (done, total) -> {};

    public void run() {
        Harness.suite("Extension store downloads");

        Harness.test("a feed within the cap is fetched", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()))) {
                Expect.equal(new String(fetcher(tls).fetch(s.url("/feed.xml"), 1024)), "<ZAP/>", "body");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a feed larger than the cap is refused", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> answer(ex, new byte[1025]))) {
                String msg = failure("fetch", () -> fetcher(tls).fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.contains("larger than 1024 bytes"), "names the cap: " + msg);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a feed without a declared length is cut off at the cap", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> {
                ex.sendResponseHeaders(200, 0);  // chunked: no Content-Length to refuse up front
                try (OutputStream o = ex.getResponseBody()) { o.write(new byte[5000]); }
            })) {
                String msg = failure("fetch", () -> fetcher(tls).fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.contains("larger than 1024 bytes"), "names the cap: " + msg);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a plain HTTP URL is refused before any connection", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            String msg = failure("fetch", () -> fetcher(tls).fetch("http://127.0.0.1:1/x", 10));
            Expect.isTrue(msg.contains("HTTPS"), "says HTTPS: " + msg);
        });

        Harness.test("a redirect to plain HTTP is refused", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> redirect(ex, "http://" + IP + ":1/feed.xml"))) {
                String msg = failure("fetch", () -> fetcher(tls).fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.contains("Refused a redirect to a non-HTTPS address"), "refused: " + msg);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a redirect to an HTTPS address is followed", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> {
                if (ex.getRequestURI().getPath().equals("/moved")) answer(ex, "<ZAP/>".getBytes());
                else redirect(ex, "/moved");
            })) {
                Expect.equal(new String(fetcher(tls).fetch(s.url("/feed.xml"), 1024)), "<ZAP/>", "body after redirect");
                Expect.equal(s.requests.get(), 2, "two requests");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a redirect loop is given up after five redirects", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> redirect(ex, "/again"))) {
                String msg = failure("fetch", () -> fetcher(tls).fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.contains("redirects"), "says redirects: " + msg);
                Expect.equal(s.requests.get(), 6, "the first request and five redirects");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an error status fails the fetch", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> { ex.sendResponseHeaders(404, -1); ex.close(); })) {
                String msg = failure("fetch", () -> fetcher(tls).fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.contains("404"), "names the status: " + msg);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a downloaded add-on is verified and moved into place", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path src = tmp();
            Path dir = tmp();
            Path zap = ZapFixtures.zap(src, "spider", "0.20.0", "release");
            try {
                byte[] bytes = Files.readAllBytes(zap);
                long[] last = { -1, -1 };
                try (Server s = new Server(tls, ex -> answer(ex, bytes))) {
                    Entry e = entry("spider", "0.20.0", bytes.length, s.url("/spider.zap"), ZapFixtures.sha256(zap));
                    Path got = fetcher(tls).download(e, dir, (done, total) -> { last[0] = done; last[1] = total; },
                            () -> false);
                    Expect.equal(got, dir.resolve("spider-release-0.20.0.zap"), "returned path");
                    Expect.equal(names(dir), List.of("spider-release-0.20.0.zap"), "only the add-on, no part file");
                    Expect.isTrue(java.util.Arrays.equals(Files.readAllBytes(got), bytes), "same bytes");
                    Expect.equal(Files.getPosixFilePermissions(got), opentrap.core.OwnerOnly.FILE, "owner-only");
                    Expect.equal(last[0], (long) bytes.length, "progress reached the end");
                    Expect.equal(last[1], (long) bytes.length, "progress total is the declared size");
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a checksum mismatch leaves nothing behind", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path src = tmp();
            Path dir = tmp();
            Path zap = ZapFixtures.zap(src, "spider", "0.20.0", "release");
            try {
                byte[] bytes = Files.readAllBytes(zap);
                try (Server s = new Server(tls, ex -> answer(ex, bytes))) {
                    Entry e = entry("spider", "0.20.0", bytes.length, s.url("/spider.zap"), "0".repeat(64));
                    String msg = failure("download", () -> fetcher(tls).download(e, dir, NO_PROGRESS, () -> false));
                    Expect.isTrue(msg.contains("Checksum mismatch for spider-release-0.20.0.zap"), "mismatch: " + msg);
                    Expect.equal(names(dir), List.of(), "dir is empty");
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a download larger than its declared size is cut off", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path dir = tmp();
            try (Server s = new Server(tls, ex -> answer(ex, new byte[5000]))) {
                Entry e = entry("spider", "0.20.0", 100, s.url("/spider.zap"), "0".repeat(64));
                String msg = failure("download", () -> fetcher(tls).download(e, dir, NO_PROGRESS, () -> false));
                Expect.isTrue(msg.contains("larger than 100 bytes"), "names the cap: " + msg);
                Expect.equal(names(dir), List.of(), "dir is empty");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a download that is not an add-on is refused after its checksum matches", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path src = tmp();
            Path dir = tmp();
            try {
                Path junk = Files.write(src.resolve("junk"), "not a zip".getBytes());
                byte[] bytes = Files.readAllBytes(junk);
                try (Server s = new Server(tls, ex -> answer(ex, bytes))) {
                    Entry e = entry("spider", "0.20.0", bytes.length, s.url("/spider.zap"), ZapFixtures.sha256(junk));
                    String msg = failure("download", () -> fetcher(tls).download(e, dir, NO_PROGRESS, () -> false));
                    Expect.isTrue(msg.contains("spider-release-0.20.0.zap"), "names the file: " + msg);
                    Expect.equal(names(dir), List.of(), "dir is empty");
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a download of another version is refused", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path src = tmp();
            Path dir = tmp();
            Path zap = ZapFixtures.zap(src, "spider", "0.19.0", "release");
            try {
                byte[] bytes = Files.readAllBytes(zap);
                try (Server s = new Server(tls, ex -> answer(ex, bytes))) {
                    Entry e = entry("spider", "0.20.0", bytes.length, s.url("/spider.zap"), ZapFixtures.sha256(zap));
                    String msg = failure("download", () -> fetcher(tls).download(e, dir, NO_PROGRESS, () -> false));
                    Expect.isTrue(msg.contains("version"), "names the version: " + msg);
                    Expect.equal(names(dir), List.of(), "dir is empty");
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a cancelled download leaves nothing behind", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path dir = tmp();
            try (Server s = new Server(tls, ex -> answer(ex, new byte[100_000]))) {
                Entry e = entry("spider", "0.20.0", 100_000, s.url("/spider.zap"), "0".repeat(64));
                boolean[] cancel = { false };
                String msg = failure("download", () -> fetcher(tls).download(e, dir,
                        (done, total) -> { if (done > 0) cancel[0] = true; }, () -> cancel[0]));
                Expect.isTrue(msg.contains("cancelled"), "says cancelled: " + msg);
                Expect.equal(names(dir), List.of(), "dir is empty");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a SOCKS upstream fails the fetch instead of going direct", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            UpstreamProxy socks = new UpstreamProxy();
            socks.type = UpstreamProxy.Type.SOCKS;
            socks.host = IP;
            socks.port = 1;
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()))) {
                Fetcher f = new Fetcher(tls.client(), host -> socks);
                String msg = failure("fetch", () -> f.fetch(s.url("/feed.xml"), 1024));
                Expect.equal(msg, "Add-on sources cannot be fetched through a SOCKS upstream proxy", "message");
                Expect.equal(s.requests.get(), 0, "nothing reached the server");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an HTTP upstream carries the fetch", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            AtomicInteger connects = new AtomicInteger();
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()));
                 ConnectProxy proxy = new ConnectProxy(connects)) {
                UpstreamProxy http = new UpstreamProxy();
                http.host = IP;
                http.port = proxy.port();
                String[] asked = { null };
                Fetcher f = new Fetcher(tls.client(), host -> { asked[0] = host; return http; });
                Expect.equal(new String(f.fetch(s.url("/feed.xml"), 1024)), "<ZAP/>", "body through the proxy");
                Expect.equal(asked[0], IP, "upstream looked up by host");
                Expect.equal(connects.get(), 1, "one CONNECT through the proxy");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an untrusted certificate fails the fetch", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()))) {
                Fetcher f = new Fetcher(SSLContext.getDefault(), host -> null);
                failure("fetch", () -> f.fetch(s.url("/feed.xml"), 1024));
                Expect.equal(s.requests.get(), 0, "no request was sent");
            } catch (IOException | NoSuchAlgorithmException e) { throw new RuntimeException(e); }
        });
    }

    /** A minimal HTTP CONNECT proxy on loopback that counts tunnels and pipes bytes both ways. */
    private static final class ConnectProxy implements AutoCloseable {
        private final java.net.ServerSocket socket;

        ConnectProxy(AtomicInteger connects) throws IOException {
            socket = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName(IP));
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try {
                        java.net.Socket client = socket.accept();
                        Thread.ofVirtual().start(() -> tunnel(client, connects));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() { return socket.getLocalPort(); }

        private static void tunnel(java.net.Socket client, AtomicInteger connects) {
            try (client) {
                var in = client.getInputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int b = in.read();
                    if (b < 0) return;
                    head.append((char) b);
                }
                String[] target = head.toString().split(" ")[1].split(":");
                try (java.net.Socket upstream = new java.net.Socket(target[0], Integer.parseInt(target[1]))) {
                    connects.incrementAndGet();
                    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
                    client.getOutputStream().flush();
                    Thread back = Thread.ofVirtual().start(() -> {
                        try { upstream.getInputStream().transferTo(client.getOutputStream()); }
                        catch (IOException ignored) { }
                    });
                    try { in.transferTo(upstream.getOutputStream()); } catch (IOException ignored) { }
                    upstream.shutdownOutput();
                    back.join();
                }
            } catch (IOException | InterruptedException ignored) {
                // the test sees a failed fetch
            }
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
