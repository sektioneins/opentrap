package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The sources the user has configured, kept in {@code root/sources}. The ZAP
 * feed is always present and only its enabled flag is stored; the bundled
 * source is the store's own and is not stored here.
 */
public final class SourceList {

    private final Path file;
    private final List<Source> sources = new ArrayList<>();
    private int next = 1;

    private SourceList(Path file) {
        this.file = file;
    }

    public static SourceList load(Path root, String minor) throws IOException {
        SourceList l = new SourceList(root.resolve("sources"));
        Properties p = PropsFile.load(l.file);
        l.sources.add(Source.zapFeed(minor, PropsFile.bool(p, "zap.enabled", true)));
        int count = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = 0; i < count; i++) {
            String k = "source." + i + ".";
            String id = p.getProperty(k + "id");
            if (id == null) continue;
            l.sources.add(new Source(id, p.getProperty(k + "name", id), SourceType.valueOf(p.getProperty(k + "type")),
                    p.getProperty(k + "location", ""), p.getProperty(k + "prefix", ""),
                    PropsFile.bool(p, k + "enabled", true)));
        }
        l.next = Integer.parseInt(p.getProperty("next", "1"));
        for (Source s : l.sources) l.next = Math.max(l.next, numberOf(s.id()) + 1);
        return l;
    }

    private static int numberOf(String id) {
        if (!id.startsWith("src-")) return 0;
        try { return Integer.parseInt(id.substring(4)); } catch (NumberFormatException e) { return 0; }
    }

    public List<Source> all() { return List.copyOf(sources); }

    /** The id the next user source gets; an id is never used twice, even after its source is removed. */
    public String nextId() { return "src-" + next; }

    public void add(Source s) {
        if (s.builtIn()) throw new IllegalArgumentException("A built-in source cannot be added");
        if (indexOf(s.id()) >= 0) throw new IllegalArgumentException("Source exists: " + s.id());
        sources.add(s);
        next = Math.max(next, numberOf(s.id()) + 1);
    }

    /** For a built-in source only {@code enabled} may change. */
    public void update(Source s) {
        int i = indexOf(s.id());
        if (i < 0) throw new IllegalArgumentException("No such source: " + s.id());
        Source old = sources.get(i);
        if (old.builtIn() && !sameBuiltIn(old, s)) {
            throw new IllegalArgumentException("Only enabled can change on " + old.name());
        }
        sources.set(i, s);
    }

    private static boolean sameBuiltIn(Source a, Source b) {
        return a.name().equals(b.name()) && a.type() == b.type() && a.location().equals(b.location())
                && a.prefix().equals(b.prefix());
    }

    public void remove(String id) {
        int i = indexOf(id);
        if (i < 0) return;
        if (sources.get(i).builtIn()) throw new IllegalArgumentException("A built-in source cannot be removed");
        sources.remove(i);
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id().equals(id)) return i;
        return -1;
    }

    public void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("zap.enabled", Boolean.toString(sources.get(0).enabled()));
        p.setProperty("next", Integer.toString(next));
        int n = 0;
        for (Source s : sources) {
            if (s.builtIn()) continue;
            String k = "source." + n++ + ".";
            p.setProperty(k + "id", s.id());
            p.setProperty(k + "name", s.name());
            p.setProperty(k + "type", s.type().name());
            PropsFile.put(p, k + "location", s.location());
            PropsFile.put(p, k + "prefix", s.prefix());
            p.setProperty(k + "enabled", Boolean.toString(s.enabled()));
        }
        p.setProperty("count", Integer.toString(n));
        PropsFile.save(file, p);
    }
}
