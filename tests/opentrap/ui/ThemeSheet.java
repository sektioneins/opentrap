package opentrap.ui;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Draws the built-in themes as a sheet for a person to review: one row per key
 * with its name and a before and an after swatch for light and dark. "Before"
 * is {@code theme-snapshot.txt}, "after" is what the bases resolve to now. Number
 * and choice keys are printed as text, and a row whose value moved is marked.
 * Not a suite; run it with
 * {@code java -Djava.awt.headless=true -cp build:build-tests opentrap.ui.ThemeSheet <file.png>}.
 */
public final class ThemeSheet {
    private static final int ROW = 22, NAME_W = 250, COL_W = 170, SW = 56, PAD = 10;

    private ThemeSheet() { }

    private static Map<String, String> before() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : Files.readAllLines(Path.of("tests", "opentrap", "ui", "theme-snapshot.txt"))) {
            String[] p = l.split(" ", 3);
            if (p.length == 3) m.put(p[0] + " " + p[1], p[2]);
        }
        return m;
    }

    private static Map<String, String> after() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            for (boolean dark : new boolean[] {false, true}) {
                for (String l : ThemeSnapshotTest.current(dark)) {
                    String[] p = l.split(" ", 3);
                    m.put(p[0] + " " + p[1], p[2]);
                }
            }
        } finally {
            Theme.configure(false);
        }
        return m;
    }

    private static Color colour(String v) {
        if (v == null || !v.startsWith("#")) return null;
        int rgb = Integer.parseInt(v.substring(1, 7), 16);
        int a = v.length() == 9 ? Integer.parseInt(v.substring(7), 16) : 255;
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a);
    }

    private static void cell(Graphics2D g, int x, int y, String v, Color ink) {
        Color c = colour(v);
        int tx = x;
        if (c != null) {
            g.setColor(Color.WHITE);
            g.fillRect(x, y + 2, SW, ROW - 4);
            g.setColor(c);
            g.fillRect(x, y + 2, SW, ROW - 4);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRect(x, y + 2, SW, ROW - 4);
            tx = x + SW + 6;
        }
        g.setColor(ink);
        g.drawString(v == null ? "-" : v, tx, y + ROW - 7);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Map<String, String> was = before(), now = after();
        TreeSet<String> keys = new TreeSet<>();
        for (String k : now.keySet()) keys.add(k.substring(k.indexOf(' ') + 1));
        int w = PAD * 2 + 26 + NAME_W + 4 * COL_W;
        int h = PAD * 2 + ROW * (keys.size() + 2);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Color ink = new Color(0x1A1A1A);
        int y = PAD, x0 = PAD + 26;
        String[] heads = {"light before", "light after", "dark before", "dark after"};
        g.setColor(ink);
        g.drawString("key  (* = value moved)", x0, y + ROW - 7);
        for (int i = 0; i < 4; i++) g.drawString(heads[i], x0 + NAME_W + i * COL_W, y + ROW - 7);
        y += ROW * 2;
        for (String k : keys) {
            String lb = was.get("light " + k), la = now.get("light " + k);
            String db = was.get("dark " + k), da = now.get("dark " + k);
            boolean moved = !java.util.Objects.equals(lb, la) || !java.util.Objects.equals(db, da);
            if (moved) {
                g.setColor(new Color(255, 245, 190));
                g.fillRect(PAD, y, w - PAD * 2, ROW);
            }
            g.setColor(ink);
            g.drawString((moved ? "* " : "  ") + k, PAD, y + ROW - 7);
            String[] vals = {lb, la, db, da};
            for (int i = 0; i < 4; i++) cell(g, x0 + NAME_W + i * COL_W, y, vals[i], ink);
            y += ROW;
        }
        g.dispose();
        File out = new File(args[0]);
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out + " (" + keys.size() + " keys)");
    }
}
