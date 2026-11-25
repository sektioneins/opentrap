package opentrap.proxy;

import opentrap.testkit.Allocation;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tests the WebSocket frame codec: encode then read must round-trip the payload
 * in both masked (client-to-server) and unmasked (server-to-client) directions,
 * across the three length encodings, and masked bytes must differ on the wire.
 */
public final class WebSocketFrameTest {

    private static byte[] roundTrip(byte[] payload, boolean mask) {
        try {
            byte[] wire = WebSocketFrame.encode(true, 1, payload, mask);
            WebSocketFrame f = WebSocketFrame.read(new ByteArrayInputStream(wire));
            Expect.notNull(f, "frame read back");
            return f.payload;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void io(ThrowingRunnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean throwsIo(ThrowingRunnable body) {
        try {
            body.run();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A stream that hands out at most 3 bytes per read, so reads split the mask phase. */
    private static InputStream trickle(InputStream in) {
        return new FilterInputStream(in) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 3));
            }
        };
    }

    private static byte[] header(int b0, int b1, long len) {
        byte[] h = new byte[10];
        h[0] = (byte) b0;
        h[1] = (byte) b1;
        for (int i = 0; i < 8; i++) h[2 + i] = (byte) (len >> (8 * (7 - i)));
        return h;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public void run() {
        Harness.suite("websocket frames");

        Harness.test("a masked text frame round-trips to the same payload", () -> {
            byte[] p = "hello websocket".getBytes(StandardCharsets.UTF_8);
            Expect.isTrue(Arrays.equals(p, roundTrip(p, true)), "masked round-trip");
        });

        Harness.test("an unmasked frame round-trips to the same payload", () -> {
            byte[] p = "server says hi".getBytes(StandardCharsets.UTF_8);
            Expect.isTrue(Arrays.equals(p, roundTrip(p, false)), "unmasked round-trip");
        });

        Harness.test("the 16-bit and 64-bit length encodings round-trip", () -> {
            byte[] medium = new byte[1000];   // uses the 126 / 16-bit length
            byte[] large = new byte[70000];   // uses the 127 / 64-bit length
            for (int i = 0; i < medium.length; i++) medium[i] = (byte) (i & 0xFF);
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 7 & 0xFF);
            Expect.isTrue(Arrays.equals(medium, roundTrip(medium, true)), "1000-byte masked");
            Expect.isTrue(Arrays.equals(large, roundTrip(large, false)), "70000-byte unmasked");
        });

        Harness.test("masking actually obscures the payload on the wire", () -> {
            byte[] p = "secret".getBytes(StandardCharsets.UTF_8);
            byte[] wire = WebSocketFrame.encode(true, 1, p, true);
            // The literal payload must not appear verbatim in a masked frame.
            boolean verbatim = indexOf(wire, p) >= 0;
            Expect.isFalse(verbatim, "masked payload present verbatim");
        });

        Harness.test("a header that declares 2 GB is read without allocating its payload", () -> io(() -> {
            byte[] wire = {(byte) 0x82, 0x7F, 0, 0, 0, 0, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xF0};
            ByteArrayInputStream in = new ByteArrayInputStream(wire);
            WebSocketFrame[] f = new WebSocketFrame[1];
            long bytes = Allocation.during(() -> f[0] = WebSocketFrame.readHeader(in));
            Expect.equal(f[0].length, 0x7FFFFFF0L, "declared length");
            Expect.equal(f[0].payload.length, 0, "payload held");
            Expect.equal(in.available(), 0, "bytes left unread");
            Expect.isTrue(bytes < 64 * 1024, "allocated " + bytes);
        }));

        Harness.test("a 64-bit length with the top bit set is refused", () -> io(() -> {
            byte[] wire = {(byte) 0x82, 0x7F, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0};
            Expect.isTrue(throwsIo(() -> WebSocketFrame.readHeader(new ByteArrayInputStream(wire))), "refused");
        }));

        Harness.test("a payload that stops short holds only what arrived", () -> io(() -> {
            byte[] wire = concat(header(0x82, 0x7F, 16L << 20), new byte[100 * 1024]);
            ByteArrayInputStream in = new ByteArrayInputStream(wire);
            boolean[] threw = {false};
            long bytes = Allocation.during(() -> {
                WebSocketFrame f = WebSocketFrame.readHeader(in);
                threw[0] = throwsIo(() -> f.readPayload(in));
            });
            Expect.isTrue(threw[0], "readPayload threw");
            Expect.isTrue(bytes < 1 << 20, "allocated " + bytes);
        }));

        Harness.test("a streamed masked payload arrives clear and keeps its first bytes", () -> io(() -> {
            byte[] p = new byte[200003];
            for (int i = 0; i < p.length; i++) p[i] = (byte) (i * 31 + 7);
            InputStream in = trickle(new ByteArrayInputStream(WebSocketFrame.encode(true, 2, p, true)));
            WebSocketFrame f = WebSocketFrame.readHeader(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] kept = f.streamPayload(in, out, null, 100000);
            Expect.isTrue(Arrays.equals(p, out.toByteArray()), "streamed payload");
            Expect.isTrue(Arrays.equals(Arrays.copyOf(p, 100000), kept), "kept prefix");
        }));

        Harness.test("a re-masked streamed payload reads back as the same frame", () -> io(() -> {
            byte[] p = new byte[150001];
            for (int i = 0; i < p.length; i++) p[i] = (byte) (i * 13 + 5);
            InputStream in = trickle(new ByteArrayInputStream(WebSocketFrame.encode(true, 2, p, false)));
            WebSocketFrame f = WebSocketFrame.readHeader(in);
            byte[] key = WebSocketFrame.newMaskKey();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(WebSocketFrame.encodeHeader(true, 2, f.length, key));
            byte[] kept = f.streamPayload(in, out, key, 0);
            Expect.equal(kept.length, 0, "kept");
            byte[] wire = out.toByteArray();
            Expect.isTrue((wire[1] & 0x80) != 0, "mask bit set");
            WebSocketFrame back = WebSocketFrame.read(new ByteArrayInputStream(wire));
            Expect.isTrue(Arrays.equals(p, back.payload), "payload after re-mask");
        }));

        Harness.test("a stream that ends mid-payload fails rather than forwarding a short frame", () -> io(() -> {
            byte[] wire = concat(WebSocketFrame.encodeHeader(true, 2, 1000, null), new byte[500]);
            ByteArrayInputStream in = new ByteArrayInputStream(wire);
            WebSocketFrame f = WebSocketFrame.readHeader(in);
            Expect.isTrue(throwsIo(() -> f.streamPayload(in, new ByteArrayOutputStream(), null, 0)), "threw");
        }));

        Harness.test("streaming a 2 GB header with a short payload allocates only what arrived", () -> io(() -> {
            byte[] wire = concat(header(0x82, 0x7F, 0x7FFFFFF0L), new byte[10 * 1024]);
            ByteArrayInputStream in = new ByteArrayInputStream(wire);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean[] threw = {false};
            long bytes = Allocation.during(() -> {
                WebSocketFrame f = WebSocketFrame.readHeader(in);
                threw[0] = throwsIo(() -> f.streamPayload(in, out, null, 16 << 20));
            });
            Expect.isTrue(threw[0], "streamPayload threw");
            Expect.equal(out.size(), 10 * 1024, "bytes forwarded");
            Expect.isTrue(bytes < 1 << 20, "allocated " + bytes);
        }));
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
