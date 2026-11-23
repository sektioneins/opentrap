package opentrap.core;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rewrites one of an advanced scope rule's search patterns (host, port or
 * file) so that, placed inside a whole-URL regex, it can only match within
 * its own segment of the URL.
 *
 * <p>In the app each pattern is searched for in the host, the port or the
 * path alone. The scan engine instead matches one regex against the whole
 * URL, where an unconfined {@code .*\.example\.com} in the host position
 * would run on into the path. So every atom that consumes a character gets a
 * lookahead guard that rejects the segment's delimiters, a literal that can
 * never occur in the segment becomes {@code (?!)}, and {@code ^} and {@code $}
 * become the segment's own boundaries. Syntax that cannot be confined this way
 * makes the rewrite fail rather than come out approximate.
 */
final class ScopeRegex {

    enum Segment { HOST, PORT, PATH }

    // A host character is anything but a delimiter that ends the host.
    private static final String HOST_GUARD = "(?![" + Scope.HOST_DELIMITERS + "])";
    // The same delimiters as plain characters rather than a class body.
    private static final String HOST_DELIMITER_CHARS = Scope.HOST_DELIMITERS.replace("\\\\", "\\");
    // The port is digits only.
    private static final String PORT_GUARD = "(?=\\d)";

    private ScopeRegex() {}

    /**
     * A regex fragment that matches inside {@code seg} exactly where
     * {@code Pattern.compile(pattern, CASE_INSENSITIVE).matcher(segmentText).find()}
     * would. Null when the pattern uses syntax that cannot be confined, or
     * when the fragment does not compile.
     */
    static String confine(String pattern, Segment seg) {
        if (pattern == null) return null;
        Walk w = new Walk(pattern, seg);
        String out = w.run();
        if (out == null) return null;
        try {
            Pattern.compile(out);
        } catch (PatternSyntaxException e) {
            return null;
        }
        return out;
    }

    /**
     * Whether a pattern that {@link #confine} accepts for {@code seg} has a
     * backreference or a named group. Its fragment then changes meaning, or
     * no longer compiles, once it sits after other groups or appears twice in
     * one regex.
     */
    static boolean refersToGroups(String pattern, Segment seg) {
        Walk w = new Walk(pattern, seg);
        w.run();
        return w.refers;
    }

    /** One left-to-right pass over the pattern, writing the confined form. */
    private static final class Walk {
        private final String p;
        private final Segment seg;
        private final StringBuilder out = new StringBuilder();
        private int i;
        boolean refers;

        Walk(String p, Segment seg) { this.p = p; this.seg = seg; }

        String run() {
            while (i < p.length()) {
                boolean ok = switch (p.charAt(i)) {
                    case '\\' -> escape();
                    case '[' -> charClass();
                    case '.' -> { atom("."); i++; yield true; }
                    case '^' -> { out.append(start()); i++; yield true; }
                    case '$' -> { out.append(end()); i++; yield true; }
                    case '(' -> group();
                    case ')', '|' -> { out.append(p.charAt(i++)); yield true; }
                    case '*', '+', '?', '{' -> quantifier();
                    default -> { literal(); yield true; }
                };
                if (!ok) return null;
            }
            return out.toString();
        }

        /** An atom that consumes one character, guarded to the segment. */
        private void atom(String a) {
            switch (seg) {
                case HOST -> out.append("(?:").append(HOST_GUARD).append(a).append(')');
                case PORT -> out.append("(?:").append(PORT_GUARD).append(a).append(')');
                case PATH -> out.append(a);
            }
        }

        /** Whether a literal character can never occur in the segment. */
        private boolean foreign(int cp) {
            return switch (seg) {
                case HOST -> HOST_DELIMITER_CHARS.indexOf(cp) >= 0;
                case PORT -> cp < '0' || cp > '9';
                case PATH -> false;
            };
        }

