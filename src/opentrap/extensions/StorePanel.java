package opentrap.extensions;

import opentrap.core.SuiteContext;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.Catalogue;
import opentrap.extensions.store.Dependency;
import opentrap.extensions.store.Entry;
import opentrap.extensions.store.Installed;
import opentrap.extensions.store.Pending;
import opentrap.extensions.store.PlainText;
import opentrap.extensions.store.Resolver;
import opentrap.extensions.store.Source;
import opentrap.extensions.store.SourceStatus;
import opentrap.extensions.store.SourceType;
import opentrap.extensions.store.Status;
import opentrap.extensions.store.Store;
import opentrap.extensions.store.Version;
import opentrap.ui.Fonts;
import opentrap.ui.SettingsDialog;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.HierarchyEvent;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Extensions > Store: what the add-on sources offer, one row per add-on, and
 * the selected add-on's detail card beside the list. Opening the tab reads the
 * cached lists only; Refresh is what fetches them. Install, Update and Switch
 * source show everything the change pulls in, then download it off the event
 * thread and queue it for the next engine start.
 */
public final class StorePanel extends JPanel {

    /** The Status filter's choices, most stable first; the second is the default. */
    static final String[] STATUS_FILTERS = {"Release only", "Release and beta", "All"};

    /** A choice in a source list: a source id and the name it shows. A null id is every source. */
    private record SourceItem(String id, String name) {
        @Override public String toString() { return name; }
    }

    /** One row: the add-on, the entry it offers from the chosen source, what is installed, what the row does. */
    private record Row(String id, Entry best, Installed have, String action) {}

    private static final SourceItem ALL_SOURCES = new SourceItem(null, "All");

    private final SuiteContext ctx;
    private final Store store;
    /** The install and version of the engine a start would run, or null for none; read off the EDT. */
    private final Supplier<ZapEngine.Install> engine;
    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JTextField search = Ui.searchField(16);
    private final JComboBox<String> status = Ui.combo(STATUS_FILTERS);
    private final DefaultComboBoxModel<SourceItem> sourceItems = new DefaultComboBoxModel<>(new SourceItem[] {ALL_SOURCES});
    private final JComboBox<SourceItem> source = Ui.combo(new SourceItem[0]);
    private final JButton refresh = Ui.button("Refresh", "fetch the add-on list of every enabled source");
    private final JButton updateAll = Ui.button("Update all", "update every add-on that has an update, with what they need");
    private final JButton emptyRefresh = Ui.button("Refresh", "fetch the add-on list of every enabled source");
    private final JLabel emptyText = Ui.label("");
    private final JLabel sourcesLine = Ui.label("");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    /** The source line picked in the detail card, by add-on id; an id not here uses the default. */
    private final Map<String, String> picked = new HashMap<>();
    private List<Entry> updates = List.of();
    private AtomicBoolean refreshCancel;
    /** Why the store is not open, once opening it has failed; null before that and once it is open. */
    private String notOpen;
    /** Set while the list or the Source filter is rebuilt, so their own events do not start another rebuild. */
    private boolean reloading;

    public StorePanel(SuiteContext ctx, Store store) {
        this(ctx, store, () -> {
            ZapEngine ze = ZapEngine.shared();
            return ze == null ? null : ze.install();
        });
    }

    /** A Store tab that opens the store, when it is not open yet, for the engine {@code engine} names. */
    StorePanel(SuiteContext ctx, Store store, Supplier<ZapEngine.Install> engine) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.store = store;
        this.engine = engine;
        setOpaque(false);

