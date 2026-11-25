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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import opentrap.core.UpstreamProxy;
import opentrap.testkit.ConnectProxy;
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
            // a thread per exchange, so a handler that blocks does not hold up the next request
            https.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()));
                 ConnectProxy proxy = new ConnectProxy(null)) {
                String[] asked = { null };
                Fetcher f = new Fetcher(tls.client(), host -> { asked[0] = host; return upstream(proxy, "", ""); });
                Expect.equal(new String(f.fetch(s.url("/feed.xml"), 1024)), "<ZAP/>", "body through the proxy");
                Expect.equal(asked[0], IP, "upstream looked up by host");
                Expect.equal(proxy.tunnels.get(), 1, "one CONNECT through the proxy");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an HTTP upstream that asks for a login gets it on the CONNECT only", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            List<String> seenByServer = Collections.synchronizedList(new ArrayList<>());
            try (Server s = new Server(tls, ex -> {
                     for (String h : List.of("Authorization", "Proxy-Authorization")) {
                         if (ex.getRequestHeaders().containsKey(h)) seenByServer.add(h);
                     }
                     answer(ex, "<ZAP/>".getBytes());
                 });
                 ConnectProxy proxy = new ConnectProxy("tester:secret")) {
                Fetcher f = new Fetcher(tls.client(), host -> upstream(proxy, "tester", "secret"));
                Expect.equal(new String(f.fetch(s.url("/feed.xml"), 1024)), "<ZAP/>", "body through the proxy");
                Expect.equal(proxy.tunnels.get(), 1, "the tunnel was opened");
                Expect.isTrue(proxy.logins.contains("Basic " + Base64.getEncoder().encodeToString("tester:secret".getBytes())),
                        "Basic login sent on the CONNECT: " + proxy.logins);
                Expect.equal(seenByServer, List.of(), "no credentials reached the server");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an HTTP upstream login without credentials fails with the proxy named", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()));
                 ConnectProxy proxy = new ConnectProxy("tester:secret")) {
                Fetcher f = new Fetcher(tls.client(), host -> upstream(proxy, "", ""));
                String msg = failure("fetch", () -> f.fetch(s.url("/feed.xml"), 1024));
                Expect.equal(msg, "The upstream proxy " + IP + ":" + proxy.port() + " asked for a login (HTTP 407)",
                        "message");
                Expect.equal(s.requests.get(), 0, "nothing reached the server");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("an untrusted certificate fails the fetch", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls, ex -> answer(ex, "<ZAP/>".getBytes()))) {
                Fetcher f = new Fetcher(SSLContext.getDefault(), host -> null);
                String msg = failure("fetch", () -> f.fetch(s.url("/feed.xml"), 1024));
                Expect.isTrue(msg.startsWith("Could not reach " + IP + ": "), "names the host: " + msg);
                Expect.equal(s.requests.get(), 0, "no request was sent");
            } catch (IOException | NoSuchAlgorithmException e) { throw new RuntimeException(e); }
        });

        Harness.test("a download cancelled while the server is silent stops at once", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path dir = tmp();
            AtomicBoolean cancel = new AtomicBoolean();
            CountDownLatch release = new CountDownLatch(1);
            try (Server s = new Server(tls, ex -> {
                ex.sendResponseHeaders(200, 100_000);
                ex.getResponseBody().flush();
                cancel.set(true);  // the client now waits on a body that does not come
                await(release);
                ex.close();
            })) {
                try {
                    Entry e = entry("spider", "0.20.0", 100_000, s.url("/spider.zap"), "0".repeat(64));
                    String msg = failure("download", () -> fetcher(tls).download(e, dir, NO_PROGRESS, cancel::get));
                    Expect.equal(msg, "Download cancelled", "message");
                    Expect.equal(names(dir), List.of(), "dir is empty");
                } finally {
                    release.countDown();
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a download that stops sending is given up as stalled", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path dir = tmp();
            CountDownLatch release = new CountDownLatch(1);
            try (Server s = new Server(tls, ex -> {
                ex.sendResponseHeaders(200, 100_000);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(new byte[1000]);
                    o.flush();
                    await(release);
                }
            })) {
                try {
                    Fetcher f = fetcher(tls);
                    f.stallMs = 100;
                    f.watchMs = 20;
                    Entry e = entry("spider", "0.20.0", 100_000, s.url("/spider.zap"), "0".repeat(64));
                    String msg = failure("download", () -> f.download(e, dir, NO_PROGRESS, () -> false));
                    Expect.isTrue(msg.startsWith(e.location() + " stalled"), "says stalled: " + msg);
                    Expect.equal(names(dir), List.of(), "dir is empty");
                } finally {
                    release.countDown();
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("two downloads of one id into one folder each stand on their own bytes", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path src = tmp();
            Path dir = tmp();
            Path zap = ZapFixtures.zap(src, "spider", "0.20.0", "release");
            try {
                byte[] good = Files.readAllBytes(zap);
                byte[] junk = new byte[good.length];
                Path junkFile = Files.write(src.resolve("junk"), junk);
                CyclicBarrier bothMidway = new CyclicBarrier(2);
                try (Server s = new Server(tls, ex -> {
                    byte[] body = ex.getRequestURI().getPath().equals("/good.zap") ? good : junk;
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream o = ex.getResponseBody()) {
                        o.write(body, 0, body.length / 2);
                        o.flush();
                        // both part files exist and are half written before either download goes on
                        try { bothMidway.await(2, TimeUnit.SECONDS); } catch (Exception e) { throw new IOException(e); }
                        o.write(body, body.length / 2, body.length - body.length / 2);
                    }
                })) {
                    Fetcher f = fetcher(tls);
                    Entry goodEntry = entry("spider", "0.20.0", good.length, s.url("/good.zap"), ZapFixtures.sha256(zap));
                    Entry junkEntry = entry("spider", "0.20.0", junk.length, s.url("/junk.zap"), ZapFixtures.sha256(junkFile));
                    Object[] results = new Object[2];
                    Thread a = Thread.ofVirtual().start(() -> results[0] = attempt(() -> f.download(goodEntry, dir, NO_PROGRESS, () -> false)));
                    Thread b = Thread.ofVirtual().start(() -> results[1] = attempt(() -> f.download(junkEntry, dir, NO_PROGRESS, () -> false)));
                    a.join();
                    b.join();
                    Expect.equal(results[0], dir.resolve("spider-release-0.20.0.zap"), "the good download is stored");
                    Expect.isTrue(results[1] instanceof IOException ex && ex.getMessage().contains("not a readable zip"),
                            "the junk download fails on its own bytes: " + results[1]);
                    Expect.equal(names(dir), List.of("spider-release-0.20.0.zap"), "only the good file is left");
                    Expect.isTrue(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("spider-release-0.20.0.zap")), good),
                            "the stored file holds the good bytes");
                }
            } catch (IOException | InterruptedException e) { throw new RuntimeException(e); }
        });
    }

    private interface IoCall { Object call() throws IOException; }

    /** The call's result, or the IOException it threw. */
    private static Object attempt(IoCall call) {
        try { return call.call(); } catch (IOException e) { return e; }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static UpstreamProxy upstream(ConnectProxy proxy, String user, String pass) {
        UpstreamProxy p = new UpstreamProxy();
        p.host = IP;
        p.port = proxy.port();
        p.user = user;
        p.pass = pass;
        return p;
    }
}
