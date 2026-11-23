package opentrap.ui;

import javax.swing.Icon;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

/**
 * The S1 wordmark as a Swing icon: the logo, "OpenTRAP" and the amber "S1"
 * badge, as in {@code icon/wordmark-s1-light.svg} and
 * {@code icon/wordmark-s1-dark.svg}.
 *
 * <p>It is drawn as vectors rather than loaded from a raster, so it stays sharp
 * at any display scale, and in the UI font, which the SVGs approximate with a
 * system font stack. The layout follows the SVGs' 80-unit-high grid; the word
 * is measured instead of stretched to the SVGs' fixed text length, so the badge
 * sits after the word whatever the font. The colours follow the active theme at
 * paint time: navy text and brackets on a light background, pale ones on a dark
 * background, with the arrows and the badge amber in both.
 */
public final class Wordmark implements Icon {

    static final Color AMBER = new Color(0xEC, 0xB9, 0x2C);
    static final Color NAVY = new Color(0x0F, 0x1C, 0x5C);
    static final Color TEXT_DARK = new Color(0xEE, 0xF1, 0xFB);
    static final Color BRACKET_DARK = new Color(0xB9, 0xC2, 0xE6);

    static final String WORD = "OpenTRAP";
    static final String BADGE = "S1";

    // The SVGs' grid, in units of an 80-unit-high mark.
    private static final double GRID = 80;
    private static final double WORD_X = 74, WORD_BASE = 54, WORD_SIZE = 52;
    private static final double BADGE_GAP = 12, BADGE_Y = 16.5, BADGE_W = 52, BADGE_H = 37.5, BADGE_ARC = 12;
    private static final double BADGE_SIZE = 28, BADGE_BASE = 45.4, MARGIN = 8;
    private static final double TRACKING = -1.0 / WORD_SIZE;

    private final int height;
    private final double scale;
    private final Font wordFont;
    private final Font badgeFont;
    private final double wordWidth;
    private final int width;

    /** The wordmark at a height in pixels; the width follows from the word in the UI font. */
    public Wordmark(int height) {
        this.height = height;
        this.scale = height / GRID;
        Map<TextAttribute, Object> tracking = Map.of(TextAttribute.TRACKING, TRACKING);
        this.wordFont = Fonts.ui(Font.BOLD, Fonts.DEFAULT_SIZE).deriveFont((float) (WORD_SIZE * scale)).deriveFont(tracking);
        this.badgeFont = wordFont.deriveFont((float) (BADGE_SIZE * scale));
        FontRenderContext frc = new FontRenderContext(null, true, true);
        this.wordWidth = wordFont.getStringBounds(WORD, frc).getWidth() / scale;
        this.width = (int) Math.ceil((badgeX() + BADGE_W + MARGIN) * scale);
    }

    private double badgeX() { return WORD_X + wordWidth + BADGE_GAP; }

    @Override public int getIconWidth() { return width; }
    @Override public int getIconHeight() { return height; }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            boolean dark = Theme.dark;
            Color text = dark ? TEXT_DARK : NAVY;

            logo(g, dark ? BRACKET_DARK : NAVY);

            g.setColor(text);
            g.setFont(wordFont);
            g.drawString(WORD, (float) (WORD_X * scale), (float) (WORD_BASE * scale));

            double bx = badgeX();
            g.setColor(AMBER);
            g.fill(new RoundRectangle2D.Double(bx * scale, BADGE_Y * scale, BADGE_W * scale, BADGE_H * scale,
                    BADGE_ARC * scale, BADGE_ARC * scale));
            g.setColor(NAVY);
            g.setFont(badgeFont);
            float sw = (float) g.getFontMetrics().getStringBounds(BADGE, g).getWidth();
            g.drawString(BADGE, (float) ((bx + BADGE_W / 2) * scale) - sw / 2, (float) (BADGE_BASE * scale));
        } finally {
            g.dispose();
        }
    }

    /**
     * The logo from {@code icon/opentrap-s1-*.svg}: its 24-unit view cropped to
     * 1.7 to 22.3 and placed in a 56-unit square at (8, 8), as the wordmark SVGs
     * place it.
     */
    private void logo(Graphics2D g0, Color bracket) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            double k = 56 / 20.6;
            g.scale(scale, scale);
            g.transform(new AffineTransform(k, 0, 0, k, 8 - 1.7 * k, 8 - 1.7 * k));

            g.setColor(AMBER);
            g.fill(polygon(6.4, 5.6, 14, 5.6, 14, 3.2, 20.8, 7, 14, 10.8, 14, 8.4, 6.4, 8.4));
            g.fill(polygon(17.6, 15.6, 10, 15.6, 10, 13.2, 3.2, 17, 10, 20.8, 10, 18.4, 17.6, 18.4));

            g.setColor(bracket);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(polyline(6.4, 2.6, 3.4, 2.6, 3.4, 11.4, 6.4, 11.4));
            g.draw(polyline(17.6, 12.6, 20.6, 12.6, 20.6, 21.4, 17.6, 21.4));
        } finally {
            g.dispose();
        }
    }

    private static Path2D polyline(double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }

    private static Path2D polygon(double... xy) {
        Path2D p = polyline(xy);
        p.closePath();
        return p;
    }
}
