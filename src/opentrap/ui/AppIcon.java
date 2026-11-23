package opentrap.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The application window icon.
 *
 * <p>The icon is the S1 logo, amber traffic arrows held by brackets, from
 * {@code icon/opentrap-s1-light.svg} and {@code icon/opentrap-s1-dark.svg},
 * rasterised to PNGs under {@code opentrap/ui/icons/} and loaded here. The two
 * differ only in the bracket colour: navy for a light background, pale blue for
 * a dark one. Windows and dialogs take the variant for the active theme, since
 * the logo shows on the theme's own background in About and Discover. The
 * launcher, the dock and the AppImage are drawn before any theme is known, so
 * they use the light variant. The original orange icon, {@code icon/opentrap.svg}
 * and {@code opentrap_<size>.png}, stays in the tree but is no longer loaded.
 *
 * <p>If the raster resources are somehow absent, a plain drawn fallback is used
 * so the window always has an icon.
 */
public final class AppIcon {

    private AppIcon() {}

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** Icons at the sizes a window manager typically wants. */
    public static List<BufferedImage> images() {
        List<BufferedImage> out = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage img = load(size);
            if (img != null) out.add(img);
        }
        if (out.isEmpty()) out.add(fallback(64));   // never leave the window iconless
        return out;
    }

    /** The logo at one size as a Swing icon, for dialogs that show it. */
    public static javax.swing.Icon icon(int size) {
        BufferedImage img = load(size);
        if (img == null) {
            // Not a rasterised size: scale the smallest one that is larger.
            for (int s : SIZES) {
                if (s >= size) { img = load(s); if (img != null) break; }
            }
            if (img == null) img = fallback(size);
            if (img.getWidth() != size) {
                BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, 0, 0, size, size, null);
                g.dispose();
                img = scaled;
            }
        }
        return new javax.swing.ImageIcon(img);
    }

    /** The resource path of the S1 raster at one size, for a dark or a light background. */
    static String resource(boolean dark, int size) {
        return "/opentrap/ui/icons/opentrap-s1-" + (dark ? "dark" : "light") + "_" + size + ".png";
    }

    private static BufferedImage load(int size) {
        return load(Theme.dark, size);
    }

    private static BufferedImage load(boolean dark, int size) {
        try (InputStream in = AppIcon.class.getResourceAsStream(resource(dark, size))) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** A minimal drawn icon, only used if the bundled rasters are missing. */
    private static BufferedImage fallback(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.ACCENT);
        double s = size, t = s * 0.14, cy1 = s * 0.38, cy2 = s * 0.62;
        g.setStroke(new java.awt.BasicStroke((float) t, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g.drawLine((int) (s * 0.2), (int) cy1, (int) (s * 0.8), (int) cy1);
        g.drawLine((int) (s * 0.8), (int) cy2, (int) (s * 0.2), (int) cy2);
        g.dispose();
        return img;
    }

    /**
     * Export the launcher icon (the light variant) as PNGs into a directory,
     * named {@code opentrap_<size>.png}, for the desktop installer. Run with
     * {@code java -cp dist/opentrap.jar opentrap.ui.AppIcon <dir>}.
     */
    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();
        for (int size : SIZES) {
            BufferedImage img = load(false, size);
            if (img == null) img = fallback(size);
            ImageIO.write(img, "png", new java.io.File(dir, "opentrap_" + size + ".png"));
        }
        System.out.println("wrote icons to " + dir.getAbsolutePath());
    }
}
