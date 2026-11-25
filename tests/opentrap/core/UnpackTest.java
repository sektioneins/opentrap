package opentrap.core;

import opentrap.testkit.Allocation;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tests how the client decodes a compressed response body: within a limit on
 * the output and a budget shared by every decode at once, and left as the
 * server sent it, with a note, when either would be passed.
 */
public final class UnpackTest {

    private static final int MB16 = 16 << 20;

    /** The tests address the server as 127.0.0.1, so it must not land on ::1. */
    private static final InetAddress LOOPBACK = loopback();

    private static InetAddress loopback() {
        try { return InetAddress.getByName("127.0.0.1"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] gzip(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /** Zlib-wrapped deflate, as a server sends for Content-Encoding: deflate. */
    private static byte[] deflate(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out)) {
            d.write(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static byte[] zeros(int n) { return new byte[n]; }

    private static byte[] repeat(byte[] member, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(member.length * times);
        for (int i = 0; i < times; i++) out.write(member, 0, member.length);
        return out.toByteArray();
    }

    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }

    private static byte[] response(String encoding, byte[] body) {
        return Bytes.concat(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: "
                + encoding + "\r\nContent-Length: " + body.length + "\r\n\r\n"), body);
    }

    private static String limitNote(String enc, String limit) {
        return "Response body left compressed (" + enc + "): it decodes to more than " + limit + ", the unpack limit.";
    }

    /** Applies with a limit and a fresh budget, and checks that the budget was given back. */
    private static String apply(HttpMessage m, int limit) {
        Unpack.Budget budget = new Unpack.Budget(Unpack.SHARED_BYTES);
        String note = Unpack.apply(m, limit, budget);
        Expect.equal(budget.inUse(), 0L, "budget given back");
        return note;
    }

    /** Checks that a decode over the limit returns the note and leaves the message as it was. */
    private static void leftCompressed(String enc, byte[] body, String limit) {
        HttpMessage m = new HttpMessage(response(enc, body));
        byte[] before = m.raw().clone();
        Expect.equal(apply(m, MB16), limitNote(enc, limit), enc + " note");
        Expect.isTrue(Arrays.equals(before, m.raw()), enc + " raw unchanged");
    }

    private static void unpackedTo(HttpMessage m, byte[] want) {
        Expect.isTrue(Arrays.equals(want, m.body()), "body is " + want.length + " decoded bytes");
        Expect.isNull(m.header("Content-Encoding"), "no Content-Encoding");
        Expect.equal(m.header("Content-Length"), String.valueOf(want.length), "Content-Length");
    }

    public void run() {
        Harness.suite("response unpacking");

        Harness.test("a gzip that expands to 10 GB is left compressed with a note", () -> {
            HttpMessage m = new HttpMessage(response("gzip", repeat(gzip(zeros(1 << 20)), 10240)));
            byte[] before = m.raw().clone();
            String[] note = new String[1];
            long bytes = Allocation.during(() -> note[0] = apply(m, MB16));
            Expect.equal(note[0], limitNote("gzip", "16 MB"), "note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
            Expect.isTrue(bytes < 3L * MB16, "allocated " + bytes);
        });

        Harness.test("a body that decodes to exactly the limit is unpacked", () -> {
            HttpMessage m = new HttpMessage(response("gzip", repeat(gzip(zeros(1 << 20)), 16)));
            String[] note = {"not run"};
            long bytes = Allocation.during(() -> note[0] = apply(m, MB16));
            Expect.isNull(note[0], "no note");
            unpackedTo(m, zeros(MB16));
            Expect.equal(m.header("Content-Length"), "16777216", "Content-Length");
            Expect.equal(m.header("Content-Type"), "text/plain", "Content-Type kept");
            Expect.isTrue(bytes < 3L * MB16, "allocated " + bytes);
        });

        Harness.test("deflate and brotli over the limit are left compressed", () -> {
            leftCompressed("deflate", deflate(zeros(MB16 + 1)), "16 MB");
            leftCompressed("br", hex("cfffff7ff82700e2b14020f7fe1f0000010003"), "16 MB");
            HttpMessage m = new HttpMessage(response("br", hex("cfffff7ff82700e2b14020f7fe7f")));
            Expect.isNull(apply(m, MB16), "brotli at the limit has no note");
            unpackedTo(m, zeros(MB16));
        });

        Harness.test("a decode that would pass the shared budget is left compressed", () -> {
            Unpack.Budget budget = new Unpack.Budget(1 << 20);
            Expect.isTrue(budget.take((1 << 20) - 1000), "held by others");
            HttpMessage m = new HttpMessage(response("gzip", gzip(zeros(4096))));
            byte[] before = m.raw().clone();
            Expect.equal(Unpack.apply(m, MB16, budget), "Response body left compressed (gzip): responses decoding"
                    + " at the same time already hold most of the 1 MB they may share.", "note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
            Expect.equal(budget.inUse(), (long) ((1 << 20) - 1000), "only the others' bytes are held");
        });

        Harness.test("a small compressed body decodes as before", () -> {
            byte[] hello = Bytes.bytes("hello");
            HttpMessage g = new HttpMessage(response("gzip", gzip(hello)));
            Expect.isNull(apply(g, MB16), "gzip has no note");
            unpackedTo(g, hello);
            HttpMessage d = new HttpMessage(response("deflate", deflate(hello)));
            Expect.isNull(apply(d, MB16), "deflate has no note");
            unpackedTo(d, hello);
        });

        Harness.test("a body that will not decode stays as received without a note", () -> {
            HttpMessage m = new HttpMessage(response("gzip", Bytes.bytes("not gzip")));
            byte[] before = m.raw().clone();
            Expect.isNull(apply(m, MB16), "no note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
        });

        Harness.test("a body that breaks off after some output gives the budget back", () -> {
            byte[] whole = gzip(zeros(1 << 20));
            HttpMessage m = new HttpMessage(response("gzip", Arrays.copyOf(whole, whole.length - 8)));
            byte[] before = m.raw().clone();
            Expect.isNull(apply(m, MB16), "no note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
        });

        Harness.test("a body encoded twice is decoded in reverse order", () -> {
            byte[] hello = Bytes.bytes("hello");
            HttpMessage a = new HttpMessage(response("gzip, deflate", deflate(gzip(hello))));
            Expect.isNull(apply(a, MB16), "gzip, deflate has no note");
            unpackedTo(a, hello);
            HttpMessage b = new HttpMessage(response("deflate, gzip", gzip(deflate(hello))));
            Expect.isNull(apply(b, MB16), "deflate, gzip has no note");
            unpackedTo(b, hello);
        });

        Harness.test("a body with more than three content codings is left as received with a note", () -> {
            HttpMessage m = new HttpMessage(response("gzip, gzip, gzip, gzip",
                    gzip(gzip(gzip(gzip(Bytes.bytes("hello")))))));
            byte[] before = m.raw().clone();
            Expect.equal(apply(m, MB16), "Response body left compressed: more than 3 content codings.", "note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
        });

        Harness.test("a body with three content codings is still decoded", () -> {
            byte[] hello = Bytes.bytes("hello");
            HttpMessage m = new HttpMessage(response("gzip, gzip, gzip", gzip(gzip(gzip(hello)))));
            Expect.isNull(apply(m, MB16), "no note");
            unpackedTo(m, hello);
        });

        Harness.test("a coding the client cannot decode leaves the whole body as received", () -> {
            for (String enc : new String[] {"gzip, zstd", "zstd, gzip"}) {
                HttpMessage m = new HttpMessage(response(enc, gzip(Bytes.bytes("hello"))));
                byte[] before = m.raw().clone();
                Expect.isNull(apply(m, MB16), enc + " has no note");
                Expect.isTrue(Arrays.equals(before, m.raw()), enc + " raw unchanged");
            }
        });

        Harness.test("identity, letter case and x-gzip are understood", () -> {
            byte[] hello = Bytes.bytes("hello");
            HttpMessage a = new HttpMessage(response("Identity, GZIP", gzip(hello)));
            Expect.isNull(apply(a, MB16), "Identity, GZIP has no note");
            unpackedTo(a, hello);
            HttpMessage b = new HttpMessage(response("x-gzip", gzip(hello)));
            Expect.isNull(apply(b, MB16), "x-gzip has no note");
            unpackedTo(b, hello);
        });

        Harness.test("two Content-Encoding lines are one list in order", () -> {
            byte[] hello = Bytes.bytes("hello");
            byte[] body = deflate(gzip(hello));
            HttpMessage m = new HttpMessage(Bytes.concat(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip"
                    + "\r\nContent-Encoding: deflate\r\nContent-Length: " + body.length + "\r\n\r\n"), body));
            Expect.isNull(apply(m, MB16), "no note");
            unpackedTo(m, hello);
            Expect.isFalse(m.headerText().toLowerCase().contains("content-encoding"), "no Content-Encoding line left");
        });

        Harness.test("a body compressed twice is bounded by its final output", () -> {
            HttpMessage m = new HttpMessage(response("gzip, gzip", gzip(repeat(gzip(zeros(1 << 20)), 10240))));
            byte[] before = m.raw().clone();
            String[] note = new String[1];
            long bytes = Allocation.during(() -> note[0] = apply(m, MB16));
            Expect.equal(note[0], limitNote("gzip, gzip", "16 MB"), "note");
            Expect.isTrue(Arrays.equals(before, m.raw()), "raw unchanged");
            Expect.isTrue(bytes < 3L * MB16, "allocated " + bytes);
        });

        Harness.test("the shared budget refuses a take past its capacity and changes nothing", () -> {
            Unpack.Budget b = new Unpack.Budget(100);
            Expect.isTrue(b.take(60), "take 60");
            Expect.isFalse(b.take(41), "take 41 more");
            Expect.equal(b.inUse(), 60L, "in use after the refusal");
            Expect.isTrue(b.take(40), "take the last 40");
            b.give(100);
            Expect.equal(b.inUse(), 0L, "in use after giving all back");
            Expect.equal(Unpack.SHARED_BYTES, 256L << 20, "shared capacity");
        });

        Harness.test("a response over the limit reaches the caller as the server sent it", () -> {
            byte[] sent = response("gzip", gzip(zeros(4096)));
            try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
                CompletableFuture<Void> done = serve(server, sent);
                HttpClient.Options o = new HttpClient.Options();
                o.unpackLimit = 1024;
                HttpClient.Result r = sendGet(server, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.unpackNote, limitNote("gzip", "1024 bytes"), "note");
                Expect.isTrue(Arrays.equals(sent, r.response.raw()), "the bytes the server sent");
                joinServer(done);
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        Harness.test("after a followed redirect the note describes the final response", () -> {
            byte[] big = gzip(zeros(4096));
            byte[] moved = Bytes.concat(Bytes.bytes("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Encoding: gzip"
                    + "\r\nContent-Length: " + big.length + "\r\n\r\n"), big);
            try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
                CompletableFuture<Void> done = serve(server, moved, response("gzip", gzip(Bytes.bytes("hello"))));
                HttpClient.Options o = new HttpClient.Options();
                o.followRedirects = true;
                o.unpackLimit = 1024;
                HttpClient.Result r = sendGet(server, o);
                Expect.isTrue(r.ok(), "response: " + r.error);
                Expect.equal(r.redirectsFollowed, 1, "one redirect");
                Expect.equal(r.unpackNote, "", "no note");
                Expect.equal(r.response.bodyText(), "hello", "final body");
                joinServer(done);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    /** Waits for the server thread, so a failure on its side shows its cause. */
    private static void joinServer(CompletableFuture<Void> done) {
        try { done.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException("server side failed", e); }
    }

    private static HttpClient.Result sendGet(ServerSocket server, HttpClient.Options o) {
        int port = server.getLocalPort();
        return new HttpClient().send(new HttpService("127.0.0.1", port, false), new HttpMessage(Bytes.bytes(
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n")), o);
    }

    /** Answer one connection per response, in order, each after reading a request head. */
    private static CompletableFuture<Void> serve(ServerSocket server, byte[]... responses) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                for (byte[] response : responses) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(5000);
                        InputStream in = s.getInputStream();
                        int b, state = 0;
                        while (state < 4 && (b = in.read()) >= 0) {
                            if ((state == 0 || state == 2) && b == 13) state++;
                            else if ((state == 1 || state == 3) && b == 10) state++;
                            else state = 0;
                        }
                        s.getOutputStream().write(response);
                        s.getOutputStream().flush();
                    }
                }
                done.complete(null);
            } catch (Exception e) { done.completeExceptionally(e); }
        });
        return done;
    }
}
