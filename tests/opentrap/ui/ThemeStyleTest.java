package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.theme.FlatButtonUI;
import opentrap.ui.theme.FlatTabbedPaneUI;
import opentrap.ui.theme.KeyType;
import opentrap.ui.theme.RoundedBorder;
import opentrap.ui.theme.ThemeKeys;

import javax.swing.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/** The shape and weight keys of a theme: radii, line width, icon stroke and element styles. */
public final class ThemeStyleTest {

    public void run() {
        Harness.suite("theme styles");

        Harness.test("a primary button is filled in the accent unless the theme asks for an outline", () -> {
            try {
                Theme.PRIMARY_BUTTON = Theme.PrimaryButton.FILLED;
                Expect.equal(new Color(centre(primaryButton()), true), Theme.ACCENT, "filled centre");
                Theme.PRIMARY_BUTTON = Theme.PrimaryButton.OUTLINE;
                Expect.equal(new Color(centre(primaryButton()), true), Theme.BG, "outline centre");
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("the selected tab is marked by an underline or by a pill", () -> {
            try {
                Theme.TAB_MARKER = Theme.TabMarker.UNDERLINE;
                Expect.equal(tabPixels()[0], Theme.ACCENT, "underline row");
                Theme.TAB_MARKER = Theme.TabMarker.PILL;
                Color[] pill = tabPixels();
                Expect.isFalse(pill[0].equals(Theme.ACCENT), "no underline under a pill");
                Expect.equal(pill[1], Theme.ACCENT_LIGHT, "pill fill");
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("an unread tool's dot is a disc in the underline's accent, larger than the bullet glyph", () -> {
            JTabbedPane tp = new JTabbedPane();
            tp.setUI((javax.swing.plaf.TabbedPaneUI) FlatTabbedPaneUI.createUI(tp));
            tp.addTab("Alpha", new JPanel());
            tp.addTab("Beta" + FlatTabbedPaneUI.UNREAD_DOT, new JPanel());
            BufferedImage img = paint(tp, 300, 100);
            Rectangle r = tp.getUI().getTabBounds(tp, 1);
            // The dot is the rightmost run of columns holding the accent's
            // colour; the title's text is grey, so it never counts.
            int maxX = -1, minX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            for (int x = r.x + r.width - 1; x >= r.x; x--) {
                boolean hit = false;
                for (int y = r.y; y < r.y + r.height; y++) {
                    if (chroma(new Color(img.getRGB(x, y), true)) * 3 > chroma(Theme.ACCENT)) {
                        hit = true;
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
                if (hit) { if (maxX < 0) maxX = x; minX = x; }
                else if (maxX >= 0) break;
            }
            Expect.isTrue(maxX >= 0, "accent pixels in the unread tab");
            Expect.isTrue(maxX < r.x + r.width - 2, "the dot inside its tab");
            java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
            double glyph = tp.getFont().createGlyphVector(frc, "\u2022").getVisualBounds().getWidth();
            Expect.isTrue(maxX - minX + 1 > glyph, "dot width " + (maxX - minX + 1) + " over the glyph's " + glyph);
            Expect.isTrue(Math.abs((maxX - minX) - (maxY - minY)) <= 1, "round, not a bar");
        });

        Harness.test("a wider line keeps the border's insets", () -> {
            try {
                RoundedBorder b = new RoundedBorder(Theme.BORDER, null, Theme.RADIUS, new Insets(4, 7, 4, 7));
                JPanel c = new JPanel();
                Theme.LINE_WIDTH = 1;
                Insets one = b.getBorderInsets(c);
                Theme.LINE_WIDTH = 2;
                Expect.equal(b.getBorderInsets(c), one, "insets");
                Expect.isTrue(alphaAt(b, c, 10, 1) >= 250, "second row is drawn at width 2");
                Theme.LINE_WIDTH = 1;
                Expect.equal(alphaAt(b, c, 10, 1), 0, "second row is empty at width 1");
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("icon strokes follow the stroke percentage", () -> {
            try {
                Expect.equal(Theme.stroke(2f), 2f, "at 100");
                Theme.ICON_STROKE = 150;
                Expect.equal(Theme.stroke(2f), 3f, "at 150");
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("every drawn icon's stroke goes through the theme's stroke weight", () -> {
            // Outlines follow LINE_WIDTH instead; brand marks and what leaves the app are not themed.
            List<String> notIcons = List.of("ui/theme/RoundedBorder.java", "ui/theme/FlatButtonUI.java",
                    "ui/AppIcon.java", "ui/Wordmark.java", "proxy/BrowserDesktopEntry.java");
            java.util.regex.Pattern unscaled = java.util.regex.Pattern.compile("new (?:java\\.awt\\.)?BasicStroke\\((?!Theme\\.stroke\\()");
            java.util.TreeMap<String, Integer> found = new java.util.TreeMap<>();
            try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src"))) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String rel = java.nio.file.Path.of("src", "opentrap").relativize(f).toString().replace('\\', '/');
                    if (notIcons.contains(rel) || rel.equals("ui/Theme.java")) continue;
                    var m = unscaled.matcher(java.nio.file.Files.readString(f));
                    int n = 0;
                    while (m.find()) n++;
                    if (n > 0) found.put(rel, n);
                }
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            Expect.equal(found.toString(), "{}", "strokes not scaled by ICON_STROKE");
        });

        Harness.test("a table built by Ui follows the grid choice", () -> {
            try {
                boolean[][] want = {{false, false}, {true, false}, {true, true}};
                Theme.TableGrid[] choices = Theme.TableGrid.values();
                Expect.equal(choices.length, 3, "choices");
                for (int i = 0; i < choices.length; i++) {
                    Theme.TABLE_GRID = choices[i];
                    JTable t = new JTable();
                    Ui.styleTable(t);
                    Expect.equal(t.getShowHorizontalLines(), want[i][0], choices[i] + " horizontal");
                    Expect.equal(t.getShowVerticalLines(), want[i][1], choices[i] + " vertical");
                }
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("each style key accepts only its range or choices", () -> {
            Map<String, KeyType> spec = ThemeKeys.spec();
            Object[][] want = {
                {"RADIUS", 0, 12}, {"CARD_RADIUS", 0, 16}, {"TAB_HOVER_RADIUS", 0, 16},
                {"LINE_WIDTH", 1, 2}, {"ICON_STROKE", 75, 150}, {"TAB_UNDERLINE", 1, 4}};
            for (Object[] w : want) {
                Expect.equal(spec.get((String) w[0]), new KeyType.Number((int) w[1], (int) w[2]), (String) w[0]);
            }
            Expect.equal(spec.get("TAB_MARKER"), new KeyType.Choice(List.of("underline", "pill")), "TAB_MARKER");
            Expect.equal(spec.get("PRIMARY_BUTTON"), new KeyType.Choice(List.of("filled", "outline")), "PRIMARY_BUTTON");
            Expect.equal(spec.get("TABLE_GRID"), new KeyType.Choice(List.of("none", "horizontal", "both")), "TABLE_GRID");
            Expect.equal(spec.get("TREE_LINES"), new KeyType.Choice(List.of("off", "on")), "TREE_LINES");
        });
    }

    private static BufferedImage paint(JComponent c, int w, int h) {
        c.setSize(w, h);
        c.doLayout();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Theme.BG);
        g.fillRect(0, 0, w, h);
        c.paint(g);
        g.dispose();
        return img;
    }

    /** How far from grey a colour is; anti-aliased edges keep a share of it. */
    private static int chroma(Color c) {
        return Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue())) - Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
    }

    private static int alphaAt(RoundedBorder b, JComponent c, int x, int y) {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        b.paintBorder(c, g, 0, 0, 20, 20);
        g.dispose();
        return img.getRGB(x, y) >>> 24;
    }

    private static BufferedImage primaryButton() {
        JButton b = new JButton();
        b.setUI((javax.swing.plaf.ButtonUI) FlatButtonUI.createUI(b));
        b.setBorder(null);
        Theme.primary(b);
        return paint(b, 60, 30);
    }

    private static int centre(BufferedImage img) {
        return img.getRGB(img.getWidth() / 2, img.getHeight() / 2);
    }

    /** The pixel on the bottom row and the one near the left edge, mid-height, of the selected first tab. */
    private static Color[] tabPixels() {
        JTabbedPane tp = new JTabbedPane();
        tp.setUI((javax.swing.plaf.TabbedPaneUI) FlatTabbedPaneUI.createUI(tp));
        tp.addTab("Alpha", new JPanel());
        BufferedImage img = paint(tp, 300, 100);
        Rectangle r = tp.getUI().getTabBounds(tp, 0);
        int x = r.x + r.width / 2;
        return new Color[] {
            new Color(img.getRGB(x, r.y + r.height - 1), true),
            new Color(img.getRGB(r.x + 4, r.y + r.height / 2), true)};
    }
}
