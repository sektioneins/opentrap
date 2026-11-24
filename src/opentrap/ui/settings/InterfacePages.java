package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.Fonts;
import opentrap.ui.Glyphs;
import opentrap.ui.Hotkeys;
import opentrap.ui.MacKeys;
import opentrap.ui.OptionsPage;
import opentrap.ui.Theme;
import opentrap.ui.Ui;
import opentrap.ui.theme.ThemeCatalog;
import opentrap.ui.theme.ThemeException;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Settings > User interface pages: Side panel, Message editor, Hotkeys, Display. */
public final class InterfacePages {
    private InterfacePages() {}

    public static SettingsPage sidePanel(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] pos = SettingsPage.radios(s, "sidepanel.position", "right", new String[]{"left", "right"}, "Left", "Right");
        JRadioButton[] mode = SettingsPage.radios(s, "sidepanel.mode", "auto", new String[]{"auto", "collapsed"},
                "Auto-expanded when there is enough space", "Always collapsed");
        p.block("Default side panel layout", SettingsPage.Kind.USER,
                "Where the Inspector side panel sits beside a message editor, and whether it opens by itself when there is room.",
                () -> { s.set("sidepanel.position", "right"); s.set("sidepanel.mode", "auto"); },
                SettingsPage.labelled("Position:", pos[0], pos[1]),
                SettingsPage.labelled("Display mode:", mode[0], mode[1]));
        String[] widgets = {"Selection", "Request attributes", "Request query parameters", "Request body parameters", "Request cookies", "Request headers", "Response headers"};
        p.block("Inspector widgets", SettingsPage.Kind.USER,
                "Which sections the Inspector shows, which open by default, and whether their values wrap.",
                () -> SettingsPage.clearPrefix(s, "inspector."),
                new WidgetTable(s, widgets));
        return p;
    }

    /** Widget / Show / Open by default / Wrap text, with Up and Down. */
    private static final class WidgetTable extends JPanel {
        private final List<String> names;
        private final Settings s;
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"Widget", "Show", "Open by default", "Wrap text"};
            @Override public int getRowCount() { return names.size(); }
            @Override public int getColumnCount() { return 4; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Boolean.class; }
            @Override public boolean isCellEditable(int r, int c) { return c > 0 && !(r == 0 && c == 1); }
            @Override public Object getValueAt(int r, int c) {
                String n = names.get(r);
                return switch (c) { case 0 -> n; case 1 -> s.getBool(key(n, "show"), true); case 2 -> s.getBool(key(n, "open"), false); default -> s.getBool(key(n, "wrap"), false); };
            }
            @Override public void setValueAt(Object v, int r, int c) {
                String n = names.get(r);
                s.setBool(key(n, c == 1 ? "show" : c == 2 ? "open" : "wrap"), Boolean.TRUE.equals(v));
            }
        };
        private static String key(String n, String what) { return "inspector." + n.toLowerCase().replace(' ', '-') + "." + what; }
        WidgetTable(Settings s, String[] widgets) {
            super(new BorderLayout(8, 0));
            this.s = s;
            setOpaque(false);
            String order = s.get("inspector.order", "");
            names = new ArrayList<>(order.isEmpty() ? List.of(widgets) : List.of(order.split("\\|")));
            JTable t = new JTable(model);
            Ui.styleTable(t);
            JButton up = Ui.button("Up"), down = Ui.button("Down");
            up.addActionListener(e -> move(t, -1));
            down.addActionListener(e -> move(t, 1));
            add(Ui.buttonColumn(up, down), BorderLayout.WEST);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(560, 190));
            add(sp, BorderLayout.CENTER);
        }
        private void move(JTable t, int d) {
            int i = t.getSelectedRow(), to = i + d;
            if (i < 0 || to < 0 || to >= names.size()) return;
            names.add(to, names.remove(i));
            s.set("inspector.order", String.join("|", names));
            model.fireTableDataChanged();
            t.setRowSelectionInterval(to, to);
        }
    }

    public static SettingsPage messageEditor(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Message editor request views", SettingsPage.Kind.USER,
                "Which views a request editor offers and whether their text wraps. Raw is always available.",
                () -> SettingsPage.clearPrefix(s, "editor.request."),
                new ViewsTable(s, "editor.request.", new String[]{"Pretty", "Raw", "Hex", "Headers", "Query params", "Body params", "Cookies", "Attributes"},
                        new boolean[]{true, true, true, false, false, false, false, false}));
        p.block("Message editor response views", SettingsPage.Kind.USER,
                "Which views a response editor offers and whether their text wraps.",
                () -> SettingsPage.clearPrefix(s, "editor.response."),
                new ViewsTable(s, "editor.response.", new String[]{"Pretty", "Raw", "Hex", "Render", "Headers"},
                        new boolean[]{true, true, true, true, false}));
        JLabel font = Ui.label(Fonts.describe(Fonts.monoFamily(), Fonts.monoSize()));
        JButton change = Ui.button("Change font ...");
        change.addActionListener(e -> chooseFont(change, "Message editor font", Fonts.monoFamily(), Fonts.monoSize(),
                (fam, sz) -> applyMonoFont(s, fam, sz, () -> restartApp(ctx))));
        JButton resetFont = Ui.button("Reset to default");
        resetFont.addActionListener(e -> resetMonoFont(s, () -> restartApp(ctx)));
        p.block("HTTP message display", SettingsPage.Kind.USER,
                "How requests and responses are rendered in every message editor. Changing the font restarts the app. Restoring this block clears the saved font, which applies at the next start.",
                () -> { SettingsPage.clearPrefix(s, "editor.display."); s.remove(Fonts.KEY_MONO); },
                SettingsPage.labelled("Font:", font, change, resetFont),
                SettingsPage.check(s, "editor.display.highlightRequest", true, "Highlight request syntax"),
                SettingsPage.check(s, "editor.display.highlightResponse", true, "Highlight response syntax"),
                SettingsPage.check(s, "editor.display.prettyByDefault", true, "Pretty print by default"),
                SettingsPage.check(s, "editor.display.hideDecodedOnHover", false, "Hide decoded text on hover"));
        JRadioButton[] cs = SettingsPage.radios(s, "editor.charset", "auto", new String[]{"auto", "platform", "raw", "specific"},
                "Recognize automatically based on message headers", "Use the platform default (UTF-8)", "Display as raw bytes", "Use a specific character set:");
        JComboBox<String> charset = SettingsPage.combo(s, "editor.charset.name", java.nio.charset.Charset.availableCharsets().keySet().toArray(new String[0]), "UTF-8");
        p.block("Character sets", SettingsPage.Kind.USER,
                "Which character set is used to display message bodies.",
                () -> { s.set("editor.charset", "auto"); s.set("editor.charset.name", "UTF-8"); },
                cs[0], cs[1], cs[2], Ui.row(cs[3], charset));
        p.block("HTML rendering", SettingsPage.Kind.USER,
                "Whether the Render view may fetch the images, styles and scripts a page refers to.",
                () -> s.setBool("render.allowHttp", false),
                SettingsPage.check(s, "render.allowHttp", false, "Make additional HTTP requests if required to render the page"));
        p.block("HTTP message search", SettingsPage.Kind.USER,
                "Defaults for the search box at the bottom of every message editor.",
                () -> SettingsPage.clearPrefix(s, "editor.search."),
                Ui.row(SettingsPage.check(s, "editor.search.caseSensitive", false, "Case sensitive"),
                       SettingsPage.check(s, "editor.search.regex", false, "Regex"),
                       SettingsPage.check(s, "editor.search.autoScroll", false, "Auto-scroll to match when text changes")));
        p.block("Hide request headers", SettingsPage.Kind.USER,
                "Headers the Pretty view folds away by default, so the interesting part of a request is visible at once.",
                () -> { s.setBool("editor.hideRequestHeaders", false); s.set("editor.hideRequestHeaders.list", ""); },
                SettingsPage.check(s, "editor.hideRequestHeaders", false, "Hide the following headers by default:"),
                new ListBox(s, "editor.hideRequestHeaders.list", List.of("accept", "accept-encoding", "accept-language", "if-modified-since", "if-none-match",
                        "priority", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user", "upgrade-insecure-requests"), "Enter a new header"));
        p.block("Hide response headers", SettingsPage.Kind.USER,
                "Headers the Pretty view folds away by default in responses.",
                () -> { s.setBool("editor.hideResponseHeaders", false); s.set("editor.hideResponseHeaders.list", ""); },
                SettingsPage.check(s, "editor.hideResponseHeaders", false, "Hide the following headers by default:"),
                new ListBox(s, "editor.hideResponseHeaders.list", List.of("accept-ranges", "alt-svc", "cache-control", "cf-ray", "connection", "content-security-policy",
                        "cross-origin-embedder-policy", "cross-origin-opener-policy", "cross-origin-resource-policy", "date", "etag", "expires", "last-modified", "nel",
                        "report-to", "server", "strict-transport-security", "vary", "via", "x-content-type-options", "x-frame-options", "x-xss-protection"), "Enter a new header"));
        return p;
    }

    /** Type / Show / Wrap text, for the editor views. The Raw row's Show is locked. */
    private static final class ViewsTable extends JPanel {
        ViewsTable(Settings s, String prefix, String[] types, boolean[] showDefaults) {
            super(new BorderLayout());
            setOpaque(false);
            AbstractTableModel model = new AbstractTableModel() {
                private final String[] cols = {"Type", "Show", "Wrap text"};
                @Override public int getRowCount() { return types.length; }
                @Override public int getColumnCount() { return 3; }
                @Override public String getColumnName(int c) { return cols[c]; }
                @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Boolean.class; }
                @Override public boolean isCellEditable(int r, int c) { return c > 0 && !(c == 1 && "Raw".equals(types[r])); }
                @Override public Object getValueAt(int r, int c) {
                    String k = prefix + types[r].toLowerCase().replace(' ', '-');
                    return switch (c) { case 0 -> types[r]; case 1 -> s.getBool(k + ".show", showDefaults[r]); default -> s.getBool(k + ".wrap", false); };
                }
                @Override public void setValueAt(Object v, int r, int c) {
                    String k = prefix + types[r].toLowerCase().replace(' ', '-');
                    s.setBool(k + (c == 1 ? ".show" : ".wrap"), Boolean.TRUE.equals(v));
                }
            };
            JTable t = new JTable(model);
            Ui.styleTable(t);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(560, 22 * types.length + 30));
            add(sp, BorderLayout.CENTER);
        }
    }

    /** A list with Remove / Clear and an Add field, persisted as newline-separated text. */
    static final class ListBox extends JPanel {
        ListBox(Settings s, String key, List<String> defaults, String placeholder) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            DefaultListModel<String> m = new DefaultListModel<>();
            String cur = s.get(key, "");
            for (String v : cur.isEmpty() ? defaults : List.of(cur.split("\n"))) if (!v.isBlank()) m.addElement(v);
            Runnable save = () -> { StringBuilder sb = new StringBuilder(); for (int i = 0; i < m.size(); i++) sb.append(m.get(i)).append('\n'); s.set(key, sb.toString()); };
            JList<String> list = new JList<>(m);
            list.setFont(Ui.PLAIN);
            JButton remove = Ui.button("Remove"), clear = Ui.button("Clear");
            remove.addActionListener(e -> { int i = list.getSelectedIndex(); if (i >= 0) { m.remove(i); save.run(); } });
            clear.addActionListener(e -> { m.clear(); save.run(); });
            JTextField entry = Ui.field("");
            entry.putClientProperty("JTextField.placeholderText", placeholder);
            JButton add = Ui.button("Add");
            add.addActionListener(e -> { if (!entry.getText().isBlank()) { m.addElement(entry.getText().strip()); entry.setText(""); save.run(); } });
            entry.addActionListener(e -> add.doClick());
            JScrollPane sp = Ui.scroll(list);
            sp.setPreferredSize(new Dimension(420, 120));
            add(Ui.buttonColumn(remove, clear), BorderLayout.WEST);
            add(sp, BorderLayout.CENTER);
            add(Ui.row(add, entry), BorderLayout.SOUTH);
        }
    }

    public static SettingsPage hotkeys(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JTextField search = Ui.field("");
        search.putClientProperty("JTextField.placeholderText", "Search");
        HotkeyTable table = new HotkeyTable(s);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
        });
        String key = MacKeys.menuMask() == java.awt.event.InputEvent.META_DOWN_MASK ? "Command" : "Control";
        p.block("Hotkeys", SettingsPage.Kind.USER,
                "Double-click an action to change its hotkey. All hotkeys must use the " + key + " key together with "
              + "another key; use the reset mark beside a row to return them all to their defaults.",
                () -> { SettingsPage.clearPrefix(s, Hotkeys.PREFIX); table.refresh(); },
                search, table);
        return p;
    }

    /** Action / Category / Hotkey, one row per shortcut the suite binds. A change applies at the next key press. */
    static final class HotkeyTable extends JPanel {
        private final Settings s;
        private final List<Hotkeys.Action> shown = new ArrayList<>(Hotkeys.actions());
        private final AbstractTableModel model = new AbstractTableModel() {
            @Override public int getRowCount() { return shown.size(); }
            @Override public int getColumnCount() { return 3; }
            @Override public String getColumnName(int c) { return c == 0 ? "Action" : c == 1 ? "Category" : "Hotkey"; }
            @Override public Object getValueAt(int r, int c) {
                Hotkeys.Action a = shown.get(r);
                return c == 0 ? a.name() : c == 1 ? a.category() : Hotkeys.text(Hotkeys.get(s, a.id()));
            }
        };
        HotkeyTable(Settings s) {
            super(new BorderLayout());
            this.s = s;
            setOpaque(false);
            JTable t = new JTable(model);
            Ui.styleTable(t);
            Ui.distributeWidths(t, 300, 150, 140);
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    int r = t.getSelectedRow();
                    if (e.getClickCount() == 2 && r >= 0) edit(shown.get(r));
                }
            });
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(600, 320));
            add(sp, BorderLayout.CENTER);
        }
        void filter(String q) {
            shown.clear();
            String n = q.strip().toLowerCase();
            for (Hotkeys.Action a : Hotkeys.actions())
                if (n.isEmpty() || a.name().toLowerCase().contains(n) || a.category().toLowerCase().contains(n)) shown.add(a);
            model.fireTableDataChanged();
        }
        void refresh() { model.fireTableDataChanged(); }

        private void edit(Hotkeys.Action a) {
            KeyStroke[] chosen = { Hotkeys.get(s, a.id()) };
            JTextField f = Ui.field(Hotkeys.text(chosen[0]));
            f.setEditable(false);
            JLabel problem = Ui.label(" ");
            problem.setForeground(Ui.ERROR_RED);
            Runnable show = () -> {
                f.setText(chosen[0] == null ? "None" : Hotkeys.text(chosen[0]));
                String why = Hotkeys.refusal(s, a.id(), chosen[0]);
                problem.setText(why == null ? " " : why);
            };
            f.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    e.consume();   // the field must not copy or select on the keys being recorded
                    switch (e.getKeyCode()) {
                        case java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_SHIFT,
                             java.awt.event.KeyEvent.VK_ALT, java.awt.event.KeyEvent.VK_META,
                             java.awt.event.KeyEvent.VK_ALT_GRAPH -> { return; }
                        default -> { }
                    }
                    chosen[0] = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx());
                    show.run();
                }
            });
            JButton none = Ui.button("None");
            none.addActionListener(e -> { chosen[0] = null; show.run(); f.requestFocusInWindow(); });
            JButton reset = Ui.button("Reset to default");
            reset.addActionListener(e -> { chosen[0] = a.def(); show.run(); f.requestFocusInWindow(); });
            show.run();
            JPanel form = Ui.vbox(Ui.label("Press the new hotkey for \"" + a.name() + "\":"), Ui.row(f, none, reset), problem);
            form.setBorder(Ui.pad(10, 10, 10, 10));
            SwingUtilities.invokeLater(f::requestFocusInWindow);
            if (JOptionPane.showConfirmDialog(this, form, "Hotkey", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                return;
            String why = Hotkeys.refusal(s, a.id(), chosen[0]);
            if (why != null) {
                JOptionPane.showMessageDialog(this, why + ". The hotkey was not changed.", "Hotkey", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Hotkeys.set(s, a.id(), chosen[0]);
            model.fireTableDataChanged();
        }
    }

    /**
     * Saves the interface font and restarts through {@code restart}. Choosing the active font
     * writes it back when the saved keys name another one (undoing a pending change), without a restart.
     */
    public static void applyUiFont(Settings s, String family, int size, Runnable restart) {
        if (family.equals(Fonts.uiFamily()) && size == Fonts.uiSize()) {
            String f = s.get(Fonts.KEY_UI_FAMILY, null), z = s.get(Fonts.KEY_UI_SIZE, null);
            if ((f != null || z != null) && !(family.equals(f) && String.valueOf(size).equals(z))) {
                s.set(Fonts.KEY_UI_FAMILY, family);
                s.set(Fonts.KEY_UI_SIZE, String.valueOf(size));
            }
            return;
        }
        s.set(Fonts.KEY_UI_FAMILY, family);
        s.set(Fonts.KEY_UI_SIZE, String.valueOf(size));
        restart.run();
    }

    /** Drops the saved interface font, so the system default applies after the restart; nothing saved, nothing to do. */
    public static void resetUiFont(Settings s, Runnable restart) {
        if (s.get(Fonts.KEY_UI_FAMILY, null) == null && s.get(Fonts.KEY_UI_SIZE, null) == null) return;
        s.remove(Fonts.KEY_UI_FAMILY);
        s.remove(Fonts.KEY_UI_SIZE);
        restart.run();
    }

    /**
     * Saves the monospace font in the {@code <Family> <N>pt} format and restarts through {@code restart}.
     * Choosing the active font writes it back when the saved key names another one, without a restart.
     */
    public static void applyMonoFont(Settings s, String family, int size, Runnable restart) {
        if (family.equals(Fonts.monoFamily()) && size == Fonts.monoSize()) {
            String saved = s.get(Fonts.KEY_MONO, null);
            if (saved != null && !saved.equals(Fonts.describe(family, size))) s.set(Fonts.KEY_MONO, Fonts.describe(family, size));
            return;
        }
        s.set(Fonts.KEY_MONO, Fonts.describe(family, size));
        restart.run();
    }

    /** Drops the saved monospace font, so the system default applies after the restart; nothing saved, nothing to do. */
    public static void resetMonoFont(Settings s, Runnable restart) {
        if (s.get(Fonts.KEY_MONO, null) == null) return;
        s.remove(Fonts.KEY_MONO);
        restart.run();
    }

    /**
     * Saves the theme's appearance in {@code ui.theme} and its id in {@code ui.theme.name} (removed for the
     * built-in light and dark), then restarts through {@code restart}. Choosing the active theme writes its
     * keys back when the saved ones name another theme, without a restart; with nothing pending it does nothing.
     */
    public static void applyTheme(Settings s, ThemeCatalog.Entry e, String activeId, Runnable restart) {
        String name = ThemeCatalog.SHIPPED.contains(e.id()) ? null : e.id();
        if (e.id().equals(activeId)
                && e.appearance().equals(s.get("ui.theme", "light")) && Objects.equals(name, s.get("ui.theme.name", null))) return;
        s.set("ui.theme", e.appearance());
        if (name == null) s.remove("ui.theme.name"); else s.set("ui.theme.name", name);
        if (!e.id().equals(activeId)) restart.run();
    }

    /** {@code Harbour (dark)}, or {@code Dark (dark, my-dark)} with the id when another theme has the same name. */
    public static String themeLabel(ThemeCatalog.Entry e, List<ThemeCatalog.Entry> all) {
        boolean clash = all.stream().anyMatch(o -> o != e && !o.id().equals(e.id()) && o.name().equals(e.name()));
        return e.name() + " (" + e.appearance() + (clash ? ", " + e.id() : "") + ")";
    }

    private static String themeSummary(ThemeCatalog.Report r) {
        StringBuilder b = new StringBuilder();
        b.append("Name: ").append(r.name()).append('\n');
        b.append("Based on: ").append(r.base()).append('\n');
        b.append("Appearance: ").append(r.appearance()).append('\n');
        if (r.author() != null && !r.author().isBlank()) b.append("Author: ").append(r.author()).append('\n');
        if (!r.leftToBase().isEmpty()) b.append("Keys left to the base theme: ").append(r.leftToBase().size()).append('\n');
        if (!r.unknownKeys().isEmpty()) b.append("Ignored keys: ").append(String.join(", ", r.unknownKeys())).append('\n');
        if (!r.clamped().isEmpty()) b.append("Values brought into range: ").append(String.join(", ", r.clamped())).append('\n');
        for (var f : r.contrast())
            b.append(String.format(java.util.Locale.ROOT, "Low contrast: %s on %s is %.1f, wanted %.1f%n",
                    f.pair().fg(), f.pair().bg(), f.ratio(), f.pair().min()));
        if (r.replaces()) b.append("Replaces the theme ").append(r.id()).append(" you already have.\n");
        b.append("\nAdd this theme?");
        return b.toString();
    }

    private static void plainDialog(Component parent, String text, String title, int optionType, int messageType, Runnable onOk) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false); a.setOpaque(false); a.setLineWrap(true); a.setWrapStyleWord(true);
        a.setColumns(48);
        int ok = optionType == JOptionPane.DEFAULT_OPTION ? JOptionPane.OK_OPTION : JOptionPane.YES_OPTION;
        if (JOptionPane.showConfirmDialog(parent, a, title, optionType, messageType) == ok && onOk != null) onOk.run();
    }

    private static final String RESTART_BY_HAND = "Restart " + opentrap.ui.Main.NAME + " to use the new setting.";

    /** Relaunches the app so a changed font or theme is picked up everywhere. */
    public static void restartApp(SuiteContext ctx) {
        restart(ctx, System.getenv("APPIMAGE") != null,
                () -> JOptionPane.showConfirmDialog(null,
                        opentrap.ui.Main.NAME + " restarts to apply the change. This temporary project is not saved, so its traffic is lost.\nRestart now?",
                        "Restart", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION,
                () -> opentrap.ui.UiScale.relaunchWith(opentrap.ui.UiScale.desired(), opentrap.ui.Main.launchArgs),
                () -> System.exit(0),
                () -> JOptionPane.showMessageDialog(null, RESTART_BY_HAND, "Restart", JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * The restart sequence with its side effects injected. Inside an AppImage a
     * relaunch tears down the mount and kills the app, so the saved setting is
     * kept and the user is asked to restart by hand. A temporary project (no
     * file) is lost by a restart, so that is confirmed first; declining keeps
     * the saved setting for the next start. A bound project is saved first, as
     * for a scale change.
     */
    public static void restart(SuiteContext ctx, boolean inAppImage, java.util.function.BooleanSupplier confirmTemporary,
                               java.util.function.BooleanSupplier relaunch, Runnable exit, Runnable tell) {
        flushPrefs();
        if (inAppImage) { tell.run(); return; }
        if (ctx.projectFile() == null && !confirmTemporary.getAsBoolean()) return;
        opentrap.ui.StateIO.saveBeforeRestart(ctx);
        if (relaunch.getAsBoolean()) exit.run();
        else tell.run();
    }

    private static void flushPrefs() {
        try { java.util.prefs.Preferences.userRoot().node("opentrap.v1").flush(); } catch (Exception _) { }
    }

    /** Family and size dialog; calls {@code onOk} only when confirmed. */
    private static void chooseFont(Component parent, String title, String family, int size, java.util.function.BiConsumer<String, Integer> onOk) {
        JComboBox<String> fam = Ui.combo(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(java.util.Locale.ROOT));
        fam.setSelectedItem(family);
        JSpinner sz = new JSpinner(new SpinnerNumberModel(Math.clamp(size, 6, 40), 6, 40, 1));
        JPanel f = Ui.form("Family:", fam, "Size:", sz);
        f.setBorder(Ui.pad(10, 10, 10, 10));
        if (JOptionPane.showConfirmDialog(parent, f, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)
            onOk.accept(String.valueOf(fam.getSelectedItem()), (Integer) sz.getValue());
    }

    public static SettingsPage display(SuiteContext ctx, JComponent existingDisplay) {
        return display(ctx, existingDisplay, new ThemeCatalog(Path.of(System.getProperty("user.home"), ".opentrap", "themes")));
    }

    /** The display page over the user theme folder of {@code themes}. */
    public static SettingsPage display(SuiteContext ctx, JComponent existingDisplay, ThemeCatalog themes) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JLabel uiFont = Ui.label(Fonts.describe(Fonts.uiFamily(), Fonts.uiSize()));
        JButton changeUi = Ui.button("Change font ...");
        changeUi.addActionListener(e -> chooseFont(changeUi, "Interface font", Fonts.uiFamily(), Fonts.uiSize(),
                (fam, sz) -> applyUiFont(s, fam, sz, () -> restartApp(ctx))));
        JButton resetUi = Ui.button("Reset to default");
        resetUi.addActionListener(e -> resetUiFont(s, () -> restartApp(ctx)));
        JComboBox<String> themeBox = Ui.combo(new String[0]);
        JButton addTheme = Ui.button("Add from file ...");
        JButton removeTheme = Ui.button("Remove");
        List<ThemeCatalog.Entry> entries = new ArrayList<>();
        boolean[] filling = {false};
        Runnable refresh = () -> {
            filling[0] = true;
            try {
                entries.clear();
                entries.addAll(themes.list());
                themeBox.removeAllItems();
                int sel = 0;
                for (int i = 0; i < entries.size(); i++) {
                    themeBox.addItem(themeLabel(entries.get(i), entries));
                    if (entries.get(i).id().equals(Theme.activeId())) sel = i;
                }
                themeBox.setSelectedIndex(sel);
                removeTheme.setEnabled(!entries.isEmpty() && !entries.get(sel).shipped());
            } finally { filling[0] = false; }
        };
        refresh.run();
        themeBox.addActionListener(e -> {
            int i = themeBox.getSelectedIndex();
            if (filling[0] || i < 0) return;
            removeTheme.setEnabled(!entries.get(i).shipped());
            applyTheme(s, entries.get(i), Theme.activeId(), () -> restartApp(ctx));
        });
        addTheme.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Add a theme");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Theme files (*.theme)", "theme"));
            if (fc.showOpenDialog(addTheme) != JFileChooser.APPROVE_OPTION) return;
            Path file = fc.getSelectedFile().toPath();
            try {
                ThemeCatalog.Report r = themes.check(file);
                plainDialog(addTheme, themeSummary(r), "Add theme", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, () -> {
                    try { themes.add(file); refresh.run(); }
                    catch (ThemeException | java.io.IOException ex) {
                        plainDialog(addTheme, String.valueOf(ex.getMessage()), "Add theme", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null);
                    }
                });
            } catch (ThemeException | java.io.IOException ex) {
                plainDialog(addTheme, String.valueOf(ex.getMessage()), "Add theme", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null);
            }
        });
        removeTheme.addActionListener(e -> {
            int i = themeBox.getSelectedIndex();
            if (i < 0 || entries.get(i).shipped()) return;
            ThemeCatalog.Entry gone = entries.get(i);
            plainDialog(removeTheme, "Remove the theme " + gone.name() + "?", "Remove theme", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, () -> {
                try {
                    themes.remove(gone.id());
                    refresh.run();
                    if (gone.id().equals(Theme.activeId())) {
                        entries.stream().filter(x -> x.id().equals(gone.appearance())).findFirst()
                                .ifPresent(b -> applyTheme(s, b, Theme.activeId(), () -> restartApp(ctx)));
                    }
                } catch (java.io.IOException ex) {
                    plainDialog(removeTheme, String.valueOf(ex.getMessage()), "Remove theme", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null);
                }
            });
        });
        p.block("Appearance", SettingsPage.Kind.USER,
                "The interface font and colour theme. Changing the font or the theme restarts the app so every panel comes up consistent. Restoring this block clears the saved font and sets the theme back to Light, which applies at the next start.",
                () -> { s.remove(Fonts.KEY_UI_FAMILY); s.remove(Fonts.KEY_UI_SIZE); s.set("ui.theme", "light"); s.remove("ui.theme.name"); },
                SettingsPage.labelled("Font:", uiFont, changeUi, resetUi),
                SettingsPage.labelled("Theme:", themeBox, addTheme, removeTheme));
        if (existingDisplay != null) p.addSection(existingDisplay);
        JCheckBox configure = SettingsPage.check(s, "ui.scale.configure", true, "Configure scaling settings");
        JCheckBox systemDpi = SettingsPage.check(s, "ui.scale.systemDpi", true, "Use system DPI settings");
        JComboBox<String> factor = SettingsPage.combo(s, "ui.scale.factor", new String[]{"1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0"},
                String.valueOf(opentrap.ui.UiScale.desired()));
        factor.addActionListener(e -> {
            try { opentrap.ui.UiScale.changeTo(Double.parseDouble(String.valueOf(factor.getSelectedItem())), opentrap.ui.Main.launchArgs); } catch (Exception _) { }
        });
        // Not on macOS, where a scale changes nothing but sharpness (UiScale.supported).
        if (opentrap.ui.UiScale.supported())
            p.block("Scaling", SettingsPage.Kind.USER,
                    "Scale the whole interface for high-density screens. Changing the scale factor restarts the app.",
                    () -> SettingsPage.clearPrefix(s, "ui.scale."),
                    configure, systemDpi, SettingsPage.labelled("Scale factor:", factor), Ui.note("Requires a restart to take effect."));
        p.block("Title bar appearance", SettingsPage.Kind.USER,
                "Draw the top-level menu inside the window's title bar, where the window manager allows it.",
                () -> s.setBool("ui.mergeTitleBar", true),
                SettingsPage.check(s, "ui.mergeTitleBar", true, "Merge top-level menu with system title bar"));
        JRadioButton[] zebra = SettingsPage.radios(s, "ui.zebra", "on", new String[]{"on", "off"}, "Zebra stripe", "No zebra stripe");
        p.block("Table appearance", SettingsPage.Kind.USER,
                "Whether tables alternate row colours.",
                () -> s.set("ui.zebra", "on"),
                Ui.row(preview(true), preview(false)), Ui.row(zebra[0], Box.createHorizontalStrut(80), zebra[1]));
        JRadioButton[] ev = SettingsPage.radios(s, "ui.issueEvidence", "grouped", new String[]{"grouped", "ungrouped"}, "Grouped evidence", "Ungrouped evidence");
        p.block("Issues", SettingsPage.Kind.USER,
                "Control how the evidence for an issue is displayed: grouped by request, or one item per piece of evidence.",
                () -> { s.set("ui.issueEvidence", "grouped"); s.setBool("ui.expandEvidence", false); },
                Ui.row(ev[0], Box.createHorizontalStrut(60), ev[1]),
                SettingsPage.check(s, "ui.expandEvidence", false, "Expand evidence groups by default"));
        p.block("Title bar actions", SettingsPage.Kind.USER,
                "Choose the actions shown as icons in the title bar, and the order in which they appear. The settings cog is always shown at the end.",
                () -> s.set("ui.titleActions", ""),
                Ui.note("Drag to reorder. Click the X to remove."),
                new TitleActions(s));
        return p;
    }

    private static JComponent preview(boolean zebra) {
        JPanel card = new JPanel(new GridLayout(4, 1));
        card.setPreferredSize(new Dimension(150, 70));
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        for (int i = 0; i < 4; i++) {
            JPanel row = new JPanel();
            row.setBackground(zebra && i % 2 == 1 ? Theme.SURFACE : Theme.BG);
            card.add(row);
        }
        return card;
    }

    /** The reorderable list of title bar actions, with an add button and an X per row. */
    static final class TitleActions extends JPanel {
        static final String[] ALL = {"Open browser", "Open command palette", "Open search window"};
        private final Settings s;
        private final List<String> order = new ArrayList<>();
        TitleActions(Settings s) {
            super(new BorderLayout(0, 4));
            this.s = s;
            setOpaque(false);
            String cur = s.get("ui.titleActions", "");
            order.addAll(cur.isEmpty() ? List.of(ALL) : List.of(cur.split("\\|")));
            rebuild();
        }
        private void save() { s.set("ui.titleActions", String.join("|", order)); }
        private void rebuild() {
            removeAll();
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            JButton add = Glyphs.button(Glyphs.plus(16, Theme.ICON), "Add an action", () -> {
                List<String> missing = new ArrayList<>();
                for (String a : ALL) if (!order.contains(a)) missing.add(a);
                if (missing.isEmpty()) return;
                Object pick = JOptionPane.showInputDialog(this, "Action:", "Add title bar action", JOptionPane.PLAIN_MESSAGE, null, missing.toArray(), missing.get(0));
                if (pick != null) { order.add(String.valueOf(pick)); save(); rebuild(); }
            });
            top.add(add, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            JPanel rows = new JPanel();
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setOpaque(false);
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                String name = order.get(i);
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(true);
                row.setBackground(Theme.SURFACE);
                row.setBorder(Ui.pad(4, 8, 4, 8));
                row.setMaximumSize(new Dimension(420, 32));
                Icon ic = switch (name) { case "Open browser" -> Glyphs.search(16, Theme.ICON); case "Open command palette" -> Glyphs.chevronRight(); default -> Glyphs.search(16, Theme.ICON); };
                JLabel l = new JLabel(name, ic, SwingConstants.LEADING);
                l.setFont(Ui.PLAIN);
                JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
                right.setOpaque(false);
                right.add(Glyphs.button(Glyphs.arrow(14, Theme.ICON, 2), "Move up", () -> { if (idx > 0) { order.add(idx - 1, order.remove(idx)); save(); rebuild(); } }));
                right.add(Glyphs.button(Glyphs.arrow(14, Theme.ICON, 1), "Move down", () -> { if (idx < order.size() - 1) { order.add(idx + 1, order.remove(idx)); save(); rebuild(); } }));
                right.add(Glyphs.button(Glyphs.cross(14, Theme.ICON), "Remove", () -> { order.remove(idx); save(); rebuild(); }));
                row.add(l, BorderLayout.CENTER);
                row.add(right, BorderLayout.EAST);
                rows.add(row);
                rows.add(Box.createVerticalStrut(4));
            }
            add(rows, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }
}
