package opentrap.extensions;

import opentrap.core.Debug;
import opentrap.core.SuiteContext;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.Catalogue;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Entry;
import opentrap.extensions.store.Installed;
import opentrap.extensions.store.Kind;
import opentrap.extensions.store.Pending;
import opentrap.extensions.store.Resolver;
import opentrap.extensions.store.Source;
import opentrap.extensions.store.Store;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Extensions > Installed: every add-on the store holds, what is queued for the
 * next engine start, and below them the extension jars loaded this session,
 * in one list. Enable, Disable and Remove queue a change for the store's add-ons
 * (taking along what needs them or what they need) and act at once on a jar;
 * Undo drops a queued change. Add jar... opens the {@link LoadExtensionDialog};
 * a loaded jar records its type, file and status, and loading runs the jar's
 * entry point when it implements {@code opentrap.core.ToolModule}.
 */
public final class InstalledPanel extends JPanel {

    /** The kind a loaded jar is listed with. */
    static final String JAR_KIND = "OpenTRAP extension";
    static final String[] KINDS = {"All", Kind.ZAP_ADDON.label(), JAR_KIND};
    static final String[] STATES = {"All", "Active", "Disabled", "Pending", "Missing"};

    /** What a bundled add-on whose engine file is gone says on its row. */
    static final String MISSING = "Missing: its file in the engine is gone";

    /** The questions and messages the panel puts to the user; tests answer them without a display. */
    interface Dialogs {
        /** Asks {@code text}; true when the user picks {@code verb}. */
        boolean confirm(String title, String text, String verb);
        void tell(String title, String text);
    }

    /** One loaded extension. */
    static final class Loaded {
        boolean enabled = true;
        boolean autoReload;
        final String type, name;
        final File file;
        String status = "Loaded";
        /** A rough measure of what the extension costs to run, 0-100. */
        int impact;
        final StringBuilder output = new StringBuilder();
        final StringBuilder errors = new StringBuilder();
        Loaded(String type, String name, File file) { this.type = type; this.name = name; this.file = file; }
    }

    /**
     * One row: a store add-on ({@code id} set; {@code have} null while only
     * its install is queued, or when it is missing) or a loaded jar
     * ({@code jar} set). {@code group} is what the State filter matches.
     */
    private record Row(Object key, String kind, String name, String version, String source, String state,
                       boolean core, String group, String id, Installed have, List<Pending> queued,
                       String error, Loaded jar) {}

    /** What carrying the previous ZAP version's add-ons over queues, which stay off, and which cannot come. */
    record CarryPlan(List<Entry> plan, List<String> keepOff, List<String> unavailable) {}

    /** One store change queued for one add-on. */
    interface Change { void queue(String id) throws IOException; }

    /**
     * What taking add-ons out takes along: the add-ons that need them and are
     * not on their way out already, and the OpenTRAP features that stop.
     */
    record TakeOut(List<String> with, List<CoreAddOns.Feature> stops) {}

