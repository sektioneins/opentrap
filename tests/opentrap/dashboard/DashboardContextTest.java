package opentrap.dashboard;

import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.Hotkeys;

import javax.swing.JComponent;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard builds its interface in its constructor, before the frame hands
 * it the context in init(). Its request log carries the history right-click menu
 * and the Send to shortcuts, which both need the context, so they must be
 * installed once init() has run, not while the context is still null.
 */
public final class DashboardContextTest {

    /** The action map key a Send to shortcut is bound under (Hotkeys.Lookup.ACTION). */
    private static final String SEND_TO_REPEATER = "opentrap.hotkey." + Hotkeys.sendTo("repeater");

    public void run() {
        Harness.suite("Dashboard context");

        Harness.test("the dashboard builds before it is given a context", () -> {
            new DashboardTool();
        });

        Harness.test("the request log gets the Send to shortcuts once the context arrives", () -> {
            DashboardTool d = new DashboardTool();
            d.init(new SuiteContext());
            List<JTable> withShortcuts = new ArrayList<>();
            for (JTable t : tables(d.tabs()[0].component()))
                if (t.getActionMap().get(SEND_TO_REPEATER) != null) withShortcuts.add(t);
            Expect.equal(withShortcuts.size(), 1, "dashboard tables with the Send to shortcuts");
        });
    }

    private static List<JTable> tables(Component c) {
        List<JTable> out = new ArrayList<>();
        if (c instanceof JTable t) out.add(t);
        if (c instanceof Container k) for (Component child : k.getComponents()) out.addAll(tables(child));
        return out;
    }
}
