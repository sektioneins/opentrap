package opentrap.proxy;

import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.WebSocketMessage;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Tests the WebSocket relay against the frame cap: a frame up to the cap is held
 * whole and goes through match and replace and interception, a larger one is
 * streamed through unchanged and recorded with only its first part, and a header
 * alone never makes the relay wait for or hold the payload it declares.
 */
public final class WebSocketRelayTest {

    private static final int CAP = 1024;

    /** An input stream fed by the test; reads block until data arrives or the feed ends. */
    private static final class Feed extends InputStream {
        private static final byte[] END = new byte[0];
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private byte[] cur = new byte[0];
        private int pos;
        private boolean ended;

        void push(byte[] b) { if (b.length > 0) queue.add(b.clone()); }
        void end()          { queue.add(END); }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos == cur.length) {
                if (ended) return -1;
                try { cur = queue.take(); } catch (InterruptedException e) { throw new InterruptedIOException(); }
                pos = 0;
                if (cur == END) ended = true;
            }
            int k = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, k);
            pos += k;
            return k;
        }
    }

    /**
     * An output stream that collects bytes under a private lock. The relay holds
     * the stream's own monitor for the whole of a streamed frame, so locking on
     * {@code this} would block the test's polling until the frame ends.
     */
    private static final class Sink extends OutputStream {
        private final Object lock = new Object();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override public void write(int b) { synchronized (lock) { buf.write(b); } }
        @Override public void write(byte[] b, int off, int len) { synchronized (lock) { buf.write(b, off, len); } }
        int size()     { synchronized (lock) { return buf.size(); } }
        byte[] bytes() { synchronized (lock) { return buf.toByteArray(); } }
    }

    /** One relay over in-memory streams, running on a virtual thread. */
    private static final class Relay {
        final SuiteContext ctx = new SuiteContext();
        final Feed client = new Feed();
        final Feed server = new Feed();
        final Sink toClient = new Sink();
        final Sink toServer = new Sink();
        final WebSocketConnection conn;
        final Thread thread;

        Relay(List<MatchReplaceRule> rules) {
            conn = new WebSocketConnection(ctx, new HttpService("ws.example.test", 443, true), "/ws",
                    toClient, toServer, rules, CAP);
            thread = Thread.ofVirtual().name("ws-relay-test").start(() -> conn.run(client, server));
        }

        int recorded() { return ctx.wsStore().size(); }

        /**
         * End the server feed first: {@code run} waits up to a second for the
         * server-to-client pump after the client side ends.
         */
        void finish() {
            server.end();
            client.end();
            try { thread.join(2000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            Expect.isFalse(thread.isAlive(), "relay ended");
        }
    }

    private static void waitFor(BooleanSupplier cond, String what) {
        long end = System.nanoTime() + 2_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError(what + " not reached within 2000 ms");
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    private static byte[] repeat(char c, int n) {
        return String.valueOf(c).repeat(n).getBytes(StandardCharsets.US_ASCII);
    }

    private static WebSocketFrame readBack(InputStream in) {
        try {
            WebSocketFrame f = WebSocketFrame.read(in);
            Expect.notNull(f, "frame read back");
            return f;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<MatchReplaceRule> aToB() {
        return List.of(new MatchReplaceRule(true, MatchReplaceRule.WS_BOTH, "a", "b", ""));
    }

    public void run() {
        Harness.suite("websocket relay");

        Harness.test("a server frame above the cap reaches the client whole and is recorded truncated", () -> {
            Relay r = new Relay(null);
            byte[] payload = pattern(5000);
            byte[] wire = WebSocketFrame.encode(true, 2, payload, false);
            r.server.push(wire);
            waitFor(() -> r.recorded() == 1, "one frame recorded");
            byte[] out = r.toClient.bytes();
            Expect.equal(out.length, wire.length, "client output length");
            Expect.equal(out[1] & 0x80, 0, "mask bit clear toward the client");
            WebSocketFrame f = readBack(new ByteArrayInputStream(out));
            Expect.isTrue(f.fin, "fin");
            Expect.equal(f.opcode, 2, "opcode");
            Expect.isTrue(Arrays.equals(f.payload, payload), "client got the same payload");
            WebSocketMessage m = r.ctx.wsStore().get(0);
            Expect.equal(m.length(), CAP, "recorded length");
            Expect.equal(m.declaredLength, 5000L, "declared length");
            Expect.isTrue(m.truncated(), "recorded truncated");
            Expect.isTrue(Arrays.equals(m.payload(), Arrays.copyOf(payload, CAP)), "recorded the first part");
            r.finish();
        });

        Harness.test("a client frame above the cap reaches the server whole and masked", () -> {
            Relay r = new Relay(null);
            byte[] payload = pattern(5000);
            byte[] wire = WebSocketFrame.encode(true, 2, payload, true);
            r.client.push(wire);
            waitFor(() -> r.recorded() == 1, "one frame recorded");
            byte[] out = r.toServer.bytes();
            Expect.equal(out.length, wire.length, "server output length");
            Expect.isTrue((out[1] & 0x80) != 0, "mask bit set toward the server");
            WebSocketFrame f = readBack(new ByteArrayInputStream(out));
            Expect.isTrue(Arrays.equals(f.payload, payload), "server got the same payload");
            Expect.equal(r.ctx.wsStore().get(0).declaredLength, 5000L, "declared length");
            r.finish();
        });

        Harness.test("a header that declares 2 GB is forwarded before its payload arrives", () -> {
            Relay r = new Relay(null);
            r.server.push(WebSocketFrame.encodeHeader(true, 2, 0x7FFFFFF0L, null));
            r.server.push(pattern(100));
            waitFor(() -> r.toClient.size() == 10 + 100, "header and first bytes at the client");
            byte[] out = r.toClient.bytes();
            Expect.isTrue(Arrays.equals(Arrays.copyOf(out, 10), WebSocketFrame.encodeHeader(true, 2, 0x7FFFFFF0L, null)),
                    "the declared length is passed on");
            Expect.isTrue(Arrays.equals(Arrays.copyOfRange(out, 10, 110), pattern(100)), "the first bytes are passed on");
            Expect.equal(r.recorded(), 0, "nothing recorded yet");
            r.finish();
            Expect.isFalse(WebSocketConnection.live().contains(r.conn), "relay no longer live");
            Expect.equal(r.recorded(), 0, "nothing recorded");
        });

        Harness.test("a text frame at the cap is still held whole and rewritten", () -> {
            Relay r = new Relay(aToB());
            byte[] wire = WebSocketFrame.encode(true, 1, repeat('a', CAP), false);
            r.server.push(wire);
            // A frame held whole is recorded before it is written, so wait for the output.
            waitFor(() -> r.toClient.size() == wire.length, "the frame at the client");
            Expect.equal(r.recorded(), 1, "one frame recorded");
            WebSocketFrame f = readBack(new ByteArrayInputStream(r.toClient.bytes()));
            Expect.isTrue(Arrays.equals(f.payload, repeat('b', CAP)), "client got the rewritten text");
            WebSocketMessage m = r.ctx.wsStore().get(0);
            Expect.isTrue(m.edited, "recorded edited");
            Expect.isFalse(m.truncated(), "recorded whole");
            r.finish();
        });

        Harness.test("a text frame one byte over the cap goes through unchanged", () -> {
            Relay r = new Relay(aToB());
            r.server.push(WebSocketFrame.encode(true, 1, repeat('a', CAP + 1), false));
            waitFor(() -> r.recorded() == 1, "one frame recorded");
            WebSocketFrame f = readBack(new ByteArrayInputStream(r.toClient.bytes()));
            Expect.isTrue(Arrays.equals(f.payload, repeat('a', CAP + 1)), "client got the text unchanged");
            WebSocketMessage m = r.ctx.wsStore().get(0);
            Expect.isTrue(m.truncated(), "recorded truncated");
            Expect.isFalse(m.edited, "recorded not edited");
            r.finish();
        });

        Harness.test("an intercepted relay forwards a frame above the cap without asking", () -> {
            AtomicInteger asked = new AtomicInteger();
            try {
                WebSocketConnection.setInterceptor(frame -> {
                    asked.incrementAndGet();
                    return WsInterceptor.Decision.forward();
                });
                WebSocketConnection.intercepting = true;
                Relay r = new Relay(null);
                byte[] big = WebSocketFrame.encode(true, 2, pattern(5000), false);
                r.server.push(big);
                waitFor(() -> r.recorded() == 1, "the large frame recorded");
                Expect.equal(r.toClient.size(), big.length, "the large frame reached the client");
                Expect.equal(asked.get(), 0, "interceptor not asked for the large frame");
                byte[] small = WebSocketFrame.encode(true, 2, pattern(10), false);
                r.server.push(small);
                // A frame held whole is recorded before it is written, so wait for the output.
                waitFor(() -> r.toClient.size() == big.length + small.length, "the small frame at the client");
                Expect.equal(r.recorded(), 2, "the small frame recorded");
                Expect.equal(asked.get(), 1, "interceptor asked for the small frame");
                r.finish();
            } finally {
                WebSocketConnection.intercepting = false;
                WebSocketConnection.setInterceptor(null);
            }
        });

        Harness.test("a frame injected during a streamed client frame lands after it", () -> {
            Relay r = new Relay(null);
            byte[] payload = pattern(5000);
            byte[] wire = WebSocketFrame.encode(true, 2, payload, true);
            int header = wire.length - payload.length;
            r.client.push(Arrays.copyOf(wire, header + 100));
            waitFor(() -> r.toServer.size() > header, "the streamed frame under way");
            Throwable[] failure = {null};
            Thread inject = Thread.ofPlatform().name("ws-inject-test").start(() -> {
                try { r.conn.sendTextToServer("hi"); } catch (Throwable e) { failure[0] = e; }
            });
            // Push the rest only once the injection waits for the stream, or has
            // already gone through without waiting, which the read-back catches.
            waitFor(() -> inject.getState() == Thread.State.BLOCKED || inject.getState() == Thread.State.TERMINATED,
                    "injection blocked or done");
            r.client.push(Arrays.copyOfRange(wire, header + 100, wire.length));
            try { inject.join(2000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            Expect.isFalse(inject.isAlive(), "injection done");
            Expect.isNull(failure[0], "injection failed");
            waitFor(() -> r.recorded() == 2, "both frames recorded");
            ByteArrayInputStream in = new ByteArrayInputStream(r.toServer.bytes());
            WebSocketFrame first = readBack(in);
            Expect.equal(first.opcode, 2, "first frame opcode");
            Expect.isTrue(Arrays.equals(first.payload, payload), "first frame is the streamed one");
            WebSocketFrame second = readBack(in);
            Expect.equal(second.opcode, 1, "second frame opcode");
            Expect.equal(new String(second.payload, StandardCharsets.UTF_8), "hi", "second frame is the injected one");
            Expect.equal(in.available(), 0, "nothing after the two frames");
            r.finish();
        });

        Harness.test("a peer that ends mid-frame leaves the broken frame unrecorded", () -> {
            Relay r = new Relay(null);
            r.server.push(WebSocketFrame.encodeHeader(true, 2, 5000, null));
            r.server.push(pattern(100));
            waitFor(() -> r.toClient.size() == 4 + 100, "header and first bytes at the client");
            r.finish();
            Expect.isFalse(WebSocketConnection.live().contains(r.conn), "relay no longer live");
            Expect.equal(r.recorded(), 0, "nothing recorded");
        });
    }
}
