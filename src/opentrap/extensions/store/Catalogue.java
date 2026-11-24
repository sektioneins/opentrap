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
import opentrap.core.Debug;
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
     * Takes {@code sources} as {@link #useSources} does, then fetches every
     * enabled one and scans local folders. A source that fails keeps its
     * previous list and fetch time and gets the error. Stops before the next
     * source once {@code cancelled} is true.
     */
    public void refresh(List<Source> sources, BooleanSupplier cancelled) {
        useSources(sources);
        fetchAll(sources, cancelled);
    }

    /**
     * The fetching half of {@link #refresh}, for sources {@link #useSources}
     * has taken already; it never sets the source list, so a list read before
     * a change cannot replace the changed one. A source changed meanwhile is
     * skipped, and what was fetched for it is dropped.
     */
    public void fetchAll(List<Source> sources, BooleanSupplier cancelled) {
        for (Source s : List.copyOf(sources)) {
            if (cancelled.getAsBoolean()) return;
            if (!s.enabled() || s.type() == SourceType.BUNDLED || !state.sources.contains(s)) continue;
            Slot previous = state.slots.getOrDefault(s.id(), Slot.EMPTY);
            Slot next = s.type() == SourceType.LOCAL_FOLDER ? scan(s, previous) : fetch(s, previous);
            update(old -> old.sources.contains(s) ? old.withSlot(s.id(), next) : old);
        }
    }

    /**
     * Takes the sources after the user added, changed, switched or removed
     * one. Makes no request. A feed whose URL is the same keeps its list, read
     * again from its cache when its name or prefix changed, so the entries
     * carry the new name and are checked against the new prefix. A feed new
     * to the catalogue starts from its cache, which is only read when it was
     * fetched from the feed's URL. One moved to another URL, or turned into a
     * local folder, has no list until the next refresh, and the old URL's
     * cached list is deleted. A local folder is scanned again.
     * Synchronized, so a refresh cannot write a cache file between the
     * deletion and the new list.
     */
    public synchronized void useSources(List<Source> sources) {
        List<Source> list = List.copyOf(sources);
        State st = state;
        Map<String, Source> before = new HashMap<>();
        for (Source s : st.sources) before.put(s.id(), s);
        Map<String, Slot> changed = new HashMap<>();
        for (Source s : list) {
            if (s.type() == SourceType.BUNDLED) continue;
            Source old = before.get(s.id());
            Slot previous = st.slots.getOrDefault(s.id(), Slot.EMPTY);
            boolean samePlace = old != null && old.type() == s.type() && old.location().equals(s.location());
            if (s.type() == SourceType.LOCAL_FOLDER) {
                if (old != null) dropCache(old);
                if (!s.equals(old)) changed.put(s.id(), scan(s, samePlace ? previous : Slot.EMPTY));
            } else if (old == null) {
                changed.put(s.id(), readCache(s));
            } else if (!samePlace) {
                dropCache(old);
                changed.put(s.id(), Slot.EMPTY);
            } else if (!old.name().equals(s.name()) || !old.prefix().equals(s.prefix())) {
                Slot again = readCache(s);
                changed.put(s.id(), again.error() == null && previous.error() != null
                        ? again.failed(previous.error()) : again);
            }
        }
        for (Source old : st.sources) {
            if (list.stream().noneMatch(s -> s.id().equals(old.id()))) dropCache(old);
        }
        // a slot not changed here is taken as it is now, so a refresh that finished meanwhile is kept
        update(old -> {
            Map<String, Slot> slots = new HashMap<>();
            for (Source s : list) {
                Slot slot = changed.containsKey(s.id()) ? changed.get(s.id()) : old.slots.get(s.id());
                if (slot != null) slots.put(s.id(), slot);
            }
            return new State(list, Map.copyOf(slots), old.bundled, old.bundledAt);
        });
    }

    /** Deletes a feed's cached list, which belongs to a URL the source no longer has. */
    private void dropCache(Source s) {
        if (s.type() == SourceType.LOCAL_FOLDER || s.type() == SourceType.BUNDLED) return;
        try {
            Files.deleteIfExists(cacheFile(s));
        } catch (IOException e) {
            Debug.log("the cached list of " + s.name() + " could not be deleted: " + e.getMessage());
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

    /** The sources as last loaded or refreshed, in their order; the bundled source is not among them. */
    public List<Source> sources() { return state.sources; }

    /** The source with this id, if the catalogue knows it. */
    public java.util.Optional<Source> source(String sourceId) {
        for (Source s : state.sources) if (s.id().equals(sourceId)) return java.util.Optional.of(s);
        if (Source.BUNDLED_ID.equals(sourceId) && state.bundled != null) {
            return java.util.Optional.of(new Source(Source.BUNDLED_ID, "Bundled", SourceType.BUNDLED, "", "", true));
        }
        return java.util.Optional.empty();
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
            // with useSources kept out, so a source moved meanwhile in this process never gets its
            // old URL's list cached; the URL in the file covers another instance moving it
            synchronized (this) {
                if (!state.sources.contains(s)) return previous;
                OwnerOnly.write(file, out -> {
                    out.write((now + "\n" + s.location() + "\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                });
            }
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
            // a time line, the URL it was fetched from, then the feed
            int nl = lineEnd(all, 0);
            int nl2 = lineEnd(all, nl + 1);
            Instant fetched = Instant.parse(new String(all, 0, nl, StandardCharsets.UTF_8));
            String from = new String(all, nl + 1, nl2 - nl - 1, StandardCharsets.UTF_8);
            // fetched from another URL, before the source was moved: not this source's list
            if (!from.equals(s.location())) return Slot.EMPTY;
            Listing listing = ZapFeed.parse(
                    new ByteArrayInputStream(all, nl2 + 1, all.length - nl2 - 1), s, engine);
            return new Slot(listing, fetched, null);
        } catch (IOException | RuntimeException e) {
            return Slot.EMPTY.failed("The cached list could not be read: " + why(e));
        }
    }

    /** Where the line starting at {@code from} ends. */
    private static int lineEnd(byte[] all, int from) throws IOException {
        int nl = from;
        while (nl < all.length && all[nl] != '\n') nl++;
        if (nl >= all.length) throw new IOException("The cached list is cut short");
        return nl;
    }

    private Path cacheFile(Source s) throws IOException {
        if (!SOURCE_FILE.matcher(s.id()).matches()) throw new IOException("Refused a source id that is not a plain name");
        return cacheDir.resolve(s.id());
    }

    private static String why(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
