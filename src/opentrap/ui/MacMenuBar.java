package opentrap.ui;

import javax.swing.BorderFactory;
import javax.swing.UIDefaults;
import java.awt.Desktop;
import java.util.Locale;

/**
 * The macOS screen menu bar: on macOS the main window's menus sit in the
 * system menu bar at the top of the screen, as in any Mac application, instead
 * of in a bar inside the window. Other systems keep the bar in the window.
 *
 * <p>The JDK moves a {@code JMenuBar} to the top of the screen only when
 * {@code apple.laf.useScreenMenuBar} is true and the bar's UI is Aqua's
 * {@code AquaMenuBarUI}. The suite runs Metal with its own theme, so the theme
 * maps {@code MenuBarUI} to the Aqua class for the menu bar alone; the menus
 * and items keep their Swing UIs, which the screen menu bar does not draw. The
 * Aqua class is not exported from {@code java.desktop}, but Swing creates UIs
 * of its own module directly, so the name in the defaults is enough.
 *
 * <p>{@code -Dapple.laf.useScreenMenuBar=false} keeps the menus in the window.
 */
public final class MacMenuBar {

    private MacMenuBar() {}

    static final String PROPERTY = "apple.laf.useScreenMenuBar";
    static final String AQUA_UI = "com.apple.laf.AquaMenuBarUI";

    static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /**
     * Ask for the screen menu bar on macOS, unless the property was given on
     * the command line. Call before any menu bar is created.
     */
    public static void request() {
        request(System.getProperty("os.name"));
    }

    static void request(String osName) {
        if (isMac(osName) && System.getProperty(PROPERTY) == null) System.setProperty(PROPERTY, "true");
    }

    /** Whether menu bars go to the top of the screen in this process. */
    public static boolean active() {
        return isMac(System.getProperty("os.name")) && Boolean.getBoolean(PROPERTY);
    }

    /**
     * Hand menu bars to Aqua when the screen menu bar is on. Aqua's bar paints
     * its background with {@code MenuBar.backgroundPainter}, which Metal does
     * not define; it is only drawn if the bar ever stays in a window.
     */
    static void installDefaults(UIDefaults d) {
        if (!active()) return;
        d.put("MenuBarUI", AQUA_UI);
        d.put("MenuBar.backgroundPainter", BorderFactory.createEmptyBorder());
    }

    /**
     * Fill the application menu that macOS adds in front of the screen menus:
     * its About and Settings items open the suite's own about box and settings
     * instead of Java's defaults (a generic about box, no settings item).
     */
    static void installAppMenu(Runnable about, Runnable settings) {
        if (!Desktop.isDesktopSupported()) return;
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler(e -> about.run());
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler(e -> settings.run());
    }
}
