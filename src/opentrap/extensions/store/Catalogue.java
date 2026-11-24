package opentrap.extensions.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import opentrap.core.OwnerOnly;

/**
 * What every source offers. Feed lists are cached under {@code minorDir/cache},
 * so the store works offline and opening it makes no request; only
 * {@link #refresh} goes to the network. Local folders are read from disk each
 * time and have no cache file. Each change is published as an immutable
 * snapshot, so the UI thread can read while a refresh runs on another.
 */
public final class Catalogue {

    private static final Pattern SOURCE_FILE = Pattern.compile("[A-Za-z0-9_-]+");

    /** One source's last good list and how its last try went. */
    private record Slot(Listing listing, Instant fetched, String error) {
        static final Slot EMPTY = new Slot(new Listing(List.of(), List.of()), null, null);

        Slot failed(String why) { return new Slot(listing, fetched, why); }

        SourceStatus status() { return new SourceStatus(fetched, error, listing.rejected()); }
    }

    private record State(List<Source> sources, Map<String, Slot> slots, Listing bundled, Instant bundledAt) {
        static final State EMPTY = new State(List.of(), Map.of(), null, null);

        State withSlot(String id, Slot slot) {
            Map<String, Slot> m = new HashMap<>(slots);
            m.put(id, slot);
            return new State(sources, Map.copyOf(m), bundled, bundledAt);
        }
    }

    private final Path cacheDir;
    private final Version engine;
    private final Fetcher fetcher;
    private volatile State state = State.EMPTY;

    public Catalogue(Path minorDir, Version engine, Fetcher fetcher) {
        this.cacheDir = minorDir.resolve("cache");
        this.engine = engine;
        this.fetcher = fetcher;
    }

    /** Reads each source's cached list, and scans local folders. Makes no request. */
    public void loadCache(List<Source> sources) {
        List<Source> list = List.copyOf(sources);
        Map<String, Slot> slots = new HashMap<>();
        for (Source s : list) {
            if (s.type() == SourceType.BUNDLED) continue;
            slots.put(s.id(), s.type() == SourceType.LOCAL_FOLDER ? scan(s, Slot.EMPTY) : readCache(s));
        }
        update(old -> new State(list, Map.copyOf(slots), old.bundled, old.bundledAt));
    }

    /**
     * Fetches every enabled source and scans local folders. A source that fails
     * keeps its previous list and fetch time and gets the error. Stops before the
     * next source once {@code cancelled} is true.
     */
    public void refresh(List<Source> sources, BooleanSupplier cancelled) {
        List<Source> list = List.copyOf(sources);
        update(old -> {
            Map<String, Slot> kept = new HashMap<>();
            for (Source s : list) if (old.slots.containsKey(s.id())) kept.put(s.id(), old.slots.get(s.id()));
            return new State(list, Map.copyOf(kept), old.bundled, old.bundledAt);
        });
        for (Source s : list) {
            if (cancelled.getAsBoolean()) return;
            if (!s.enabled() || s.type() == SourceType.BUNDLED) continue;
            Slot previous = state.slots.getOrDefault(s.id(), Slot.EMPTY);
            Slot next = s.type() == SourceType.LOCAL_FOLDER ? scan(s, previous) : fetch(s, previous);
            update(old -> old.sources.contains(s) ? old.withSlot(s.id(), next) : old);
        }
    }

    /** The add-ons shipped with the engine; offered as a source of its own. */
    public void setBundled(Listing bundled) {
        Instant now = Instant.now();
        update(old -> new State(old.sources, old.slots, bundled, now));
    }

    /** Every enabled source's entry for {@code id}, newest version first; equal versions keep source order, bundled last. */
    public List<Entry> offers(String id) {
        State st = state;
        List<Entry> out = new ArrayList<>();
        for (Source s : st.sources) {
            Slot slot = st.slots.get(s.id());
            if (s.enabled() && slot != null) addMatching(out, slot.listing, id);
        }
        if (st.bundled != null) addMatching(out, st.bundled, id);
        out.sort((a, b) -> b.version().compareTo(a.version()));
        return List.copyOf(out);
    }

    /** Every add-on id some enabled source offers. */
    public Set<String> ids() {
        State st = state;
        Set<String> out = new LinkedHashSet<>();
        for (Source s : st.sources) {
            Slot slot = st.slots.get(s.id());
            if (s.enabled() && slot != null) for (Entry e : slot.listing.entries()) out.add(e.id());
        }
        if (st.bundled != null) for (Entry e : st.bundled.entries()) out.add(e.id());
        return Set.copyOf(out);
    }

    /** The source's status; never fetched, no error for a source the catalogue does not know. */
    public SourceStatus status(String sourceId) {
        State st = state;
        if (Source.BUNDLED_ID.equals(sourceId) && st.bundled != null) {
            return new SourceStatus(st.bundledAt, null, st.bundled.rejected());
        }
        return st.slots.getOrDefault(sourceId, Slot.EMPTY).status();
    }

    private static void addMatching(List<Entry> out, Listing l, String id) {
        for (Entry e : l.entries()) if (e.id().equals(id)) out.add(e);
    }

    private synchronized void update(UnaryOperator<State> change) {
        state = change.apply(state);
    }

    private Slot scan(Source s, Slot previous) {
        try {
            Path folder = Path.of(s.location());
            if (!Files.isDirectory(folder)) throw new IOException("Folder not found: " + folder);
            return new Slot(ZapAddOnFile.scanFolder(folder, s), Instant.now(), null);
        } catch (IOException | RuntimeException e) {
            return previous.failed(why(e));
        }
    }

    private Slot fetch(Source s, Slot previous) {
        try {
            Path file = cacheFile(s);
            byte[] body = fetcher.fetch(s.location(), Fetcher.FEED_CAP);
            Listing listing = ZapFeed.parse(new ByteArrayInputStream(body), s, engine);
            Instant now = Instant.now();
            OwnerOnly.write(file, out -> {
                out.write((now + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(body);
            });
            return new Slot(listing, now, null);
        } catch (IOException | RuntimeException e) {
            return previous.failed(why(e));
        }
    }

    private Slot readCache(Source s) {
        try {
            Path file = cacheFile(s);
            byte[] all;
            try {
                all = Files.readAllBytes(file);
            } catch (NoSuchFileException e) {
                return Slot.EMPTY;
            }
            int nl = 0;
            while (nl < all.length && all[nl] != '\n') nl++;
            if (nl == all.length) throw new IOException("The cached list is cut short");
            Instant fetched = Instant.parse(new String(all, 0, nl, StandardCharsets.UTF_8));
            Listing listing = ZapFeed.parse(
                    new ByteArrayInputStream(all, nl + 1, all.length - nl - 1), s, engine);
            return new Slot(listing, fetched, null);
        } catch (IOException | RuntimeException e) {
            return Slot.EMPTY.failed("The cached list could not be read: " + why(e));
        }
    }

    private Path cacheFile(Source s) throws IOException {
        if (!SOURCE_FILE.matcher(s.id()).matches()) throw new IOException("Refused a source id that is not a plain name");
        return cacheDir.resolve(s.id());
    }

    private static String why(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
