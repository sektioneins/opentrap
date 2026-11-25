package opentrap.extensions.store;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Feed text is never shown as HTML: this reduces it to plain text with simple line structure. */
public final class PlainText {

    private PlainText() {}

    // A tag's match stops at the next "<", so text with many unclosed tags is reduced in linear time, not quadratic.
    private static final Pattern LI = Pattern.compile("(?i)<li\\b[^<>]*>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|br|div|h[1-6]|ul|ol|li|tr|table|pre)\\b[^<>]*>");
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]+);");

    public static String of(String html) {
        if (html == null) return "";
        String s = LI.matcher(html).replaceAll("\n- ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = decode(s);
        StringBuilder out = new StringBuilder();
        int blanks = 0;
        for (String line : s.split("\\R", -1)) {
            String t = line.strip();
            if (t.isEmpty()) { blanks++; continue; }
            if (out.length() > 0) out.append(blanks > 0 && !t.startsWith("- ") ? "\n\n" : "\n");
            blanks = 0;
            out.append(t);
        }
        return out.toString();
    }

    private static String decode(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(entity(m.group(0), m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String entity(String whole, String name) {
        switch (name) {
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            default:
        }
        if (name.startsWith("#")) {
            try {
                int cp = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                if (Character.isValidCodePoint(cp) && cp != 0) return new String(Character.toChars(cp));
            } catch (NumberFormatException ignored) { }
        }
        return whole;
    }
}
