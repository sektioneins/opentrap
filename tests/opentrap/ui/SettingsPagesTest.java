package opentrap.ui;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.settings.InterfacePages;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.List;

/**
 * Builds every page in the settings tree.
 *
 * <p>A page that throws while being built takes the whole dialog down, and
 * nothing notices until someone clicks that one entry in the tree. Five pages
 * did exactly that: the tables they carry were handed to a JTable from a field
 * initialiser, which runs before the constructor has set the column names, so
 * the table asked a model with null columns how many it had.
 *
 * <p>Clicking through the tools does not exercise any of this, which is why it
 * went out. This case clicks every entry for us.
 */
public final class SettingsPagesTest {

    public void run() {
        Harness.suite("settings pages");

        Harness.test("every page in the tree builds", () -> {
            SuiteContext ctx = new SuiteContext();
            List<String> failures = SettingsDialog.buildEveryPage(ctx);
            Expect.equal(String.join("\n    ", failures), "",
                    "pages that threw on the way open");
        });

        Harness.test("a page is built fresh each time it is shown", () -> {
            // The dialog releases its panes when it closes, so a second open
            // must be able to build them again rather than reuse dead ones.
            SuiteContext ctx = new SuiteContext();
            Expect.equal(String.join("\n    ", SettingsDialog.buildEveryPage(ctx)), "",
                    "first build");
            Expect.equal(String.join("\n    ", SettingsDialog.buildEveryPage(ctx)), "",
                    "second build");
        });

        Harness.test("choosing a UI font saves family and size and restarts once", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                InterfacePages.applyUiFont(s, "Fira Sans", 15, n::incrementAndGet);
                Expect.equal(s.get(Fonts.KEY_UI_FAMILY, null), "Fira Sans", "family");
                Expect.equal(s.get(Fonts.KEY_UI_SIZE, null), "15", "size");
                Expect.equal(n.get(), 1, "restarts");
            } finally { s.remove(Fonts.KEY_UI_FAMILY); s.remove(Fonts.KEY_UI_SIZE); }
        });

        Harness.test("resetting the UI font removes both keys and restarts once", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                s.set(Fonts.KEY_UI_FAMILY, "Fira Sans"); s.set(Fonts.KEY_UI_SIZE, "15");
                InterfacePages.resetUiFont(s, n::incrementAndGet);
                Expect.isTrue(s.get(Fonts.KEY_UI_FAMILY, null) == null, "family removed");
                Expect.isTrue(s.get(Fonts.KEY_UI_SIZE, null) == null, "size removed");
                Expect.equal(n.get(), 1, "restarts");
            } finally { s.remove(Fonts.KEY_UI_FAMILY); s.remove(Fonts.KEY_UI_SIZE); }
        });

        Harness.test("choosing a monospace font saves it in the editor font format and restarts once", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                InterfacePages.applyMonoFont(s, "Fira Code", 14, n::incrementAndGet);
                Expect.equal(s.get(Fonts.KEY_MONO, null), "Fira Code 14pt", "saved value");
                Expect.equal(n.get(), 1, "restarts");
            } finally { s.remove(Fonts.KEY_MONO); }
        });

        Harness.test("resetting the monospace font removes the key and restarts once", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                s.set(Fonts.KEY_MONO, "Fira Code 14pt");
                InterfacePages.resetMonoFont(s, n::incrementAndGet);
                Expect.isTrue(s.get(Fonts.KEY_MONO, null) == null, "key removed");
                Expect.equal(n.get(), 1, "restarts");
            } finally { s.remove(Fonts.KEY_MONO); }
        });

        Harness.test("choosing the active UI font changes nothing and does not restart", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                InterfacePages.applyUiFont(s, Fonts.uiFamily(), Fonts.uiSize(), n::incrementAndGet);
                Expect.equal(n.get(), 0, "restarts");
                Expect.isTrue(s.get(Fonts.KEY_UI_FAMILY, null) == null, "family not written");
                Expect.isTrue(s.get(Fonts.KEY_UI_SIZE, null) == null, "size not written");
            } finally { s.remove(Fonts.KEY_UI_FAMILY); s.remove(Fonts.KEY_UI_SIZE); }
        });

        Harness.test("choosing the active monospace font changes nothing and does not restart", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            try {
                InterfacePages.applyMonoFont(s, Fonts.monoFamily(), Fonts.monoSize(), n::incrementAndGet);
                Expect.equal(n.get(), 0, "restarts");
                Expect.isTrue(s.get(Fonts.KEY_MONO, null) == null, "key not written");
            } finally { s.remove(Fonts.KEY_MONO); }
        });

        Harness.test("resetting a font that was never saved does not restart", () -> {
            Settings s = new Settings();
            AtomicInteger n = new AtomicInteger();
            s.remove(Fonts.KEY_UI_FAMILY); s.remove(Fonts.KEY_UI_SIZE); s.remove(Fonts.KEY_MONO);
            InterfacePages.resetUiFont(s, n::incrementAndGet);
            InterfacePages.resetMonoFont(s, n::incrementAndGet);
            Expect.equal(n.get(), 0, "restarts");
        });

        Harness.test("a restart in an AppImage tells the user instead of relaunching", () -> {
            AtomicInteger relaunch = new AtomicInteger(), told = new AtomicInteger();
            InterfacePages.restart(new SuiteContext(), true, () -> true,
                    () -> { relaunch.incrementAndGet(); return true; }, () -> relaunch.incrementAndGet(),
                    told::incrementAndGet);
            Expect.equal(relaunch.get(), 0, "relaunches");
            Expect.equal(told.get(), 1, "messages");
        });

        Harness.test("a restart that could not relaunch tells the user and keeps running", () -> {
            AtomicInteger exits = new AtomicInteger(), told = new AtomicInteger();
            InterfacePages.restart(new SuiteContext(), false, () -> true, () -> false,
                    exits::incrementAndGet, told::incrementAndGet);
            Expect.equal(exits.get(), 0, "exits");
            Expect.equal(told.get(), 1, "messages");
        });

        Harness.test("a temporary project is only restarted after the user agrees", () -> {
            AtomicInteger relaunch = new AtomicInteger(), exits = new AtomicInteger(), told = new AtomicInteger();
            SuiteContext temp = new SuiteContext();
            InterfacePages.restart(temp, false, () -> false,
                    () -> { relaunch.incrementAndGet(); return true; }, exits::incrementAndGet, told::incrementAndGet);
            Expect.equal(relaunch.get(), 0, "relaunched after No");
            Expect.equal(told.get(), 0, "message after No");
            InterfacePages.restart(temp, false, () -> true,
                    () -> { relaunch.incrementAndGet(); return true; }, exits::incrementAndGet, told::incrementAndGet);
            Expect.equal(relaunch.get(), 1, "relaunched after Yes");
            Expect.equal(exits.get(), 1, "exits after Yes");
        });

        Harness.test("the display page shows the active UI font with change and reset, and no size box", () -> {
            var all = parts(InterfacePages.display(new SuiteContext(), null));
            Expect.isTrue(all.stream().anyMatch(c -> c instanceof JLabel l
                    && Fonts.describe(Fonts.uiFamily(), Fonts.uiSize()).equals(l.getText())), "active font label");
            Expect.equal(count(all, "Change font ..."), 1, "change buttons");
            Expect.equal(count(all, "Reset to default"), 1, "reset buttons");
            Expect.isTrue(all.stream().noneMatch(c -> c instanceof JComboBox<?> b && has(b, "13") && has(b, "20")),
                    "no size combo");
        });

        Harness.test("the message editor page shows the active monospace font with change and reset", () -> {
            var all = parts(InterfacePages.messageEditor(new SuiteContext()));
            Expect.isTrue(all.stream().anyMatch(c -> c instanceof JLabel l
                    && Fonts.describe(Fonts.monoFamily(), Fonts.monoSize()).equals(l.getText())), "active font label");
            Expect.equal(count(all, "Change font ..."), 1, "change buttons");
            Expect.equal(count(all, "Reset to default"), 1, "reset buttons");
        });
    }

    private static java.util.List<Component> parts(Component root) {
        var out = new ArrayList<Component>();
        out.add(root);
        if (root instanceof Container c) for (Component k : c.getComponents()) out.addAll(parts(k));
        return out;
    }

    private static int count(java.util.List<Component> all, String text) {
        return (int) all.stream().filter(c -> c instanceof JButton b && text.equals(b.getText())).count();
    }

    private static boolean has(JComboBox<?> b, String item) {
        for (int i = 0; i < b.getItemCount(); i++) if (item.equals(String.valueOf(b.getItemAt(i)))) return true;
        return false;
    }
}
