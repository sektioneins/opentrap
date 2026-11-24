package opentrap.ui.theme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The WCAG contrast check for the colour pairs a theme draws text and marks
 * with. It reports; it never blocks a theme.
 */
public final class Contrast {
    private Contrast() { }

    /** A foreground key on a background key, and the least ratio that reads well. */
    public record Pair(String fg, String bg, double min) { }

    /** A pair whose ratio is below its minimum. */
    public record Failure(Pair pair, double ratio) { }

    public static final List<Pair> PAIRS = List.of(
        new Pair("TEXT", "BG", 4.5),
        new Pair("TEXT", "CARD", 4.5),
        new Pair("TEXT", "SURFACE", 4.5),
        new Pair("TEXT", "HEADER", 4.5),
        // The out-of-scope banner is TEXT on ACCENT_FAINT.
        new Pair("TEXT", "ACCENT_FAINT", 4.5),
        new Pair("TEXT_ON_ACCENT", "ACCENT", 4.5),
        new Pair("TEXT_ON_CONTROL", "CONTROL", 4.5),
        // The selected row of the command palette and the scan presets.
        new Pair("TEXT_ON_SELECTION", "ACCENT_LIGHT", 4.5),
        new Pair("LINK", "BG", 4.5),
        new Pair("ERROR", "BG", 4.5),
        new Pair("MUTED", "BG", 3.0),
        new Pair("SEVERITY_HIGH", "BG", 3.0),
        new Pair("SEVERITY_MEDIUM", "BG", 3.0),
        new Pair("SEVERITY_LOW", "BG", 3.0));

    /** The contrast ratio of two colours, from 1 to 21, ignoring alpha. */
    public static double ratio(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** The pairs below their minimum in a resolved set of values. */
    public static List<Failure> failures(Map<String, Object> values) {
        List<Failure> out = new ArrayList<>();
        for (Pair p : PAIRS) {
            if (values.get(p.fg()) instanceof Color fg && values.get(p.bg()) instanceof Color bg) {
                double r = ratio(fg, bg);
                if (r < p.min()) out.add(new Failure(p, r));
            }
        }
        return out;
    }

    /**
     * The failures of a theme where at least one of the two colours differs from
     * the base alone, so a weakness the base already has is not blamed on the theme.
     */
    public static List<Failure> changed(Map<String, Object> theme, Map<String, Object> baseAlone) {
        List<Failure> out = new ArrayList<>();
        for (Failure f : failures(theme)) {
            Pair p = f.pair();
            if (!java.util.Objects.equals(theme.get(p.fg()), baseAlone.get(p.fg()))
                    || !java.util.Objects.equals(theme.get(p.bg()), baseAlone.get(p.bg()))) out.add(f);
        }
        return out;
    }
}
