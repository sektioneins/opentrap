package opentrap.sequencer;

import opentrap.core.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the input fields of a page as {@code name=value} entries, for the
 * Sequencer's form-field choice. The scan is written by hand and moves forward
 * only, so it reads each character a constant number of times: a page full of
 * broken or unclosed tags cannot stall the Sequencer the way a backtracking
 * pattern would.
 */
final class FormFields {

    /** Bytes of a body that are scanned; the rest is ignored. */
    static final int SCAN_LIMIT = 16 << 20;
    /** Distinct fields listed at most. */
    static final int MAX_FIELDS = 1000;
    /** Characters of a name or a value kept before it is cut. */
    static final int MAX_TEXT = 256;

    private FormFields() {}

    /** The fields of the first {@link #SCAN_LIMIT} bytes of a body, read as ISO-8859-1. */
    static List<String> of(byte[] body) {
        if (body == null) return new ArrayList<>();
        return of(Bytes.str(Bytes.slice(body, 0, Math.min(body.length, SCAN_LIMIT))));
    }

    /** The distinct {@code name=value} entries of the input tags in {@code html}, in document order. */
    static List<String> of(String html) {
        Set<String> found = new LinkedHashSet<>();
        int n = html.length();
        int p = 0;
        while (found.size() < MAX_FIELDS) {
            int lt = html.indexOf('<', p);
            if (lt < 0) break;
            p = lt + 1;
            if (!html.regionMatches(true, p, "input", 0, 5)) continue;
            p += 5;
            if (p < n && !isSpace(html.charAt(p)) && html.charAt(p) != '/' && html.charAt(p) != '>') continue;

            String name = null;
            String value = null;
            while (p < n) {
                char c = html.charAt(p);
                if (c == '>') { p++; break; }
                if (isSpace(c) || c == '/') { p++; continue; }
                int start = p;
                if (c == '=') p++;
                else while (p < n && !isSpace(html.charAt(p)) && html.charAt(p) != '/'
                        && html.charAt(p) != '>' && html.charAt(p) != '=') p++;
                String attr = html.substring(start, p);
                p = skipSpace(html, p);
                String val = "";
                if (p < n && html.charAt(p) == '=') {
                    p = skipSpace(html, p + 1);
                    if (p < n && (html.charAt(p) == '"' || html.charAt(p) == '\'')) {
                        int close = html.indexOf(html.charAt(p), p + 1);
                        int end = close < 0 ? n : close;
                        val = html.substring(p + 1, end);
                        p = close < 0 ? n : close + 1;
                    } else {
                        int vs = p;
                        while (p < n && !isSpace(html.charAt(p)) && html.charAt(p) != '>') p++;
                        val = html.substring(vs, p);
                    }
                }
                if (name == null && attr.equalsIgnoreCase("name")) name = val;
                else if (value == null && attr.equalsIgnoreCase("value")) value = val;
            }
            if (name != null && !name.isEmpty()) {
                found.add(cut(name) + "=" + cut(value == null ? "" : value));
            }
        }
        return new ArrayList<>(found);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    private static int skipSpace(String s, int p) {
        while (p < s.length() && isSpace(s.charAt(p))) p++;
        return p;
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "...";
    }
}
