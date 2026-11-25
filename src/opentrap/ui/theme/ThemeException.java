package opentrap.ui.theme;

/** A theme file that cannot be used, with the line that caused it when there is one. */
public final class ThemeException extends Exception {

    private final int line;

    /** @param line the 1-based line of the problem, or 0 when it is not tied to one line */
    public ThemeException(int line, String message) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    public int line() { return line; }

    /** How much of a value an error quotes. */
    static final int QUOTE = 60;

    /**
     * {@code text} as an error quotes it: runs of whitespace as one space, and at
     * most {@code max} characters, ending in "..." when cut. A theme value can be
     * as long as the file, and the message goes into dialogs and notices.
     */
    public static String excerpt(String text, int max) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 3) + "...";
    }

    static String excerpt(String text) {
        return excerpt(text, QUOTE);
    }
}
