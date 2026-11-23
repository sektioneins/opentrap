package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.UIDefaults;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * Pins the keyboard shortcuts to each system's convention. Metal binds copy,
 * paste and the rest to Control everywhere; on macOS the theme moves them to
 * Command, word moves to Option and the line and text ends to Command with the
 * arrows, as Mac applications have them, and other systems keep Metal's.
 */
public final class MacKeysTest {

    private static UIDefaults rewritten(String osName) {
        UIDefaults d = new MetalLookAndFeel().getDefaults();
        MacKeys.installDefaults(d, osName);
        return d;
    }

    private static Object bound(UIDefaults d, String map, String key) {
        InputMap m = (InputMap) d.get(map);
        return m.get(KeyStroke.getKeyStroke(key));
    }

    public void run() {
        Harness.suite("macOS keyboard shortcuts");

        Harness.test("on macOS the text commands move from Control to Command", () -> {
            UIDefaults d = rewritten("Mac OS X");
            for (String c : MacKeys.TEXT) {
                String map = c + ".focusInputMap";
                Expect.equal(bound(d, map, "meta C"), "copy-to-clipboard", c + " Cmd-C");
                Expect.equal(bound(d, map, "meta V"), "paste-from-clipboard", c + " Cmd-V");
                Expect.equal(bound(d, map, "meta X"), "cut-to-clipboard", c + " Cmd-X");
                Expect.equal(bound(d, map, "meta A"), "select-all", c + " Cmd-A");
                Expect.equal(bound(d, map, "ctrl C"), null, c + " Ctrl-C");
                Expect.equal(bound(d, map, "ctrl V"), null, c + " Ctrl-V");
                Expect.equal(bound(d, map, "ctrl A"), null, c + " Ctrl-A");
            }
        });

        Harness.test("on macOS a text field moves by word with Option and to the line ends with Command", () -> {
            UIDefaults d = rewritten("Mac OS X");
            String map = "TextField.focusInputMap";
            Expect.equal(bound(d, map, "alt LEFT"), "caret-previous-word", "Option-Left");
            Expect.equal(bound(d, map, "shift alt RIGHT"), "selection-next-word", "Option-Shift-Right");
            Expect.equal(bound(d, map, "alt BACK_SPACE"), "delete-previous-word", "Option-Backspace");
            Expect.equal(bound(d, map, "meta LEFT"), "caret-begin-line", "Cmd-Left");
            Expect.equal(bound(d, map, "shift meta RIGHT"), "selection-end-line", "Cmd-Shift-Right");
            Expect.equal(bound(d, map, "ctrl LEFT"), null, "Ctrl-Left");
            Expect.equal(bound(d, map, "ctrl H"), "delete-previous", "Ctrl-H stays, as in Aqua");
        });

        Harness.test("on macOS Command with Up and Down goes to the start and end of a text area", () -> {
            UIDefaults d = rewritten("Mac OS X");
            String map = "TextArea.focusInputMap";
            Expect.equal(bound(d, map, "meta UP"), "caret-begin", "Cmd-Up");
            Expect.equal(bound(d, map, "shift meta DOWN"), "selection-end", "Cmd-Shift-Down");
            Expect.equal(bound(d, map, "ctrl HOME"), null, "Ctrl-Home");
        });

        Harness.test("on macOS a password field moves to the line ends, never by word", () -> {
            UIDefaults d = rewritten("Mac OS X");
            String map = "PasswordField.focusInputMap";
            Expect.equal(bound(d, map, "meta LEFT"), "caret-begin-line", "Cmd-Left");
            Expect.equal(bound(d, map, "meta RIGHT"), "caret-end-line", "Cmd-Right");
            Expect.equal(bound(d, map, "alt LEFT"), null, "Option-Left");
        });

        Harness.test("on macOS lists, tables and trees copy and select all with Command", () -> {
            UIDefaults d = rewritten("Mac OS X");
            for (String map : MacKeys.SELECTION) {
                Expect.equal(bound(d, map, "meta C"), "copy", map + " Cmd-C");
                Expect.equal(bound(d, map, "meta A"), "selectAll", map + " Cmd-A");
                Expect.equal(bound(d, map, "ctrl C"), null, map + " Ctrl-C");
            }
            Expect.equal(bound(d, "Table.ancestorInputMap", "ctrl DOWN"), "selectNextRowChangeLead",
                    "navigation keys stay on Control");
        });

        Harness.test("other systems keep the Control bindings", () -> {
            UIDefaults d = rewritten("Linux");
            Expect.equal(bound(d, "TextField.focusInputMap", "ctrl C"), "copy-to-clipboard", "text field Ctrl-C");
            Expect.equal(bound(d, "TextField.focusInputMap", "meta C"), null, "text field Cmd-C");
            Expect.equal(bound(d, "Table.ancestorInputMap", "ctrl C"), "copy", "table Ctrl-C");
        });

        Harness.test("a menu shortcut uses Command on macOS and Control elsewhere", () -> {
            Expect.equal(MacKeys.menuMask("Mac OS X"), java.awt.event.InputEvent.META_DOWN_MASK, "macOS");
            Expect.equal(MacKeys.menuMask("Linux"), java.awt.event.InputEvent.CTRL_DOWN_MASK, "Linux");
            KeyStroke cmdShiftK = KeyStroke.getKeyStroke("shift meta K");
            Expect.equal(MacKeys.text(cmdShiftK, "Mac OS X"), "\u21e7\u2318K", "written the Mac way");
            Expect.equal(MacKeys.text(KeyStroke.getKeyStroke("ctrl K"), "Linux"), "Ctrl+K", "written with Ctrl");
        });
    }
}
