package opentrap.ui;

import opentrap.core.Settings;

import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIDefaults;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The suite's rebindable keyboard shortcuts. Each one has an id, a default on
 * the menu modifier (Command on macOS, Control elsewhere) and an optional
 * override in the user settings under {@code hotkey.<id>}, stored in the
 * {@link KeyStroke} string form ({@code "shift meta P"}); an empty value means
 * no shortcut.
 *
 * <p>Components bind them through {@link #install}, which looks the setting up
 * on every key press, so a change in Settings takes effect at once without
 * telling every bound component.
 */
public final class Hotkeys {

    private Hotkeys() {}

    public static final String PREFIX = "hotkey.";

    /** One rebindable action. */
    public record Action(String id, String name, String category, KeyStroke def) {}

    /** The Go to and Send to tools that have a shortcut, in the order they are listed. */
    public static final List<String> GO_TO = List.of("proxy", "intruder", "repeater", "logger", "organizer");
    public static final List<String> SEND_TO = List.of("intruder", "repeater", "organizer");

    public static List<Action> actions() {
        return actions(MacKeys.menuMask());
    }

    /** The actions with their defaults on the given menu modifier. */
    static List<Action> actions(int menuMask) {
        List<Action> out = new ArrayList<>();
        out.add(new Action("palette", "Open command palette", "Open", KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask)));
        for (String t : GO_TO)
            out.add(new Action(goTo(t), "Go to " + title(t), "Switch tab",
                    KeyStroke.getKeyStroke(letter(t), menuMask | InputEvent.SHIFT_DOWN_MASK)));
        for (String t : SEND_TO)
            out.add(new Action(sendTo(t), "Send to " + title(t), "Send to", KeyStroke.getKeyStroke(letter(t), menuMask)));
        return out;
    }

    public static String goTo(String tool) { return "goto." + tool; }

    public static String sendTo(String tool) { return "send." + tool; }

    private static String title(String tool) { return Character.toUpperCase(tool.charAt(0)) + tool.substring(1); }

    private static int letter(String tool) { return KeyEvent.getExtendedKeyCodeForChar(tool.charAt(0)); }

    static Action action(String id) {
        for (Action a : actions()) if (a.id().equals(id)) return a;
        return null;
    }

    /** The shortcut of an action, or null when it has none or is not one of the suite's. */
    public static KeyStroke get(Settings s, String id) {
        Action a = action(id);
        if (a == null) return null;
        String v = s == null ? null : s.get(PREFIX + id, null);
        if (v == null) return a.def();
        return v.isBlank() ? null : KeyStroke.getKeyStroke(v);
    }

    /** Store a shortcut; null stores "no shortcut", the default goes back to following the default. */
    public static void set(Settings s, String id, KeyStroke ks) {
        Action a = action(id);
        if (a != null && ks != null && ks.equals(a.def())) s.remove(PREFIX + id);
        else s.set(PREFIX + id, ks == null ? "" : ks.toString());
    }

    /** How a shortcut is written on this system; empty for none. */
    public static String text(KeyStroke ks) { return ks == null ? "" : MacKeys.text(ks); }

    /**
     * Why a shortcut cannot be given to an action, or null when it can. It
     * must use the menu modifier, must not be one the text fields, lists and
     * tables already use (copy, paste and the like), and must not be taken by
     * another of the suite's actions.
     */
    public static String refusal(Settings s, String id, KeyStroke ks) {
        return refusal(s, id, ks, MacKeys.menuMask(), MacKeys.TEXT, MacKeys.SELECTION, javax.swing.UIManager.getDefaults());
    }

    static String refusal(Settings s, String id, KeyStroke ks, int menuMask, String[] text, String[] selection, UIDefaults d) {
        if (ks == null) return null;
        if ((ks.getModifiers() & menuMask) == 0)
            return "Hotkeys must use " + (menuMask == InputEvent.META_DOWN_MASK ? "\u2318" : "Ctrl");
        for (String c : text) if (bound(d, c + ".focusInputMap", ks)) return MacKeys.text(ks) + " is used for editing text";
        for (String m : selection) if (bound(d, m, ks)) return MacKeys.text(ks) + " is used in lists and tables";
        for (Action a : actions())
            if (!a.id().equals(id) && ks.equals(get(s, a.id()))) return MacKeys.text(ks) + " is already used by \"" + a.name() + "\"";
        return null;
    }

    private static boolean bound(UIDefaults d, String key, KeyStroke ks) {
        return d.get(key) instanceof InputMap m && m.get(ks) != null;
    }

    /**
     * Take the default shortcuts out of the look and feel's text, list and
     * table maps, where they would win over the suite's while such a component
     * has the focus. Only Metal's Control-Shift-O, which flips a text field's
     * direction, is in the way; it is the default for "Go to Organizer" on
     * systems where the menu modifier is Control.
     */
    static void freeDefaults(UIDefaults d) {
        freeDefaults(d, actions());
    }

    static void freeDefaults(UIDefaults d, List<Action> actions) {
        List<String> keys = new ArrayList<>();
        for (String c : MacKeys.TEXT) keys.add(c + ".focusInputMap");
        keys.addAll(List.of(MacKeys.SELECTION));
        for (String k : keys) {
            if (!(d.get(k) instanceof InputMap m)) continue;
            boolean changed = false;
            javax.swing.plaf.InputMapUIResource out = new javax.swing.plaf.InputMapUIResource();
            KeyStroke[] all = m.allKeys();
            if (all == null) continue;
            for (KeyStroke ks : all) {
                boolean taken = false;
                for (Action a : actions) if (ks.equals(a.def())) taken = true;
                if (taken) changed = true; else out.put(ks, m.get(ks));
            }
            if (changed) d.put(k, out);
        }
    }

    /**
     * Bind actions to a component under the given condition. The shortcuts
     * come from the settings at each key press; while {@code settings} gives
     * null, the defaults apply. The bindings sit just above the look and
     * feel's, so they win over a UI binding on the same key.
     */
    public static void install(JComponent c, int condition, Supplier<Settings> settings, Map<String, Runnable> actions) {
        InputMap own = c.getInputMap(condition);
        Lookup lookup = new Lookup(settings, List.copyOf(actions.keySet()));
        lookup.setParent(own.getParent());
        own.setParent(lookup);
        for (var e : actions.entrySet()) {
            Runnable r = e.getValue();
            c.getActionMap().put(Lookup.ACTION + e.getKey(), new AbstractAction() {
                @Override public void actionPerformed(ActionEvent ev) { r.run(); }
            });
        }
    }

    /** An input map that answers from the current settings instead of a fixed table. */
    static final class Lookup extends InputMap {
        static final String ACTION = "opentrap.hotkey.";
        private final Supplier<Settings> settings;
        private final List<String> ids;

        Lookup(Supplier<Settings> settings, List<String> ids) {
            this.settings = settings;
            this.ids = ids;
        }

        @Override public Object get(KeyStroke ks) {
            if (ks != null && !ks.isOnKeyRelease() && ks.getKeyCode() != KeyEvent.VK_UNDEFINED) {
                Settings s = settings.get();
                for (String id : ids) if (ks.equals(Hotkeys.get(s, id))) return ACTION + id;
            }
            return super.get(ks);
        }
    }
}
