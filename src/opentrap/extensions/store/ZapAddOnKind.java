package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ZAP add-ons in one minor version's folder: {@code enabled/} is the only
 * folder ZAP is given, {@code disabled/} holds the rest, {@code staged/} the
 * downloads waiting for a restart. Entries may be links into an engine install,
 * so every move and delete acts on the link itself and never on its target.
 */
public final class ZapAddOnKind implements KindHandler {

    /** The source and origin of a file the record does not know. */
    static final String UNKNOWN = "unknown";

    private static final Pattern ANY_ADDON =
            Pattern.compile("([A-Za-z0-9_]+)-(release|beta|alpha)-([A-Za-z0-9._]+)\\.zap");

    private final Path minorDir;
    private final Version zap;

    /** The folders of {@code minorDir}, for the ZAP version {@code zap} whose engine bundles add-ons. */
    public ZapAddOnKind(Path minorDir, Version zap) {
        this.minorDir = minorDir;
        this.zap = zap;
    }

    @Override public Kind kind() { return Kind.ZAP_ADDON; }

    public Path enabledDir() { return minorDir.resolve("enabled"); }

    public Path disabledDir() { return minorDir.resolve("disabled"); }

    @Override public Path stagingDir() { return minorDir.resolve("staged"); }

    @Override
    public void apply(StoreRecord rec, Pending p) throws IOException {
        switch (p.action()) {
            case INSTALL, UPDATE, SWITCH -> put(rec, p);
            case ENABLE -> moveTo(rec, p.id(), true);
            case DISABLE -> moveTo(rec, p.id(), false);
            case REMOVE -> remove(rec, p.id());
        }
        rec.pending().remove(p);
        rec.save();
    }

    private void put(StoreRecord rec, Pending p) throws IOException {
        Entry t = p.target();
        String name = p.stagedFile();
        if (t == null || name == null || !idFile(p.id()).matcher(name).matches()) {
            throw new IOException("Pending " + p.action() + " of " + p.id() + " has no usable staged file: " + name);
        }
        Path staged = stagingDir().resolve(name);
        boolean inStaged = Files.exists(staged, LinkOption.NOFOLLOW_LINKS);
        boolean inEnabled = Files.exists(enabledDir().resolve(name), LinkOption.NOFOLLOW_LINKS);
        boolean inDisabled = Files.exists(disabledDir().resolve(name), LinkOption.NOFOLLOW_LINKS);
        // checked before anything is deleted, so a missing download never costs the add-on it was to replace
        if (!inStaged && !inEnabled && !inDisabled) throw new IOException("Staged file " + name + " is missing");
        // what was downloaded and verified is what is moved: a bundled link is the engine's own file
        if (inStaged && !Source.BUNDLED_ID.equals(t.sourceId())
                && !ZapAddOnFile.sha256(staged).equalsIgnoreCase(t.sha256())) {
            throw new IOException("Staged file " + name + " no longer matches the hash it was queued with");
        }
        Installed old = rec.installed().get(p.id());
        boolean active;
        if (!inStaged) {
            active = inEnabled;
        } else if (p.action() == Pending.Action.UPDATE) {
            // where the current file is wins; the record decides only when it is gone
            if (!files(disabledDir(), p.id()).isEmpty()) active = false;
            else if (!files(enabledDir(), p.id()).isEmpty()) active = true;
            else active = old == null || old.active();
        } else {
            active = true;
        }
        deleteIdFiles(p.id(), inStaged ? null : name);
        if (inStaged) {
            Path dir = active ? enabledDir() : disabledDir();
            Files.createDirectories(dir);
            Files.move(staged, dir.resolve(name));
        }
        String origin = Source.BUNDLED_ID.equals(t.sourceId()) ? Bundled.origin(zap) : t.sourceId();
        rec.installed().put(p.id(), new Installed(t.kind(), p.id(), t.version(), name, t.sourceId(), origin,
                active, t.sha256()));
        // installed again from Store, so a later engine change keeps it
        rec.removedBundled().remove(p.id());
    }

