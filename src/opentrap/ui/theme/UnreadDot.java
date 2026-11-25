package opentrap.ui.theme;

import opentrap.ui.Theme;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The unread mark after a tab title or a status-bar link: a disc in the
 * accent, the colour of the selected tab's underline, sized from the font so
 * it reads larger than a bullet glyph.
 */
public final class UnreadDot implements Icon {

    private final FontMetrics fm;

    /** A dot for text set in these metrics, as an icon that sits after the text. */
    public UnreadDot(FontMetrics fm) {
        this.fm = fm;
    }

    static int size(FontMetrics fm) { return Math.max(6, Math.round(fm.getAscent() * 0.6f)); }

    static int gap(FontMetrics fm) { return Math.max(3, Math.round(fm.getAscent() * 0.3f)); }

    /** The space the dot takes after the text, gap included. */
    static int width(FontMetrics fm) { return gap(fm) + size(fm); }

    /** Paint the dot after text that ends at {@code x}, on the text's baseline. */
    static void paint(Graphics2D g, FontMetrics fm, int x, int baseline) {
        int d = size(fm);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Theme.ACCENT);
        g2.fillOval(x + gap(fm), baseline - Math.round(fm.getAscent() * 0.36f) - d / 2, d, d);
        g2.dispose();
    }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        // The icon is as tall as a line of text, so a label centres it on
        // the same box as its text and the baseline falls where the text's does.
        paint((Graphics2D) g, fm, x, y + fm.getAscent());
    }

    @Override public int getIconWidth() { return width(fm); }

    @Override public int getIconHeight() { return fm.getHeight(); }
}
