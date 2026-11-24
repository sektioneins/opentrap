package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.ZapFixtures;

/** The store facade: queued changes applied at engine start, the folders ZAP is given, core add-ons, carry-over. */
public final class StoreTest {

    private static final Version ZAP = Version.parse("2.17.0");

    private interface Body { void run(Path root, Path engine, Path folder) throws Exception; }

    /**
     * A store root, a fake engine install (jar, lib, empty plugin) and a local
     * folder added as source {@code src-1}, all in a fresh temp folder.
     */
    private static void inTemp(Body body) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("opentrap-store");
            Path root = tmp.resolve("addons");
            Path engine = tmp.resolve("engine");
            Files.createDirectories(engine.resolve("lib"));
            Files.createDirectories(engine.resolve("plugin"));
            Files.write(engine.resolve("zap-2.17.0.jar"), new byte[0]);
            Path folder = Files.createDirectories(tmp.resolve("mine"));
            SourceList sources = SourceList.load(root, "2.17");
            sources.add(new Source("src-1", "Mine", SourceType.LOCAL_FOLDER, folder.toString(), "", true));
            sources.save();
            body.run(root, engine, folder);
        } catch (RuntimeException | AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) delete(tmp);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }

    /** Every source here is a local folder or the engine's plugin folder, so the fetcher is never used. */
    private static Store store(Path root, Path engine) throws IOException {
        Store s = new Store(root, new Fetcher(null, host -> null));
        s.open(ZAP, engine);
        return s;
    }

    private static Entry offer(Store s, String id) {
        List<Entry> offers = s.catalogue().offers(id);
        Expect.isFalse(offers.isEmpty(), id + " is offered");
        return offers.get(0);
    }

    private static void install(Store s, String id) throws Exception {
        s.install(s.plan(offer(s, id)), (done, total) -> {}, () -> false);
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var st = Files.list(dir)) { return st.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    private static Installed held(String id, boolean active, String sourceId) {
        return new Installed(Kind.ZAP_ADDON, id, Version.parse("1"), id + "-release-1.zap", sourceId, sourceId,
                active, "ab".repeat(32));
    }

    public void run() {
        Harness.suite("Extension store");

        Harness.test("the engine start hook applies pending changes and names the folders", () -> inTemp((root, engine, folder) -> {
            ZapFixtures.zap(folder, "foo", "1.0.0", "release");
            Store s = store(root, engine);
            install(s, "foo");
            Expect.equal(s.pendingCount(), 1, "the install is queued");
            List<String> args = s.startHook().beforeLaunch(engine, "2.17.0");
            Path minor = root.resolve("2.17");
            Expect.equal(names(minor.resolve("enabled")), List.of("foo-release-1.0.0.zap"), "enabled/");
            Expect.equal(s.pending(), List.of(), "nothing is left pending");
            Expect.equal(StoreRecord.load(minor).pending(), List.of(), "the saved record has nothing pending");
            Expect.equal(s.installed().stream().map(Installed::id).toList(), List.of("foo"), "installed");
            Path runtime = root.resolve("runtime").resolve(RuntimeInstall.key(engine));
            Expect.equal(args, List.of("-installdir", runtime.toString(),
                    "-config", "start.addonDirs=" + minor.resolve("enabled")), "the engine arguments");
            Expect.isTrue(Files.isSymbolicLink(runtime.resolve("lib")), "the runtime folder links the install");
        }));

        Harness.test("a pending change that fails stays queued and the rest are applied", () -> inTemp((root, engine, folder) -> {
            ZapFixtures.zap(folder, "bar", "1.0.0", "release");
            ZapFixtures.zap(folder, "foo", "1.0.0", "release");
            Store s = store(root, engine);
            install(s, "bar");
            install(s, "foo");
            Files.delete(root.resolve("2.17/staged/bar-release-1.0.0.zap"));
            List<String> args = s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(names(root.resolve("2.17/enabled")), List.of("foo-release-1.0.0.zap"), "enabled/");
            Expect.equal(s.pending().stream().map(Pending::id).toList(), List.of("bar"), "bar stays pending");
            Expect.isTrue(args.contains("-installdir"), "the engine still gets the store's folders");
        }));

        Harness.test("the engine start hook clears part files left by a crash", () -> inTemp((root, engine, folder) -> {
            Path staged = Files.createDirectories(root.resolve("2.17/staged"));
            Path old = Files.write(staged.resolve(".foo.123.part"), new byte[1]);
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            Path live = Files.write(staged.resolve(".bar.456.part"), new byte[1]);
            store(root, engine).startHook().beforeLaunch(engine, "2.17.0");
            Expect.isFalse(Files.exists(old), "the old part file is gone");
            Expect.isTrue(Files.exists(live), "a part file still being written is kept");
        }));

        Harness.test("a bundled add-on whose engine file is gone is named missing", () -> inTemp((root, engine, folder) -> {
            Path foo = ZapFixtures.zap(engine.resolve("plugin"), "foo", "1.0.0", "release");
            Store s = store(root, engine);
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.isTrue(Files.isSymbolicLink(root.resolve("2.17/enabled/foo-release-1.0.0.zap")), "foo is linked in");
            Expect.equal(s.missing(), List.of(), "nothing is missing yet");
            Files.delete(foo);
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(s.missing(), List.of("foo"), "foo is missing");
            Expect.isTrue(s.installed().isEmpty(), "foo is no longer installed");
            Expect.equal(names(root.resolve("2.17/enabled")), List.of(), "the dangling link is gone");
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(s.missing(), List.of(), "the next start clears the list");
        }));

        Harness.test("undo drops a queued change and its staged file", () -> inTemp((root, engine, folder) -> {
            ZapFixtures.zap(folder, "foo", "1.0.0", "release");
            Store s = store(root, engine);
            install(s, "foo");
            Expect.equal(names(root.resolve("2.17/staged")), List.of("foo-release-1.0.0.zap"), "staged/");
            s.undo("foo");
            Expect.equal(s.pendingCount(), 0, "nothing is pending");
            Expect.equal(names(root.resolve("2.17/staged")), List.of(), "the staged file is gone");
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(names(root.resolve("2.17/enabled")), List.of(), "nothing was installed");
        }));

        Harness.test("enable, disable and remove are queued until the engine starts", () -> inTemp((root, engine, folder) -> {
            ZapFixtures.zap(folder, "foo", "1.0.0", "release");
            ZapFixtures.zap(folder, "bar", "1.0.0", "release");
            Store s = store(root, engine);
            install(s, "foo");
            install(s, "bar");
            s.startHook().beforeLaunch(engine, "2.17.0");
            s.disable("foo");
            s.remove("bar");
            Expect.equal(s.pending().stream().map(p -> p.action() + " " + p.id()).toList(),
                    List.of("DISABLE foo", "REMOVE bar"), "queued in order");
            Expect.equal(names(root.resolve("2.17/enabled")).size(), 2, "nothing moved before the start");
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(names(root.resolve("2.17/enabled")), List.of(), "enabled/");
            Expect.equal(names(root.resolve("2.17/disabled")), List.of("foo-release-1.0.0.zap"), "disabled/");
            s.enable("foo");
            s.startHook().beforeLaunch(engine, "2.17.0");
            Expect.equal(names(root.resolve("2.17/enabled")), List.of("foo-release-1.0.0.zap"), "foo is back");
        }));

        Harness.test("listeners hear a change after the store lock is released", () -> inTemp((root, engine, folder) -> {
            Store s = store(root, engine);
            AtomicReference<String> heard = new AtomicReference<>("not called");
            s.addListener(() -> {
                try (StoreLock _ = StoreLock.acquire(root)) {
                    heard.set("free");
                } catch (Exception e) {
                    heard.set("held: " + e);
                }
            });
            s.disable("foo");
            Expect.equal(heard.get(), "free", "the listener could take the lock");
        }));

        Harness.test("dependencies of an installed add-on are read from its file", () -> inTemp((root, engine, folder) -> {
            ZapFixtures.zap(folder, "base", "1.0.0", "release");
            ZapFixtures.zap(folder, "user", "1.0.0", "release", "base", "");
            Store s = store(root, engine);
            install(s, "user");
            Expect.equal(s.pending().stream().map(Pending::id).toList(), List.of("base", "user"),
                    "the dependency is installed first");
            s.startHook().beforeLaunch(engine, "2.17.0");
            try (var st = Files.list(folder)) { for (Path p : st.toList()) Files.delete(p); }
            Store fresh = store(root, engine);
            Expect.isTrue(fresh.catalogue().offers("user").isEmpty(), "no source offers user any more");
            Expect.equal(fresh.dependents("base"), List.of("user"), "user needs base");
        }));

        Harness.test("a disabled crawl add-on is named for the crawl", () -> {
            Map<String, Installed> installed = new java.util.LinkedHashMap<>();
            for (String id : CoreAddOns.needs(CoreAddOns.Feature.CRAWL)) installed.put(id, held(id, true, "bundled"));
            installed.put("spider", held("spider", false, "bundled"));
            Expect.equal(CoreAddOns.missing(CoreAddOns.Feature.CRAWL, installed), List.of("spider"), "missing");
            Expect.isTrue(CoreAddOns.stoppedBy("spider").contains(CoreAddOns.Feature.CRAWL), "the crawl stops");
            Expect.isFalse(CoreAddOns.stoppedBy("spider").contains(CoreAddOns.Feature.AUDIT), "the audit does not");
            installed.remove("network");
            Expect.equal(CoreAddOns.missing(CoreAddOns.Feature.CRAWL, installed), List.of("spider", "network"),
                    "an add-on not installed is missing too");
            Expect.equal(CoreAddOns.Feature.AJAX_CRAWL.label(), "AJAX crawl", "the label");
        });

        Harness.test("add-ons of the previous ZAP version are offered for carry-over", () -> inTemp((root, engine, folder) -> {
            for (String minor : new String[] {"2.15", "2.16", "2.17"}) {
                StoreRecord rec = StoreRecord.load(Files.createDirectories(root.resolve(minor)));
                rec.installed().put("custom" + minor.replace(".", ""), held("custom" + minor.replace(".", ""), false, "src-1"));
                rec.installed().put("spider", held("spider", true, Source.BUNDLED_ID));
                rec.save();
            }
            Files.createDirectories(root.resolve("runtime"));
            Expect.equal(Carryover.fromPrevious(root, "2.17").stream().map(Installed::id).toList(), List.of("custom216"),
                    "the highest folder below 2.17, bundled left out");
            Expect.equal(Carryover.fromPrevious(root, "2.15"), List.of(), "nothing below the first");
        }));
    }
}
