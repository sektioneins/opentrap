package opentrap.ui;

import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.UIDefaults;
import javax.swing.plaf.InputMapUIResource;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Keyboard shortcuts as each system has them: Command on macOS, Control
 * elsewhere.
 *
 * <p>The suite runs Metal, whose text fields, lists, tables and trees bind
 * copy, paste, select all and the rest to Control on every system. On macOS
 * the theme rewrites those bindings the way Mac applications have them: the
 * commands move to Command, word moves and word deletes to Option, Command
 * with Left and Right goes to the start and end of the line, and with Up and
 * Down to the start and end of the text. Control with H and Control-Shift-O
 * stay, as in Aqua. Elsewhere the bindings are left alone.
 */
public final class MacKeys {

    private MacKeys() {}

    /** The text components, whose maps take the Mac editing keys. */
    static final String[] TEXT = {"TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane"};

    /** The maps of lists, tables and trees, where only the command keys move. */
    static final String[] SELECTION = {"List.focusInputMap", "Table.ancestorInputMap", "Tree.focusInputMap"};

    private static final int MODIFIERS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
            | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    /** The modifier of menu shortcuts. Unlike the toolkit's, it also answers in a headless JVM. */
    public static int menuMask() {
        return menuMask(System.getProperty("os.name"));
    }

    static int menuMask(String osName) {
        return MacMenuBar.isMac(osName) ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /** A menu shortcut: the key with the menu modifier and any others given. */
    public static KeyStroke shortcut(int keyCode, int extraModifiers) {
        return KeyStroke.getKeyStroke(keyCode, menuMask() | extraModifiers);
    }

    /** How a shortcut is written on this system, for tooltips and lists. */
    public static String text(KeyStroke ks) {
        return text(ks, System.getProperty("os.name"));
    }

    /** On macOS the modifier symbols in the order Mac menus use, then the key; elsewhere {@code "Ctrl+Shift+K"}. */
    static String text(KeyStroke ks, String osName) {
        int m = ks.getModifiers();
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        StringBuilder sb = new StringBuilder();
        if (MacMenuBar.isMac(osName)) {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append('\u2303');
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append('\u2325');
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append('\u21e7');
            if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append('\u2318');
            return sb.append(key).toString();
        }
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append("Meta+");
        return sb.append(key).toString();
    }

    /** Rewrite the look and feel's key bindings for macOS. Call after the look and feel is set. */
    static void installDefaults(UIDefaults d) {
        installDefaults(d, System.getProperty("os.name"));
    }

    static void installDefaults(UIDefaults d, String osName) {
        if (!MacMenuBar.isMac(osName)) return;
        for (String c : TEXT) {
            String key = c + ".focusInputMap";
            if (d.get(key) instanceof InputMap m) d.put(key, text(m, c.equals("PasswordField")));
        }
        for (String key : SELECTION) {
            if (d.get(key) instanceof InputMap m) d.put(key, selection(m));
        }
    }

    /**
     * A text map with the Mac editing keys. A password field moves by line
     * only, so as not to give away where the spaces are; its Control-Left and
     * Control-Right already go to the line ends, and they move to Command.
     */
    static InputMap text(InputMap m, boolean password) {
        InputMapUIResource out = new InputMapUIResource();
        KeyStroke[] all = m.allKeys();
        if (all == null) return out;
        for (KeyStroke ks : all) {
            int mods = ks.getModifiers() & MODIFIERS;
            KeyStroke to = ks;
            if ((mods & InputEvent.CTRL_DOWN_MASK) != 0 && (mods & InputEvent.META_DOWN_MASK) == 0) {
                int rest = mods & ~InputEvent.CTRL_DOWN_MASK;
                int code = ks.getKeyCode();
                boolean keep = code == KeyEvent.VK_H || code == KeyEvent.VK_INSERT
                        || (code == KeyEvent.VK_O && rest == InputEvent.SHIFT_DOWN_MASK);
                if (!keep) {
                    int key = code;
                    int mod = InputEvent.META_DOWN_MASK;
                    switch (code) {
                        case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_KP_LEFT, KeyEvent.VK_KP_RIGHT ->
                                mod = password ? InputEvent.META_DOWN_MASK : InputEvent.ALT_DOWN_MASK;
                        case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> mod = InputEvent.ALT_DOWN_MASK;
                        case KeyEvent.VK_HOME -> key = KeyEvent.VK_UP;
                        case KeyEvent.VK_END -> key = KeyEvent.VK_DOWN;
                        default -> { }
                    }
                    to = KeyStroke.getKeyStroke(key, rest | mod, ks.isOnKeyRelease());
                }
            }
            out.put(to, m.get(ks));
        }
        lineEnd(out, KeyEvent.VK_LEFT, 0, DefaultEditorKit.beginLineAction);
        lineEnd(out, KeyEvent.VK_KP_LEFT, 0, DefaultEditorKit.beginLineAction);
        lineEnd(out, KeyEvent.VK_RIGHT, 0, DefaultEditorKit.endLineAction);
        lineEnd(out, KeyEvent.VK_KP_RIGHT, 0, DefaultEditorKit.endLineAction);
        lineEnd(out, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionBeginLineAction);
        lineEnd(out, KeyEvent.VK_KP_LEFT, InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionBeginLineAction);
        lineEnd(out, KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionEndLineAction);
        lineEnd(out, KeyEvent.VK_KP_RIGHT, InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionEndLineAction);
        return out;
    }

    /** Command with an arrow goes to the line end, unless the map already binds it. */
    private static void lineEnd(InputMap m, int code, int extra, String action) {
        KeyStroke ks = KeyStroke.getKeyStroke(code, InputEvent.META_DOWN_MASK | extra);
        if (m.get(ks) == null) m.put(ks, action);
    }

    /** A list, table or tree map with copy, cut, paste, select all and clear selection on Command. */
    static InputMap selection(InputMap m) {
        InputMapUIResource out = new InputMapUIResource();
        KeyStroke[] all = m.allKeys();
        if (all == null) return out;
        for (KeyStroke ks : all) {
            int mods = ks.getModifiers() & MODIFIERS;
            int code = ks.getKeyCode();
            boolean command = mods == InputEvent.CTRL_DOWN_MASK && switch (code) {
                case KeyEvent.VK_A, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_X,
                     KeyEvent.VK_SLASH, KeyEvent.VK_BACK_SLASH -> true;
                default -> false;
            };
            KeyStroke to = command ? KeyStroke.getKeyStroke(code, InputEvent.META_DOWN_MASK, ks.isOnKeyRelease()) : ks;
            out.put(to, m.get(ks));
        }
        return out;
    }
}
