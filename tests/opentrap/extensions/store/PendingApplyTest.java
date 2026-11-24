package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.ZapFixtures;

/** Applying queued changes to the enabled, disabled and staged folders, and reconciling the record with them. */
public final class PendingApplyTest {

    private interface Body { void run(Path minor, ZapAddOnKind kind, StoreRecord rec) throws Exception; }

    private static void inTemp(Body body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("opentrap-pending");
            ZapAddOnKind kind = new ZapAddOnKind(dir, Version.parse("2.17.0"));
            Files.createDirectories(kind.enabledDir());
            Files.createDirectories(kind.disabledDir());
            Files.createDirectories(kind.stagingDir());
            body.run(dir, kind, StoreRecord.load(dir));
        } catch (RuntimeException | AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (dir != null) delete(dir);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }

    private static Entry entry(String id, String version, String file) {
        return entry("src-1", id, version, file, "ab".repeat(32));
    }

    private static Entry entry(String sourceId, String id, String version, String file, String sha) {
        return new Entry(Kind.ZAP_ADDON, sourceId, id, "Name " + id, "", "", Version.parse(version), Status.RELEASE,
                "", "", "", 1, file, "https://github.com/zaproxy/x", sha, List.of());
    }

    private static Pending stage(ZapAddOnKind k, Pending.Action a, String id, String version) {
        Path zap = ZapFixtures.zap(k.stagingDir(), id, version, "release");
        String name = zap.getFileName().toString();
        return new Pending(a, id, entry("src-1", id, version, name, ZapFixtures.sha256(zap)), name);
    }

