package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JMenuBar;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

/**
 * Pins the macOS screen menu bar: macOS asks for it unless the command line
 * chose, other systems keep the bar in the window, and with it on the theme
 * hands menu bars to Aqua, the only UI the JDK moves to the top of the screen.
 */
public final class MacMenuBarTest {

    /** Run with the screen menu bar property set to a value (null clears it), then put it back. */
    private static void withProperty(String value, Runnable body) {
        String was = System.getProperty(MacMenuBar.PROPERTY);
        try {
            if (value == null) System.clearProperty(MacMenuBar.PROPERTY);
            else System.setProperty(MacMenuBar.PROPERTY, value);
            body.run();
        } finally {
            if (was == null) System.clearProperty(MacMenuBar.PROPERTY);
            else System.setProperty(MacMenuBar.PROPERTY, was);
        }
    }

    private static boolean onMac() {
        return MacMenuBar.isMac(System.getProperty("os.name"));
    }

    public void run() {
        Harness.suite("macOS screen menu bar");

        Harness.test("macOS asks for the screen menu bar", () -> withProperty(null, () -> {
            MacMenuBar.request("Mac OS X");
            Expect.equal(System.getProperty(MacMenuBar.PROPERTY), "true", "property");
        }));

        Harness.test("a choice made on the command line is kept", () -> withProperty("false", () -> {
            MacMenuBar.request("Mac OS X");
            Expect.equal(System.getProperty(MacMenuBar.PROPERTY), "false", "property");
        }));

        Harness.test("Linux and Windows keep the menu bar in the window", () -> withProperty(null, () -> {
            MacMenuBar.request("Linux");
            MacMenuBar.request("Windows 11");
            Expect.isTrue(System.getProperty(MacMenuBar.PROPERTY) == null, "property left unset");
        }));

        Harness.test("with the screen menu bar on macOS the theme hands menu bars to Aqua", () -> withProperty("true", () -> {
            UIDefaults d = new UIDefaults();
            MacMenuBar.installDefaults(d);
            if (onMac()) {
                Expect.equal(d.get("MenuBarUI"), MacMenuBar.AQUA_UI, "menu bar UI");
                Expect.isTrue(d.get("MenuBar.backgroundPainter") != null, "Aqua's background painter defined");
            } else {
                Expect.isTrue(d.get("MenuBarUI") == null, "no Aqua off macOS");
            }
        }));

        Harness.test("with the screen menu bar off the theme keeps its own menu bar", () -> withProperty("false", () -> {
            UIDefaults d = new UIDefaults();
            MacMenuBar.installDefaults(d);
            Expect.isTrue(d.get("MenuBarUI") == null, "menu bar UI left to the theme");
        }));

        Harness.test("on macOS a menu bar created under the theme gets Aqua's UI", () -> {
            if (!onMac()) return;   // the Aqua classes exist only in a macOS JDK
            UIDefaults d = UIManager.getDefaults();
            Object ui = d.get("MenuBarUI"), painter = d.get("MenuBar.backgroundPainter");
            try {
                withProperty("true", () -> MacMenuBar.installDefaults(d));
                Expect.equal(new JMenuBar().getUI().getClass().getName(), MacMenuBar.AQUA_UI, "menu bar UI");
            } finally {
                d.put("MenuBarUI", ui);
                d.put("MenuBar.backgroundPainter", painter);
            }
        });
    }
}
