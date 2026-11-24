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
}
