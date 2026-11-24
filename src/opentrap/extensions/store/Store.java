package opentrap.extensions.store;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import opentrap.core.Debug;
import opentrap.core.OwnerOnly;
import opentrap.engine.ZapEngine;

/**
 * The add-on store as the UI and the engine use it: what is installed, what
 * is queued, what the sources offer. Changes are only queued here; the engine
 * applies them through {@link #startHook()} before ZAP starts.
 *
 * <p>Every action that changes files takes the {@link StoreLock}, loads the
 * record, changes it, saves it and releases the lock, and only then tells the
 * listeners, so a listener may call back into the store.
 */
public final class Store {

    /**
     * A part file or unqueued staged file this old belongs to no running
     * download: a download that receives nothing for a minute gives up and
     * deletes its own, and a finished one is queued at once.
     */
    private static final Duration STALE = Duration.ofMinutes(10);

    /** In a minor's folder once the previous minor's add-ons were offered for it. */
    private static final String CARRYOVER_MARK = "carryover-offered";

    private static Store shared;

    /** What {@link #open} set up for one ZAP version and engine install. */
    private record Opened(Version zap, String minor, Path engine, Path minorDir, ZapAddOnKind kind,
                          Catalogue catalogue) {}

    private final Path root;
    private final Fetcher fetcher;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Opened opened;
    private volatile Map<String, Installed> installed = Map.of();
    private volatile List<Pending> pending = List.of();
    private volatile List<String> missing = List.of();
    private volatile String lastStartError;
    private volatile Map<String, String> applyErrors = Map.of();
    private volatile boolean reflectsEngine;

    public Store(Path root, Fetcher fetcher) {
        this.root = root;
        this.fetcher = fetcher;
    }

    /** Sets up the store of {@code ~/.opentrap/addons} once; the first call wins. */
    public static synchronized void initShared(Fetcher f) {
        if (shared == null) shared = new Store(Path.of(System.getProperty("user.home"), ".opentrap", "addons"), f);
    }

    /** The store of {@code ~/.opentrap/addons}; {@link #initShared} must have been called. */
    public static synchronized Store shared() {
        if (shared == null) throw new IllegalStateException("The add-on store is not set up yet");
        return shared;
    }

    /** The store of {@code ~/.opentrap/addons}, or null before {@link #initShared}, as in tests. */
    public static synchronized Store sharedIfSet() { return shared; }

    /**
     * Reads the sources, the cached lists and the record for this ZAP version.
     * Makes no request. Opening the same version and install again keeps the
     * catalogue, so a refresh in progress is not lost.
     */
    public synchronized void open(Version zap, Path engineInstall) throws IOException {
        Opened o = opened;
        if (o == null || !o.zap.equals(zap) || !o.engine.equals(engineInstall)) {
            String minor = zap.part(0) + "." + zap.part(1);
            Path minorDir = root.resolve(minor);
            List<Source> sources = SourceList.load(root, minor).all();
            Catalogue cat = new Catalogue(minorDir, zap, fetcher);
            // before the catalogue is published, so no refresh can run on it yet
            cat.loadCache(sources);
            o = new Opened(zap, minor, engineInstall, minorDir, new ZapAddOnKind(minorDir), cat);
        }
        o.catalogue.setBundled(Bundled.listing(engineInstall, zap));
        opened = o;
        publish(StoreRecord.load(o.minorDir));
    }

    private Opened opened() {
        Opened o = opened;
        if (o == null) throw new IllegalStateException("The add-on store is not open");
        return o;
    }

    /** Whether {@link #open} has run, so the catalogue and the actions can be used. */
    public boolean isOpen() { return opened != null; }

    public Catalogue catalogue() { return opened().catalogue; }

    /** The installed add-ons as last read. */
    public List<Installed> installed() { return List.copyOf(installed.values()); }

    /** The queued changes as last read, oldest first. */
    public List<Pending> pending() { return pending; }

    public int pendingCount() { return pending.size(); }

    /**
     * Whether an engine start in this JVM has brought the record in line with
     * the engine's add-ons. Before that the record may still lack the bundled
     * add-ons (it is empty before the first start), so it cannot say what the
     * running engine lacks.
     */
    public boolean reflectsEngine() { return reflectsEngine; }

    /**
     * The add-ons {@code f} needs that the engine does not run, by the record
     * of the last engine start. None while the record does not reflect the
     * engine yet: a check then lets the feature start.
     */
    public List<String> notActive(CoreAddOns.Feature f) {
        if (!reflectsEngine) return List.of();
        return CoreAddOns.missing(f, installed);
    }

