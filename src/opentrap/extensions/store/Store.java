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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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
     * A part file this old belongs to no running download: a download that
     * receives nothing for a minute gives up and deletes its own.
     */
    private static final Duration STALE_PART = Duration.ofMinutes(10);

    private static Store shared;

    /** What {@link #open} set up for one ZAP version and engine install. */
    private record Opened(Version zap, String minor, Path engine, Path minorDir, ZapAddOnKind kind,
                          Catalogue catalogue, List<Source> sources) {}

    private final Path root;
    private final Fetcher fetcher;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Opened opened;
    private volatile Map<String, Installed> installed = Map.of();
    private volatile List<Pending> pending = List.of();
    private volatile List<String> missing = List.of();

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
            o = new Opened(zap, minor, engineInstall, minorDir, new ZapAddOnKind(minorDir), cat, sources);
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

    public Catalogue catalogue() { return opened().catalogue; }

    /** The installed add-ons as last read. */
    public List<Installed> installed() { return List.copyOf(installed.values()); }

    /** The queued changes as last read, oldest first. */
    public List<Pending> pending() { return pending; }

    public int pendingCount() { return pending.size(); }

    /** Bundled add-ons the last engine start dropped because their engine file is gone. */
    public List<String> missing() { return missing; }

    /** Fetches every enabled source; the only call that goes to the network. */
    public void refresh(BooleanSupplier cancelled) {
        Opened o = opened();
        List<Source> sources;
        try {
            sources = SourceList.load(root, o.minor).all();
        } catch (IOException e) {
            Debug.log("add-on sources could not be read, refreshing the ones read at open: " + e.getMessage());
            sources = o.sources;
        }
        o.catalogue.refresh(sources, cancelled);
        fire();
    }

    /** The chosen entry and what it needs, dependencies first. */
    public List<Entry> plan(Entry chosen) throws Resolver.Unresolvable { return resolver().plan(chosen); }

    public List<Entry> updates() { return resolver().updates(); }

    /** Active add-ons that need {@code id}. */
    public List<String> dependents(String id) { return resolver().dependents(id); }

    /** Disabled add-ons that {@code id} needs. */
    public List<String> needs(String id) { return resolver().needs(id); }

    /**
     * Puts every entry's file into {@code staged/} (a download, a copy from a
     * local folder, or a link to the engine's own file) and then queues them
     * all. Nothing is queued when any of them fails.
     */
    public void install(List<Entry> plan, Fetcher.Progress progress, BooleanSupplier cancelled) throws IOException {
        Opened o = opened();
        for (Entry e : plan) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
            stage(o, e, progress, cancelled);
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
        fire();
    }

    public void addListener(Runnable l) { listeners.add(l); }

    /** What {@link ZapEngine} asks before each launch. */
    public ZapEngine.StartHook startHook() { return this::beforeLaunch; }

    /**
     * Brings the folders in line and applies every queued change, then names
     * the folders ZAP is to use. A change that fails stays queued and the rest
     * go on; any other failure is thrown, and the engine then starts without
     * these arguments.
     */
    private List<String> beforeLaunch(Path engineInstall, String zapVersion) throws IOException {
        Version zap = Version.parse(zapVersion);
        List<String> args = new ArrayList<>();
        try (StoreLock _ = StoreLock.acquire(root)) {
            open(zap, engineInstall);
            Opened o = opened();
            ZapAddOnKind kind = o.kind;
            StoreRecord rec = StoreRecord.load(o.minorDir);
            // first, so files the record does not know are taken in before the bundled set is
            kind.reconcile(rec);
            List<String> dropped = Bundled.reconcile(engineInstall, zap, kind, rec);
            if (!dropped.isEmpty()) {
                Debug.log("bundled add-ons whose engine file is gone: " + String.join(", ", dropped));
            }
            missing = List.copyOf(dropped);
            clearStaleParts(kind.stagingDir());
            for (Pending p : List.copyOf(rec.pending())) {
                try {
                    kind.apply(rec, p);
                } catch (IOException | RuntimeException e) {
                    Debug.log("queued " + p.action() + " of " + p.id() + " failed, kept for the next start: " + e);
                }
            }
            kind.reconcile(rec);
            rec.save();
            publish(rec);
            OwnerOnly.directory(kind.enabledDir());
            if (RuntimeInstall.linksSupported()) {
                args.add("-installdir");
                args.add(RuntimeInstall.prepare(engineInstall, root.resolve("runtime")).toString());
            }
            args.add("-config");
            args.add("start.addonDirs=" + kind.enabledDir());
        }
        fire();
        return args;
    }

    /** Removes part files ({@code .<id>.<random>.part}) that a crash left behind. */
    private static void clearStaleParts(Path staged) throws IOException {
        if (!Files.isDirectory(staged)) return;
        Instant before = Instant.now().minus(STALE_PART);
        List<Path> list;
        try (Stream<Path> s = Files.list(staged)) { list = s.toList(); }
        for (Path p : list) {
            String n = p.getFileName().toString();
            if (n.startsWith(".") && n.endsWith(".part") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before)) {
                Files.deleteIfExists(p);
            }
        }
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

    private void fire() {
        for (Runnable l : listeners) {
            try { l.run(); }
            catch (RuntimeException e) { Debug.log("add-on store listener: " + e); }
        }
    }
}
