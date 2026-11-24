package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/** Settings > Suite pages: REST API, Updates, Temporary files location, Startup behavior, Shutdown behavior. */
public final class SuitePages {
    private SuitePages() {}

    public static final String CONFIRM_CLOSE = "suite.confirmClose";

    public static SettingsPage restApi(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JLabel url = Ui.label("Service URL: " + s.get("rest.url", "http://127.0.0.1:1337"));
        JButton change = Ui.button("Change...");
        change.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(change, "Service URL:", s.get("rest.url", "http://127.0.0.1:1337"));
            if (v != null && !v.isBlank()) { s.set("rest.url", v.strip()); url.setText("Service URL: " + v.strip()); }
        });
        p.block("REST API", SettingsPage.Kind.USER,
                "The REST API lets other tools drive OpenTRAP: start scans, read issues, fetch traffic. It listens on the service URL "
              + "and requires an API key unless you choose otherwise.",
                () -> SettingsPage.clearPrefix(s, "rest."),
                Ui.row(url, change),
                SettingsPage.check(s, "rest.running", false, "Service running"),
                SettingsPage.check(s, "rest.noKey", false, "Allow access without API key (using this option is not secure)"),
                Ui.bold("API Keys"),
                new KeyedTable(s, "rest.keys", new String[]{"Enabled", "Name", "Created"}, 100, "Add", "Edit", "Remove"));
        return p;
    }

    public static SettingsPage updates(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JButton check = Ui.primaryButton("Check for updates", "open the project's releases page");
        check.addActionListener(e -> {
            try { Desktop.getDesktop().browse(java.net.URI.create(opentrap.ui.Upstream.URL + "/releases")); } catch (Exception _) { }
        });
        p.block("Updates [installer version only]", SettingsPage.Kind.USER,
                "Whether OpenTRAP checks for a newer release when it starts, and from which channel.",
                () -> SettingsPage.clearPrefix(s, "updates."),
                SettingsPage.check(s, "updates.auto", false, "Enable auto-updates (requires restart)"),
                SettingsPage.labelled("Channel:", SettingsPage.combo(s, "updates.channel", new String[]{"No Channel Selected", "Stable", "Early adopter"}, "No Channel Selected")),
                check);
        return p;
    }

    public static SettingsPage tempFiles(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] r = SettingsPage.radios(s, "temp.mode", "default", new String[]{"default", "custom"}, "Use default system temp directory", "Use custom location:");
        p.block("Temporary files location", SettingsPage.Kind.USER,
                "Where large temporary files (streaming responses, engine working data) are written. Default: " + System.getProperty("java.io.tmpdir"),
                () -> { s.set("temp.mode", "default"); s.set("temp.dir", ""); },
                r[0], r[1], OptionsPage.indent(SettingsPage.pathRow(s, "temp.dir", "", true, "Choose folder ..."), 24));
        return p;
    }

    public static SettingsPage startup(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Automated tasks on startup", SettingsPage.Kind.USER,
                "Whether the startup dialog pre-selects \"Pause automated tasks\", so a reopened project does not start scanning at once.",
                () -> s.setBool("startup.pauseTasks", true),
                SettingsPage.check(s, "startup.pauseTasks", true, "Select \"Pause automated tasks\" by default"));
        p.block("Unrecognized project files", SettingsPage.Kind.USER,
                "A project file this installation did not create, save or open with trust is opened only after "
                + "you confirm. Its session handling rules, macros, cookies and project settings act on the "
                + "requests you send, so without trust they are left out. Always trusting project files "
                + "loads them without asking.",
                () -> s.setBool(opentrap.ui.ProjectTrust.ALWAYS, false),
                SettingsPage.check(s, opentrap.ui.ProjectTrust.ALWAYS, false, "Always trust project files"));
        return p;
    }

    public static SettingsPage shutdown(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("OpenTRAP's closing behavior", SettingsPage.Kind.USER,
                "Skip the confirmation dialog when closing the suite.",
                () -> s.setBool(CONFIRM_CLOSE, true),
                SettingsPage.check(s, "suite.noConfirmClose", false, "Don't confirm before closing OpenTRAP"));
        return p;
    }
}
