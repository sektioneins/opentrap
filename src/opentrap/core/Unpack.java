package opentrap.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Stacked codings are undone in reverse order of the header, one stream over
 * the next, so only the final output is held. The budget counts that decoded
 * output while a decode runs; it does not count decoder state at all. Each
 * brotli stage keeps a window of up to 16 MB, so a body with more than
 * {@link #MAX_CODINGS} codings is left as received, which bounds that state at
 * {@code MAX_CODINGS} x 16 MB per decode. The array a successful decode builds
 * is bounded only by the per-body limit.
 */
final class Unpack {
    private Unpack() {}

    /**
     * How many decoded bytes all responses decoding at the same time may hold
     * together. ProjectSettingsPanel states it as text; change both together.
     */
    static final long SHARED_BYTES = 256L << 20;
    static final Budget SHARED = new Budget(SHARED_BYTES);

    /** How many stacked content codings a body may have and still be decoded. */
    static final int MAX_CODINGS = 3;

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
     * note saying why when the body has more than {@link #MAX_CODINGS} codings
     * or would decode past {@code limit} or past what the budget has left; the
     * message is then left as it was.
     */
    static String apply(HttpMessage m, int limit, Budget budget) {
        List<String> codings = codings(m);
        if (codings.isEmpty()) return null;
        // Checked before any decoder is built or the list is joined, so a long
        // header costs neither decoder state nor a note that repeats it.
        if (codings.size() > MAX_CODINGS) {
            return "Response body left compressed: more than " + MAX_CODINGS + " content codings.";
        }
        for (String c : codings) if (!known(c)) return null;
        byte[] raw = m.raw();
        int off = m.bodyOffset();
        if (off >= raw.length) return null;
        String enc = String.join(", ", codings);
        long taken = 0;
        try (InputStream dec = decoders(codings, new ByteArrayInputStream(raw, off, raw.length - off))) {
            List<byte[]> chunks = new ArrayList<>();
            long total = 0;
            while (true) {
                byte[] chunk = new byte[CHUNK];
                int k = 0, n;
                while (k < CHUNK && (n = dec.read(chunk, k, CHUNK - k)) > 0) k += n;
                if (!budget.take(k)) {
                    return "Response body left compressed (" + enc + "): responses decoding at the same time"
                            + " already hold most of the " + size(budget.capacity) + " they may share.";
                }
                taken += k;
                total += k;
                if (total > limit) {
                    return "Response body left compressed (" + enc + "): it decodes to more than "
                            + size(limit) + ", the unpack limit.";
                }
                // A chunk that came back short ended the stream; keep only what it holds.
                if (k > 0) chunks.add(k < CHUNK ? Arrays.copyOf(chunk, k) : chunk);
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

    /** The codings of every Content-Encoding line in order, lowercased, without empty tokens and identity. */
    private static List<String> codings(HttpMessage m) {
        List<String> out = new ArrayList<>();
        for (String line : m.headerLines()) {
            if (!line.regionMatches(true, 0, "Content-Encoding:", 0, 17)) continue;
            for (String t : line.substring(17).split(",")) {
                String c = t.trim().toLowerCase(Locale.ROOT);
                if (!c.isEmpty() && !c.equals("identity")) out.add(c);
            }
        }
        return out;
    }

    private static boolean known(String coding) {
        return switch (coding) {
            case "gzip", "x-gzip", "deflate", "br" -> true;
            default -> false;
        };
    }

    /** Chains the decoders over the body, the last coding applied being the first undone. */
    private static InputStream decoders(List<String> codings, InputStream src) throws Exception {
        InputStream in = src;
        try {
            for (int i = codings.size() - 1; i >= 0; i--) in = decoder(codings.get(i), in);
        } catch (Throwable e) {
            // A stage that fails to build (a bad gzip header) leaves the ones below it open.
            try { in.close(); } catch (Exception _) { }
            throw e;
        }
        return in;
    }

    private static InputStream decoder(String coding, InputStream src) throws Exception {
        return switch (coding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(src);
            case "deflate" -> new InflaterInputStream(src);
            case "br" -> new org.brotli.dec.BrotliInputStream(src);
            default -> throw new IllegalStateException("no decoder for " + coding);
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
            System.arraycopy(c, 0, out, at, c.length);
            at += c.length;
        }
        m.setRaw(out);
    }

    /** A size as a whole number of MB when it is one, else in bytes. */
    private static String size(long n) {
        return n % (1 << 20) == 0 ? (n >> 20) + " MB" : n + " bytes";
    }
}