        private void literal() {
            int cp = p.codePointAt(i);
            i += Character.charCount(cp);
            if (foreign(cp)) out.append("(?!)");
            else out.appendCodePoint(cp);
        }

        // The host starts right after "://". The port starts after its ":".
        // The path starts where host and port end, found by looking back to
        // the scheme; host plus port never exceed 300 characters, and the
        // bound is there because a Java lookbehind needs a maximum length.
        // Looking back only to "//" would also hold after a "//" inside the
        // path.
        private String start() {
            return switch (seg) {
                case HOST -> "(?<=://)";
                case PORT -> "(?<=:)";
                case PATH -> "(?<=^https?://[^/]{1,300})";
            };
        }

        // The host ends at the port's ":" or the path's "/", or with the URL
        // when it has neither; the port ends at the path or with the URL. The
        // path runs to the end of the URL, which the engine cuts at the query.
        private String end() {
            return switch (seg) {
                case HOST -> "(?=[:/]|$)";
                case PORT -> "(?=/|$)";
                case PATH -> "$";
            };
        }

        private boolean escape() {
            if (i + 1 >= p.length()) return false;
            char d = p.charAt(i + 1);
            int from = i;
            i += 2;
            switch (d) {
                case 'Q' -> { quote(); return true; }
                case 'd', 'D', 's', 'S', 'w', 'W', 'h', 'H', 'v', 'V',
                     't', 'n', 'r', 'f', 'a', 'e' -> { }
                case 'p', 'P', 'x', 'N' -> {
                    if (i < p.length() && p.charAt(i) == '{') {
                        int close = p.indexOf('}', i);
                        if (close < 0) return false;
                        i = close + 1;
                    } else if (d == 'N') {
                        return false;
                    } else {
                        i += d == 'x' ? 2 : 1;
                    }
                }
                case 'u' -> i += 4;
                case 'c' -> i += 1;
                case '0' -> {
                    // Up to three octal digits, the first of three at most 3.
                    int digits = 0;
                    while (digits < 3 && i < p.length() && p.charAt(i) >= '0' && p.charAt(i) <= '7'
                            && (digits < 2 || p.charAt(i - 2) <= '3')) {
                        i++;
                        digits++;
                    }
                }
                case 'A' -> { out.append(start()); return true; }
                case 'z', 'Z' -> { out.append(end()); return true; }
                case 'b', 'B' -> {
                    // A word boundary sees the same non-word delimiters at
                    // either edge of the host or the port as at the edge of
                    // the text alone. The path starts with "/" after a host
                    // character, which is a boundary in the URL but not at the
                    // start of the path.
                    if (seg == Segment.PATH) return false;
                    if (i < p.length() && p.charAt(i) == '{') return false;
                    out.append('\\').append(d);
                    return true;
                }
                case 'R', 'X' -> {
                    if (seg != Segment.PATH) return false;
                    out.append('\\').append(d);
                    return true;
                }
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    // Further digits stay plain literals, which the regex
                    // parser reads the same way as before, since no capturing
                    // group is added.
                    refers = true;
                    out.append('\\').append(d);
                    return true;
                }
                case 'k' -> {
                    int close = p.indexOf('>', i);
                    if (i >= p.length() || p.charAt(i) != '<' || close < 0) return false;
                    i = close + 1;
                    refers = true;
                    out.append(p, from, i);
                    return true;
                }
                default -> {
                    // \G depends on where the search started. Any other letter
                    // is not an escape Java knows.
                    if (Character.isLetter(d)) return false;
                }
            }
            if (i > p.length()) return false;
            atom(p.substring(from, i));
            return true;
        }