        // ---- filter bar ----
        status.setSelectedIndex(1);
        source.setModel(sourceItems);
        JButton sources = Ui.button("Sources...", "add, change or switch off add-on sources in Settings > Extensions");
        sources.addActionListener(e -> SettingsDialog.open(SwingUtilities.getWindowAncestor(this), ctx, "Extensions"));
        refresh.addActionListener(e -> refresh());
        emptyRefresh.addActionListener(e -> refresh());
        updateAll.addActionListener(e -> updateAll());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reload(); }
        });
        status.addActionListener(e -> reload());
        source.addActionListener(e -> { if (!reloading) reload(); });
        // Refresh turns into Cancel while it runs: it keeps the wider text's width, so the bar does not move
        int refreshWidth = Math.max(refresh.getPreferredSize().width, Ui.button("Cancel").getPreferredSize().width);
        JComponent[] controls = {status, source, refresh, updateAll, sources};
        int h = search.getPreferredSize().height;
        for (JComponent c : controls) h = Math.max(h, c.getPreferredSize().height);
        for (JComponent c : controls) {
            int w = c == refresh ? refreshWidth : c.getPreferredSize().width;
            c.setPreferredSize(new Dimension(w, h));
        }
        search.setPreferredSize(new Dimension(search.getPreferredSize().width, h));
        JPanel bar = Ui.filterBar(search, Ui.label("Status:"), status, Ui.label("Source:"), source,
                refresh, updateAll, sources);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(bar, BorderLayout.NORTH);
        top.add(new PendingStrip(ctx, store), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // ---- list and detail card ----
        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f,
                                                                     int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (!sel) setForeground(Theme.ACCENT);
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloading) showSelected();
        });
        JScrollPane list = Ui.scroll(table);
        list.setPreferredSize(new Dimension(360, 10));
        detail.setOpaque(false);
        detail.setBorder(Ui.pad(10, 12, 10, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, detail);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setDividerLocation(380);

        JPanel empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        emptyText.setForeground(Theme.MUTED);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        empty.add(emptyText, gc);
        gc.gridy = 1;
        gc.insets = new Insets(8, 0, 0, 0);
        empty.add(emptyRefresh, gc);
        body.setOpaque(false);
        body.add(split, "list");
        body.add(empty, "empty");
        add(body, BorderLayout.CENTER);

        sourcesLine.setForeground(Theme.MUTED);
        sourcesLine.setBorder(Ui.pad(4, 10, 6, 10));
        add(sourcesLine, BorderLayout.SOUTH);

        // a store listener may run on any thread; the panel changes on the EDT only
        store.addListener(() -> SwingUtilities.invokeLater(this::reload));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) retitle();
        });
        reload();
        if (!store.isOpen()) {
            Thread.ofVirtual().name("addon-store-open").start(() -> {
                String why = openFromEngine(store, engine);
                SwingUtilities.invokeLater(() -> {
                    notOpen = why;
                    reload();
                });
            });
        }
    }

    /** The rows as listed, for tests. */
    TableModel model() { return model; }

    /** The Store tab's title: with the number of updates when there are any. */
    String tabTitle() { return updates.isEmpty() ? "Store" : "Store (" + updates.size() + ")"; }

    /**
     * What a row offers to do with {@code best}, given what is installed of
     * it ({@code have}, null for nothing) and whether {@code best} is a new
     * build of the installed version from a local folder.
     */
    static String rowAction(Entry best, Installed have, boolean newBuild) {
        if (have == null) return "Install";
        if (!Resolver.sameLine(have.sourceId(), best.sourceId())) return "Switch source";
        if (newBuild) return "New build";
        return best.version().compareTo(have.version()) > 0 ? "Update" : "Installed";
    }

    /**
     * Opens the store for the engine a start would run, as the engine's start
     * hook does, reading only files: the sources, the cached lists and the
     * engine's own add-ons. Returns why it could not, or null once it is open.
     */
    static String openFromEngine(Store store, Supplier<ZapEngine.Install> engine) {
        try {
            if (store.isOpen()) return null;
            ZapEngine.Install in = engine.get();
            if (in == null) return "No scan engine is installed, so there is no ZAP version to list add-ons for.";
            store.open(Version.parse(in.version()), in.dir());
            return null;
        } catch (Exception e) {
            return "The add-on store could not be opened: " + why(e);
        }
    }

    // ---- the list ----

    private void reload() {
        if (!store.isOpen()) {
            rows.clear();
            model.fireTableDataChanged();
            showDetail(null);
            emptyText.setText(notOpen != null ? notOpen : "Not fetched yet");
            // Refresh tries the open again, so it stays on after a failed one
            emptyRefresh.setVisible(true);
            refresh.setEnabled(refreshCancel == null || !refreshCancel.get());
            updateAll.setEnabled(false);
            sourcesLine.setText(" ");
            cards.show(body, "empty");
            return;
        }
        Catalogue cat = store.catalogue();
        String keep = selectedId();
        reloading = true;  // before refillSources, whose changes fire the Source filter's listener
        try {
            refillSources(cat);
            updates = store.updates();
            Map<String, Entry> updateOf = new HashMap<>();
            for (Entry u : updates) updateOf.put(u.id(), u);
            Map<String, Installed> have = new HashMap<>();
            for (Installed in : store.installed()) have.put(in.id(), in);
            int floor = statusFloor();
            SourceItem only = (SourceItem) source.getSelectedItem();
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            rows.clear();
            for (String id : cat.ids()) {
                List<Entry> offers = new ArrayList<>();
                for (Entry e : cat.offers(id)) {
                    if (e.status().rank() < floor) continue;
                    if (only != null && only.id() != null && !only.id().equals(e.sourceId())) continue;
                    offers.add(e);
                }
                if (offers.isEmpty()) continue;
                Installed in = have.get(id);
                String line = picked.get(id);
                if (line == null || !offersLine(offers, line)) line = defaultLine(in, offers, cat);
                Entry update = updateOf.get(id);
                Entry best = best(offers, line, in, update);
                if (!q.isEmpty() && !matches(best, q)) continue;
                boolean newBuild = in != null && best.equals(update) && update.version().equals(in.version());
                String action = rowAction(best, in, newBuild);
                // only what the store counts as an update is offered as one: never a less stable version
                if (action.equals("Update") && !best.equals(update)) action = "Installed";
                rows.add(new Row(id, best, in, action));
            }
            rows.sort(Comparator.comparing((Row r) -> r.best().name().toLowerCase(Locale.ROOT))
                    .thenComparing(Row::id));
            model.fireTableDataChanged();
            int at = 0;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(keep)) at = i;
            if (!rows.isEmpty()) table.setRowSelectionInterval(at, at);
        } finally {
            reloading = false;
        }
        showSelected();
        boolean fetched = everFetched(cat);
        refresh.setEnabled(refreshCancel == null || !refreshCancel.get());
        updateAll.setEnabled(!updates.isEmpty() && refreshCancel == null);
        emptyText.setText(fetched ? "No add-on matches the filters." : "Not fetched yet");
        emptyRefresh.setVisible(!fetched);
        cards.show(body, rows.isEmpty() ? "empty" : "list");
        showSources(cat);
        retitle();
    }

    /** The lowest status rank the Status filter lets through. */
    private int statusFloor() {
        return switch (status.getSelectedIndex()) {
            case 0 -> Status.RELEASE.rank();
            case 1 -> Status.BETA.rank();
            default -> Status.ALPHA.rank();
        };
    }

    /** The Source filter's choices: every enabled source, and the bundled add-ons; keeps the choice made. */
    private void refillSources(Catalogue cat) {
        List<SourceItem> want = new ArrayList<>();
        want.add(ALL_SOURCES);
        for (Source s : cat.sources()) if (s.enabled()) want.add(new SourceItem(s.id(), s.name()));
        cat.source(Source.BUNDLED_ID).ifPresent(s -> want.add(new SourceItem(s.id(), sourceName(cat, s.id()))));
        List<SourceItem> now = new ArrayList<>();
        for (int i = 0; i < sourceItems.getSize(); i++) now.add(sourceItems.getElementAt(i));
        if (now.equals(want)) return;
        SourceItem chosen = (SourceItem) source.getSelectedItem();
        sourceItems.removeAllElements();
        for (SourceItem s : want) sourceItems.addElement(s);
        sourceItems.setSelectedItem(chosen != null && want.contains(chosen) ? chosen : ALL_SOURCES);
    }

    /** The key a source line is picked by: ZAP's feed and the bundled set are one line. */
    private static String lineKey(String sourceId) {
        return Resolver.sameLine(sourceId, Source.ZAP_ID) ? Source.ZAP_ID : sourceId;
    }

    private static boolean offersLine(List<Entry> offers, String line) {
        for (Entry e : offers) if (Resolver.sameLine(e.sourceId(), line)) return true;
        return false;
    }

    /** The source an add-on's row offers first: where it is installed from, else ZAP's, else the first enabled one. */
    private static String defaultLine(Installed in, List<Entry> offers, Catalogue cat) {
        if (in != null && offersLine(offers, in.sourceId())) return lineKey(in.sourceId());
        if (offersLine(offers, Source.ZAP_ID)) return Source.ZAP_ID;
        for (Source s : cat.sources()) if (s.enabled() && offersLine(offers, s.id())) return s.id();
        return lineKey(offers.get(0).sourceId());
    }

    /**
     * The entry a row offers from {@code line}. For an add-on installed from
     * that line it is its update, else the installed version, so that a less
     * stable version never shows as its update; otherwise the newest.
     */
    private static Entry best(List<Entry> offers, String line, Installed in, Entry update) {
        List<Entry> from = new ArrayList<>();
        for (Entry e : offers) if (Resolver.sameLine(e.sourceId(), line)) from.add(e);
        if (in != null && Resolver.sameLine(in.sourceId(), line)) {
            if (update != null && from.contains(update)) return update;
            for (Entry e : from) if (e.version().compareTo(in.version()) <= 0) return e;
        }
        return from.get(0);
    }

    private static boolean matches(Entry e, String q) {
        return e.name().toLowerCase(Locale.ROOT).contains(q) || e.id().toLowerCase(Locale.ROOT).contains(q)
                || PlainText.of(e.description()).toLowerCase(Locale.ROOT).contains(q);
    }

    /** Whether any enabled source other than the bundled set has a list, fetched now or cached before. */
    private static boolean everFetched(Catalogue cat) {
        for (Source s : cat.sources()) {
            if (s.enabled() && s.type() != SourceType.BUNDLED && cat.status(s.id()).fetched() != null) return true;
        }
        return false;
    }

    private String selectedId() {
        int r = table.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r).id();
    }

    private Row selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    private void retitle() {
        JTabbedPane tabs = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
        if (tabs == null) return;
        int i = tabs.indexOfComponent(this);
        if (i >= 0 && !tabs.getTitleAt(i).equals(tabTitle())) tabs.setTitleAt(i, tabTitle());
    }

    // ---- the per-source status line ----

    private void showSources(Catalogue cat) {
        Instant now = Instant.now();
        List<String> parts = new ArrayList<>();
        StringBuilder refused = new StringBuilder();
        for (Source s : cat.sources()) {
            if (!s.enabled() || s.type() == SourceType.BUNDLED) continue;
            SourceStatus st = cat.status(s.id());
            parts.add(s.name() + ": " + statusText(s, st, now));
            for (String r : st.rejected()) {
                refused.append(refused.isEmpty() ? "Left out: " : "; ").append(s.name()).append(" - ").append(r);
            }
        }
        sourcesLine.setText(parts.isEmpty() ? "No source is switched on. Sources... switches one on."
                : String.join("  ·  ", parts));
        sourcesLine.setToolTipText(refused.isEmpty() ? null : refused.toString());
    }

    /** How one source stands, as the status line says it. */
    static String statusText(Source s, SourceStatus st, Instant now) {
        String text;
        if (st.fetched() == null) text = "not fetched yet";
        else text = (s.type() == SourceType.LOCAL_FOLDER ? "read " : "fetched ") + ago(st.fetched(), now);
        if (st.error() != null) text += ", last try failed: " + st.error();
        if (!st.rejected().isEmpty()) text += ", " + st.rejected().size() + " left out";
        return text;
    }

    static String ago(Instant then, Instant now) {
        long min = Math.max(0, Duration.between(then, now).toMinutes());
        if (min < 1) return "just now";
        if (min < 60) return min == 1 ? "1 minute ago" : min + " minutes ago";
        long h = min / 60;
        if (h < 48) return h == 1 ? "1 hour ago" : h + " hours ago";
        return h / 24 + " days ago";
    }

    // ---- the detail card ----

    private void showSelected() { showDetail(selected()); }

    private void showDetail(Row r) {
        detail.removeAll();
        if (r == null) {
            JLabel l = Ui.label(rows.isEmpty() ? "" : "Select an add-on to see its details.");
            l.setForeground(Theme.MUTED);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            detail.add(l, BorderLayout.CENTER);
        } else {
            detail.add(Ui.scroll(card(r)), BorderLayout.CENTER);
        }
        detail.revalidate();
        detail.repaint();
    }

    private JComponent card(Row r) {
        Catalogue cat = store.catalogue();
        Entry e = r.best();
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setBorder(Ui.pad(4, 6, 20, 6));

        JPanel title = new JPanel(new BorderLayout(10, 0));
        title.setOpaque(false);
        JLabel name = new JLabel(e.name());
        name.setFont(Fonts.ui(Font.BOLD, 17));
        title.add(name, BorderLayout.CENTER);
        title.add(actionButton(r), BorderLayout.EAST);
        add(col, title);

        List<String> by = new ArrayList<>();
        if (!e.author().isBlank()) by.add(e.author());
        by.add(sourceName(cat, e.sourceId()));
        add(col, muted(String.join("  ·  ", by)));
        List<String> facts = new ArrayList<>();
        facts.add(e.version().toString());
        facts.add(e.status().label());
        if (!e.date().isBlank()) facts.add(e.date());
        if (e.size() > 0) facts.add(size(e.size()));
        add(col, muted(String.join("  ·  ", facts)));
        if (r.have() != null) {
            Installed in = r.have();
            add(col, muted("Installed: " + in.version() + " from " + installedFrom(cat, in)
                    + (in.active() ? "" : ", disabled")));
        }
        Pending p = pendingOf(r.id());
        if (p != null) add(col, muted("Queued: " + p.action().name().toLowerCase(Locale.ROOT)
                + " at the next engine start"));
        add(col, Box.createVerticalStrut(10));

        List<Object> form = new ArrayList<>();
        form.add("Source");
        form.add(Ui.natural(sourcePicker(r, cat)));
        if (!e.dependencies().isEmpty()) {
            List<String> needs = new ArrayList<>();
            for (Dependency d : e.dependencies()) {
                needs.add(d.range().toString().isBlank() ? d.id() : d.id() + " " + d.range());
            }
            form.add("Needs");
            form.add(Ui.label(String.join(", ", needs)));
        }
        URI docs = webLink(e.infoUrl());
        if (docs != null) {
            form.add("Docs");
            form.add(Ui.link(Ui.truncate(e.infoUrl(), 80), () -> browse(docs)));
        }
        add(col, Ui.form(form.toArray()));

        String description = PlainText.of(e.description());
        if (!description.isEmpty()) {
            add(col, Box.createVerticalStrut(10));
            add(col, para(description));
        }
        String changes = PlainText.of(e.changes());
        if (!changes.isEmpty()) {
            add(col, Box.createVerticalStrut(10));
            add(col, Ui.bold("Changes"));
            add(col, para(changes));
        }
        return col;
    }

    /** Every source line that offers this add-on at the chosen status, with the row's own selected. */
    private JComboBox<SourceItem> sourcePicker(Row r, Catalogue cat) {
        Map<String, SourceItem> lines = new LinkedHashMap<>();
        int floor = statusFloor();
        for (Entry o : cat.offers(r.id())) {
            if (o.status().rank() < floor) continue;
            String key = lineKey(o.sourceId());
            lines.putIfAbsent(key, new SourceItem(key, sourceName(cat, lineSourceId(cat, r.id(), key, floor))));
        }
        JComboBox<SourceItem> c = Ui.combo(lines.values().toArray(new SourceItem[0]));
        SourceItem now = lines.get(lineKey(r.best().sourceId()));
        if (now != null) c.setSelectedItem(now);
        c.setEnabled(lines.size() > 1);
        c.addActionListener(ev -> {
            SourceItem s = (SourceItem) c.getSelectedItem();
            if (s == null || s.equals(now)) return;
            picked.put(r.id(), s.id());
            SwingUtilities.invokeLater(this::reload);
        });
        return c;
    }

    /** The source a line is named after: ZAP's feed when it offers the add-on, else the bundled set. */
    private static String lineSourceId(Catalogue cat, String id, String key, int floor) {
        if (!Source.ZAP_ID.equals(key)) return key;
        for (Entry o : cat.offers(id)) {
            if (o.status().rank() >= floor && Source.ZAP_ID.equals(o.sourceId())) return Source.ZAP_ID;
        }
        return Source.BUNDLED_ID;
    }

    private static String sourceName(Catalogue cat, String sourceId) {
        if (Source.BUNDLED_ID.equals(sourceId)) return "Bundled with ZAP";
        return cat.source(sourceId).map(Source::name).orElse(sourceId);
    }

    /** Where an installed add-on came from: the record's own words for the bundled set, else its source's name. */
    private static String installedFrom(Catalogue cat, Installed in) {
        if (Source.BUNDLED_ID.equals(in.sourceId())) return in.origin();
        return cat.source(in.sourceId()).map(Source::name).orElse(in.origin());
    }

    private Pending pendingOf(String id) {
        for (Pending p : store.pending()) if (p.id().equals(id)) return p;
        return null;
    }

    /** Why a change to {@code id} cannot be queued now because one is queued already, or null. */
    private String queued(String id) {
        Pending p = pendingOf(id);
        if (p == null) return null;
        return "already queued for the next engine start: " + p.action().name().toLowerCase(Locale.ROOT)
                + "; undo it in Installed first";
    }

    /** Why the row's action button is off, or null when it is on: installed, queued, or offline. */
    String whyNot(String id) {
        for (Row r : rows) {
            if (!r.id().equals(id)) continue;
            if (r.action().equals("Installed")) return "installed";
            String q = queued(id);
            return q != null ? q : unreachable(store.catalogue(), r.best().sourceId());
        }
        return "not listed";
    }

    /** Sets the Source filter to the source with this id, or to All for null; for tests. */
    void showSource(String sourceId) {
        for (int i = 0; i < sourceItems.getSize(); i++) {
            SourceItem it = sourceItems.getElementAt(i);
            if (java.util.Objects.equals(it.id(), sourceId)) { source.setSelectedItem(it); return; }
        }
        throw new IllegalArgumentException("No source " + sourceId + " in the Source filter");
    }

    /** The row's action for {@code id}, or null when it is not listed; for tests. */
    String actionOf(String id) {
        for (Row r : rows) if (r.id().equals(id)) return r.action();
        return null;
    }

    private JButton actionButton(Row r) {
        String text = r.action().equals("New build") ? "Update" : r.action();
        JButton b = Ui.primaryButton(text, null);
        String why = whyNot(r.id());
        if (r.action().equals("Installed")) {
            b.setEnabled(false);
        } else if (queued(r.id()) != null) {
            b.setText("Queued");
            b.setEnabled(false);
            b.setToolTipText(why);
        } else if (why != null) {
            b.setEnabled(false);
            b.setToolTipText(why);
        } else {
            b.setToolTipText(r.action().equals("New build")
                    ? "the folder holds another build of the installed version"
                    : "download it now; it takes effect when the engine restarts");
            b.addActionListener(ev -> act(r));
        }
        return b;
    }

    /** Why entries of this source cannot be downloaded now, or null: only a feed needs the network. */
    static String unreachable(Catalogue cat, String sourceId) {
        Source s = cat.source(sourceId).orElse(null);
        if (s == null || (s.type() != SourceType.ZAP_FEED && s.type() != SourceType.FEED_URL)) return null;
        String error = cat.status(sourceId).error();
        return error == null ? null : "could not reach " + s.name() + " at the last refresh: " + error;
    }

    // ---- actions ----

    private void act(Row r) {
        List<Entry> plan;
        try {
            plan = store.plan(r.best());
        } catch (Resolver.Unresolvable ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), r.action() + " " + r.best().name(),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String verb = r.action().equals("New build") ? "Update" : r.action();
        if (!confirm(verb + " " + r.best().name(), confirmText(r, plan), verb)) return;
        download(plan, verb + " " + r.best().name());
    }

    /** What Update all downloads, dependencies first and each id once, and one "name: reason" per update left out. */
    record UpdatePlan(List<Entry> plan, List<String> leftOut) {}

    /**
     * Every update the store counts, with what it needs, except those whose
     * source cannot be reached, whose dependencies cannot be met, or whose
     * add-on has a change queued already: another update would be downloaded
     * and queued twice, and one over a queued removal would bring it back.
     */
    UpdatePlan planUpdates() {
        Catalogue cat = store.catalogue();
        List<Entry> plan = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Entry u : store.updates()) {
            String why = queued(u.id());
            if (why == null) why = unreachable(cat, u.sourceId());
            if (why != null) { refused.add(u.name() + ": " + why); continue; }
            try {
                for (Entry e : store.plan(u)) {
                    boolean held = false;
                    for (Entry x : plan) if (x.id().equals(e.id())) held = true;
                    if (!held) plan.add(e);
                }
            } catch (Resolver.Unresolvable ex) {
                refused.add(u.name() + ": " + ex.getMessage());
            }
        }
        return new UpdatePlan(List.copyOf(plan), List.copyOf(refused));
    }

    private void updateAll() {
        UpdatePlan up = planUpdates();
        List<Entry> plan = up.plan();
        List<String> refused = up.leftOut();
        StringBuilder text = new StringBuilder();
        if (!plan.isEmpty()) text.append(listText("These add-ons are downloaded now and take effect when the engine restarts:", plan));
        if (!refused.isEmpty()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("Left out:\n");
            for (String s : refused) text.append("  ").append(s).append('\n');
        }
        if (plan.isEmpty()) {
            JOptionPane.showMessageDialog(this, Ui.note(text.toString().strip()), "Update all", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!confirm("Update all", text.toString().strip(), "Update all")) return;
        download(plan, "Update all");
    }

    /** What the confirmation says before anything is downloaded: every entry, dependencies first. */
    private String confirmText(Row r, List<Entry> plan) {
        Catalogue cat = store.catalogue();
        StringBuilder b = new StringBuilder();
        if (r.action().equals("Switch source")) {
            Installed in = r.have();
            Entry e = r.best();
            b.append("Switch ").append(e.name()).append(" from ").append(installedFrom(cat, in)).append(' ')
                    .append(in.version())
                    .append(" to ").append(sourceName(cat, e.sourceId())).append(' ').append(e.version()).append('.');
            if (e.version().compareTo(in.version()) < 0) b.append(" This is an older version.");
            b.append("\n\n");
        }
        b.append(listText("These add-ons are downloaded now and take effect when the engine restarts:", plan));
        return b.toString().strip();
    }

    private String listText(String head, List<Entry> plan) {
        Catalogue cat = store.catalogue();
        StringBuilder b = new StringBuilder(head).append('\n');
        for (Entry e : plan) {
            b.append("  ").append(e.name()).append(' ').append(e.version()).append(" (").append(e.status().label())
                    .append("), from ").append(sourceName(cat, e.sourceId()));
            if (e.size() > 0) b.append(", ").append(size(e.size()));
            b.append('\n');
        }
        return b.toString();
    }

    private boolean confirm(String title, String text, String verb) {
        JTextArea note = Ui.note(text);
        Ui.sizeNote(note, 480);
        String[] options = {verb, "Cancel"};
        int c = JOptionPane.showOptionDialog(this, note, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        return c == 0;
    }

    /** Downloads and queues {@code plan} off the EDT, with a progress dialog whose Cancel stops it. */
    private void download(List<Entry> plan, String title) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        JLabel what = Ui.label(plan.size() == 1 ? "Downloading " + plan.get(0).name()
                : "Downloading " + plan.size() + " add-ons");
        JButton cancel = Ui.button("Cancel");
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable stop = () -> {
            cancelled.set(true);
            cancel.setEnabled(false);
            what.setText("Cancelling...");
        };
        cancel.addActionListener(e -> stop.run());
        dlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { stop.run(); }
        });
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(Ui.pad(14, 16, 12, 16));
        p.add(what, BorderLayout.NORTH);
        bar.setPreferredSize(new Dimension(360, bar.getPreferredSize().height));
        p.add(bar, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setOpaque(false);
        south.add(cancel);
        p.add(south, BorderLayout.SOUTH);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);

        // the latest progress, shown at most once per EDT turn however many blocks arrive
        AtomicLong done = new AtomicLong();
        AtomicLong total = new AtomicLong(-1);
        AtomicBoolean shown = new AtomicBoolean(true);
        Thread.ofVirtual().name("addon-store-install").start(() -> {
            String error = null;
            try {
                store.install(plan, (d, t) -> {
                    done.set(d);
                    total.set(t);
                    if (shown.getAndSet(false)) SwingUtilities.invokeLater(() -> {
                        shown.set(true);
                        long tt = total.get();
                        bar.setIndeterminate(tt <= 0);
                        if (tt > 0) bar.setValue((int) Math.min(1000, done.get() * 1000 / tt));
                    });
                }, cancelled::get);
            } catch (InterruptedIOException ex) {
                if (!cancelled.get()) error = why(ex);
            } catch (Exception ex) {
                error = why(ex);
            }
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                dlg.dispose();
                if (failed != null) {
                    JOptionPane.showMessageDialog(this, Ui.note("Nothing was queued: " + failed), title,
                            JOptionPane.ERROR_MESSAGE);
                }
            });
        });
        dlg.setVisible(true);
    }

    /** Fetches every enabled source off the EDT; pressed again while it runs, it cancels before the next source. */
    private void refresh() {
        if (refreshCancel != null) {
            refreshCancel.set(true);
            refresh.setEnabled(false);
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        refreshCancel = cancel;
        refresh.setText("Cancel");
        emptyRefresh.setEnabled(false);
        updateAll.setEnabled(false);
        sourcesLine.setText("Refreshing...");
        Thread.ofVirtual().name("addon-store-refresh").start(() -> {
            String why = null;
            String failed = null;
            try {
                why = openFromEngine(store, engine);
                if (why == null) store.refresh(cancel::get);
            } catch (RuntimeException e) {
                failed = why(e);
            } finally {
                String notOpenNow = why;
                String error = failed;
                SwingUtilities.invokeLater(() -> {
                    refreshCancel = null;
                    refresh.setText("Refresh");
                    refresh.setEnabled(true);
                    emptyRefresh.setEnabled(true);
                    notOpen = notOpenNow;
                    reload();
                    if (error != null) sourcesLine.setText("The refresh failed: " + error);
                });
            }
        });
    }

    // ---- small helpers ----

    /** A feed's docs link, opened only when it is a web address. */
    private static URI webLink(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.strip());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http")) && u.getHost() != null ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void browse(URI u) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(u);
                return;
            }
        } catch (Exception _) {
            // fall through to the clipboard
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(u.toString()), null);
        JOptionPane.showMessageDialog(this, Ui.note("Could not open a browser here. The link was copied:\n" + u),
                "Docs", JOptionPane.INFORMATION_MESSAGE);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String why(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.PLAIN);
        l.setForeground(Theme.MUTED);
        return l;
    }

    private static JTextArea para(String text) {
        JTextArea a = Ui.note(text);
        Ui.sizeNote(a, 640);
        return a;
    }

    private static void add(JPanel col, Component c) {
        if (c instanceof JComponent jc) {
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension p = jc.getPreferredSize();
            jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
        }
        col.add(c);
    }

    private final class Model extends AbstractTableModel {
        private final String[] names = {"Name", "Version", "Status", ""};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.best().name();
                case 1 -> row.best().version().toString();
                case 2 -> row.best().status().label();
                default -> switch (row.action()) {
                    case "Update" -> "update";
                    case "New build" -> "new build";
                    default -> "";
                };
            };
        }
    }
}
