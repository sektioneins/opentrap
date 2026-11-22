package opentrap.proxy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rewrites behind the proxy toolbar's response toggles (unhide hidden
 * fields, enable disabled fields, remove length limits, JavaScript validation,
 * object tags and all JavaScript). They run on every HTML response while a
 * toggle is on, whatever the server sent, so each must stay linear in the
 * length of the body.
 *
 * <p>A pattern that starts with {@code \s+} would be tried at every character
 * of a long whitespace run and scan to the end of the run each time, which is
 * quadratic. Here the pattern takes one whitespace character, then requires
 * that the character before it is not whitespace ({@code (?<!\s\s)}), then
 * takes the rest of the run, so a match can start only at the first character
 * of a run and each run is scanned once. A non-whitespace character still
 * fails at once, as it did before. The match is the same as before: a regex
 * match starts at the leftmost position, so it always took the whole run. The
 * quantifiers that follow are possessive, which is safe because each is
 * followed by something it cannot match, so none of them ever gave anything
 * back. Span removal ({@code <x.*?</x>}) is done by {@link #removeSpans}, which
 * never rescans text it has passed.
 */
final class HtmlRewrite {

    private static final Pattern DISABLED =
            Pattern.compile("(?i)\\s(?<!\\s\\s)\\s*+disabled(\\s*+=\\s*+[\"']?[^\"'>\\s]*+[\"']?)?");
    private static final Pattern MAXLENGTH =
            Pattern.compile("(?i)\\s(?<!\\s\\s)\\s*+maxlength\\s*+=\\s*+[\"']?\\d++[\"']?");
    private static final Pattern ONSUBMIT =
            Pattern.compile("(?i)\\s(?<!\\s\\s)\\s*+onsubmit\\s*+=\\s*+\"[^\"]*+\"");
    private static final Pattern ON_HANDLER =
            Pattern.compile("(?i)\\s(?<!\\s\\s)\\s*+on[a-z]++\\s*+=\\s*+\"[^\"]*+\"");

    private static final Pattern HIDDEN =
            Pattern.compile("(?i)type\\s*=\\s*([\"']?)hidden\\1");

    private static final Pattern OBJECT_OPEN = Pattern.compile("(?i)<object");
    private static final Pattern OBJECT_CLOSE = Pattern.compile("(?i)</object>");
    private static final Pattern EMBED_OPEN = Pattern.compile("(?i)<embed");
    private static final Pattern TAG_CLOSE = Pattern.compile(">");
    private static final Pattern SCRIPT_OPEN = Pattern.compile("(?i)<script");
    private static final Pattern SCRIPT_CLOSE = Pattern.compile("(?i)</script>");

    private HtmlRewrite() {}

    static String unhideHiddenFields(String html) {
        return HIDDEN.matcher(html).replaceAll("type=$1text$1");
    }

    static String enableDisabledFields(String html) {
        return DISABLED.matcher(html).replaceAll("");
    }

    static String removeLengthLimits(String html) {
        return MAXLENGTH.matcher(html).replaceAll("");
    }

    static String removeJsValidation(String html) {
        return ONSUBMIT.matcher(html).replaceAll("");
    }

    static String removeObjectTags(String html) {
        return removeSpans(removeSpans(html, OBJECT_OPEN, OBJECT_CLOSE), EMBED_OPEN, TAG_CLOSE);
    }

    static String removeAllJavaScript(String html) {
        String b = removeSpans(html, SCRIPT_OPEN, SCRIPT_CLOSE);
        return ON_HANDLER.matcher(b).replaceAll("");
    }

    /**
     * Removes every span from a match of {@code open} to the first match of
     * {@code close} after it. When no close follows an open, none follows a
     * later open either, so the scan stops and the rest stays as it is, the way
     * {@code <x.*?</x>} behaves. Every search starts past the previous match.
     */
    private static String removeSpans(String s, Pattern open, Pattern close) {
        Matcher o = open.matcher(s), c = close.matcher(s);
        StringBuilder out = null;
        int copied = 0;
        while (o.find(copied) && c.find(o.end())) {
            if (out == null) out = new StringBuilder(s.length());
            out.append(s, copied, o.start());
            copied = c.end();
        }
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }
}
