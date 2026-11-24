package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The add-ons in an engine install's {@code plugin/}, taken in as store
 * add-ons. They are never copied: each is a link in {@code enabled/} or
 * {@code disabled/} named after the engine file.
 */
public final class Bundled {

    private Bundled() {}

    /** What the record says a bundled add-on came with. */
    static String origin(Version zap) { return "bundled with ZAP " + zap; }

    /** The engine's own add-ons as the built-in "bundled" source lists them. */
    public static Listing listing(Path engineInstall, Version zap) throws IOException {
        Path plugin = engineInstall.resolve("plugin");
        if (!Files.isDirectory(plugin)) return new Listing(List.of(), List.of());
        Source src = new Source(Source.BUNDLED_ID, "Bundled with ZAP " + zap, SourceType.BUNDLED,
                plugin.toAbsolutePath().toString(), "", true);
        return ZapAddOnFile.scanFolder(plugin, src);
    }

    /**
     * Takes the engine's add-ons in (once per engine install and {@code plugin/}
     * content) and always removes links whose target is gone. Returns the ids of
     * bundled add-ons dropped because their engine file is gone. Changes the
     * record in memory; the caller saves it.
     */
    public static List<String> reconcile(Path engineInstall, Version zap, ZapAddOnKind kind, StoreRecord rec)
            throws IOException {
        // where each bundled add-on is, before its link may go: a renamed engine file keeps that state
        Map<String, Boolean> wasActive = new LinkedHashMap<>();
        for (Installed in : rec.installed().values()) {
            if (!Source.BUNDLED_ID.equals(in.sourceId())) continue;
            if (!ZapAddOnKind.files(kind.disabledDir(), in.id()).isEmpty()) wasActive.put(in.id(), false);
            else if (!ZapAddOnKind.files(kind.enabledDir(), in.id()).isEmpty()) wasActive.put(in.id(), true);
        }
        for (Path dir : new Path[] {kind.enabledDir(), kind.disabledDir()}) removeDangling(dir);
        Map<String, Boolean> gone = new LinkedHashMap<>();
        for (Installed in : List.copyOf(rec.installed().values())) {
            if (Source.BUNDLED_ID.equals(in.sourceId()) && ZapAddOnKind.files(kind.enabledDir(), in.id()).isEmpty()
                    && ZapAddOnKind.files(kind.disabledDir(), in.id()).isEmpty()) {
                rec.installed().remove(in.id());
                gone.put(in.id(), wasActive.getOrDefault(in.id(), in.active()));
            }
        }
        Listing listing = listing(engineInstall, zap);
        List<String> files = listing.entries().stream().map(Entry::file).sorted().toList();
        String seen = RuntimeInstall.key(engineInstall) + "-" + RuntimeInstall.hash16(String.join("\n", files));
        if (!rec.seenEngines().contains(seen)) {
            Map<String, Entry> best = new LinkedHashMap<>();
            for (Entry e : listing.entries()) {
                Entry old = best.get(e.id());
                if (old == null || old.version().compareTo(e.version()) < 0) best.put(e.id(), e);
            }
            for (Entry e : best.values()) take(e, zap, kind, rec, gone.get(e.id()));
            rec.seenEngines().add(seen);
        }
        // only what ends the call without a file: a renamed engine file was linked again above
        return gone.keySet().stream().filter(id -> !rec.installed().containsKey(id)).toList();
    }

    /** {@code wasActive} is the state of a bundled add-on whose old link was just removed, else null. */
    private static void take(Entry e, Version zap, ZapAddOnKind kind, StoreRecord rec, Boolean wasActive)
            throws IOException {
        Installed held = rec.installed().get(e.id());
        boolean active;
        if (held == null) {
            if (rec.removedBundled().contains(e.id())) return;
            active = wasActive == null || wasActive;
        } else {
            boolean ours = Source.ZAP_ID.equals(held.sourceId()) || Source.BUNDLED_ID.equals(held.sourceId());
            if (!ours || held.version().compareTo(e.version()) >= 0) return;
            // where the file is wins; the record decides only when there is none
            if (!ZapAddOnKind.files(kind.disabledDir(), e.id()).isEmpty()) active = false;
            else if (!ZapAddOnKind.files(kind.enabledDir(), e.id()).isEmpty()) active = true;
            else active = held.active();
        }
        Path dir = active ? kind.enabledDir() : kind.disabledDir();
        Path link = dir.resolve(e.file());
        // a same-named entry the record does not know is taken as it is
        boolean there = held == null && Files.exists(link, LinkOption.NOFOLLOW_LINKS);
        if (!there) {
            kind.deleteIdFiles(e.id(), null);
            Files.createDirectories(dir);
            Files.createSymbolicLink(link, Path.of(e.location()));
        }
        rec.installed().put(e.id(), new Installed(Kind.ZAP_ADDON, e.id(), e.version(), e.file(), Source.BUNDLED_ID,
                origin(zap), active, e.sha256()));
    }

    /** Deletes links whose target does not exist; never follows a link to delete, never touches a target. */
    private static void removeDangling(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> list;
        try (Stream<Path> s = Files.list(dir)) { list = s.toList(); }
        for (Path p : list) {
            if (Files.isSymbolicLink(p) && !Files.exists(p)) Files.delete(p);
        }
    }
}
