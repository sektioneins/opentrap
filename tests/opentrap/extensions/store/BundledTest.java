package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.ZapFixtures;

/** Bundled add-ons taken in as store add-ons, and the linked runtime install ZAP starts from. */
public final class BundledTest {

    private static final Version ZAP = Version.parse("2.17.0");

    private interface Body { void run(Path root, Path engine, ZapAddOnKind kind, StoreRecord rec) throws Exception; }

    /** A fake engine install (jar, lib, plugin) and an empty minor folder, both in a fresh temp folder. */
    private static void inTemp(Body body) {
        Path root = null;
        try {
            root = Files.createTempDirectory("opentrap-bundled");
            Path engine = root.resolve("engine");
            Files.createDirectories(engine.resolve("lib"));
            Files.createDirectories(engine.resolve("plugin"));
            Files.write(engine.resolve("zap-2.17.0.jar"), new byte[0]);
            Path minor = root.resolve("minor");
            ZapAddOnKind kind = new ZapAddOnKind(minor, ZAP);
            Files.createDirectories(kind.enabledDir());
            Files.createDirectories(kind.disabledDir());
            body.run(root, engine, kind, StoreRecord.load(minor));
        } catch (RuntimeException | AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (root != null) delete(root);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }

    private static List<String> names(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    private static Installed held(String id, String version, String sourceId, String origin, boolean active) {
        return new Installed(Kind.ZAP_ADDON, id, Version.parse(version), id + "-release-" + version + ".zap",
                sourceId, origin, active, "ab".repeat(32));
    }

    /** Every path under a folder with its type and size, to prove nothing changed. */
    private static List<String> tree(Path dir) throws IOException {
        try (var w = Files.walk(dir)) {
            return w.sorted().map(p -> {
                try {
                    return dir.relativize(p) + ":" + (Files.isDirectory(p) ? "d" : Files.size(p));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    public void run() {
        Harness.suite("Extension store bundled add-ons");

        Harness.test("a new engine's add-ons are linked in as active", () -> inTemp((root, engine, k, rec) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            ZapFixtures.zap(engine.resolve("plugin"), "bar", "2.0.0", "beta");
            Listing l = Bundled.listing(engine, ZAP);
            Expect.equal(l.entries().size(), 2, "listed");
            Expect.equal(l.entries().get(0).sourceId(), "bundled", "source id");
            Expect.equal(Bundled.reconcile(engine, ZAP, k, rec), List.of(), "nothing dropped");
            Expect.equal(names(k.enabledDir()), List.of("bar-beta-2.0.0.zap", "foo-release-1.0.0.zap"), "linked");
            Path link = k.enabledDir().resolve(foo.getFileName());
            Expect.isTrue(Files.isSymbolicLink(link), "a link, not a copy");
            Expect.equal(Files.readSymbolicLink(link), foo.toAbsolutePath(), "points at the engine file");
            Installed in = rec.installed().get("foo");
            Expect.equal(in.sourceId(), "bundled", "sourceId");
            Expect.equal(in.origin(), "bundled with ZAP 2.17.0", "origin");
            Expect.equal(in.file(), foo.getFileName().toString(), "file");
            Expect.equal(in.sha256(), ZapFixtures.sha256(foo), "hash of the engine file");
            Expect.isTrue(in.active(), "active");
            Expect.equal(rec.seenEngines().size(), 1, "engine remembered");
        }));

        Harness.test("a newer bundled version replaces the older one and keeps it disabled", () -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.1.0", "release");
            ZapFixtures.zap(k.disabledDir(), "foo", "1.0.0", "release");
            rec.installed().put("foo", held("foo", "1.0.0", "zap", "ZAP add-ons", false));
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.disabledDir()), List.of("foo-release-1.1.0.zap"), "disabled");
            Expect.equal(names(k.enabledDir()), List.of(), "enabled");
            Expect.isTrue(Files.isSymbolicLink(k.disabledDir().resolve("foo-release-1.1.0.zap")), "link");
            Installed in = rec.installed().get("foo");
            Expect.equal(in.version(), Version.parse("1.1.0"), "version");
            Expect.equal(in.sourceId(), "bundled", "source");
            Expect.equal(in.origin(), "bundled with ZAP 2.17.0", "origin");
            Expect.isFalse(in.active(), "still disabled");
        }));

        Harness.test("a bundled add-on never replaces one from a custom source", () -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "2.0.0", "release");
            ZapFixtures.zap(engine.resolve("plugin"), "bar", "1.0.0", "release");
            ZapFixtures.zap(k.enabledDir(), "foo", "1.0.0", "release");
            ZapFixtures.zap(k.enabledDir(), "bar", "1.5.0", "release");
            rec.installed().put("foo", held("foo", "1.0.0", "mine", "Mine", true));
            rec.installed().put("bar", held("bar", "1.5.0", "bundled", "bundled with ZAP 2.16.0", true));
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.enabledDir()), List.of("bar-release-1.5.0.zap", "foo-release-1.0.0.zap"), "untouched");
            Expect.equal(rec.installed().get("foo").version(), Version.parse("1.0.0"), "custom kept");
            Expect.equal(rec.installed().get("bar").version(), Version.parse("1.5.0"), "newer kept");
        }));

        Harness.test("a bundled add-on the user removed is not linked in again", () -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            rec.removedBundled().add("foo");
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.enabledDir()), List.of(), "not linked");
            Expect.isFalse(rec.installed().containsKey("foo"), "not recorded");
        }));

        Harness.test("a bundled add-on updated from ZAP's feed and removed stays removed when the engine changes", () -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Path staged = ZapFixtures.zap(Files.createDirectories(k.stagingDir()), "foo", "1.1.0", "release");
            String name = staged.getFileName().toString();
            Entry fromZap = new Entry(Kind.ZAP_ADDON, Source.ZAP_ID, "foo", "Foo", "", "", Version.parse("1.1.0"),
                    Status.RELEASE, "", "", "", 1, name, "https://github.com/zaproxy/x", ZapFixtures.sha256(staged),
                    List.of());
            for (Pending p : List.of(new Pending(Pending.Action.UPDATE, "foo", fromZap, name),
                    new Pending(Pending.Action.REMOVE, "foo", null, null))) {
                rec.pending().add(p);
                k.apply(rec, p);
            }
            ZapFixtures.zap(engine.resolve("plugin"), "baz", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.enabledDir()), List.of("baz-release-1.0.0.zap"), "foo is not linked back");
            Expect.isFalse(rec.installed().containsKey("foo"), "nor recorded");
        }));

        Harness.test("a removed bundled add-on installed again from Store follows a newer engine file", () -> inTemp((root, engine, k, rec) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Pending rm = new Pending(Pending.Action.REMOVE, "foo", null, null);
            rec.pending().add(rm);
            k.apply(rec, rm);
            // installed again from the bundled set: the staged file is a link to the engine's own
            Entry e = Bundled.listing(engine, ZAP).entries().get(0);
            Files.createSymbolicLink(Files.createDirectories(k.stagingDir()).resolve(e.file()), foo.toAbsolutePath());
            Pending in = new Pending(Pending.Action.INSTALL, "foo", e, e.file());
            rec.pending().add(in);
            k.apply(rec, in);
            Expect.equal(rec.installed().get("foo").origin(), "bundled with ZAP 2.17.0", "named after the engine");
            Files.delete(foo);
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.1.0", "release");
            Expect.equal(Bundled.reconcile(engine, ZAP, k, rec), List.of(), "not dropped as missing");
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.1.0.zap"), "the newer engine file is linked");
        }));

        Harness.test("an engine already taken in is not taken in again", () -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Files.delete(k.enabledDir().resolve("foo-release-1.0.0.zap"));
            rec.installed().remove("foo");
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.enabledDir()), List.of(), "left as the user had it");
            ZapFixtures.zap(engine.resolve("plugin"), "baz", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(names(k.enabledDir()), List.of("baz-release-1.0.0.zap", "foo-release-1.0.0.zap"),
                    "a changed plugin folder is taken in");
        }));

        Harness.test("an add-on whose engine file is gone is dropped and reported", () -> inTemp((root, engine, k, rec) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Path bar = ZapFixtures.zap(engine.resolve("plugin"), "bar", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            Files.move(k.enabledDir().resolve("bar-release-1.0.0.zap"), k.disabledDir().resolve("bar-release-1.0.0.zap"));
            Files.delete(foo);
            Files.delete(bar);
            Expect.equal(Bundled.reconcile(engine, ZAP, k, rec), List.of("bar", "foo"), "reported");
            Expect.equal(names(k.enabledDir()), List.of(), "no dangling link in enabled");
            Expect.equal(names(k.disabledDir()), List.of(), "no dangling link in disabled");
            Expect.isFalse(rec.installed().containsKey("foo"), "record entry gone");
            Expect.isFalse(rec.installed().containsKey("bar"), "record entry gone, disabled one too");
            Expect.isTrue(Files.isDirectory(engine.resolve("plugin")), "engine left alone");
        }));

        Harness.test("a bundled add-on whose engine file was replaced by a newer one keeps its state", () -> inTemp((root, engine, k, rec) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Path bar = ZapFixtures.zap(engine.resolve("plugin"), "bar", "1.0.0", "release");
            Bundled.reconcile(engine, ZAP, k, rec);
            rec.installed().put("foo", new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.0.0"), foo.getFileName().toString(),
                    "bundled", "bundled with ZAP 2.17.0", false, "ab".repeat(32)));
            Files.move(k.enabledDir().resolve(foo.getFileName()), k.disabledDir().resolve(foo.getFileName()));
            Files.delete(foo);
            Files.delete(bar);
            Path newer = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.1.0", "release");
            Expect.equal(Bundled.reconcile(engine, ZAP, k, rec), List.of("bar"), "only the add-on without a file is reported");
            Expect.equal(names(k.disabledDir()), List.of("foo-release-1.1.0.zap"), "new link where the old one was");
            Expect.equal(names(k.enabledDir()), List.of(), "enabled empty");
            Expect.equal(Files.readSymbolicLink(k.disabledDir().resolve(newer.getFileName())), newer.toAbsolutePath(), "target");
            Installed in = rec.installed().get("foo");
            Expect.equal(in.version(), Version.parse("1.1.0"), "version");
            Expect.isFalse(in.active(), "still disabled");
            Expect.isFalse(rec.installed().containsKey("bar"), "bar dropped");
        }));

        Harness.test("a same-named link the record does not know is recorded", () -> inTemp((root, engine, k, rec) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Files.createSymbolicLink(k.enabledDir().resolve(foo.getFileName()), foo.toAbsolutePath());
            Bundled.reconcile(engine, ZAP, k, rec);
            Expect.equal(rec.installed().get("foo").file(), foo.getFileName().toString(), "recorded");
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "kept");
        }));

        Harness.test("the runtime install links everything except the add-ons",() -> inTemp((root, engine, k, rec) -> {
            ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            List<String> before = tree(engine);
            Path runtimeRoot = root.resolve("runtime");
            Path run = RuntimeInstall.prepare(engine, runtimeRoot);
            Expect.equal(run.getParent(), runtimeRoot, "under the runtime root");
            Expect.equal(run.getFileName().toString().length(), 16, "key is 16 hex");
            Expect.isTrue(Files.isSymbolicLink(run.resolve("lib")), "lib is a link");
            Expect.isTrue(Files.isSymbolicLink(run.resolve("zap-2.17.0.jar")), "jar is a link");
            Expect.equal(run.resolve("lib").toRealPath(), engine.resolve("lib").toRealPath(), "target");
            Expect.isFalse(Files.isSymbolicLink(run.resolve("plugin")), "plugin is not a link");
            Expect.isTrue(Files.isDirectory(run.resolve("plugin"), LinkOption.NOFOLLOW_LINKS), "plugin is a folder");
            Expect.equal(names(run.resolve("plugin")), List.of(), "plugin is empty");
            Expect.equal(tree(engine), before, "nothing written into the install");
            Expect.equal(RuntimeInstall.prepare(engine, runtimeRoot), run, "same key again");
        }));

        Harness.test("the runtime install follows the engine when entries change", () -> inTemp((root, engine, k, rec) -> {
            Path runtimeRoot = root.resolve("runtime");
            Path run = RuntimeInstall.prepare(engine, runtimeRoot);
            Files.createDirectories(engine.resolve("db"));
            Files.delete(engine.resolve("lib"));
            Files.createDirectories(engine.resolve("lib2"));
            Expect.equal(RuntimeInstall.prepare(engine, runtimeRoot), run, "same install");
            Expect.isTrue(Files.isSymbolicLink(run.resolve("db")), "new entry linked");
            Expect.isTrue(Files.isSymbolicLink(run.resolve("lib2")), "renamed entry linked");
            Expect.isFalse(Files.exists(run.resolve("lib"), LinkOption.NOFOLLOW_LINKS), "removed entry's link gone");
        }));

        Harness.test("a different install gets a different key", () -> inTemp((root, engine, k, rec) -> {
            Path other = root.resolve("other");
            Files.createDirectories(other.resolve("plugin"));
            Files.write(other.resolve("zap-2.17.0.jar"), new byte[0]);
            Path runtimeRoot = root.resolve("runtime");
            Expect.isFalse(RuntimeInstall.prepare(engine, runtimeRoot).equals(RuntimeInstall.prepare(other, runtimeRoot)),
                    "keys differ");
        }));

        Harness.test("links are supported unless the operating system is Windows", () -> {
            String os = System.getProperty("os.name");
            Expect.equal(RuntimeInstall.linksSupported(), !os.startsWith("Windows"), "os " + os);
        });
    }
}
