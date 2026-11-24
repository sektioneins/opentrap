package opentrap.extensions;

import opentrap.engine.ZapEngine;
import opentrap.extensions.store.Installed;
import opentrap.extensions.store.Pending;
import opentrap.extensions.store.Source;
import opentrap.extensions.store.SourceType;
import opentrap.extensions.store.Store;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Settings > Extensions > Add-on sources: ZAP's own feed and the feeds and
 * local folders the user added, with Add, Edit and Remove. ZAP's feed can be
 * switched off, not changed or removed. Every change is saved through the
 * store and reaches Store at once from the cached lists and the folders; no
 * change contacts a source. Adding a source, or pointing one somewhere else,
 * first asks whether to trust what it offers.
 */
public final class SourcesPanel extends JPanel {

    /** The questions and messages the panel puts to the user; tests answer them without a display. */
    interface Dialogs {
        /** Asks for a source's details, starting from {@code start}; what was entered, or null on Cancel. */
        Form form(String title, Form start);
        /** Asks {@code text}; the index of the option picked, or -1 when the question is closed. */
        int choose(String title, String text, String... options);
        void tell(String title, String text);
    }

    /** What the add and edit form holds, as entered. */
    record Form(String name, SourceType type, String location, String prefix) {}

    /** A change to the sources, run off the EDT; returns what to tell afterwards, or null. */
    private interface Change { String run() throws IOException; }

    private final Store store;
    private final Dialogs dialogs;
    private final List<Source> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton add = Ui.button("Add", "add a feed URL or a local folder of add-ons");
    private final JButton edit = Ui.button("Edit", "change the selected source, or switch ZAP's feed on or off");
    private final JButton remove = Ui.button("Remove", "remove the selected source");
    private final JLabel note = Ui.label(" ");
    // a store listener may run on any thread; the panel changes on the EDT only
    private final Runnable listener = () -> SwingUtilities.invokeLater(this::reload);
    /** Why the store is not open, once opening it has failed; null before that and once it is open. */
    private String notOpen;
    /** Set while the list is rebuilt, so its selection events do not act half-way. */
    private boolean reloading;
    /** How many changes have run to their end, for tests to wait on. */
    private final AtomicInteger finished = new AtomicInteger();

    public SourcesPanel(Store store) {
        this(store, () -> {
            ZapEngine ze = ZapEngine.shared();
            return ze == null ? null : ze.install();
        }, null);
    }