        /** {@code \Q...\E}, or to the end of the pattern without an {@code \E}. */
        private void quote() {
            int close = p.indexOf("\\E", i);
            String text = close < 0 ? p.substring(i) : p.substring(i, close);
            i = close < 0 ? p.length() : close + 2;
            if (text.isEmpty()) return;
            if (seg == Segment.PATH || text.codePoints().noneMatch(this::foreign)) {
                // Always closed, or the rest of the URL regex would be quoted.
                out.append("\\Q").append(text).append("\\E");
                return;
            }
            // A quantifier after the quote applies to its last character
            // only, so that one is kept apart from the rest.
            int last = text.offsetByCodePoints(text.length(), -1);
            quoted(text.substring(0, last));
            quoted(text.substring(last));
        }

        private void quoted(String text) {
            if (text.isEmpty()) return;
            if (text.codePoints().anyMatch(this::foreign)) out.append("(?!)");
            else out.append("\\Q").append(text).append("\\E");
        }

        /**
         * A character class, scanned to its matching {@code ]}. Escapes and
         * quotes inside it are skipped whole, and a {@code ]} right after the
         * opening {@code [} or {@code [^} is a literal.
         */
        private boolean charClass() {
            int j = i;
            int depth = 0;
            while (true) {
                if (j >= p.length()) return false;
                char ch = p.charAt(j);
                if (ch == '[') {
                    depth++;
                    j++;
                    if (j < p.length() && p.charAt(j) == '^') j++;
                    if (j < p.length() && p.charAt(j) == ']') j++;
                } else if (ch == ']') {
                    j++;
                    if (--depth == 0) break;
                } else if (ch == '\\' && j + 1 < p.length() && p.charAt(j + 1) == 'Q') {
                    int close = p.indexOf("\\E", j + 2);
                    if (close < 0) return false;
                    j = close + 2;
                } else if (ch == '\\') {
                    j += 2;
                } else {
                    j++;
                }
            }
            String cls = p.substring(i, j);
            try {
                Pattern.compile(cls);
            } catch (PatternSyntaxException e) {
                return false;
            }
            i = j;
            atom(cls);
            return true;
        }

        private boolean group() {
            if (i + 1 >= p.length() || p.charAt(i + 1) != '?') {
                out.append('(');
                i++;
                return true;
            }
            String rest = p.substring(i);
            for (String opener : new String[] {"(?:", "(?=", "(?!", "(?>"}) {
                if (rest.startsWith(opener)) {
                    out.append(opener);
                    i += opener.length();
                    return true;
                }
            }
            if (rest.startsWith("(?<=") || rest.startsWith("(?<!")) {
                // A lookbehind at the start of the path would see the host in
                // the URL, where the path alone has nothing before it.
                if (seg == Segment.PATH) return false;
                out.append(rest, 0, 4);
                i += 4;
                return true;
            }
            if (rest.startsWith("(?<")) {
                int close = rest.indexOf('>');
                if (close < 0) return false;
                refers = true;
                out.append(rest, 0, close + 1);
                i += close + 1;
                return true;
            }
            // Inline flags, (?flags) or (?flags:...). Comments mode changes
            // what every following character means, which this pass does not
            // follow.
            int j = 2;
            while (j < rest.length() && "idmsuxcU-".indexOf(rest.charAt(j)) >= 0) j++;
            if (j >= rest.length() || (rest.charAt(j) != ')' && rest.charAt(j) != ':')) return false;
            String flags = rest.substring(0, j + 1);
            if (flags.indexOf('x') >= 0) return false;
            out.append(flags);
            i += flags.length();
            return true;
        }

        /** {@code * + ?} or {@code {n,m}}, with an optional lazy or possessive suffix. */
        private boolean quantifier() {
            int from = i;
            if (p.charAt(i) == '{') {
                int close = p.indexOf('}', i);
                if (close < 0 || !p.substring(i + 1, close).matches("\\d+(,\\d*)?")) return false;
                i = close + 1;
            } else {
                i++;
            }
            if (i < p.length() && (p.charAt(i) == '?' || p.charAt(i) == '+')) i++;
            out.append(p, from, i);
            return true;
        }
    }
}
