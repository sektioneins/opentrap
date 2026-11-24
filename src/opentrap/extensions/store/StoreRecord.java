package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What the store has installed and what is queued for the next engine start,
 * kept in {@code minorDir/store} (one per ZAP minor version). Change it only
 * while holding a {@link StoreLock}: acquire, load, change, save, release.
 */
public final class StoreRecord {

    private final Path file;
    private final Map<String, Installed> installed = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Set<String> removedBundled = new LinkedHashSet<>();
    private final Set<String> seenEngines = new LinkedHashSet<>();

    private StoreRecord(Path file) { this.file = file; }

    /** A missing file is an empty record. */
    public static StoreRecord load(Path minorDir) throws IOException {
        StoreRecord r = new StoreRecord(minorDir.resolve("store"));
        Properties p = PropsFile.load(r.file);
        for (int i = 0, n = count(p, "installed"); i < n; i++) {
            String k = "installed." + i + ".";
            Installed in = new Installed(Kind.valueOf(p.getProperty(k + "kind")), p.getProperty(k + "id"),
                    Version.parse(p.getProperty(k + "version")), p.getProperty(k + "file"),
                    p.getProperty(k + "sourceId"), p.getProperty(k + "origin"),
                    PropsFile.bool(p, k + "active", true), p.getProperty(k + "sha256"));
            r.installed.put(in.id(), in);
        }
        for (int i = 0, n = count(p, "pending"); i < n; i++) {
            String k = "pending." + i + ".";
            Entry target = p.containsKey(k + "target.id") ? readEntry(p, k + "target.") : null;
            r.pending.add(new Pending(Pending.Action.valueOf(p.getProperty(k + "action")), p.getProperty(k + "id"),
                    target, p.getProperty(k + "stagedFile")));
        }
        for (int i = 0, n = count(p, "removedBundled"); i < n; i++) r.removedBundled.add(p.getProperty("removedBundled." + i));
        for (int i = 0, n = count(p, "seenEngines"); i < n; i++) r.seenEngines.add(p.getProperty("seenEngines." + i));
        return r;
    }

    /** The installed add-ons by id. */
    public Map<String, Installed> installed() { return installed; }

    /** Changes waiting for the next engine start, oldest first. */
    public List<Pending> pending() { return pending; }

    /** Ids of bundled add-ons the user has removed, so a later start does not bring them back. */
    public Set<String> removedBundled() { return removedBundled; }

    /** Engine versions whose bundled add-ons have already been taken in. */
    public Set<String> seenEngines() { return seenEngines; }

    public void save() throws IOException {
        Properties p = new Properties();
        int i = 0;
        for (Installed in : installed.values()) {
            String k = "installed." + i++ + ".";
            p.setProperty(k + "kind", in.kind().name());
            p.setProperty(k + "id", in.id());
            p.setProperty(k + "version", in.version().toString());
            PropsFile.put(p, k + "file", in.file());
            PropsFile.put(p, k + "sourceId", in.sourceId());
            PropsFile.put(p, k + "origin", in.origin());
            p.setProperty(k + "active", Boolean.toString(in.active()));
            PropsFile.put(p, k + "sha256", in.sha256());
        }
        p.setProperty("installed.count", Integer.toString(i));
        i = 0;
        for (Pending pe : pending) {
            String k = "pending." + i++ + ".";
            p.setProperty(k + "action", pe.action().name());
            p.setProperty(k + "id", pe.id());
            PropsFile.put(p, k + "stagedFile", pe.stagedFile());
            if (pe.target() != null) writeEntry(p, k + "target.", pe.target());
        }
        p.setProperty("pending.count", Integer.toString(i));
        writeList(p, "removedBundled", removedBundled);
        writeList(p, "seenEngines", seenEngines);
        PropsFile.save(file, p);
    }

    private static int count(Properties p, String name) {
        return Integer.parseInt(p.getProperty(name + ".count", "0"));
    }

    private static void writeList(Properties p, String name, Set<String> values) {
        int i = 0;
        for (String v : values) p.setProperty(name + "." + i++, v);
        p.setProperty(name + ".count", Integer.toString(i));
    }

    private static void writeEntry(Properties p, String k, Entry e) {
        p.setProperty(k + "kind", e.kind().name());
        PropsFile.put(p, k + "sourceId", e.sourceId());
        p.setProperty(k + "id", e.id());
        PropsFile.put(p, k + "name", e.name());
        PropsFile.put(p, k + "description", e.description());
        PropsFile.put(p, k + "author", e.author());
        p.setProperty(k + "version", e.version().toString());
        p.setProperty(k + "status", e.status().label());
        PropsFile.put(p, k + "date", e.date());
        PropsFile.put(p, k + "changes", e.changes());
        PropsFile.put(p, k + "infoUrl", e.infoUrl());
        p.setProperty(k + "size", Long.toString(e.size()));
        PropsFile.put(p, k + "file", e.file());
        PropsFile.put(p, k + "location", e.location());
        PropsFile.put(p, k + "sha256", e.sha256());
        int i = 0;
        for (Dependency d : e.dependencies()) {
            p.setProperty(k + "dependency." + i + ".id", d.id());
            p.setProperty(k + "dependency." + i + ".range", d.range().toString());
            i++;
        }
        p.setProperty(k + "dependency.count", Integer.toString(i));
    }

    private static Entry readEntry(Properties p, String k) {
        List<Dependency> deps = new ArrayList<>();
        for (int i = 0, n = Integer.parseInt(p.getProperty(k + "dependency.count", "0")); i < n; i++) {
            deps.add(new Dependency(p.getProperty(k + "dependency." + i + ".id"),
                    VersionRange.parse(p.getProperty(k + "dependency." + i + ".range"))));
        }
        return new Entry(Kind.valueOf(p.getProperty(k + "kind")), p.getProperty(k + "sourceId"), p.getProperty(k + "id"),
                p.getProperty(k + "name"), p.getProperty(k + "description"), p.getProperty(k + "author"),
                Version.parse(p.getProperty(k + "version")), Status.parse(p.getProperty(k + "status")),
                p.getProperty(k + "date"), p.getProperty(k + "changes"), p.getProperty(k + "infoUrl"),
                Long.parseLong(p.getProperty(k + "size", "0")), p.getProperty(k + "file"),
                p.getProperty(k + "location"), p.getProperty(k + "sha256"), List.copyOf(deps));
    }
}
