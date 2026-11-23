package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Pins the start-up dialog's header to the S1 wordmark: navy or pale text by
 * theme, the amber badge after the word, and none of the upstream orange the
 * header used to draw its name in.
 */
public final class WordmarkTest {

    private static BufferedImage render(boolean dark, int height) {
        boolean was = Theme.dark;
        try {
            Theme.dark = dark;
            Wordmark mark = new Wordmark(height);
            BufferedImage img = new BufferedImage(mark.getIconWidth(), mark.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            mark.paintIcon(null, g, 0, 0);
            g.dispose();
            return img;
        } finally {
            Theme.dark = was;
        }
    }

    private static boolean near(int argb, Color c) {
        if ((argb >>> 24) < 0xF0) return false;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.abs(r - c.getRed()) <= 8 && Math.abs(g - c.getGreen()) <= 8 && Math.abs(b - c.getBlue()) <= 8;
    }

    private static int count(BufferedImage img, Color c) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if (near(img.getRGB(x, y), c)) n++;
        return n;
    }

    /** The leftmost column, from {@code from} on, that holds a pixel of this colour; -1 if none. */
    private static int firstColumn(BufferedImage img, Color c, int from) {
        for (int x = from; x < img.getWidth(); x++)
            for (int y = 0; y < img.getHeight(); y++)
                if (near(img.getRGB(x, y), c)) return x;
        return -1;
    }

    public void run() {
        Harness.suite("wordmark");

        Harness.test("the wordmark draws navy text and an amber badge on a light theme", () -> {
            BufferedImage img = render(false, 40);
            Expect.isTrue(count(img, Wordmark.NAVY) > 200, "navy word and brackets");
            Expect.isTrue(count(img, Wordmark.AMBER) > 400, "amber arrows and badge");
            Expect.equal(count(img, Wordmark.TEXT_DARK), 0, "no pale text on a light theme");
        });

        Harness.test("the wordmark draws pale text and brackets on a dark theme", () -> {
            BufferedImage img = render(true, 40);
            Expect.isTrue(count(img, Wordmark.TEXT_DARK) > 200, "pale word");
            Expect.isTrue(count(img, Wordmark.BRACKET_DARK) > 20, "pale brackets");
            Expect.isTrue(count(img, Wordmark.AMBER) > 400, "amber arrows and badge");
        });

        Harness.test("the amber badge follows the word", () -> {
            BufferedImage img = render(false, 40);
            // The logo's arrows end at about 40 % of the height; the word starts after them.
            int wordStart = firstColumn(img, Wordmark.NAVY, img.getHeight());
            Expect.isTrue(wordStart > 0, "word drawn after the logo");
            int badgeStart = firstColumn(img, Wordmark.AMBER, wordStart);
            Expect.isTrue(badgeStart > wordStart + 2 * img.getHeight(), "badge after the word, at " + badgeStart);
            Expect.isTrue(img.getWidth() - badgeStart < img.getHeight(), "badge closes the mark");
        });

        Harness.test("the wordmark uses none of the upstream orange accent", () -> {
            Color orange = new Color(0xDB, 0x61, 0x2F);
            Expect.equal(count(render(false, 40), orange), 0, "light");
            Expect.equal(count(render(true, 40), orange), 0, "dark");
        });

        Harness.test("the start-up header shows the wordmark under the app's name", () -> {
            JLabel logo = ProjectStartupDialog.wordmark();
            Expect.isTrue(logo.getIcon() instanceof Wordmark, "header icon is the wordmark");
            Expect.isTrue(logo.getText() == null || logo.getText().isEmpty(), "no separate name text");
            Expect.equal(logo.getAccessibleContext().getAccessibleName(), Main.NAME, "accessible name");
        });
    }
}
