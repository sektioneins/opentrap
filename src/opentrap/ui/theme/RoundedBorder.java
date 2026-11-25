package opentrap.ui.theme;

import opentrap.ui.Theme;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.UIResource;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A one-pixel rounded outline with inner padding. When a focus colour is given
 * the outline switches to it while the component (or a child of it) has focus,
 * so inputs show where typing will go without a heavy glow.
 */
public class RoundedBorder extends AbstractBorder implements UIResource {

    private final Color line;
    private final Color focus;
    private final int radius;
    private final Insets pad;

    public RoundedBorder(Color line, Color focus, int radius, Insets pad) {
        this.line = line;
        this.focus = focus;
        this.radius = radius;
        this.pad = pad;
    }

    @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean focused = focus != null && c instanceof JComponent jc && hasFocusWithin(jc);
        g2.setColor(focused ? focus : line);
        // A wider line grows inward, so the outer edge and the insets stay put.
        float lw = Theme.LINE_WIDTH, d = lw - 1;
        g2.setStroke(new BasicStroke(lw));
        g2.draw(new RoundRectangle2D.Float(x + d, y + d, w - 1 - 2 * d, h - 1 - 2 * d, radius, radius));
        g2.dispose();
    }

    private static boolean hasFocusWithin(JComponent c) {
        if (c.hasFocus()) return true;
        for (Component k : c.getComponents()) if (k.hasFocus()) return true;
        return false;
    }

    @Override public Insets getBorderInsets(Component c) { return new Insets(pad.top, pad.left, pad.bottom, pad.right); }
    @Override public Insets getBorderInsets(Component c, Insets i) {
        i.set(pad.top, pad.left, pad.bottom, pad.right);
        return i;
    }
    @Override public boolean isBorderOpaque() { return false; }
}
