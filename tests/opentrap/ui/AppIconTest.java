package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pins the application icon to the S1 logo: the variant drawn for the active
 * theme's background in windows and dialogs, and the light variant in the
 * launcher export, which is written before any theme is known.
 */
public final class AppIconTest {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static BufferedImage bundled(String path) {
        try (InputStream in = AppIcon.class.getResourceAsStream(path)) {
            Expect.isTrue(in != null, "bundled raster " + path);
            return ImageIO.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }

    public void run() {
        Harness.suite("application icon");

        Harness.test("both S1 variants are bundled at every window icon size", () -> {
            for (int size : SIZES) {
                for (boolean dark : new boolean[]{false, true}) {
                    BufferedImage img = bundled(AppIcon.resource(dark, size));
                    Expect.equal(img.getWidth(), size, "width of " + AppIcon.resource(dark, size));
                }
            }
        });

        Harness.test("the window icon is the S1 variant for the active theme", () -> {
            boolean was = Theme.dark;
            try {
                for (boolean dark : new boolean[]{false, true}) {
                    Theme.dark = dark;
                    List<BufferedImage> imgs = AppIcon.images();
                    Expect.equal(imgs.size(), SIZES.length, "one icon per size");
                    BufferedImage big = imgs.get(imgs.size() - 1);
                    Expect.isTrue(samePixels(bundled(AppIcon.resource(dark, 256)), big),
                            "256 px icon is the " + (dark ? "dark" : "light") + " variant");
                    Expect.isTrue(!samePixels(bundled(AppIcon.resource(!dark, 256)), big),
                            "256 px icon is not the other variant");
                }
            } finally {
                Theme.dark = was;
            }
        });

        Harness.test("the launcher export writes the light S1 variant whatever the theme", () -> {
            boolean was = Theme.dark;
            Path dir = null;
            try {
                dir = Files.createTempDirectory("appicon");
                Theme.dark = true;
                AppIcon.main(new String[]{dir.toString()});
                BufferedImage out = ImageIO.read(new File(dir.toFile(), "opentrap_256.png"));
                Expect.isTrue(samePixels(bundled(AppIcon.resource(false, 256)), out),
                        "exported 256 px icon is the light variant");
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                Theme.dark = was;
                if (dir != null) {
                    try {
                        for (int size : SIZES) Files.deleteIfExists(dir.resolve("opentrap_" + size + ".png"));
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                        // a leftover temp directory does not change the result
                    }
                }
            }
        });
    }
}
