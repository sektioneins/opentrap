package opentrap.ui;

import opentrap.core.Settings;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferencesFactory;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIDefaults;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.event.InputEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pins the rebindable shortcuts: their defaults on the menu modifier, what a
 * setting stores, which keys are refused, and that a bound component answers
 * from the settings of the moment, so a change in Settings needs no rebinding.
 */
public final class HotkeysTest {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int META = InputEvent.META_DOWN_MASK;

    private static Settings settings() {
        MemoryPreferencesFactory.reset();
        return new Settings();
    }

    private static KeyStroke key(String s) { return KeyStroke.getKeyStroke(s); }

    private static Hotkeys.Action action(java.util.List<Hotkeys.Action> list, String id) {
        for (Hotkeys.Action a : list) if (a.id().equals(id)) return a;
        throw new AssertionError("no action " + id);
    }

    public void run() {
        Harness.suite("hotkeys");

        Harness.test("the defaults sit on the menu modifier, Go to with Shift", () -> {
            var ctrl = Hotkeys.actions(CTRL);
            Expect.equal(action(ctrl, "palette").def(), key("ctrl K"), "palette on Ctrl");
            Expect.equal(action(ctrl, "goto.proxy").def(), key("shift ctrl P"), "Go to Proxy on Ctrl");
            Expect.equal(action(ctrl, "send.repeater").def(), key("ctrl R"), "Send to Repeater on Ctrl");
            var meta = Hotkeys.actions(META);
            Expect.equal(action(meta, "goto.organizer").def(), key("shift meta O"), "Go to Organizer on Cmd");
            Expect.equal(action(meta, "send.intruder").def(), key("meta I"), "Send to Intruder on Cmd");
        });

        Harness.test("a stored hotkey replaces the default, None clears it and the default forgets it", () -> {
            Settings s = settings();
            Hotkeys.Action palette = Hotkeys.action("palette");
            Expect.equal(Hotkeys.get(s, "palette"), palette.def(), "default before a change");

            Hotkeys.set(s, "palette", key("shift ctrl J"));
            Expect.equal(Hotkeys.get(s, "palette"), key("shift ctrl J"), "after a change");

            Hotkeys.set(s, "palette", null);
            Expect.equal(Hotkeys.get(s, "palette"), null, "after None");

            Hotkeys.set(s, "palette", palette.def());
            Expect.equal(s.get(Hotkeys.PREFIX + "palette", null), null, "the default leaves nothing stored");
            Expect.equal(Hotkeys.get(s, "palette"), palette.def(), "default again");
        });

        Harness.test("a hotkey without the menu modifier, on an editing key or taken by another action is refused", () -> {
            Settings s = settings();
            UIDefaults d = new MetalLookAndFeel().getDefaults();
            String id = "goto.proxy";
            Expect.isTrue(Hotkeys.refusal(s, id, key("shift P"), CTRL, MacKeys.TEXT, MacKeys.SELECTION, d) != null,
                    "no modifier");
            Expect.isTrue(Hotkeys.refusal(s, id, key("ctrl C"), CTRL, MacKeys.TEXT, MacKeys.SELECTION, d) != null,
                    "copy in text fields");
            Hotkeys.Action repeater = Hotkeys.action("send.repeater");
            String clash = Hotkeys.refusal(s, id, repeater.def(), repeater.def().getModifiers() & (CTRL | META),
                    MacKeys.TEXT, MacKeys.SELECTION, d);
            Expect.isTrue(clash != null && clash.contains("Send to Repeater"), "taken by Send to Repeater: " + clash);
            Expect.equal(Hotkeys.refusal(s, id, key("shift ctrl F2"), CTRL, MacKeys.TEXT, MacKeys.SELECTION, d), null,
                    "a free key");
            Expect.equal(Hotkeys.refusal(s, id, null, CTRL, MacKeys.TEXT, MacKeys.SELECTION, d), null, "None");
        });

        Harness.test("Metal's Ctrl-Shift-O direction flip gives way to Go to Organizer where Ctrl is the menu key", () -> {
            UIDefaults d = new MetalLookAndFeel().getDefaults();
            Hotkeys.freeDefaults(d, Hotkeys.actions(CTRL));
            Expect.equal(((javax.swing.InputMap) d.get("TextField.focusInputMap")).get(key("shift ctrl O")), null,
                    "text field Ctrl-Shift-O");
            Expect.equal(((javax.swing.InputMap) d.get("TextField.focusInputMap")).get(key("ctrl C")), "copy-to-clipboard",
                    "the other bindings stay");
        });

        Harness.test("a bound component follows a hotkey changed after it was bound", () -> {
            Settings s = settings();
            JPanel c = new JPanel();
            AtomicInteger runs = new AtomicInteger();
            Hotkeys.install(c, JComponent.WHEN_FOCUSED, () -> s, Map.of("palette", runs::incrementAndGet));
            KeyStroke def = Hotkeys.action("palette").def();
            Object bound = c.getInputMap(JComponent.WHEN_FOCUSED).get(def);
            Expect.equal(bound, "opentrap.hotkey.palette", "the default is bound");
            c.getActionMap().get(bound).actionPerformed(null);
            Expect.equal(runs.get(), 1, "the action ran");

            Hotkeys.set(s, "palette", key("shift ctrl F2"));
            Expect.equal(c.getInputMap(JComponent.WHEN_FOCUSED).get(def), null, "the old key is free");
            Expect.equal(c.getInputMap(JComponent.WHEN_FOCUSED).get(key("shift ctrl F2")), "opentrap.hotkey.palette",
                    "the new key is bound");
        });
    }
}