    /**
     * The sources of {@code store}, which it opens, when it is not open yet,
     * for the engine {@code engine} names, asking through {@code dialogs}
     * (null for the usual dialogs).
     */
    SourcesPanel(Store store, Supplier<ZapEngine.Install> engine, Dialogs dialogs) {
        super(new BorderLayout(8, 0));
        this.store = store;
        this.dialogs = dialogs != null ? dialogs : swingDialogs();
        setOpaque(false);

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {150, 130, 280, 40};
        for (int c = 0; c < widths.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        table.getColumnModel().getColumn(3).setMaxWidth(50);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloading) showButtons();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) editSelected();
            }
        });
        add.addActionListener(e -> addSource());
        edit.addActionListener(e -> editSelected());
        remove.addActionListener(e -> removeSelected());

        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(560, 120));
        note.setForeground(Theme.MUTED);
        note.setBorder(Ui.pad(4, 0, 0, 0));
        add(Ui.buttonColumn(add, edit, remove), BorderLayout.WEST);
        add(sp, BorderLayout.CENTER);
        add(note, BorderLayout.SOUTH);

        reload();
        if (!store.isOpen()) {
            Thread.ofVirtual().name("addon-store-open").start(() -> {
                String why = StorePanel.openFromEngine(store, engine);
                SwingUtilities.invokeLater(() -> {
                    notOpen = why;
                    reload();
                });
            });
        }
    }

    // The settings dialog builds its pages each time it opens, while the store
    // lives as long as the suite: only a page on screen listens to it.
    @Override public void addNotify() {
        super.addNotify();
        store.addListener(listener);
        reload();
    }

    @Override public void removeNotify() {
        store.removeListener(listener);
        super.removeNotify();
    }

    // ---- what the form makes and what it asks ----

    /**
     * The source the add or edit form describes, with no id yet and switched
     * on. A feed URL must be HTTPS with a host; its download prefix too, and
     * left empty it is the feed's scheme and host (with its port). A local
     * folder must exist and has no prefix.
     *
     * @throws IllegalArgumentException with the reason, for the user
     */
    static Source fromForm(String name, SourceType type, String location, String prefix) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) throw new IllegalArgumentException("Enter a name for the source.");
        String loc = location == null ? "" : location.strip();
        String pre = prefix == null ? "" : prefix.strip();
        if (type == SourceType.FEED_URL) {
            URI feed = https(loc, "The feed URL must be an HTTPS URL, such as https://example.com/ZapVersions.xml.");
            String p = pre.isEmpty() ? "https://" + feed.getRawAuthority() + "/" : downloadPrefix(pre);
            return new Source("", n, type, loc, p, true);
        }
        if (type == SourceType.LOCAL_FOLDER) {
            if (loc.isEmpty()) throw new IllegalArgumentException("Enter the folder of .zap files.");
            Path dir;
            try {
                dir = Path.of(loc);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("No such folder: " + loc);
            }
            // a relative path would depend on the folder OpenTRAP happened to be started in
            if (!dir.isAbsolute()) throw new IllegalArgumentException("The folder path must be absolute: " + loc);
            dir = dir.normalize();
            if (!Files.isDirectory(dir)) throw new IllegalArgumentException("No such folder: " + loc);
            return new Source("", n, type, dir.toString(), "", true);
        }
        throw new IllegalArgumentException("Only a feed URL or a local folder can be added as a source.");
    }

    /** A prefix that ends at a path, so {@code https://host} cannot also match {@code https://host.other/}. */
    private static String downloadPrefix(String pre) {
        String why = "The download prefix must be an HTTPS URL with a host, such as https://example.com/downloads/.";
        URI u = https(pre, why);
        if (u.getRawQuery() != null || u.getRawFragment() != null) throw new IllegalArgumentException(why);
        return u.getRawPath() == null || u.getRawPath().isEmpty() ? pre + "/" : pre;
    }

    /** {@code s} as an HTTPS URL with a host and no user name in it. */
    private static URI https(String s, String why) {
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(why);
        }
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) {
            throw new IllegalArgumentException(why);
        }
        return u;
    }

    /** The question before a source is added: what trusting it means, and where it fetches from. */
    static String trustText(Source s) { return trustText(s, "Add this source?"); }

    private static String trustText(Source s, String question) {
        StringBuilder b = new StringBuilder("Add-ons from ").append(s.name())
                .append(" run as code inside the scan engine, with your rights, and can do anything a program you")
                .append(" start can do. ").append(question);
        if (s.type() == SourceType.LOCAL_FOLDER) {
            b.append("\n\nFolder: ").append(s.location());
        } else {
            b.append("\n\nFeed URL: ").append(s.location()).append("\nDownloads must start with: ").append(s.prefix());
        }
        return b.toString();
    }

    /** What Remove asks when add-ons are installed from {@code s}. */
    private static String removeText(Source s, List<String> own, InstalledPanel.TakeOut out) {
        StringBuilder b = new StringBuilder("Remove the source ").append(s.name()).append("?\n\n")
                .append(own.size() == 1 ? "This add-on is" : "These add-ons are").append(" installed from it: ")
                .append(String.join(", ", own)).append(". Keep ").append(own.size() == 1 ? "it" : "them")
                .append(" installed, working but without updates, or remove ").append(own.size() == 1 ? "it" : "them")
                .append(" at the next engine start?");
        if (!out.with().isEmpty()) {
            b.append("\n\nRemoving also removes the add-ons that need ").append(own.size() == 1 ? "it" : "them")
                    .append(": ").append(String.join(", ", out.with())).append('.');
        }
        if (!out.stops().isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (var f : out.stops()) labels.add(f.label());
            b.append("\n\nRemoving stops these OpenTRAP features: ").append(String.join(", ", labels)).append('.');
        }
        return b.toString();
    }

    private static String typeLabel(SourceType t) {
        return switch (t) {
            case ZAP_FEED -> "ZAP feed (built in)";
            case FEED_URL -> "Feed URL";
            case LOCAL_FOLDER -> "Local folder";
            case BUNDLED -> "Bundled";
        };
    }

    // ---- the list ----

    /** The rows as listed, for tests. */
    TableModel model() { return model; }

    /** Selects the source {@code id}, for tests. */
    void select(String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) table.setRowSelectionInterval(i, i);
    }

    /** How many changes the panel started have run to their end, succeeded or not; for tests. */
    int changesFinished() { return finished.get(); }

    /** Which of Add, Edit and Remove are on, as "AER" with "-" for one that is off; for tests. */
    String buttons() {
        return (add.isEnabled() ? "A" : "-") + (edit.isEnabled() ? "E" : "-") + (remove.isEnabled() ? "R" : "-");
    }

    private void reload() {
        String keep = selected() == null ? null : selected().id();
        reloading = true;
        try {
            rows.clear();
            // the catalogue holds the sources as last saved, so the EDT reads no file
            if (store.isOpen()) rows.addAll(store.catalogue().sources());
            model.fireTableDataChanged();
            if (keep != null) select(keep);
        } finally {
            reloading = false;
        }
        note.setText(store.isOpen() ? " " : notOpen != null ? notOpen : "Reading the add-on store...");
        showButtons();
    }

    private Source selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    private void showButtons() {
        boolean open = store.isOpen();
        Source s = selected();
        add.setEnabled(open);
        edit.setEnabled(open && s != null);
        remove.setEnabled(open && s != null && !s.builtIn());
    }

    // ---- actions ----

    /** Asks for a new source, then whether to trust it, and adds it. */
    void addSource() {
        if (!store.isOpen()) return;
        String title = "Add an add-on source";
        Source s = ask(title, new Form("", SourceType.FEED_URL, "", ""));
        if (s == null) return;
        if (dialogs.choose(title, trustText(s), "Add source", "Cancel") != 0) return;
        change(title, () -> {
            store.addSource(s);
            return null;
        });
    }

    /**
     * Changes the selected source; pointing it at another place asks the
     * trust question again. ZAP's feed only offers to switch it on or off.
     */
    void editSelected() {
        Source s = selected();
        if (s == null || !store.isOpen()) return;
        if (s.builtIn()) {
            List<Pending> queued = s.enabled() ? queuedFrom(s.id()) : List.of();
            String text = s.name() + " is built in: it can be switched on or off, but not changed or removed.";
            if (s.enabled()) {
                text += " Switched off, its add-ons are no longer listed in Store and get no updates;"
                        + " those installed keep working." + droppedNote(queued);
            }
            if (dialogs.choose(s.name(), text, s.enabled() ? "Switch off" : "Switch on", "Cancel") == 0) {
                switchTo(s.id(), !s.enabled(), queued);
            }
            return;
        }
        String title = "Edit " + s.name();
        Source f = ask(title, new Form(s.name(), s.type(), s.location(), s.prefix()));
        if (f == null) return;
        Source next = new Source(s.id(), f.name(), f.type(), f.location(), f.prefix(), s.enabled());
        if (next.equals(s)) return;
        boolean moved = next.type() != s.type() || !next.location().equals(s.location())
                || !next.prefix().equals(s.prefix());
        if (moved && dialogs.choose(title, trustText(next, "Save the changed source?"), "Save", "Cancel") != 0) return;
        // refused when the saved source is no longer s, so moved above was asked against what is saved
        change(title, () -> {
            store.updateSource(s, next);
            return null;
        });
    }

    /**
     * Switches the source {@code id} on or off. Switching off stops its
     * updates, so the changes queued from it are dropped, after asking.
     */
    void setOn(String id, boolean on) {
        if (!store.isOpen()) return;
        List<Pending> queued = on ? List.of() : queuedFrom(id);
        if (!queued.isEmpty()) {
            String name = id;
            for (Source s : rows) if (s.id().equals(id)) name = s.name();
            String text = "Switch off " + name + "? Its add-ons are no longer listed in Store and get no updates;"
                    + " those installed keep working." + droppedNote(queued);
            if (dialogs.choose("Switch off " + name, text, "Switch off", "Cancel") != 0) {
                reload();  // the On box shows the saved state again
                return;
            }
        }
        switchTo(id, on, queued);
    }

    private void switchTo(String id, boolean on, List<Pending> queued) {
        change(on ? "Switch on" : "Switch off", () -> {
            store.enableSource(id, on);
            return undoAll(queued);
        });
    }

    /** The queued changes that would bring in an add-on from {@code sourceId}. */
    private List<Pending> queuedFrom(String sourceId) {
        List<Pending> out = new ArrayList<>();
        for (Pending p : store.pending()) {
            if (p.target() != null && sourceId.equals(p.target().sourceId())) out.add(p);
        }
        return out;
    }

    /** What a question says about the queued changes it drops; empty when there are none. */
    static String droppedNote(List<Pending> queued) {
        if (queued.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (Pending p : queued) {
            String what = switch (p.action()) {
                case UPDATE -> "update to ";
                case SWITCH -> "switch to ";
                default -> "install ";
            };
            names.add(p.id() + " (" + what + p.target().version() + ")");
        }
        return "\n\nThese changes queued from it are dropped: " + String.join(", ", names) + ".";
    }

    /** Drops every queued change of the add-ons in {@code queued}; what to tell when one fails, or null. */
    private String undoAll(List<Pending> queued) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (Pending p : queued) ids.add(p.id());
        for (String id : ids) {
            try {
                store.undo(id);
            } catch (IOException | RuntimeException e) {
                return "The change queued for " + id + " could not be dropped: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        + ". Undo it in Extensions > Installed.";
            }
        }
        return null;
    }

    /**
     * Removes the selected source after asking whether to keep the add-ons
     * installed from it or to remove them, with what needs them, at the next
     * engine start, as Installed removes an add-on.
     */
    void removeSelected() {
        Source s = selected();
        if (s == null || s.builtIn() || !store.isOpen()) return;
        String title = "Remove " + s.name();
        Set<String> going = new HashSet<>();
        for (Pending p : store.pending()) if (p.action() == Pending.Action.REMOVE) going.add(p.id());
        List<String> own = new ArrayList<>();
        for (Installed in : store.installed()) {
            if (s.id().equals(in.sourceId()) && !going.contains(in.id())) own.add(in.id());
        }
        // nothing from a removed source may still go into the engine, whatever is kept
        List<Pending> queued = queuedFrom(s.id());
        if (own.isEmpty()) {
            if (dialogs.choose(title, "Remove the source " + s.name() + "? Its add-ons are no longer listed in Store."
                    + droppedNote(queued), "Remove", "Cancel") != 0) return;
            change(title, () -> {
                store.removeSource(s.id());
                return undoAll(queued);
            });
            return;
        }
        InstalledPanel.TakeOut out = InstalledPanel.takeOut(store, own);
        int pick = dialogs.choose(title, removeText(s, own, out) + droppedNote(queued),
                "Keep add-ons", "Remove add-ons", "Cancel");
        if (pick == 0) {
            change(title, () -> {
                store.removeSource(s.id());
                return undoAll(queued);
            });
            return;
        }
        if (pick != 1) return;
        List<String> all = new ArrayList<>(out.with());
        all.addAll(own);
        if (!out.stops().isEmpty() && dialogs.choose(title,
                InstalledPanel.stopsAgain("Remove", String.join(", ", all), out.stops()),
                "Remove anyway", "Cancel") != 0) return;
        change(title, () -> {
            store.removeSource(s.id());
            String failed = undoAll(queued);
            if (failed != null) return failed;
            // what needs the source's add-ons first, as Installed queues it
            return InstalledPanel.queueEach(all, store::remove);
        });
    }

    /** Asks with the form until what is entered makes a source; null on Cancel. */
    private Source ask(String title, Form start) {
        Form f = start;
        while (true) {
            f = dialogs.form(title, f);
            if (f == null) return null;
            try {
                return fromForm(f.name(), f.type(), f.location(), f.prefix());
            } catch (IllegalArgumentException e) {
                dialogs.tell(title, e.getMessage());
            }
        }
    }

    /** Runs {@code c} off the EDT, then lists the sources again and tells what went wrong. */
    private void change(String title, Change c) {
        Thread.ofVirtual().name("addon-store-sources").start(() -> {
            String msg;
            try {
                msg = c.run();
            } catch (IOException | RuntimeException e) {
                msg = "The sources could not be changed: " + (e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName());
            }
            String told = msg;
            finished.incrementAndGet();
            SwingUtilities.invokeLater(() -> {
                reload();
                if (told != null) dialogs.tell(title, told);
            });
        });
    }

    // ---- dialogs ----

    private Dialogs swingDialogs() {
        return new Dialogs() {
            @Override public Form form(String title, Form start) {
                Fields f = new Fields(start);
                int r = JOptionPane.showConfirmDialog(SourcesPanel.this, f.panel, title,
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                return r == JOptionPane.OK_OPTION ? f.read() : null;
            }
            @Override public int choose(String title, String text, String... options) {
                JTextArea n = Ui.note(text);
                Ui.sizeNote(n, 480);
                return JOptionPane.showOptionDialog(SourcesPanel.this, n, title, JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, options, options[options.length - 1]);
            }
            @Override public void tell(String title, String text) {
                JTextArea n = Ui.note(text);
                Ui.sizeNote(n, 480);
                JOptionPane.showMessageDialog(SourcesPanel.this, n, title, JOptionPane.WARNING_MESSAGE);
            }
        };
    }

    /** The add and edit form: Name, Type, Location and, for a feed, Download prefix. */
    private static final class Fields {
        private static final String[] TYPES = {"Feed URL", "Local folder"};

        final JTextField name;
        final JComboBox<String> type = Ui.combo(TYPES);
        final JTextField location;
        final JButton pick = Ui.button("Select folder ...");
        final JTextField prefix;
        final JPanel panel;

        Fields(Form start) {
            name = Ui.field(start.name());
            location = Ui.field(start.location());
            prefix = Ui.field(start.prefix());
            prefix.putClientProperty("JTextField.placeholderText", "the feed's scheme and host");
            type.setSelectedIndex(start.type() == SourceType.LOCAL_FOLDER ? 1 : 0);
            int h = location.getPreferredSize().height;
            for (JComponent c : new JComponent[] {name, type, pick, prefix}) h = Math.max(h, c.getPreferredSize().height);
            for (JComponent c : new JComponent[] {name, type, location, pick, prefix}) {
                c.setPreferredSize(new Dimension(c.getPreferredSize().width, h));
            }
            pick.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(location.getText().isBlank()
                        ? System.getProperty("user.home") : location.getText());
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showOpenDialog(pick) == JFileChooser.APPROVE_OPTION) {
                    location.setText(fc.getSelectedFile().getAbsolutePath());
                }
            });
            type.addActionListener(e -> follow());
            JPanel where = new JPanel(new BorderLayout(6, 0));
            where.setOpaque(false);
            where.add(location, BorderLayout.CENTER);
            where.add(pick, BorderLayout.EAST);
            JTextArea hint = Ui.note("Every download URL of a feed must start with its download prefix. "
                    + "Left empty, it is the feed's scheme and host.");
            Ui.sizeNote(hint, 420);
            panel = Ui.vbox(Ui.form("Name:", name, "Type:", Ui.natural(type), "Location:", where,
                    "Download prefix:", prefix), hint);
            follow();
        }

        private boolean feed() { return type.getSelectedIndex() == 0; }

        /** A local folder has a folder picker and no prefix. */
        private void follow() {
            prefix.setEnabled(feed());
            pick.setVisible(!feed());
            location.putClientProperty("JTextField.placeholderText",
                    feed() ? "https://example.com/ZapVersions.xml" : "a folder of .zap files");
            location.repaint();
        }

        Form read() {
            return new Form(name.getText(), feed() ? SourceType.FEED_URL : SourceType.LOCAL_FOLDER,
                    location.getText(), feed() ? prefix.getText() : "");
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] names = {"Name", "Type", "Location", "On"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 3 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 3 && store.isOpen(); }
        @Override public Object getValueAt(int r, int c) {
            Source s = rows.get(r);
            return switch (c) {
                case 0 -> s.name();
                case 1 -> typeLabel(s.type());
                case 2 -> s.location();
                default -> s.enabled();
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 3 && v instanceof Boolean on && r < rows.size()) setOn(rows.get(r).id(), on);
        }
    }
}
