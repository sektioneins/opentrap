package opentrap.extensions.store;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.TestTls;
import opentrap.testkit.ZapFixtures;

/** The catalogue of every source's add-ons: cached for offline use, a failed refresh keeps the last good copy. */
public final class CatalogueTest {

    private static final String IP = "127.0.0.1";
    private static final Version ENGINE = Version.parse("2.17.0");
    private static final String HASH = "SHA-256:" + "a".repeat(64);

    /** A loopback HTTPS server; {@code requests} counts what reached it. */
    private static final class Server implements AutoCloseable {
        final HttpsServer https;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> body = new AtomicReference<>("");
        final AtomicBoolean fail = new AtomicBoolean();

        Server(TestTls.Pair tls) throws IOException {
            https = HttpsServer.create(new InetSocketAddress(IP, 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            https.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            HttpHandler h = ex -> {
                requests.incrementAndGet();
                if (fail.get()) {
                    ex.sendResponseHeaders(500, -1);
                    ex.close();
                    return;
                }
                byte[] b = body.get().getBytes();
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            };
            https.createContext("/", h);
            https.start();
        }

        String base() { return "https://" + IP + ":" + https.getAddress().getPort() + "/"; }

        Source source(String id, boolean enabled) {
            return new Source(id, id, SourceType.FEED_URL, base() + id + ".xml", base(), enabled);
        }

        void offer(String id, String version) {
            body.set(ZapFixtures.feed(ZapFixtures.addon(id, version, "release", base() + id + ".zap", HASH)));
        }

        @Override public void close() { https.stop(0); }
    }

    private static Path tmp() {
        try { return Files.createTempDirectory("opentrap-catalogue"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Catalogue catalogue(Path dir, TestTls.Pair tls) {
        return new Catalogue(dir, ENGINE, new Fetcher(tls.client(), host -> null));
    }

    public void run() {
        Harness.suite("Extension store catalogue");

        Harness.test("opening the catalogue makes no request", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls)) {
                s.offer("alpha", "1.0.0");
                Path dir = tmp();
                List<Source> sources = List.of(s.source("one", true));
                Catalogue c = catalogue(dir, tls);
                c.refresh(sources, () -> false);
                int after = s.requests.get();
                Catalogue reopened = catalogue(dir, tls);
                reopened.loadCache(sources);
                Expect.equal(s.requests.get(), after, "requests after loadCache");
                Expect.equal(reopened.offers("alpha").size(), 1, "offers come from the cache");
                Expect.equal(reopened.status("one").fetched(), c.status("one").fetched(), "fetch time is kept");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a failed refresh keeps the last good copy and says why", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls)) {
                s.offer("alpha", "1.0.0");
                Catalogue c = catalogue(tmp(), tls);
                List<Source> sources = List.of(s.source("one", true));
                c.refresh(sources, () -> false);
                Instant first = c.status("one").fetched();
                Expect.isTrue(first != null, "fetched after the first refresh");
                Expect.isTrue(c.status("one").error() == null, "no error after the first refresh");
                s.fail.set(true);
                c.refresh(sources, () -> false);
                Expect.equal(c.offers("alpha").size(), 1, "entry kept");
                Expect.isTrue(c.status("one").error() != null, "error set");
                Expect.equal(c.status("one").fetched(), first, "earlier fetch time kept");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a switched-off source offers nothing", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls)) {
                s.offer("alpha", "1.0.0");
                Catalogue c = catalogue(tmp(), tls);
                c.refresh(List.of(s.source("one", false)), () -> false);
                Expect.equal(c.offers("alpha").size(), 0, "no offers");
                Expect.equal(c.ids().size(), 0, "no ids");
                Expect.equal(s.requests.get(), 0, "a switched-off source is not contacted");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("two sources offering one id give both offers, newest first", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server a = new Server(tls); Server b = new Server(tls)) {
                a.offer("alpha", "1.0.0");
                b.offer("alpha", "1.2.0");
                Catalogue c = catalogue(tmp(), tls);
                c.refresh(List.of(a.source("old", true), b.source("new", true)), () -> false);
                List<Entry> offers = c.offers("alpha");
                Expect.equal(offers.size(), 2, "both offers");
                Expect.equal(offers.get(0).sourceId(), "new", "newest first");
                Expect.equal(offers.get(1).sourceId(), "old", "older second");
                Expect.equal(c.ids(), Set.of("alpha"), "ids");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a folder source shows a new build on refresh without a cache file", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path folder = tmp();
            Path dir = tmp();
            ZapFixtures.zap(folder, "alpha", "1.0.0", "release");
            Source src = new Source("local", "Local", SourceType.LOCAL_FOLDER, folder.toString(), "", true);
            Catalogue c = catalogue(dir, tls);
            c.loadCache(List.of(src));
            Expect.equal(c.offers("alpha").size(), 1, "scanned on loadCache");
            ZapFixtures.zap(folder, "alpha", "1.1.0", "release");
            c.refresh(List.of(src), () -> false);
            Expect.equal(c.offers("alpha").get(0).version(), Version.parse("1.1.0"), "new build shown");
            Expect.isTrue(c.status("local").fetched() != null, "scan time");
            Expect.isTrue(!Files.exists(dir.resolve("cache").resolve("local")), "no cache file");
        });

        Harness.test("a missing folder is a source error", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Source src = new Source("local", "Local", SourceType.LOCAL_FOLDER,
                    tmp().resolve("gone").toString(), "", true);
            Catalogue c = catalogue(tmp(), tls);
            c.loadCache(List.of(src));
            Expect.isTrue(c.status("local").error() != null, "error");
            Expect.equal(c.offers("alpha").size(), 0, "no offers");
        });

        Harness.test("a corrupt cache file is a source error, not an exception", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Path dir = tmp();
            try {
                Files.createDirectories(dir.resolve("cache"));
                Files.writeString(dir.resolve("cache").resolve("one"), "not a time\n<ZAP/>");
            } catch (IOException e) { throw new UncheckedIOException(e); }
            Source src = new Source("one", "one", SourceType.FEED_URL, "https://127.0.0.1:1/x.xml",
                    "https://127.0.0.1:1/", true);
            Catalogue c = catalogue(dir, tls);
            c.loadCache(List.of(src));
            Expect.isTrue(c.status("one").error() != null, "error");
        });

        Harness.test("a source id that is not a plain name is not written as a cache file", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls)) {
                s.offer("alpha", "1.0.0");
                Path dir = tmp();
                Catalogue c = catalogue(dir, tls);
                Source bad = new Source("../x", "bad", SourceType.FEED_URL, s.base() + "x.xml", s.base(), true);
                c.refresh(List.of(bad), () -> false);
                Expect.isTrue(c.status("../x").error() != null, "error");
                Expect.isTrue(!Files.exists(dir.resolve("x")), "nothing written outside cache");
                Expect.equal(s.requests.get(), 0, "not fetched");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("rejected feed lines are kept in the source status", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server s = new Server(tls)) {
                s.body.set(ZapFixtures.feed(
                        ZapFixtures.addon("alpha", "1.0.0", "release", s.base() + "a.zap", HASH),
                        ZapFixtures.addon("beta", "1.0.0", "release", "https://elsewhere.example/b.zap", HASH)));
                Catalogue c = catalogue(tmp(), tls);
                c.refresh(List.of(s.source("one", true)), () -> false);
                Expect.equal(c.status("one").rejected().size(), 1, "one rejected");
                Expect.isTrue(c.status("one").rejected().get(0).startsWith("beta"), "named");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("a cancelled refresh stops before the next source", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            try (Server a = new Server(tls); Server b = new Server(tls)) {
                a.offer("alpha", "1.0.0");
                b.offer("beta", "1.0.0");
                Catalogue c = catalogue(tmp(), tls);
                c.refresh(List.of(a.source("a", true), b.source("b", true)), () -> a.requests.get() > 0);
                Expect.equal(a.requests.get(), 1, "first fetched");
                Expect.equal(b.requests.get(), 0, "second not fetched");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        Harness.test("the bundled listing is offered with the time it was set", () -> {
            TestTls.Pair tls = TestTls.pair(IP);
            Entry e = new Entry(Kind.ZAP_ADDON, Source.BUNDLED_ID, "alpha", "Alpha", "", "", Version.parse("1.0.0"),
                    Status.RELEASE, "", "", "", 0, "alpha-release-1.0.0.zap", "/x", "0".repeat(64), List.of());
            Catalogue c = catalogue(tmp(), tls);
            c.setBundled(new Listing(List.of(e), List.of()));
            Expect.equal(c.offers("alpha").size(), 1, "offered");
            Expect.isTrue(c.status(Source.BUNDLED_ID).fetched() != null, "time set");
        });
    }
}
