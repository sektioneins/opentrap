package opentrap.ui.theme;

import opentrap.ui.Theme;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Flat rounded buttons. The default is a quiet grey pill that darkens on hover
 * and press; a button marked with {@link Theme#PRIMARY} is filled with the
 * accent and reads as the main action of its panel. Toggle buttons use the
 * same shape and show their on state with the accent fill.
 */
public class FlatButtonUI extends BasicButtonUI {

    private static final FlatButtonUI INSTANCE = new FlatButtonUI();

    public static ComponentUI createUI(JComponent c) { return INSTANCE; }

    @Override protected void installDefaults(AbstractButton b) {
        super.installDefaults(b);
        b.setRolloverEnabled(true);
        b.setOpaque(false);
        b.setFocusPainted(false);
    }

    @Override public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        ButtonModel m = b.getModel();
        boolean primary = Boolean.TRUE.equals(b.getClientProperty(Theme.PRIMARY))
                || (b instanceof JToggleButton && m.isSelected());
        boolean custom = b.isBackgroundSet() && !(b.getBackground() instanceof UIResource);
        boolean outline = primary && !custom && Theme.PRIMARY_BUTTON == Theme.PrimaryButton.OUTLINE;

        if (b.isContentAreaFilled()) {
            Color fill;
            if (custom) {
                fill = b.getBackground();
            } else if (!m.isEnabled()) {
                fill = Theme.SURFACE;
            } else if (primary) {
                fill = m.isPressed() ? Theme.ACCENT_DARK : m.isRollover() ? Theme.ACCENT_DARK : Theme.ACCENT;
            } else {
                fill = m.isPressed() ? Theme.SURFACE_3 : m.isRollover() ? Theme.SURFACE_2 : Theme.SURFACE;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (outline && m.isEnabled()) {
                // No fill: a line in the accent, growing inward as it widens.
                float lw = Theme.LINE_WIDTH, d = lw - 1;
                g2.setColor(Theme.ACCENT);
                g2.setStroke(new BasicStroke(lw));
                g2.draw(new RoundRectangle2D.Float(0.5f + d, 0.5f + d, c.getWidth() - 1 - 2 * d,
                        c.getHeight() - 1 - 2 * d, Theme.RADIUS * 2, Theme.RADIUS * 2));
                g2.dispose();
                super.paint(g, c);
                return;
            }
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, c.getWidth(), c.getHeight(), Theme.RADIUS * 2, Theme.RADIUS * 2));
            if (!primary && !custom) {
                g2.setColor(m.isEnabled() ? Theme.SURFACE_3 : Theme.BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, c.getWidth() - 1, c.getHeight() - 1,
                        Theme.RADIUS * 2, Theme.RADIUS * 2));
            }
            g2.dispose();
        }
        super.paint(g, c);
    }

    @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
        ButtonModel m = b.getModel();
        boolean primary = Boolean.TRUE.equals(b.getClientProperty(Theme.PRIMARY))
                || (b instanceof JToggleButton && m.isSelected());
        boolean outline = primary && Theme.PRIMARY_BUTTON == Theme.PrimaryButton.OUTLINE
                && !(b.isBackgroundSet() && !(b.getBackground() instanceof UIResource));
        Color fg = !m.isEnabled() ? Theme.MUTED
                : outline ? Theme.TEXT : primary ? Theme.TEXT_ON_ACCENT : b.getForeground();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontMetrics fm = g2.getFontMetrics(b.getFont());
        int mnemonic = b.getDisplayedMnemonicIndex();
        g2.setColor(fg);
        javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(b, g2, text, mnemonic,
                r.x + getTextShiftOffset(), r.y + fm.getAscent() + getTextShiftOffset());
        g2.dispose();
    }

    @Override protected void paintButtonPressed(Graphics g, AbstractButton b) {}
    @Override protected void paintFocus(Graphics g, AbstractButton b, Rectangle v, Rectangle t, Rectangle i) {}
}
