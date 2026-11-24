package opentrap.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The forms in which a server may read a request path. A rule written for
 * {@code /app} is stepped around by {@code /app/../admin}, and one written for
 * {@code logout} by {@code /%6cogout}, so {@link Scope#isInScope} judges the
 * path as written, with its unreserved escapes decoded and dot segments
 * removed, and with every escape decoded, each against the rule in the same form.
 */
final class ScopePath {
    private ScopePath() {}

    /** Which reading of a path a string stands for; a prefix rule is compared in the same one. */
    enum Form { RAW, CANONICAL, DECODED }

    /**
     * The path with the escapes of unreserved characters decoded and dot
     * segments removed. The split into path and query happens at the first
     * literal {@code ?} before anything is decoded, so an escaped {@code %3f}
     * stays in the path, and the query is never dot-normalised. Null stays null.
     */
    static String canonical(String path) {
        return form(path, true);
    }

    /** As {@link #canonical}, but with every valid escape decoded as UTF-8. */
    static String decoded(String path) {
        return form(path, false);
    }

    private static String form(String path, boolean unreservedOnly) {
        // Only an escape, a "/." (which also covers "/.." and "x/./y") or a
        // leading "." can change a path; "x.js" cannot.
        if (path == null || path.indexOf('%') < 0 && !path.startsWith(".") && !path.contains("/.")) return path;
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        String query = q < 0 ? null : path.substring(q);   // with the "?"
        return join(removeDotSegments(decode(p, unreservedOnly)),
                query == null ? null : decode(query, unreservedOnly));
    }

    private static String join(String path, String query) {
        return query == null ? path : path + query;
    }

    /**
     * RFC 3986 section 5.2.4: take out the {@code .} and {@code ..} segments
     * of a path.
     */
    static String removeDotSegments(String path) {
        int n = path.length(), i = 0;
        StringBuilder out = new StringBuilder(n);
        while (i < n) {
            int rest = n - i;
            if (path.startsWith("../", i)) i += 3;
            else if (path.startsWith("./", i)) i += 2;
            else if (path.startsWith("/./", i)) i += 2;
            else if (rest == 2 && path.startsWith("/.", i)) { out.append('/'); i = n; }
            else if (path.startsWith("/../", i)) { i += 3; dropLast(out); }
            else if (rest == 3 && path.startsWith("/..", i)) { dropLast(out); out.append('/'); i = n; }
            else if (rest == 1 && path.charAt(i) == '.' || rest == 2 && path.startsWith("..", i)) i = n;
            else {
                int end = path.indexOf('/', i + 1);
                if (end < 0) end = n;
                out.append(path, i, end);
                i = end;
            }
        }
        return out.toString();
    }

    private static void dropLast(StringBuilder out) {
        int i = out.lastIndexOf("/");
        out.setLength(i < 0 ? 0 : i);
    }

    /**
     * Decode {@code %XX} escapes: only those of an unreserved byte when
     * {@code unreservedOnly}, otherwise all of them, with the bytes read as
     * UTF-8. An invalid escape stays as written, and so does {@code +}.
     */
    private static String decode(String s, boolean unreservedOnly) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int v = -1;
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1)), lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) v = hi * 16 + lo;
            }
            if (v >= 0 && (!unreservedOnly || unreserved(v))) {
                bytes.write(v);
                i += 3;
                continue;
            }
            flush(bytes, out);
            if (v >= 0) { out.append(s, i, i + 3); i += 3; }
            else { out.append(c); i++; }
        }
        flush(bytes, out);
        return out.toString();
    }

    private static int hex(char c) {
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) return;
        out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        bytes.reset();
    }

    private static boolean unreserved(int b) {
        return b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9'
                || b == '-' || b == '.' || b == '_' || b == '~';
    }
}
