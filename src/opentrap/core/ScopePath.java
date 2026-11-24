package opentrap.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The forms in which a server may read a request path. A rule written for
 * {@code /app} is stepped around by {@code /app/../admin}, and one written for
 * {@code logout} by {@code /%6cogout}, so {@link Scope#isInScope} judges the
 * path as written, with its unreserved escapes decoded and dot segments
 * removed, and with every escape decoded.
 */
final class ScopePath {
    private ScopePath() {}

    /**
     * The distinct forms of {@code path} in the order raw, canonical, decoded.
     * The split into path and query happens at the first literal {@code ?}
     * before anything is decoded, so an escaped {@code %3f} stays in the path.
     * Dot segments are removed from the path part only. A null path gives a
     * list holding one null.
     */
    static List<String> forms(String path) {
        if (path == null) return Arrays.asList((String) null);
        if (path.indexOf('%') < 0 && path.indexOf('.') < 0) return List.of(path);
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        String query = q < 0 ? null : path.substring(q);   // with the "?"
        List<String> out = new ArrayList<>(3);
        out.add(path);
        add(out, join(removeDotSegments(decode(p, true)), query == null ? null : decode(query, true)));
        add(out, join(removeDotSegments(decode(p, false)), query == null ? null : decode(query, false)));
        return out;
    }

    private static void add(List<String> out, String form) {
        if (!out.contains(form)) out.add(form);
    }

    private static String join(String path, String query) {
        return query == null ? path : path + query;
    }

    /**
     * RFC 3986 section 5.2.4: take out the {@code .} and {@code ..} segments
     * of a path.
     */
    static String removeDotSegments(String path) {
        String in = path;
        StringBuilder out = new StringBuilder();
        while (!in.isEmpty()) {
            if (in.startsWith("../")) in = in.substring(3);
            else if (in.startsWith("./")) in = in.substring(2);
            else if (in.startsWith("/./")) in = in.substring(2);
            else if (in.equals("/.")) in = "/";
            else if (in.startsWith("/../")) { in = in.substring(3); dropLast(out); }
            else if (in.equals("/..")) { in = "/"; dropLast(out); }
            else if (in.equals(".") || in.equals("..")) in = "";
            else {
                int end = in.indexOf('/', 1);
                if (end < 0) end = in.length();
                out.append(in, 0, end);
                in = in.substring(end);
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
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
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