    /** What {@code f} says where it is started when {@link #notActive} names add-ons, or null. */
    public String notActiveMessage(CoreAddOns.Feature f) {
        List<String> ids = notActive(f);
        return ids.isEmpty() ? null : CoreAddOns.missingMessage(f, ids);
    }

    /** Bundled add-ons the last engine start dropped because their engine file is gone. */
    public List<String> missing() { return missing; }

    /** Fetches every enabled source; the only call that goes to the network. */
    public void refresh(BooleanSupplier cancelled) {
        Opened o = opened();
        List<Source> sources;
        // read and taken under the lock, so this list never replaces one a later change saved
        try (StoreLock _ = StoreLock.acquire(root)) {
            sources = SourceList.load(root, o.minor).all();
            o.catalogue.useSources(sources);
        } catch (IOException e) {
            Debug.log("add-on sources could not be read, refreshing the ones last read: " + e.getMessage());
            sources = o.catalogue.sources();
        }
        o.catalogue.fetchAll(sources, cancelled);
        fire();
    }

    /**
     * Adds {@code s} under the next free source id and returns it as added.
     * Like every change to the sources it makes no request: the catalogue
     * takes the new list from its cache and the local folders.
     */
    public Source addSource(Source s) throws IOException {
        AtomicReference<Source> added = new AtomicReference<>();
        changeSources(l -> {
            Source a = new Source(l.nextId(), s.name(), s.type(), s.location(), s.prefix(), s.enabled());
            l.add(a);
            added.set(a);
        });
        return added.get();
    }

    /**
     * Replaces the source {@code expected} with {@code next}, which has its
     * id; of a built-in source only {@code enabled} may change. Refused with
     * {@link ChangedElsewhere} when the saved source is no longer
     * {@code expected}, as when another OpenTRAP instance changed it since it
     * was listed; the catalogue then takes the sources as saved.
     */
    public void updateSource(Source expected, Source next) throws IOException {
        changeSources(l -> {
            Source saved = null;
            for (Source s : l.all()) if (s.id().equals(next.id())) saved = s;
            if (!expected.equals(saved)) throw new ChangedElsewhere(expected.name());
            l.update(next);
        });
    }

    /** A source changed since the caller listed it. */
    public static final class ChangedElsewhere extends IllegalStateException {
        ChangedElsewhere(String name) {
            super(name + " was changed elsewhere since it was listed. The list now shows it as saved; edit it again.");
        }
    }

    /** Switches a source on or off as saved now, built-in ones too; nothing else of it changes. */
    public void enableSource(String id, boolean on) throws IOException {
        changeSources(l -> {
            for (Source s : l.all()) {
                if (s.id().equals(id)) l.update(new Source(s.id(), s.name(), s.type(), s.location(), s.prefix(), on));
            }
        });
    }

    /** Removes a source the user added. Its installed add-ons stay installed. */
    public void removeSource(String id) throws IOException { changeSources(l -> l.remove(id)); }

    private void changeSources(Consumer<SourceList> change) throws IOException {
        Opened o = opened();
        RuntimeException refused = null;
        try (StoreLock _ = StoreLock.acquire(root)) {
            SourceList l = SourceList.load(root, o.minor);
            try {
                change.accept(l);
                l.save();
            } catch (RuntimeException e) {
                // nothing is saved; the catalogue still learns what is, which may be another instance's change
                refused = e;
                l = SourceList.load(root, o.minor);
            }
            // under the lock, so two changes reach the catalogue in the order they were saved
            o.catalogue.useSources(l.all());
        }
        fire();
        if (refused != null) throw refused;
    }

    /** The chosen entry and what it needs, dependencies first. */
    public List<Entry> plan(Entry chosen) throws Resolver.Unresolvable { return resolver().plan(chosen); }

    public List<Entry> updates() { return resolver().updates(); }

    /** Active add-ons that need {@code id}. */
    public List<String> dependents(String id) { return resolver().dependents(id); }

    /** Disabled add-ons that {@code id} needs. */
    public List<String> needs(String id) { return resolver().needs(id); }

    /** Add-ons that {@code id} needs and that are not installed at all. */
    public List<String> notInstalledNeeds(String id) { return resolver().notInstalledNeeds(id); }

    /** The ZAP minor version the store is open for, as "2.17". */
    public String minor() { return opened().minor; }