    private final Store store;
    private final Dialogs dialogs;
    private final List<Loaded> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final DefaultTableModel details = new DefaultTableModel(new Object[]{"Item", "Detail"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTextArea output = console();
    private final JTextArea errors = console();
    private final JTextField search = Ui.searchField(16);
    private final JComboBox<String> kindFilter = Ui.combo(KINDS);
    private final JComboBox<String> stateFilter = Ui.combo(STATES);
    private final JButton enable = Ui.button("Enable", "enable the selected add-on at the next engine start");
    private final JButton disable = Ui.button("Disable", "disable the selected add-on at the next engine start");
    private final JButton remove = Ui.button("Remove", "remove the selected add-on at the next engine start");
    private final JButton undo = Ui.button("Undo", "drop the change queued for the selected add-on");
    private final JLabel note = Ui.label(" ");
    /** Why the store is not open, once opening it has failed; null before that and once it is open. */
    private String notOpen;
    /** What the carry-over is doing, shown on the note line while it runs; null otherwise. */
    private String carryNote;
    private boolean carryChecked;
    /** Set while the list is rebuilt, so its selection events do not show a row half-way. */
    private boolean reloading;

    public InstalledPanel(SuiteContext ctx, Store store) {
        this(ctx, store, () -> {
            ZapEngine ze = ZapEngine.shared();
            return ze == null ? null : ze.install();
        }, null);
    }

    /**
     * An Installed tab over {@code store} (null where the add-on store is not
     * set up), which opens it when it is not open yet for the engine
     * {@code engine} names, and asks through {@code dialogs} (null for the
     * usual dialogs).
     */
    InstalledPanel(SuiteContext ctx, Store store, Supplier<ZapEngine.Install> engine, Dialogs dialogs) {
        super(new BorderLayout());
        this.store = store;
        this.dialogs = dialogs != null ? dialogs : swingDialogs();
        setOpaque(false);

        // ---- filter bar ----
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reload(); }
        });
        kindFilter.addActionListener(e -> reload());
        stateFilter.addActionListener(e -> reload());
        JComponent[] controls = {kindFilter, stateFilter};
        int h = search.getPreferredSize().height;
        for (JComponent c : controls) h = Math.max(h, c.getPreferredSize().height);
        for (JComponent c : controls) c.setPreferredSize(new Dimension(c.getPreferredSize().width, h));
        search.setPreferredSize(new Dimension(search.getPreferredSize().width, h));
        JPanel bar = Ui.filterBar(search, Ui.label("Kind:"), kindFilter, Ui.label("State:"), stateFilter);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(bar, BorderLayout.NORTH);
        if (store != null) top.add(new PendingStrip(ctx, store), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // ---- the list and its buttons ----
        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        int[] widths = {110, 220, 80, 180, 260, 50};
        for (int c = 0; c < widths.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        table.getColumnModel().getColumn(5).setMaxWidth(60);
        table.getColumnModel().getColumn(4).setCellRenderer(new StateRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new StateRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloading) showSelected();
        });

