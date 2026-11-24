package opentrap.extensions;

import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.extensions.store.Store;
import opentrap.ui.SettingsDialog;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;

/**
 * Extensions tool: load and manage add-ons that customise the suite.
 * Six sub-tabs: Installed (what is loaded), Store (add-ons from the sources),
 * APIs (the module contract), Custom scan checks and Script library (user
 * scripts, one function or all), and the settings launcher.
 */
public class ExtensionsTool implements ToolModule {

    private SuiteContext ctx;

    @Override public String name() { return "extensions"; }

    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        SettingsDialog.register("Extensions", () -> new CoreExtensionSettingsPanel(ctx.settings()));
    }

    @Override public Tab[] tabs() {
        JComponent settings = ctx == null ? new JPanel()
                : SettingsDialog.launcher(ctx, "Extensions", "Extensions settings",
                        "Startup and load behaviour for extensions, and the Java, Python and Ruby runtimes they use, live under Settings > Extensions.");
        return new Tab[]{
                new Tab("Installed", new InstalledPanel()),
                storeTab(),
                new Tab("APIs", new ApisPanel()),
                new Tab("Custom scan checks", new ScriptLibraryPanel(
                        ScriptLibraryPanel.Function.SCAN_CHECK,
                        "Custom scan checks",
                        "Extend the scanner with your own checks. A custom scan check is a script that inspects a request "
                      + "and its response and reports issues; the scanner runs every enabled check against each audit item.",
                        "Extend the scanner with custom scan checks",
                        "Write a check once and the scanner runs it everywhere. To get started, create a new check or import one.")),
                new Tab("Script library", new ScriptLibraryPanel(
                        null,
                        "Script library",
                        "Scripts are small pieces of Java that run from tools across OpenTRAP to carry out a range of tasks: "
                      + "filters, custom actions, custom columns and scan checks. Use the library to manage your scripts in one "
                      + "place, then load them directly in compatible tools when needed.",
                        "Customize OpenTRAP with Java-based scripts",
                        "To get started, create or import a new script. Looking for ideas? Use a built-in template to create "
                      + "your script or browse the project's GitHub repository for ready-to-use scripts.")),
                new Tab("Extensions settings", settings),
        };
    }

    /** The Store tab, titled with the number of updates; a short note where the add-on store is not set up. */
    private Tab storeTab() {
        Store store = Store.sharedIfSet();
        if (store == null) {
            JLabel note = Ui.label("The add-on store is not set up in this session.");
            note.setForeground(Theme.MUTED);
            note.setHorizontalAlignment(SwingConstants.CENTER);
            return new Tab("Store", note);
        }
        StorePanel panel = new StorePanel(ctx, store);
        return new Tab(panel.tabTitle(), panel);
    }
}