    /**
     * The previous ZAP minor's add-ons that were not bundled with ZAP and are
     * neither installed nor queued here, until {@link #carryoverOffered} has
     * marked this minor; none after that, so they are offered once.
     */
    public List<Installed> carryover() throws IOException {
        Opened o = opened();
        if (Files.exists(o.minorDir.resolve(CARRYOVER_MARK), LinkOption.NOFOLLOW_LINKS)) return List.of();
        Set<String> here = new HashSet<>(installed.keySet());
        for (Pending p : pending) here.add(p.id());
        return Carryover.fromPrevious(root, o.minor).stream().filter(in -> !here.contains(in.id())).toList();
    }

    /** Marks this minor's carry-over as offered, whatever the user answered. */
    public void carryoverOffered() throws IOException {
        OwnerOnly.write(opened().minorDir.resolve(CARRYOVER_MARK),
                out -> out.write((Instant.now() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** What this ZAP version offers in place of an add-on of the previous one, or null. */
    public Entry successor(Installed previous) { return resolver().successor(previous); }

    /**
     * Puts every entry's file into {@code staged/} (a download, a copy from a
     * local folder, or a link to the engine's own file) and then queues them
     * all. Nothing is queued when any of them fails.
     *
     * <p>{@code cancelled} is only asked on the calling thread: before each
     * entry and at each block a download receives. The download's own watch
     * thread reads a flag that those asks set, so the supplier need not be
     * thread-safe. A download that receives nothing gives up after its stall
     * time.
     */
    public void install(List<Entry> plan, Fetcher.Progress progress, BooleanSupplier cancelled) throws IOException {
        Opened o = opened();
        AtomicBoolean stop = new AtomicBoolean();
        Fetcher.Progress ticked = (done, total) -> {
            if (cancelled.getAsBoolean()) stop.set(true);
            progress.bytes(done, total);
        };
        for (Entry e : plan) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
            stage(o, e, ticked, stop::get);
        }
        try (StoreLock _ = StoreLock.acquire(root)) {
            StoreRecord rec = StoreRecord.load(o.minorDir);
            for (Entry e : plan) {
                Installed in = rec.installed().get(e.id());
                Pending.Action a = in == null ? Pending.Action.INSTALL
                        : Resolver.sameLine(in.sourceId(), e.sourceId()) ? Pending.Action.UPDATE
                        : Pending.Action.SWITCH;
                rec.pending().add(new Pending(a, e.id(), e, e.file()));
            }
            rec.save();
            publish(rec);
        }
        fire();
    }

    private void stage(Opened o, Entry e, Fetcher.Progress progress, BooleanSupplier cancelled) throws IOException {
        Path dir = o.kind.stagingDir();
        Source src = o.catalogue.source(e.sourceId())
                .orElseThrow(() -> new IOException("No source " + e.sourceId() + " for " + e.id()));
        switch (src.type()) {
            case ZAP_FEED, FEED_URL -> fetcher.download(e, dir, progress, cancelled);
            case LOCAL_FOLDER -> copy(e, dir, progress);
            case BUNDLED -> link(e, dir);
        }
    }

    /** A local file is copied, so a later rebuild in its folder does not change what was installed. */
    private static void copy(Entry e, Path dir, Fetcher.Progress progress) throws IOException {
        OwnerOnly.directory(dir);
        Path part = dir.resolve("." + e.id() + "." + UUID.randomUUID() + ".part");
        try {
            OwnerOnly.write(part, out -> Files.copy(Path.of(e.location()), out));
            if (!ZapAddOnFile.sha256(part).equals(e.sha256())) {
                throw new IOException(e.file() + " has changed since its folder was read");
            }
            // also accepts e.file() as <id>-<status>-<version>.zap, a plain name directly in dir
            ZapAddOnFile.check(part, e.file(), e);
            Files.move(part, dir.resolve(e.file()), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        progress.bytes(e.size(), e.size());
    }

    /** A bundled add-on is never copied: the staged file is a link to the engine's own. */
    private static void link(Entry e, Path dir) throws IOException {
        ZapAddOnFile.check(Path.of(e.location()), e.file(), e);
        OwnerOnly.directory(dir);
        Path link = dir.resolve(e.file());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of(e.location()));
    }

    public void enable(String id) throws IOException { queue(new Pending(Pending.Action.ENABLE, id, null, null)); }

    public void disable(String id) throws IOException { queue(new Pending(Pending.Action.DISABLE, id, null, null)); }

    public void remove(String id) throws IOException { queue(new Pending(Pending.Action.REMOVE, id, null, null)); }

    private void queue(Pending p) throws IOException {
        Opened o = opened();
        try (StoreLock _ = StoreLock.acquire(root)) {
            StoreRecord rec = StoreRecord.load(o.minorDir);
            rec.pending().add(p);
            rec.save();
            publish(rec);
        }
        fire();
    }

    /** Drops every queued change of {@code id}, and the staged files no other queued change needs. */
    public void undo(String id) throws IOException {
        Opened o = opened();
        try (StoreLock _ = StoreLock.acquire(root)) {
            StoreRecord rec = StoreRecord.load(o.minorDir);
            List<Pending> dropped = rec.pending().stream().filter(p -> p.id().equals(id)).toList();
            rec.pending().removeAll(dropped);
            // saved first: a crash before the files go leaves a stray file, never a change without one
            rec.save();
            Set<String> kept = new HashSet<>();
            for (Pending p : rec.pending()) if (p.stagedFile() != null) kept.add(p.stagedFile());
            for (Pending p : dropped) {
                String f = p.stagedFile();
                if (f != null && !kept.contains(f) && plainName(f)) {
                    Files.deleteIfExists(o.kind.stagingDir().resolve(f));
                }
            }
            publish(rec);
        }
        Map<String, String> errs = new LinkedHashMap<>(applyErrors);
        if (errs.remove(id) != null) applyErrors = Collections.unmodifiableMap(errs);
        fire();
    }

    /**
     * Hear every change. After an action on this store a listener runs on the
     * thread that called it, once the lock is released. After an engine start
     * it runs on a thread of its own, so it can never hold up or call back into
     * the engine's start.
     */
    public void addListener(Runnable l) { listeners.add(l); }

    /** Stops telling {@code l}, for a view that is closed while the store lives on. */
    public void removeListener(Runnable l) { listeners.remove(l); }

    /**
     * Why the last engine start could not use the store as it should, or null
     * after a clean start. ZAP was still given {@code enabled/} unless the ZAP
     * version could not be read.
     */
    public String lastStartError() { return lastStartError; }

    /** Queued changes that failed at the last engine start, by add-on id, with the reason. They stay queued. */
    public Map<String, String> applyErrors() { return applyErrors; }

    /** Calls {@code c} each time {@link #lastStartError()} turns to a new failure. */
    public void onStartError(Consumer<String> c) {
        AtomicReference<String> last = new AtomicReference<>();
        addListener(() -> {
            String now = lastStartError;
            String before = last.getAndSet(now);
            if (now != null && !now.equals(before)) c.accept(now);
        });
    }

    /** What {@link ZapEngine} asks before each launch. */
    public ZapEngine.StartHook startHook() { return this::beforeLaunch; }

    /**
     * Brings the folders in line and applies every queued change, then names
     * the folders ZAP is to use. A step that fails is kept in
     * {@link #lastStartError()} and the others still run: a queued change that
     * fails stays queued, and ZAP is always given {@code enabled/}. It runs
     * from the linked install only once the bundled add-ons are in the store's
     * folders and the install is prepared; otherwise it runs from the engine's
     * own install, whose {@code plugin/} it then loads as well. Only a ZAP
     * version that cannot be read, so that no folder is known, is thrown, and
     * the engine starts without these arguments.
     */
    private List<String> beforeLaunch(Path engineInstall, String zapVersion) throws IOException {
        // Set again only once this start has brought the record in line: a
        // start that cannot do so runs an engine the old record may not match.
        reflectsEngine = false;
        Version zap;
        try {
            zap = Version.parse(zapVersion);
        } catch (IllegalArgumentException e) {
            String why = "The add-on store was not used: ZAP version \"" + zapVersion + "\" cannot be read";
            lastStartError = why;
            fireApart();
            throw new IOException(why, e);
        }
        Path minorDir = root.resolve(zap.part(0) + "." + zap.part(1));
        ZapAddOnKind kind = new ZapAddOnKind(minorDir);
        List<String> errors = new ArrayList<>();
        Path runtime = null;
        try (StoreLock _ = StoreLock.acquire(root)) {
            try {
                open(zap, engineInstall);
            } catch (IOException | RuntimeException e) {
                errors.add("the store could not be opened: " + why(e));
            }
            boolean bundledInPlace = false;
            try {
                StoreRecord rec = StoreRecord.load(minorDir);
                // first, so files the record does not know are taken in before the bundled set is
                kind.reconcile(rec);
                List<String> dropped = Bundled.reconcile(engineInstall, zap, kind, rec);
                bundledInPlace = true;
                if (!dropped.isEmpty()) {
                    Debug.log("bundled add-ons whose engine file is gone: " + String.join(", ", dropped));
                }
                missing = List.copyOf(dropped);
                Map<String, String> failed = new LinkedHashMap<>();
                for (Pending p : List.copyOf(rec.pending())) {
                    try {
                        kind.apply(rec, p);
                    } catch (IOException | RuntimeException e) {
                        failed.put(p.id(), p.action().name().toLowerCase(Locale.ROOT) + ": " + why(e));
                    }
                }
                applyErrors = Collections.unmodifiableMap(failed);
                if (!failed.isEmpty()) {
                    errors.add("queued changes failed and stay queued: " + String.join(", ", failed.keySet()));
                }
                kind.reconcile(rec);
                rec.save();
                publish(rec);
                reflectsEngine = true;
                clearStale(kind.stagingDir(), rec);
                OwnerOnly.directory(kind.enabledDir());
            } catch (IOException | RuntimeException e) {
                errors.add("the add-on folders could not be brought in line: " + why(e));
            }
            if (bundledInPlace && RuntimeInstall.linksSupported()) {
                try {
                    runtime = RuntimeInstall.prepare(engineInstall, root.resolve("runtime"));
                } catch (IOException | RuntimeException e) {
                    errors.add("the linked engine install could not be prepared: " + why(e));
                }
            }
        } catch (IOException | RuntimeException e) {
            errors.add("the store could not be locked: " + why(e));
        }
        String error = errors.isEmpty() ? null : "Add-on store at engine start: " + String.join("; ", errors);
        if (error != null) Debug.log(error);
        lastStartError = error;
        List<String> args = new ArrayList<>();
        if (runtime != null) {
            args.add("-installdir");
            args.add(runtime.toString());
        }
        args.add("-config");
        args.add("start.addonDirs=" + kind.enabledDir());
        fireApart();
        return args;
    }

    /**
     * Removes what no one will use, once it is older than {@link #STALE}:
     * part files and their temp files ({@code .<name>.part}, {@code .<name>.tmp})
     * a crash left behind, and staged files no queued change names. A younger
     * one may belong to a download or install still on its way, in this or
     * another instance.
     */
    private static void clearStale(Path staged, StoreRecord rec) throws IOException {
        if (!Files.isDirectory(staged)) return;
        Set<String> named = new HashSet<>();
        for (Pending p : rec.pending()) if (p.stagedFile() != null) named.add(p.stagedFile());
        Instant before = Instant.now().minus(STALE);
        List<Path> list;
        try (Stream<Path> s = Files.list(staged)) { list = s.toList(); }
        for (Path p : list) {
            String n = p.getFileName().toString();
            boolean leftover = n.startsWith(".") ? n.endsWith(".part") || n.endsWith(".tmp") : !named.contains(n);
            boolean fileOrLink = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p);
            if (leftover && fileOrLink
                    && Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before)) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String why(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private Resolver resolver() {
        Opened o = opened();
        Map<String, Installed> in = installed;
        Resolver byOffer = new Resolver(o.catalogue, in);
        return new Resolver(o.catalogue, in, i -> dependenciesOf(o.kind, byOffer, i));
    }

    /** From the installed file itself, else from the catalogue's matching offer. */
    private static List<Dependency> dependenciesOf(ZapAddOnKind kind, Resolver byOffer, Installed in) {
        if (in.file() != null && plainName(in.file())) {
            for (Path dir : new Path[] {kind.enabledDir(), kind.disabledDir()}) {
                Path f = dir.resolve(in.file());
                if (!Files.exists(f)) continue;
                try {
                    return ZapAddOnFile.read(f).dependencies();
                } catch (IOException e) {
                    Debug.log("dependencies of " + in.id() + " read from its offer, its file is unreadable: " + e);
                    break;
                }
            }
        }
        return byOffer.offeredDependencies(in);
    }

    /** A file name directly in a folder: no separator, no parent step, not hidden. */
    private static boolean plainName(String name) {
        return !name.isEmpty() && !name.startsWith(".") && Path.of(name).getFileName().toString().equals(name);
    }

    private void publish(StoreRecord rec) {
        installed = Collections.unmodifiableMap(new LinkedHashMap<>(rec.installed()));
        pending = List.copyOf(rec.pending());
    }

    /** {@link #fire()} on a thread of its own. */
    private void fireApart() {
        Thread.ofVirtual().name("addon-store-listeners").start(this::fire);
    }

    private void fire() {
        for (Runnable l : listeners) {
            try { l.run(); }
            catch (RuntimeException e) { Debug.log("add-on store listener: " + e); }
        }
    }
}
