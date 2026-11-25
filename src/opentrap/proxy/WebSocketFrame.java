package opentrap.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A single RFC 6455 WebSocket frame. Reading unmasks the payload; encoding masks
 * it again when sending client-to-server, so a frame can be parsed, inspected or
 * edited and put back on the wire correctly in either direction.
 *
 * <p>Reading is split so a caller can decide from the header whether to hold a
 * frame or stream it: {@link #readHeader} takes the header only, and the payload
 * then comes either through {@link #readPayload}, which grows its buffer with the
 * bytes that actually arrive, or through {@link #streamPayload}, which passes it
 * on in chunks without holding it. A peer's declared length never sizes an
 * allocation.
 */
public final class WebSocketFrame {

    /** Largest piece read or written at once while streaming or growing a payload. */
    public static final int STREAM_CHUNK = 64 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    public boolean fin;
    public int opcode;
    public byte[] payload = new byte[0];
    /** The payload length the header declares, 0 to Long.MAX_VALUE. */
    public long length;
    /** The mask key from the header, or null when the frame is not masked. */
    private byte[] mask;

    /** Read one whole frame, returning null at end of stream. Payload is returned unmasked. */
    public static WebSocketFrame read(InputStream in) throws IOException {
        WebSocketFrame f = readHeader(in);
        if (f == null) return null;
        f.readPayload(in);
        return f;
    }

    /**
     * Read a frame header through the mask key and nothing more, returning null at
     * end of stream. {@code payload} stays empty; {@code length} holds the declared size.
     */
    public static WebSocketFrame readHeader(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        WebSocketFrame f = new WebSocketFrame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            if (len < 0) throw new IOException("invalid frame length");
        }
        f.length = len;
        if (masked) {
            f.mask = new byte[4];
            readFully(in, f.mask);
        }
        return f;
    }

    /**
     * Read {@code length} payload bytes into {@code payload}, unmasked. The buffer
     * grows with the bytes read, so a frame that declares more than it delivers
     * costs only what arrived.
     */
    public void readPayload(InputStream in) throws IOException {
        if (length > Integer.MAX_VALUE - 8) throw new IOException("frame too large");
        int len = (int) length;
        byte[] buf = new byte[Math.min(len, STREAM_CHUNK)];
        int have = 0;
        while (have < len) {
            if (have == buf.length) buf = Arrays.copyOf(buf, (int) Math.min((long) len, 2L * buf.length));
            int k = in.read(buf, have, Math.min(buf.length - have, STREAM_CHUNK));
            if (k < 0) throw new IOException("unexpected end of WebSocket stream");
            have += k;
        }
        if (mask != null) for (int i = 0; i < len; i++) buf[i] ^= mask[i & 3];
        payload = buf;
    }

    /**
     * Move exactly {@code length} payload bytes from {@code in} to {@code out} in pieces
     * of at most {@link #STREAM_CHUNK}, unmasking with the frame's key and masking with
     * {@code outKey} (null writes them clear). Returns the first {@code min(keep, length)}
     * bytes in clear. Does not flush.
     */
    public byte[] streamPayload(InputStream in, OutputStream out, byte[] outKey, int keep) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] buf = new byte[(int) Math.min(length, STREAM_CHUNK)];
        long done = 0;
        while (done < length) {
            int k = in.read(buf, 0, (int) Math.min(buf.length, length - done));
            if (k < 0) throw new IOException("unexpected end of WebSocket stream");
            if (mask != null) for (int i = 0; i < k; i++) buf[i] ^= mask[(int) ((done + i) & 3)];
            long room = keep - done;
            if (room > 0) kept.write(buf, 0, (int) Math.min(room, k));
            if (outKey != null) for (int i = 0; i < k; i++) buf[i] ^= outKey[(int) ((done + i) & 3)];
            out.write(buf, 0, k);
            done += k;
        }
        return kept.toByteArray();
    }

    /** The header for a payload of {@code length} bytes; {@code maskKey} is null or 4 bytes. */
    public static byte[] encodeHeader(boolean fin, int opcode, long length, byte[] maskKey) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((fin ? 0x80 : 0) | (opcode & 0x0F));
        int lenCode = length < 126 ? (int) length : (length <= 0xFFFF ? 126 : 127);
        o.write((maskKey != null ? 0x80 : 0) | lenCode);
        if (lenCode == 126) {
            o.write((int) (length >> 8) & 0xFF);
            o.write((int) length & 0xFF);
        } else if (lenCode == 127) {
            for (int i = 7; i >= 0; i--) o.write((int) ((length >> (8 * i)) & 0xFF));
        }
        if (maskKey != null) o.write(maskKey, 0, 4);
        return o.toByteArray();
    }

    /** A fresh random 4-byte mask key. */
    public static byte[] newMaskKey() {
        byte[] key = new byte[4];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Encode a frame, masking the payload when {@code mask} is true (client-to-server). */
    public static byte[] encode(boolean fin, int opcode, byte[] payload, boolean mask) {
        if (payload == null) payload = new byte[0];
        byte[] key = mask ? newMaskKey() : null;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(encodeHeader(fin, opcode, payload.length, key));
        if (mask) {
            for (int i = 0; i < payload.length; i++) o.write(payload[i] ^ key[i & 3]);
        } else {
            o.writeBytes(payload);
        }
        return o.toByteArray();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("unexpected end of WebSocket stream");
        return b & 0xFF;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int k = in.read(buf, off, buf.length - off);
            if (k < 0) throw new IOException("unexpected end of WebSocket stream");
            off += k;
        }
    }
}
