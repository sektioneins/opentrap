package opentrap.extensions;

import opentrap.core.Settings;
import opentrap.extensions.store.Store;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * Settings > Extensions > Core extension settings: startup and load behaviour,
 * the add-on sources, and where the Java, Python and Ruby runtimes for
 * extensions live.
 */
public final class CoreExtensionSettingsPanel extends OptionsPage {

    /** Whether every enabled add-on source is fetched at startup. */
    static final String UPDATE_ON_STARTUP = "ext.updateOnStartup";

    /**
     * Off, so a fresh install makes no outbound request on its own: on a
     * client's network an unasked-for fetch may be blocked, logged or against
     * the rules of the engagement. Only a key never set reads this default; a
     * choice the user made is stored and kept.
     */
    static final boolean UPDATE_ON_STARTUP_DEFAULT = false;

    /** Whether the user has switched the startup check for add-on updates on. */
    static boolean updateOnStartup(Settings s) { return s.getBool(UPDATE_ON_STARTUP, UPDATE_ON_STARTUP_DEFAULT); }

    /** The page, with the sources of {@code store}, or a note where the add-on store is not set up (null). */
    public CoreExtensionSettingsPanel(Settings s, Store store) {
        JCheckBox reload = bind(s, "ext.reloadOnStartup", true, "Automatically reload extensions on startup");
        JCheckBox update = bind(s, UPDATE_ON_STARTUP, UPDATE_ON_STARTUP_DEFAULT,
                "Check for add-on updates on startup (contacts every enabled source)");
        section("Startup behavior", reload, update);

        section("Add-on sources",
                Ui.note("Where Extensions > Store lists add-ons from. A source is contacted only when you ask for it, "
                        + "as with Refresh in Store, or at startup when the check above is on."),
                store != null ? new SourcesPanel(store) : Ui.note("The add-on store is not set up in this session."));

        JCheckBox hide = bind(s, "ext.hideReloadDialog", true, "Hide dialog on reload");
        section("Load behavior", hide);

        section("Java environment",
                Ui.note("Folder for loading library JAR files (optional)"),
                folderRow(s, "ext.javaLibs", ""));

        section("Python environment",
                Ui.note("Location of Jython standalone JAR file"),
                fileRow(s, "ext.jython", ""),
                Ui.note("Folder for loading modules (optional)"),
                folderRow(s, "ext.pythonModules", ""));

        section("Ruby environment",
                Ui.note("Location of JRuby JAR file"),
                fileRow(s, "ext.jruby", ""));
    }

    private static JCheckBox bind(Settings s, String key, boolean def, String text) {
        JCheckBox c = Ui.check(text, s.getBool(key, def));
        c.addItemListener(e -> s.setBool(key, c.isSelected()));
        return c;
    }

    private static JPanel folderRow(Settings s, String key, String def) { return pathRow(s, key, def, true); }
    private static JPanel fileRow(Settings s, String key, String def) { return pathRow(s, key, def, false); }

    private static JPanel pathRow(Settings s, String key, String def, boolean folder) {
        JTextField f = Ui.field(s.get(key, def));
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
        });
        JButton pick = Ui.button(folder ? "Select folder ..." : "Select file ...");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(f.getText().isBlank() ? System.getProperty("user.home") : f.getText());
            if (folder) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) f.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        p.add(f);
        p.add(pick);
        return p;
    }
}