    private void moveTo(StoreRecord rec, String id, boolean enable) throws IOException {
        Path from = enable ? disabledDir() : enabledDir();
        Path to = enable ? enabledDir() : disabledDir();
        List<Path> src = files(from, id);
        if (!src.isEmpty()) {
            Files.createDirectories(to);
            Path f = src.get(0);
            Files.move(f, to.resolve(f.getFileName()));
        }
        Installed in = rec.installed().get(id);
        if (in != null) {
            rec.installed().put(id, new Installed(in.kind(), id, in.version(), in.file(), in.sourceId(), in.origin(),
                    enable, in.sha256()));
        }
    }

    private void remove(StoreRecord rec, String id) throws IOException {
        Installed in = rec.installed().get(id);
        // also one updated from ZAP's feed: the engine's plugin/ may hold it, and a later engine change would link it
        if (in != null && in.sourceId() != null && Resolver.sameLine(in.sourceId(), Source.BUNDLED_ID)
                && rec.removedBundled().add(id)) {
            // remembered before the file goes, so a crash in between cannot bring a bundled add-on back
            rec.save();
        }
        deleteIdFiles(id, null);
        rec.installed().remove(id);
    }

    @Override
    public List<Installed> reconcile(StoreRecord rec) throws IOException {
        Map<String, Installed> found = new LinkedHashMap<>();
        for (boolean active : new boolean[] {true, false}) {
            Path dir = active ? enabledDir() : disabledDir();
            if (!Files.isDirectory(dir)) continue;
            List<Path> list;
            try (Stream<Path> s = Files.list(dir)) { list = s.filter(ZapAddOnKind::isFileOrLink).sorted().toList(); }
            for (Path f : list) {
                Matcher m = ANY_ADDON.matcher(f.getFileName().toString());
                if (!m.matches() || found.containsKey(m.group(1))) continue;
                Installed in = describe(rec.installed().get(m.group(1)), f, m, active);
                if (in != null) found.put(in.id(), in);
            }
        }
        rec.installed().clear();
        rec.installed().putAll(found);
        return List.copyOf(found.values());
    }

    private static Installed describe(Installed old, Path f, Matcher m, boolean active) throws IOException {
        String name = f.getFileName().toString();
        Version version;
        try {
            version = ZapAddOnFile.read(f).version();
        } catch (IOException _) {
            try {
                version = Version.parse(m.group(3));
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        if (old != null && name.equals(old.file())) {
            return new Installed(Kind.ZAP_ADDON, old.id(), version, name, old.sourceId(), old.origin(), active,
                    old.sha256());
        }
        String sha = Files.isSymbolicLink(f) ? "" : ZapAddOnFile.sha256(f);
        return new Installed(Kind.ZAP_ADDON, m.group(1), version, name, UNKNOWN, UNKNOWN, active, sha);
    }

    /** A regular file or a link (even a dangling one); a folder is never an add-on. */
    private static boolean isFileOrLink(Path f) {
        return Files.isSymbolicLink(f) || Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS);
    }

    private static Pattern idFile(String id) {
        return Pattern.compile(Pattern.quote(id) + "-(release|beta|alpha)-[A-Za-z0-9._]+\\.zap");
    }

    /** Entries (files or links) of the id in a folder; none when the folder does not exist. */
    static List<Path> files(Path dir, String id) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        Pattern p = idFile(id);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(f -> p.matcher(f.getFileName().toString()).matches() && isFileOrLink(f)).sorted()
                    .forEach(out::add);
        }
        return out;
    }

    /** Deletes the id's entries in enabled/ and disabled/ (links themselves), except the one named {@code keep}. */
    void deleteIdFiles(String id, String keep) throws IOException {
        for (Path dir : new Path[] {enabledDir(), disabledDir()}) {
            for (Path f : files(dir, id)) {
                if (!f.getFileName().toString().equals(keep)) Files.delete(f);
            }
        }
    }
}
