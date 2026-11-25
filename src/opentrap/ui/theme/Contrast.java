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
        // The chosen preset and scan row, and a flashing tab, are on ACCENT_LIGHT.
        new Pair("TEXT", "ACCENT_LIGHT", 4.5),
        new Pair("TAB_TEXT", "ACCENT_LIGHT", 4.5),
        new Pair("TAB_TEXT_ON", "ACCENT_LIGHT", 4.5),
        new Pair("TAB_TEXT", "BG", 4.5),
        new Pair("TAB_TEXT_ON", "BG", 4.5),
        new Pair("TEXT_ON_ACCENT", "ACCENT_DARK", 4.5),
        // Text on the fills of buttons, chips and the segmented control.
        new Pair("TEXT", "SURFACE_2", 4.5),
        new Pair("TEXT", "SURFACE_3", 4.5),
        new Pair("TEXT", "SEGMENT_OFF", 4.5),
        // Text on a tinted selection or a mark: selected rows, search hits, payload marks, diff rows.
        new Pair("TEXT", "CONTROL_TINT", 4.5),
        new Pair("TEXT", "HIGHLIGHT", 4.5),
        new Pair("TEXT", "POSITION_MARK", 4.5),
        new Pair("TEXT", "DIFF_ADDED", 4.5),
        new Pair("TEXT", "DIFF_DELETED", 4.5),
        new Pair("TEXT", "DIFF_MODIFIED", 4.5),
        new Pair("MUTED", "ACCENT_LIGHT", 3.0),
        new Pair("MUTED", "BG", 3.0),
        new Pair("SEVERITY_HIGH", "BG", 3.0),
        new Pair("SEVERITY_MEDIUM", "BG", 3.0),
        new Pair("SEVERITY_LOW", "BG", 3.0));

    /** The fills that badges, pills and tinted list rows carry a glyph or a number on. */
    public static final List<String> BADGE_FILLS = List.of("ACCENT", "WARN", "DIALOG_ERROR", "SEVERITY_HIGH",
        "SEVERITY_MEDIUM", "SEVERITY_LOW", "SEVERITY_INFO", "DECODE_URL", "DECODE_HTML", "DECODE_BASE64",
        "DECODE_HEX", "DECODE_OCTAL", "DECODE_BINARY", "DECODE_COMPRESSED", "CONTROL", "TOGGLE_OFF");

    /** What a badge fill is checked with: the light on-colour or the dark one, whichever reads better. */
    public static final String BADGE_TEXT = "TEXT_ON_BADGE or TEXT_ON_BADGE_DARK";

    /** The contrast ratio of two colours, from 1 to 21, ignoring alpha; {@link #failures} composites it. */
    public static double ratio(Color a, Color b) {
        return ratio(a.getRGB(), b.getRGB());
    }

    /**
     * The on-colour of the two that reads better on {@code fill}. With white and pure
     * black one of them always reaches 4.5 to 1, so a badge never needs a third colour.
     */
    public static Color onBadge(Color fill, Color light, Color dark) {
        return ratio(dark, fill) > ratio(light, fill) ? dark : light;
    }

    private static double luminance(int r, int g, int b) {
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /**
     * The pairs below their minimum in a resolved set of values. A colour with
     * alpha is measured as it is painted: the background over the page
     * ({@code BG}, taken as opaque) and the text over that background.
     */
    public static List<Failure> failures(Map<String, Object> values) {
        List<Failure> out = new ArrayList<>();
        int page = values.get("BG") instanceof Color c ? c.getRGB() | 0xFF000000 : 0xFFFFFFFF;
        for (Pair p : PAIRS) {
            if (values.get(p.fg()) instanceof Color fg && values.get(p.bg()) instanceof Color bg) {
                int under = p.bg().equals("BG") ? page : over(bg, page);
                double r = ratio(over(fg, under), under);
                if (r < p.min()) out.add(new Failure(p, r));
            }
        }
        if (values.get("TEXT_ON_BADGE") instanceof Color light && values.get("TEXT_ON_BADGE_DARK") instanceof Color dark) {
            for (String fill : BADGE_FILLS) {
                if (values.get(fill) instanceof Color bg) {
                    int under = over(bg, page);
                    double r = Math.max(ratio(over(light, under), under), ratio(over(dark, under), under));
                    if (r < 4.5) out.add(new Failure(new Pair(BADGE_TEXT, fill, 4.5), r));
                }
            }
        }
        return out;
    }

    /** {@code c} painted over the opaque {@code under}, both as {@code 0xAARRGGBB}. */
    private static int over(Color c, int under) {
        double a = c.getAlpha() / 255.0;
        return 0xFF000000 | blend(c.getRed(), under >> 16, a) << 16 | blend(c.getGreen(), under >> 8, a) << 8
            | blend(c.getBlue(), under, a);
    }

    private static int blend(int top, int bottom, double a) {
        return (int) Math.round(top * a + (bottom & 255) * (1 - a));
    }

    private static double ratio(int a, int b) {
        double la = luminance(a >> 16 & 255, a >> 8 & 255, a & 255), lb = luminance(b >> 16 & 255, b >> 8 & 255, b & 255);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * The failures of a theme where at least one of the two colours differs from
     * the base alone, so a weakness the base already has is not blamed on the theme.
     */
    public static List<Failure> changed(Map<String, Object> theme, Map<String, Object> baseAlone) {
        List<Failure> out = new ArrayList<>();
        for (Failure f : failures(theme)) {
            Pair p = f.pair();
            boolean moved = !java.util.Objects.equals(theme.get(p.bg()), baseAlone.get(p.bg()));
            for (String key : p.fg().split(" or ")) {
                moved |= !java.util.Objects.equals(theme.get(key), baseAlone.get(key));
            }
            if (moved) out.add(f);
        }
        return out;
    }
}