        JButton addJar = Ui.button("Add jar...", "load an OpenTRAP extension from a file");
        addJar.addActionListener(e -> {
            LoadExtensionDialog.Result r = LoadExtensionDialog.show(this);
            if (r != null) load(r);
        });
        enable.addActionListener(e -> act(this::enableAddOn, l -> l.enabled = true));
        disable.addActionListener(e -> act(this::disableAddOn, l -> l.enabled = false));
        remove.addActionListener(e -> act(this::removeAddOn, items::remove));
        undo.addActionListener(e -> {
            Row r = selected();
            if (r != null && r.id() != null) queue("Undo", List.of(r.id()), store::undo);
        });
        note.setForeground(Theme.MUTED);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        south.setBorder(Ui.pad(6, 10, 6, 10));
        south.add(note, BorderLayout.CENTER);
        south.add(Ui.buttonRow(enable, disable, remove, undo, addJar), BorderLayout.EAST);
        JPanel centre = new JPanel(new BorderLayout());
        centre.setOpaque(false);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(600, 200));
        centre.add(sp, BorderLayout.CENTER);
        centre.add(south, BorderLayout.SOUTH);

        JTable dt = new JTable(details);
        Ui.styleTable(dt);
        JTabbedPane detail = new JTabbedPane();
        detail.setFont(Ui.PLAIN);
        detail.addTab("Details", Ui.scroll(dt));
        detail.addTab("Output", Ui.scroll(output));
        detail.addTab("Errors", Ui.scroll(errors));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centre, detail);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        split.setDividerSize(5);
        add(split, BorderLayout.CENTER);

        if (store != null) {
            // a store listener may run on any thread; the panel changes on the EDT only
            store.addListener(() -> SwingUtilities.invokeLater(this::reload));
            // the carry-over is asked once the window is up, not while the suite is still being built
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) maybeOfferCarryover();
            });
        }
        reload();
        if (store != null && !store.isOpen()) {
            Thread.ofVirtual().name("addon-store-open").start(() -> {
                String why = StorePanel.openFromEngine(store, engine);
                SwingUtilities.invokeLater(() -> {
                    notOpen = why;
                    reload();
                });
            });
        }
    }

    // ---- what a row says ----

    /** What a store row's State says for {@code i} (null while only its install is queued) and one queued change. */
    static String stateText(Installed i, Pending p) { return stateText(i, p, null); }

    /** As {@link #stateText(Installed, Pending)}, with why the change failed at the last engine start, or null. */
    static String stateText(Installed i, Pending p, String applyError) {
        String s;
        if (p == null) {
            s = i == null ? "" : i.active() ? "Active" : "Disabled";
        } else {
            String v = p.target() == null ? "" : p.target().version().toString();
            s = switch (p.action()) {
                case ENABLE -> "Enabled at next engine start";
                case DISABLE -> "Disabled at next engine start";
                case REMOVE -> "Removed at next engine start";
                case INSTALL -> "Install " + v + " at next engine start";
                case UPDATE -> "Update to " + v + " at next engine start";
                case SWITCH -> "Switch to " + v + " from another source at next engine start";
            };
        }
        return applyError == null ? s : s + " (failed: " + reason(applyError) + ")";
    }

    /** The store's "action: reason" without the action, which the row's state already says. */
    private static String reason(String applyError) {
        for (Pending.Action a : Pending.Action.values()) {
            String head = a.name().toLowerCase(Locale.ROOT) + ": ";
            if (applyError.startsWith(head)) return applyError.substring(head.length());
        }
        return applyError;
    }

    /** What Disable asks first: the add-ons that go with {@code id}, and the features that stop. */
    static String disableWarning(String id, List<String> dependents, List<CoreAddOns.Feature> stops) {
        return warning("Disable", "disables", id, dependents, stops);
    }

    /** What Remove asks first, as {@link #disableWarning}. */
    static String removeWarning(String id, List<String> dependents, List<CoreAddOns.Feature> stops) {
        return warning("Remove", "removes", id, dependents, stops);
    }

    private static String warning(String verb, String does, String id, List<String> dependents,
                                  List<CoreAddOns.Feature> stops) {
        StringBuilder b = new StringBuilder(verb).append(' ').append(id).append(" at the next engine start?");
        if (!dependents.isEmpty()) {
            b.append("\n\nThis also ").append(does).append(" the add-ons that need it: ")
                    .append(String.join(", ", dependents)).append('.');
        }
        if (!stops.isEmpty()) {
            b.append("\n\nThese OpenTRAP features stop working: ").append(labels(stops)).append('.');
        }
        return b.toString();
    }

    /** The second question before an add-on a feature needs goes. */
    static String stopsAgain(String verb, String id, List<CoreAddOns.Feature> stops) {
        return labels(stops) + " cannot run without " + id + " until it is installed and enabled again. "
                + verb + " it anyway?";
    }

    /** What Enable asks when {@code id} takes others along, or needs some that are not installed. */
    static String enableText(String id, List<String> with, List<String> notInstalled) {
        StringBuilder b = new StringBuilder("Enable ").append(id).append(" at the next engine start?");
        if (!with.isEmpty()) {
            b.append("\n\nThis also enables what it needs: ").append(String.join(", ", with)).append('.');
        }
        if (!notInstalled.isEmpty()) {
            b.append("\n\nIt also needs ").append(String.join(", ", notInstalled))
                    .append(notInstalled.size() == 1 ? ", which is" : ", which are")
                    .append(" not installed. Install ").append(notInstalled.size() == 1 ? "it" : "them")
                    .append(" from Store; until then ZAP does not load ").append(id).append('.');
        }
        return b.toString();
    }

    private static String labels(List<CoreAddOns.Feature> features) {
        List<String> out = new ArrayList<>();
        for (CoreAddOns.Feature f : features) out.add(f.label());
        return String.join(", ", out);
    }

    /** Everything {@code next} reaches from {@code id}, each once, in the order found, without {@code id}. */
    static List<String> closure(String id, Function<String, List<String>> next) {
        List<String> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            for (String n : next.apply(todo.poll())) {
                if (!n.equals(id) && !out.contains(n)) {
                    out.add(n);
                    todo.add(n);
                }
            }
        }
        return out;
    }

    // ---- the list ----

    /** The rows as listed, for tests. */
    TableModel model() { return model; }

    /** The State of the row named {@code name}, or null when it is not listed; for tests. */
    String stateOf(String name) {
        for (Row r : rows) if (r.name().equals(name)) return r.state();
        return null;
    }

    /** Sets the Kind and State filters by their text; for tests. */
    void filter(String kind, String state) {
        kindFilter.setSelectedItem(kind);
        stateFilter.setSelectedItem(state);
    }

    private void reload() {
        Object keep = selectedKey();
        reloading = true;
        try {
            List<Row> all = new ArrayList<>();
            if (store != null && store.isOpen()) all.addAll(storeRows());
            all.sort(Comparator.comparing((Row r) -> r.name().toLowerCase(Locale.ROOT)).thenComparing(Row::id));
            // the jars stay below the store's add-ons, in the order they were loaded
            for (Loaded l : items) all.add(jarRow(l));
            String kind = (String) kindFilter.getSelectedItem();
            String state = (String) stateFilter.getSelectedItem();
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            rows.clear();
            for (Row r : all) {
                if (!"All".equals(kind) && !r.kind().equals(kind)) continue;
                if (!"All".equals(state) && !r.group().equals(state)) continue;
                if (!q.isEmpty() && !matches(r, q)) continue;
                rows.add(r);
            }
            model.fireTableDataChanged();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).key().equals(keep)) table.setRowSelectionInterval(i, i);
            }
        } finally {
            reloading = false;
        }
        showSelected();
        note.setText(noteText());
        note.setToolTipText(note.getText().isBlank() ? null : note.getText());
        maybeOfferCarryover();
    }

    private List<Row> storeRows() {
        Catalogue cat = store.catalogue();
        Map<String, List<Pending>> queued = new LinkedHashMap<>();
        for (Pending p : store.pending()) queued.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(p);
        Map<String, String> failed = store.applyErrors();
        List<Row> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (Installed in : store.installed()) {
            List<Pending> q = queued.getOrDefault(in.id(), List.of());
            String err = failed.get(in.id());
            String group = !q.isEmpty() ? "Pending" : in.active() ? "Active" : "Disabled";
            out.add(new Row("store:" + in.id(), in.kind().label(), nameOf(cat, in.id()), in.version().toString(),
                    installedFrom(cat, in), state(in, q, err), core(in.id()), group, in.id(), in, q, err, null));
            listed.add(in.id());
        }
        // an install queued for an add-on not installed yet, as from Store
        for (Map.Entry<String, List<Pending>> e : queued.entrySet()) {
            if (listed.contains(e.getKey())) continue;
            String id = e.getKey();
            Entry t = e.getValue().get(0).target();
            String err = failed.get(id);
            out.add(new Row("store:" + id, t == null ? Kind.ZAP_ADDON.label() : t.kind().label(),
                    t == null ? nameOf(cat, id) : t.name(), t == null ? "-" : t.version().toString(),
                    t == null ? "-" : sourceName(cat, t.sourceId()), state(null, e.getValue(), err), core(id),
                    "Pending", id, null, e.getValue(), err, null));
            listed.add(id);
        }
        for (String id : store.missing()) {
            if (!listed.add(id)) continue;
            out.add(new Row("store:" + id, Kind.ZAP_ADDON.label(), nameOf(cat, id), "-", "Bundled with ZAP", MISSING,
                    core(id), "Missing", id, null, List.of(), null, null));
        }
        return out;
    }

    private static String state(Installed in, List<Pending> q, String applyError) {
        if (q.isEmpty()) return stateText(in, null, applyError);
        List<String> parts = new ArrayList<>();
        for (Pending p : q) parts.add(stateText(in, p));
        String s = String.join("; ", parts);
        return applyError == null ? s : s + " (failed: " + reason(applyError) + ")";
    }

    private static Row jarRow(Loaded l) {
        return new Row(l, JAR_KIND, l.name, "-", "local file", l.enabled ? l.status : "Disabled", false,
                l.enabled ? "Active" : "Disabled", null, null, List.of(), null, l);
    }

    private static boolean core(String id) { return !CoreAddOns.stoppedBy(id).isEmpty(); }

    private static boolean matches(Row r, String q) {
        return r.name().toLowerCase(Locale.ROOT).contains(q) || r.source().toLowerCase(Locale.ROOT).contains(q)
                || (r.id() != null && r.id().toLowerCase(Locale.ROOT).contains(q));
    }

    /** The name an add-on's sources give it, else its id. */
    private static String nameOf(Catalogue cat, String id) {
        for (Entry e : cat.offers(id)) return e.name();
        return id;
    }

    private static String sourceName(Catalogue cat, String sourceId) {
        if (Source.BUNDLED_ID.equals(sourceId)) return "Bundled with ZAP";
        return cat.source(sourceId).map(Source::name).orElse(sourceId);
    }

    /** Where an installed add-on came from: the record's own words for the bundled set, else its source's name. */
    private static String installedFrom(Catalogue cat, Installed in) {
        if (Source.BUNDLED_ID.equals(in.sourceId()) && in.origin() != null && !in.origin().isEmpty()) {
            return Character.toUpperCase(in.origin().charAt(0)) + in.origin().substring(1);
        }
        String fallback = in.origin() != null ? in.origin() : in.sourceId();
        return in.sourceId() == null ? fallback : cat.source(in.sourceId()).map(Source::name).orElse(fallback);
    }

    private String noteText() {
        if (carryNote != null) return carryNote;
        if (store == null) return "The add-on store is not set up in this session; only extension jars are listed.";
        if (!store.isOpen()) return notOpen != null ? notOpen : "Reading the add-on store...";
        String e = store.lastStartError();
        return e != null ? e : " ";
    }

    private Object selectedKey() {
        Row r = selected();
        return r == null ? null : r.key();
    }

    private Row selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    /** Selects the row named {@code name}; for tests. */
    void select(String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name().equals(name)) { table.setRowSelectionInterval(i, i); return; }
        }
        throw new IllegalArgumentException(name + " is not listed");
    }

    /** Which of Enable, Disable, Remove and Undo are on, as "EDRU" with "-" for off; for tests. */
    String buttons() {
        return (enable.isEnabled() ? "E" : "-") + (disable.isEnabled() ? "D" : "-")
                + (remove.isEnabled() ? "R" : "-") + (undo.isEnabled() ? "U" : "-");
    }

    private void showSelected() {
        details.setRowCount(0);
        output.setText("");
        errors.setText("");
        Row r = selected();
        boolean jar = r != null && r.jar() != null;
        boolean held = r != null && r.have() != null;
        boolean queued = r != null && !r.queued().isEmpty();
        enable.setEnabled(jar ? !r.jar().enabled : held && !queued && !r.have().active());
        disable.setEnabled(jar ? r.jar().enabled : held && !queued && r.have().active());
        remove.setEnabled(jar || held && !queued);
        undo.setEnabled(queued);
        String busy = queued ? "a change is queued for it; Undo drops it" : null;
        for (JButton b : new JButton[] {enable, disable, remove}) b.setToolTipText(b.isEnabled() ? null : busy);
        if (r == null) return;
        if (jar) {
            Loaded l = r.jar();
            details.addRow(new Object[]{"Name", l.name});
            details.addRow(new Object[]{"Type", l.type});
            details.addRow(new Object[]{"File", l.file.getAbsolutePath()});
            details.addRow(new Object[]{"Status", l.status});
            details.addRow(new Object[]{"Enabled", l.enabled ? "Yes" : "No"});
            details.addRow(new Object[]{"System impact", impactLabel(l.impact) + " (" + l.impact + " of 100)"});
            details.addRow(new Object[]{"Auto-reload", l.autoReload ? "Yes" : "No"});
            output.setText(l.output.toString());
            errors.setText(l.errors.toString());
            return;
        }
        details.addRow(new Object[]{"Name", r.name()});
        details.addRow(new Object[]{"Id", r.id()});
        details.addRow(new Object[]{"Kind", r.kind()});
        details.addRow(new Object[]{"Version", r.version()});
        details.addRow(new Object[]{"Source", r.source()});
        details.addRow(new Object[]{"State", r.state()});
        if (held) {
            Installed in = r.have();
            if (in.file() != null) details.addRow(new Object[]{"File", in.file()});
            if (in.sha256() != null) details.addRow(new Object[]{"SHA-256", in.sha256()});
            List<String> needers = store.dependents(in.id());
            if (!needers.isEmpty()) details.addRow(new Object[]{"Needed by", String.join(", ", needers)});
        }
        List<CoreAddOns.Feature> stops = CoreAddOns.stoppedBy(r.id());
        if (!stops.isEmpty()) details.addRow(new Object[]{"Core add-on of", labels(stops)});
        if (r.error() != null) errors.setText("At the last engine start: " + r.error() + "\n");
    }

    // ---- actions ----

    /** Runs the store change for the selected add-on, or {@code onJar} for a jar and lists it again. */
    private void act(java.util.function.Consumer<String> onAddOn, java.util.function.Consumer<Loaded> onJar) {
        Row r = selected();
        if (r == null) return;
        if (r.jar() != null) {
            onJar.accept(r.jar());
            reload();
        } else if (r.id() != null) {
            onAddOn.accept(r.id());
        }
    }

    /**
     * Queues enabling {@code id} with the disabled add-ons it needs, after
     * naming any it needs that are not installed at all.
     */
    void enableAddOn(String id) {
        Set<String> busy = queuedIds();
        List<String> with = new ArrayList<>();
        for (String n : closure(id, store::needs)) if (!busy.contains(n)) with.add(n);
        List<String> absent = new ArrayList<>();
        List<String> all = new ArrayList<>(with);
        all.add(id);
        for (String x : all) {
            for (String n : store.notInstalledNeeds(x)) if (!busy.contains(n) && !absent.contains(n)) absent.add(n);
        }
        if ((!with.isEmpty() || !absent.isEmpty())
                && !dialogs.confirm("Enable " + id, enableText(id, with, absent), "Enable")) return;
        queue("Enable " + id, all, store::enable);
    }

    /** Queues disabling {@code id} with the active add-ons that need it, after the warnings. */
    void disableAddOn(String id) { takeOut(id, false); }

    /** Queues removing {@code id} with the active add-ons that need it, after the warnings. */
    void removeAddOn(String id) { takeOut(id, true); }

    /** What disabling or removing {@code ids} takes along, by the store's record. */
    static TakeOut takeOut(Store store, List<String> ids) {
        Set<String> going = new HashSet<>();
        for (Pending p : store.pending()) {
            if (p.action() == Pending.Action.DISABLE || p.action() == Pending.Action.REMOVE) going.add(p.id());
        }
        List<String> with = new ArrayList<>();
        for (String id : ids) {
            for (String d : closure(id, store::dependents)) {
                if (!going.contains(d) && !ids.contains(d) && !with.contains(d)) with.add(d);
            }
        }
        // only an add-on the engine runs now stops a feature when it goes
        Set<CoreAddOns.Feature> stopping = new java.util.TreeSet<>();
        for (Installed in : store.installed()) {
            if (ids.contains(in.id()) && in.active()) stopping.addAll(CoreAddOns.stoppedBy(in.id()));
        }
        for (String d : with) stopping.addAll(CoreAddOns.stoppedBy(d));
        return new TakeOut(List.copyOf(with), List.copyOf(stopping));
    }

    private void takeOut(String id, boolean removing) {
        TakeOut out = takeOut(store, List.of(id));
        List<String> with = out.with();
        List<CoreAddOns.Feature> stops = out.stops();
        Installed self = null;
        for (Installed in : store.installed()) if (in.id().equals(id)) self = in;
        String verb = removing ? "Remove" : "Disable";
        String title = verb + " " + id;
        String text = removing ? removeWarning(id, with, stops) : disableWarning(id, with, stops);
        if (removing && self != null && Source.BUNDLED_ID.equals(self.sourceId())) {
            text += "\n\nIt is bundled with ZAP and stays removed until it is installed again from Store.";
        }
        if ((removing || !with.isEmpty() || !stops.isEmpty())
                && !dialogs.confirm(title, text, with.isEmpty() ? verb : verb + " all")) return;
        if (!stops.isEmpty() && !dialogs.confirm(title, stopsAgain(verb, id, stops), verb + " anyway")) return;
        List<String> all = new ArrayList<>(with);
        all.add(id);
        queue(title, all, removing ? store::remove : store::disable);
    }

    private Set<String> queuedIds() {
        Set<String> out = new HashSet<>();
        for (Pending p : store.pending()) out.add(p.id());
        return out;
    }

    /** Queues {@code change} for each of {@code ids} in turn off the EDT; the first failure stops it and is told. */
    private void queue(String title, List<String> ids, Change change) {
        Thread.ofVirtual().name("addon-store-queue").start(() -> {
            String msg = queueEach(ids, change);
            if (msg != null) SwingUtilities.invokeLater(() -> dialogs.tell(title, msg));
        });
    }

    /**
     * Queues {@code change} for each of {@code ids} in turn on the calling
     * thread. The first failure stops it; returns what to tell then, or null.
     */
    static String queueEach(List<String> ids, Change change) {
        List<String> done = new ArrayList<>();
        for (String id : ids) {
            try {
                change.queue(id);
                done.add(id);
            } catch (IOException | RuntimeException e) {
                return id + " could not be queued: " + why(e)
                        + (done.isEmpty() ? "" : "\n\nQueued before it: " + String.join(", ", done)
                        + " (Undo drops those)");
            }
        }
        return null;
    }

    // ---- a new ZAP version ----

    private void maybeOfferCarryover() {
        if (carryChecked || store == null || !store.isOpen()) return;
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null && w.isShowing()) offerCarryover();
    }

    /**
     * Looks off the EDT for the previous ZAP version's add-ons and, when there
     * are any, asks once whether to look them up for this version. The answer,
     * yes or no, is kept in the store, so the question is not asked again.
     */
    void offerCarryover() {
        if (carryChecked) return;
        carryChecked = true;
        Thread.ofVirtual().name("addon-store-carryover").start(() -> {
            List<Installed> carry;
            String minor;
            try {
                carry = store.carryover();
                minor = store.minor();
            } catch (IOException | RuntimeException e) {
                Debug.log("add-ons of the previous ZAP version could not be read: " + e);
                return;
            }
            if (carry.isEmpty()) return;
            SwingUtilities.invokeLater(() -> askCarryover(carry, minor));
        });
    }

    /** The question before the previous version's add-ons are looked up in the sources. */
    static String carryoverQuestion(List<Installed> carry, String minor) {
        List<String> ids = new ArrayList<>();
        for (Installed in : carry) ids.add(in.id() + (in.active() ? "" : " (disabled)"));
        return "ZAP " + minor + " starts with only its bundled add-ons. These were installed for the previous "
                + "ZAP version: " + String.join(", ", ids) + ".\n\nLook them up in their sources for ZAP " + minor
                + " and queue them? This fetches every enabled source. Store can install them later as well.";
    }

    private void askCarryover(List<Installed> carry, String minor) {
        String title = "Add-ons for ZAP " + minor;
        boolean yes = dialogs.confirm(title, carryoverQuestion(carry, minor), "Look them up");
        if (yes) {
            carryNote = "Looking up the previous ZAP version's add-ons for ZAP " + minor + "...";
            note.setText(carryNote);
        }
        Thread.ofVirtual().name("addon-store-carryover").start(() -> {
            try {
                store.carryoverOffered();
            } catch (IOException e) {
                Debug.log("the carry-over could not be marked as offered: " + e);
            }
            if (!yes) return;
            CarryPlan cp;
            try {
                store.refresh(() -> false);
                cp = carryPlan(store, carry);
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    carryNote = null;
                    reload();
                    dialogs.tell(title, "The add-ons could not be looked up: " + why(e));
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                carryNote = null;
                reload();
                queueCarryover(title, cp, minor);
            });
        });
    }

    /**
     * For each of the previous version's add-ons, this version's entry from
     * its own line with what it needs; those that were disabled stay so. One
     * that no reachable source offers is named with why.
     */
    static CarryPlan carryPlan(Store store, List<Installed> carry) {
        Catalogue cat = store.catalogue();
        List<Entry> plan = new ArrayList<>();
        List<String> off = new ArrayList<>();
        List<String> none = new ArrayList<>();
        for (Installed in : carry) {
            Entry e = store.successor(in);
            if (e == null) { none.add(in.id()); continue; }
            String why = StorePanel.unreachable(cat, e.sourceId());
            if (why != null) { none.add(in.id() + " (" + why + ")"); continue; }
            try {
                for (Entry x : store.plan(e)) {
                    if (plan.stream().noneMatch(p -> p.id().equals(x.id()))) plan.add(x);
                }
                if (!in.active()) off.add(in.id());
            } catch (Resolver.Unresolvable ex) {
                none.add(in.id() + " (" + ex.getMessage() + ")");
            }
        }
        return new CarryPlan(List.copyOf(plan), List.copyOf(off), List.copyOf(none));
    }

    /** What the carry-over says before it downloads. */
    static String carryoverText(CarryPlan cp, String minor) {
        StringBuilder b = new StringBuilder();
        if (!cp.plan().isEmpty()) {
            b.append("These add-ons are downloaded now and take effect when the engine restarts:\n");
            for (Entry e : cp.plan()) {
                b.append("  ").append(e.name()).append(' ').append(e.version()).append(" (")
                        .append(e.status().label()).append(")\n");
            }
            if (!cp.keepOff().isEmpty()) {
                b.append("These stay disabled: ").append(String.join(", ", cp.keepOff())).append(".\n");
            }
        }
        if (!cp.unavailable().isEmpty()) {
            if (!b.isEmpty()) b.append('\n');
            b.append("Not available for ZAP ").append(minor).append(":\n");
            for (String s : cp.unavailable()) b.append("  ").append(s).append('\n');
        }
        return b.toString().strip();
    }

    private void queueCarryover(String title, CarryPlan cp, String minor) {
        String text = carryoverText(cp, minor);
        if (cp.plan().isEmpty()) {
            dialogs.tell(title, text);
            return;
        }
        if (!dialogs.confirm(title, text, "Queue")) return;
        StorePanel.download(this, store, cp.plan(), title, () -> {
            for (String id : cp.keepOff()) store.disable(id);
        });
    }

    // ---- extension jars ----

    /**
     * A rough system-impact score from the extension's own size: a bigger jar
     * with more classes loads more code and costs more to run. It is a measure
     * of the file, not a prediction about behaviour, which is why it is called
     * an estimate everywhere it is shown.
     */
    private static int measureImpact(File jar) {
        if (jar == null || !jar.isFile()) return 0;
        long kb = jar.length() / 1024;
        int classes = 0;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) if (en.nextElement().getName().endsWith(".class")) classes++;
        } catch (Exception _) { }
        int score = (int) Math.min(60, kb / 40) + Math.min(40, classes / 10);
        return Math.max(1, Math.min(100, score));
    }

    private static String impactLabel(int score) {
        return score < 25 ? "Low" : score < 60 ? "Medium" : "High";
    }

    private static JTextArea console() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(Ui.MONO);
        return a;
    }

    private void load(LoadExtensionDialog.Result r) {
        Loaded l = new Loaded(r.type(), r.file().getName(), r.file());
        if (!r.file().isFile()) {
            l.status = "File not found";
            l.errors.append("No such file: ").append(r.file()).append('\n');
        } else if ("Java".equals(r.type())) {
            l.status = loadJar(l, r.file());
        } else {
            l.status = r.type() + " extensions need a " + r.type() + " runtime; set its location under Settings > Extensions";
            l.errors.append(l.status).append('\n');
        }
        l.impact = measureImpact(r.file());
        items.add(l);
        reload();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).jar() == l) table.setRowSelectionInterval(i, i);
    }

    /** Try the jar: find the first class implementing ToolModule and instantiate it. */
    private static String loadJar(Loaded l, File jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.net.URLClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{jar.toURI().toURL()},
                    InstalledPanel.class.getClassLoader());
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class") || n.contains("$")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    if (opentrap.core.ToolModule.class.isAssignableFrom(c) && !c.isInterface()) {
                        Object inst = c.getDeclaredConstructor().newInstance();
                        l.output.append("Loaded ").append(cn).append(" as a tool module (")
                                .append(((opentrap.core.ToolModule) inst).name()).append(")\n");
                        return "Loaded";
                    }
                } catch (Throwable t) {
                    l.errors.append(cn).append(": ").append(t).append('\n');
                }
            }
            l.output.append("No class in the jar implements opentrap.core.ToolModule.\n");
            return "No entry point";
        } catch (Exception e) {
            l.errors.append(e).append('\n');
            return "Failed: " + e.getMessage();
        }
    }

    // ---- small helpers ----

    private Dialogs swingDialogs() {
        return new Dialogs() {
            @Override public boolean confirm(String title, String text, String verb) {
                JTextArea n = Ui.note(text);
                Ui.sizeNote(n, 480);
                String[] options = {verb, "Cancel"};
                return JOptionPane.showOptionDialog(InstalledPanel.this, n, title, JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, options, options[1]) == 0;
            }
            @Override public void tell(String title, String text) {
                JTextArea n = Ui.note(text);
                Ui.sizeNote(n, 480);
                JOptionPane.showMessageDialog(InstalledPanel.this, n, title, JOptionPane.WARNING_MESSAGE);
            }
        };
    }

    private static String why(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Queued changes in the accent colour, a missing add-on or a failed change in red, the core mark muted. */
    private final class StateRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f,
                                                                 int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, f, r, c);
            if (sel || r >= rows.size()) return this;
            Row row = rows.get(r);
            if (c == 5) setForeground(Theme.MUTED);
            else if (row.error() != null || "Missing".equals(row.group())) setForeground(Ui.SEV_HIGH);
            else if ("Pending".equals(row.group())) setForeground(Theme.ACCENT);
            else setForeground(t.getForeground());
            return this;
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] names = {"Kind", "Name", "Version", "Source", "State", ""};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.kind();
                case 1 -> row.name();
                case 2 -> row.version();
                case 3 -> row.source();
                case 4 -> row.state();
                default -> row.core() ? "core" : "";
            };
        }
    }
}
