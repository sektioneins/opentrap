package opentrap.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Replaces a compressed response body with its plaintext, within two bounds.
 * A limit per body keeps a small compressed body from becoming an allocation
 * the size of its output, and a budget shared by every decode at once keeps
 * many such bodies arriving together from adding up. A body that would pass
 * either stays as the server sent it, and the caller gets a note saying why.
 */
final class Unpack {
    private Unpack() {}

    /** How many decoded bytes all responses decoding at the same time may hold together. */
    static final long SHARED_BYTES = 256L << 20;
    static final Budget SHARED = new Budget(SHARED_BYTES);

    private static final int CHUNK = 64 * 1024;

    /** Bytes held by decodes in progress, never more than the capacity. */
    static final class Budget {
        private final long capacity;
        private final AtomicLong inUse = new AtomicLong();

        Budget(long capacity) { this.capacity = capacity; }

        /** Holds {@code n} more bytes if that stays within the capacity; otherwise changes nothing. */
        boolean take(long n) {
            while (true) {
                long cur = inUse.get();
                if (cur + n > capacity) return false;
                if (inUse.compareAndSet(cur, cur + n)) return true;
            }
        }

        void give(long n) { inUse.addAndGet(-n); }

        long inUse() { return inUse.get(); }
    }

    /** As {@link #apply(HttpMessage, int, Budget)} with the budget every response shares. */
    static String apply(HttpMessage m, int limit) { return apply(m, limit, SHARED); }

    /**
     * Replaces the body with its plaintext and drops Content-Encoding. Returns
     * null when the body was replaced or there was nothing to decode, and the
     * note saying why when the body would decode past {@code limit} or past
     * what the budget has left; the message is then left as it was.
     */
    static String apply(HttpMessage m, int limit, Budget budget) {
        String ce = m.header("Content-Encoding");
        if (ce == null) return null;
        String enc = ce.trim().toLowerCase(Locale.ROOT);
        byte[] raw = m.raw();
        int off = m.bodyOffset();
        if (off >= raw.length) return null;
        String name = enc.contains("gzip") ? "gzip"
                : enc.contains("deflate") ? "deflate"
                : enc.contains("br") ? "br" : null;
        if (name == null) return null;
        long taken = 0;
        try (InputStream dec = decoder(name, new ByteArrayInputStream(raw, off, raw.length - off))) {
            List<byte[]> chunks = new ArrayList<>();
            long total = 0;
            while (true) {
                byte[] chunk = new byte[CHUNK];
                int k = 0, n;
                while (k < CHUNK && (n = dec.read(chunk, k, CHUNK - k)) > 0) k += n;
                if (!budget.take(k)) {
                    return "Response body left compressed (" + name + "): responses decoding at the same time"
                            + " already hold most of the " + size(budget.capacity) + " they may share.";
                }
                taken += k;
                total += k;
                if (total > limit) {
                    return "Response body left compressed (" + name + "): it decodes to more than "
                            + size(limit) + ", the unpack limit.";
                }
                if (k > 0) chunks.add(chunk);
                if (k < CHUNK) break;
            }
            replaceBody(m, chunks, (int) total);
            return null;
        } catch (Exception | LinkageError e) {
            // A body that will not decode stays as received; trace it rather
            // than dropping the failure silently. LinkageError covers a
            // missing decoder jar, which must not kill the calling thread.
            Debug.log("could not decode " + enc + " body: " + e);
            return null;
        } finally {
            budget.give(taken);
        }
    }

    private static InputStream decoder(String name, InputStream src) throws Exception {
        return switch (name) {
            case "gzip" -> new GZIPInputStream(src);
            case "deflate" -> new InflaterInputStream(src);
            default -> new org.brotli.dec.BrotliInputStream(src);
        };
    }

    /**
     * Rewrites the headers on their own and copies the chunks once into the new
     * message, so a body near the limit is not copied again for each header.
     */
    private static void replaceBody(HttpMessage m, List<byte[]> chunks, int total) {
        HttpMessage head = new HttpMessage(Bytes.slice(m.raw(), 0, m.bodyOffset()));
        head.removeHeader("Content-Encoding");
        head.setHeader("Content-Length", String.valueOf(total));
        byte[] h = head.raw();
        byte[] out = new byte[h.length + total];
        System.arraycopy(h, 0, out, 0, h.length);
        int at = h.length;
        for (byte[] c : chunks) {
            int n = Math.min(c.length, out.length - at);
            System.arraycopy(c, 0, out, at, n);
            at += n;
        }
        m.setRaw(out);
    }

    /** A size as a whole number of MB when it is one, else in bytes. */
    private static String size(long n) {
        return n % (1 << 20) == 0 ? (n >> 20) + " MB" : n + " bytes";
    }
}