    private static List<String> names(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    public void run() {
        Harness.suite("Extension store pending changes");

        Harness.test("an install moves the staged file into enabled", () -> inTemp((minor, k, rec) -> {
            Pending p = stage(k, Pending.Action.INSTALL, "foo", "1.0.0");
            rec.pending().add(p);
            k.apply(rec, p);
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "enabled");
            Expect.equal(names(k.stagingDir()), List.of(), "staged");
            Installed in = rec.installed().get("foo");
            Expect.equal(in.version(), Version.parse("1.0.0"), "version");
            Expect.equal(in.file(), "foo-release-1.0.0.zap", "file");
            Expect.equal(in.sourceId(), "src-1", "sourceId");
            Expect.equal(in.origin(), "src-1", "origin");
            Expect.equal(in.sha256(), p.target().sha256(), "sha256");
            Expect.isTrue(in.active(), "active");
            Expect.isTrue(rec.pending().isEmpty(), "pending emptied");
            Expect.isTrue(StoreRecord.load(minor).installed().containsKey("foo"), "saved");
            Expect.isTrue(StoreRecord.load(minor).pending().isEmpty(), "saved pending");
        }));

        Harness.test("an update replaces the old file of the same id only", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.enabledDir(), "foo", "1.0.0", "release");
            ZapFixtures.zap(k.enabledDir(), "fooBar", "1.0.0", "release");
            Pending p = stage(k, Pending.Action.UPDATE, "foo", "2.0.0");
            rec.pending().add(p);
            k.apply(rec, p);
            Expect.equal(names(k.enabledDir()), List.of("foo-release-2.0.0.zap", "fooBar-release-1.0.0.zap"), "enabled");
        }));

        Harness.test("an update of a disabled add-on stays disabled", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.disabledDir(), "foo", "1.0.0", "release");
            Pending p = stage(k, Pending.Action.UPDATE, "foo", "2.0.0");
            rec.pending().add(p);
            k.apply(rec, p);
            Expect.equal(names(k.disabledDir()), List.of("foo-release-2.0.0.zap"), "disabled");
            Expect.equal(names(k.enabledDir()), List.of(), "enabled");
            Expect.isFalse(rec.installed().get("foo").active(), "record says disabled");
        }));

        Harness.test("disable and enable move the file between the folders", () -> inTemp((minor, k, rec) -> {
            Pending in = stage(k, Pending.Action.INSTALL, "foo", "1.0.0");
            rec.pending().add(in);
            k.apply(rec, in);
            Pending off = new Pending(Pending.Action.DISABLE, "foo", null, null);
            rec.pending().add(off);
            k.apply(rec, off);
            Expect.equal(names(k.disabledDir()), List.of("foo-release-1.0.0.zap"), "disabled");
            Expect.equal(names(k.enabledDir()), List.of(), "enabled");
            Expect.isFalse(rec.installed().get("foo").active(), "inactive");
            Pending on = new Pending(Pending.Action.ENABLE, "foo", null, null);
            rec.pending().add(on);
            k.apply(rec, on);
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "enabled again");
            Expect.equal(names(k.disabledDir()), List.of(), "disabled empty");
            Expect.isTrue(rec.installed().get("foo").active(), "active");
        }));

        Harness.test("removing a bundled add-on remembers it", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.enabledDir(), "foo", "1.0.0", "release");
            ZapFixtures.zap(k.enabledDir(), "bar", "1.0.0", "release");
            rec.installed().put("foo", new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.0.0"),
                    "foo-release-1.0.0.zap", "bundled", "bundled with ZAP 2.17.0", true, ""));
            rec.installed().put("bar", new Installed(Kind.ZAP_ADDON, "bar", Version.parse("1.0.0"),
                    "bar-release-1.0.0.zap", "src-1", "src-1", true, ""));
            Pending rf = new Pending(Pending.Action.REMOVE, "foo", null, null);
            Pending rb = new Pending(Pending.Action.REMOVE, "bar", null, null);
            rec.pending().add(rf);
            rec.pending().add(rb);
            k.apply(rec, rf);
            k.apply(rec, rb);
            Expect.equal(names(k.enabledDir()), List.of(), "enabled");
            Expect.isTrue(rec.installed().isEmpty(), "record empty");
            Expect.equal(List.copyOf(rec.removedBundled()), List.of("foo"), "only the bundled one is remembered");
            Expect.equal(List.copyOf(StoreRecord.load(minor).removedBundled()), List.of("foo"), "saved");
        }));

        Harness.test("removing a bundled add-on updated from ZAP's feed remembers it", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.enabledDir(), "foo", "1.1.0", "release");
            rec.installed().put("foo", new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.1.0"),
                    "foo-release-1.1.0.zap", "zap", "zap", true, ""));
            Pending r = new Pending(Pending.Action.REMOVE, "foo", null, null);
            rec.pending().add(r);
            k.apply(rec, r);
            Expect.equal(List.copyOf(StoreRecord.load(minor).removedBundled()), List.of("foo"),
                    "ZAP's feed and the bundled set are one line");
        }));

        Harness.test("installing a removed bundled add-on again forgets the removal", () -> inTemp((minor, k, rec) -> {
            rec.removedBundled().add("foo");
            rec.removedBundled().add("bar");
            for (String[] a : new String[][] {{"bundled", "foo"}, {"zap", "bar"}}) {
                Path zap = ZapFixtures.zap(k.stagingDir(), a[1], "1.0.0", "release");
                String name = zap.getFileName().toString();
                Pending p = new Pending(Pending.Action.INSTALL, a[1],
                        entry(a[0], a[1], "1.0.0", name, ZapFixtures.sha256(zap)), name);
                rec.pending().add(p);
                k.apply(rec, p);
            }
            Expect.equal(List.copyOf(StoreRecord.load(minor).removedBundled()), List.of(),
                    "neither is remembered as removed");
            Expect.equal(rec.installed().get("foo").origin(), "bundled with ZAP 2.17.0",
                    "the bundled one names the engine it came with");
        }));

        Harness.test("removing an add-on deletes a link without touching its target", () -> inTemp((minor, k, rec) -> {
            Path target = ZapFixtures.zap(minor, "foo", "1.0.0", "release");
            Files.createSymbolicLink(k.enabledDir().resolve(target.getFileName()), target);
            Pending r = new Pending(Pending.Action.REMOVE, "foo", null, null);
            rec.pending().add(r);
            k.apply(rec, r);
            Expect.equal(names(k.enabledDir()), List.of(), "link gone");
            Expect.isTrue(Files.exists(target), "target kept");
        }));

        Harness.test("a change interrupted after the move is finished on the next apply", () -> inTemp((minor, k, rec) -> {
            Pending p = stage(k, Pending.Action.INSTALL, "foo", "1.0.0");
            rec.pending().add(p);
            Files.move(k.stagingDir().resolve(p.stagedFile()), k.enabledDir().resolve(p.stagedFile()));
            k.apply(rec, p);
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "enabled");
            Expect.isTrue(rec.pending().isEmpty(), "pending empty");
            Expect.isTrue(rec.installed().containsKey("foo"), "recorded");
            k.apply(rec, p);
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "repeat is a no-op");
        }));

        Harness.test("an install whose file is nowhere fails and keeps the pending item", () -> inTemp((minor, k, rec) -> {
            Pending p = new Pending(Pending.Action.INSTALL, "foo", entry("foo", "1.0.0", "foo-release-1.0.0.zap"),
                    "foo-release-1.0.0.zap");
            rec.pending().add(p);
            try {
                k.apply(rec, p);
            } catch (IOException e) {
                Expect.isTrue(e.getMessage().contains("foo-release-1.0.0.zap"), "names the file: " + e.getMessage());
                Expect.equal(rec.pending().size(), 1, "pending kept");
                return;
            }
            throw new AssertionError("expected IOException");
        }));

        Harness.test("a staged file that no longer matches its hash fails and stays queued", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.enabledDir(), "foo", "1.0.0", "release");
            Pending good = stage(k, Pending.Action.UPDATE, "foo", "2.0.0");
            Pending p = new Pending(good.action(), "foo", entry("src-1", "foo", "2.0.0", good.stagedFile(),
                    "cd".repeat(32)), good.stagedFile());
            rec.pending().add(p);
            try {
                k.apply(rec, p);
                throw new AssertionError("expected IOException");
            } catch (IOException e) {
                Expect.isTrue(e.getMessage().contains("foo-release-2.0.0.zap") && e.getMessage().contains("hash"),
                        "names the file and the hash: " + e.getMessage());
            }
            Expect.equal(names(k.enabledDir()), List.of("foo-release-1.0.0.zap"), "the installed file is kept");
            Expect.equal(names(k.stagingDir()), List.of("foo-release-2.0.0.zap"), "the staged file is not moved");
            Expect.equal(rec.pending(), List.of(p), "still queued");
        }));

        Harness.test("the folders win over a stale record", () -> inTemp((minor, k, rec) -> {
            ZapFixtures.zap(k.disabledDir(), "foo", "1.0.0", "release");
            rec.installed().put("foo", new Installed(Kind.ZAP_ADDON, "foo", Version.parse("1.0.0"),
                    "foo-release-1.0.0.zap", "src-1", "src-1", true, "cd".repeat(32)));
            rec.installed().put("gone", new Installed(Kind.ZAP_ADDON, "gone", Version.parse("1.0.0"),
                    "gone-release-1.0.0.zap", "src-1", "src-1", true, ""));
            List<Installed> out = k.reconcile(rec);
            Expect.equal(out.size(), 1, "dropped the entry without a file");
            Installed in = out.get(0);
            Expect.equal(in.id(), "foo", "id");
            Expect.isFalse(in.active(), "disabled");
            Expect.equal(in.sourceId(), "src-1", "source kept");
            Expect.equal(in.sha256(), "cd".repeat(32), "hash kept");
            Expect.isFalse(rec.installed().get("foo").active(), "record follows");
            Expect.isFalse(rec.installed().containsKey("gone"), "record dropped");
        }));

        Harness.test("a folder named like an add-on file is ignored", () -> inTemp((minor, k, rec) -> {
            Files.createDirectories(k.enabledDir().resolve("x-release-1.zap"));
            ZapFixtures.zap(k.enabledDir(), "foo", "1.0.0", "release");
            Expect.equal(k.reconcile(rec).size(), 1, "only the file");
            Expect.isFalse(rec.installed().containsKey("x"), "folder not taken in");
            Pending p = new Pending(Pending.Action.REMOVE, "x", null, null);
            rec.pending().add(p);
            k.apply(rec, p);
            Expect.isTrue(Files.isDirectory(k.enabledDir().resolve("x-release-1.zap")), "folder not deleted by a remove");
        }));

        Harness.test("a file with no record entry is taken in as unknown",() -> inTemp((minor, k, rec) -> {
            Path z = ZapFixtures.zap(k.enabledDir(), "foo", "1.2.0", "beta");
            Path target = ZapFixtures.zap(minor, "lnk", "3.0.0", "release");
            Files.createSymbolicLink(k.enabledDir().resolve(target.getFileName()), target);
            Files.write(k.enabledDir().resolve("junk-release-1.zap"), new byte[] {1});
            List<Installed> out = k.reconcile(rec);
            Installed foo = rec.installed().get("foo");
            Expect.equal(foo.sourceId(), "unknown", "source");
            Expect.equal(foo.origin(), "unknown", "origin");
            Expect.equal(foo.version(), Version.parse("1.2.0"), "version");
            Expect.equal(foo.sha256(), ZapFixtures.sha256(z), "hash of a regular file");
            Expect.isTrue(foo.active(), "active");
            Installed lnk = rec.installed().get("lnk");
            Expect.equal(lnk.sha256(), "", "no hash for a link");
            Expect.equal(lnk.version(), Version.parse("3.0.0"), "link version from the name");
            Expect.equal(rec.installed().get("junk").version(), Version.parse("1"), "unreadable file parsed from the name");
            Expect.equal(out.size(), 3, "all three listed");
        }));
    }
}
