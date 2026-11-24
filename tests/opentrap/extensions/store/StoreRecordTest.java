package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/** The sources list, the installed record, pending changes and the lock that keeps two instances apart. */
public final class StoreRecordTest {

    private interface Body { void run(Path dir) throws Exception; }

    private static void inTemp(Body body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("opentrap-store");
            body.run(dir);
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

    private static void throwsIae(Runnable r, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException: " + what);
    }

    private static Entry entry(String id) {
        return new Entry(Kind.ZAP_ADDON, "src-1", id, "Name " + id, "A description\nwith two lines", "Some Author",
                Version.parse("1.2.3"), Status.BETA, "2026-11-24", "- fixed = a thing\n- added: b", "https://example.org/i",
                12345L, id + "-beta-1.2.3.zap", "https://example.org/" + id + ".zap", "a".repeat(64),
                List.of(new Dependency("commonlib", VersionRange.parse(">= 1.29.0 & < 2.0.0")),
                        new Dependency("database", VersionRange.ANY)));
    }

    private static void corrupt(Path root, String file, String text, Body load, String names) {
        try {
            Files.writeString(root.resolve(file), text);
            load.run(root);
        } catch (IOException e) {
            Expect.isTrue(e.getMessage().contains(root.resolve(file).toString()), "names the file: " + e.getMessage());
            Expect.isTrue(e.getMessage().contains(names), "names " + names + ": " + e.getMessage());
            return;
        } catch (Exception e) {
            throw new AssertionError("expected IOException for " + names + " but got " + e, e);
        }
        throw new AssertionError("expected IOException for " + names);
    }

    public void run() {
        Harness.suite("Extension store record");

        Harness.test("the ZAP feed is always listed and can only be switched off", () -> inTemp(root -> {
            SourceList l = SourceList.load(root, "2.17");
            Expect.equal(l.all().size(), 1, "one source on a fresh root");
            Expect.equal(l.all().get(0), Source.zapFeed("2.17", true), "the ZAP feed, enabled");
            throwsIae(() -> l.update(new Source("zap", "ZAP add-ons", SourceType.ZAP_FEED, "https://example.org/x.xml",
                    "https://github.com/zaproxy/", true)), "a new location");
            l.update(Source.zapFeed("2.17", false));
            l.save();
            Expect.isFalse(SourceList.load(root, "2.17").all().get(0).enabled(), "the ZAP feed stays switched off");
            throwsIae(() -> l.remove("zap"), "removing the ZAP feed");
        }));

        Harness.test("a user source survives a save and a load", () -> inTemp(root -> {
            SourceList l = SourceList.load(root, "2.17");
            Source s = new Source(l.nextId(), "Mine", SourceType.FEED_URL, "https://example.org/feed.xml",
                    "https://example.org/dl/", false);
            Expect.equal(s.id(), "src-1", "first user id");
            l.add(s);
            l.save();
            SourceList again = SourceList.load(root, "2.17");
            Expect.equal(again.all().size(), 2, "two sources");
            Expect.equal(again.all().get(1), s, "every field");
            Expect.equal(again.nextId(), "src-2", "ids are not reused");
            again.remove("src-1");
            again.save();
            Expect.equal(SourceList.load(root, "2.17").nextId(), "src-2", "a removed id stays used");
            Expect.equal(SourceList.load(root, "2.17").all().size(), 1, "removed");
        }));

        Harness.test("the installed record and pending list survive a save and a load", () -> inTemp(root -> {
            StoreRecord r = StoreRecord.load(root);
            Expect.isTrue(r.installed().isEmpty() && r.pending().isEmpty(), "missing file is empty");
            Installed in = new Installed(Kind.ZAP_ADDON, "spider", Version.parse("0.20.0"), "spider-release-0.20.0.zap",
                    "src-1", "bundled with ZAP 2.17.0", true, "b".repeat(64));
            r.installed().put("spider", in);
            r.pending().add(new Pending(Pending.Action.INSTALL, "ascanrules", entry("ascanrules"), "staged/x.zap"));
            r.pending().add(new Pending(Pending.Action.REMOVE, "spider", null, null));
            r.removedBundled().add("old");
            r.seenEngines().add("2.17.0");
            r.save();
            StoreRecord again = StoreRecord.load(root);
            Expect.equal(again.installed().get("spider"), in, "installed");
            Expect.equal(again.pending().size(), 2, "pending count");
            Expect.equal(again.pending().get(0), new Pending(Pending.Action.INSTALL, "ascanrules", entry("ascanrules"),
                    "staged/x.zap"), "the install with its full entry");
            Expect.equal(again.pending().get(1), new Pending(Pending.Action.REMOVE, "spider", null, null), "the removal");
            Expect.equal(again.removedBundled(), Set.of("old"), "removed bundled");
            Expect.equal(again.seenEngines(), Set.of("2.17.0"), "seen engines");
        }));

        Harness.test("the store file is owner-only", () -> inTemp(root -> {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
            StoreRecord r = StoreRecord.load(root);
            r.seenEngines().add("2.17.0");
            r.save();
            SourceList.load(root, "2.17").save();
            Expect.equal(Files.getPosixFilePermissions(root.resolve("store")),
                    PosixFilePermissions.fromString("rw-------"), "store mode");
            Expect.equal(Files.getPosixFilePermissions(root.resolve("sources")),
                    PosixFilePermissions.fromString("rw-------"), "sources mode");
        }));

        Harness.test("two writers under the lock do not lose a change", () -> inTemp(root -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] ts = new Thread[2];
            for (int t = 0; t < ts.length; t++) {
                final String id = "w" + t;
                ts[t] = new Thread(() -> {
                    try {
                        for (int i = 0; i < 20; i++) {
                            try (StoreLock lock = StoreLock.acquire(root)) {
                                StoreRecord r = StoreRecord.load(root);
                                r.pending().add(new Pending(Pending.Action.DISABLE, id + "_" + i, null, null));
                                r.save();
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) t.join();
            if (failure.get() != null) throw new AssertionError("a writer failed: " + failure.get(), failure.get());
            Expect.equal(StoreRecord.load(root).pending().size(), 40, "every change kept");
        }));

        Harness.test("a nested acquire on one thread is refused and keeps the outer lock", () -> inTemp(root -> {
            AtomicBoolean got = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try (StoreLock l = StoreLock.acquire(root)) {
                    got.set(true);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            StoreLock outer = StoreLock.acquire(root);
            try {
                try {
                    StoreLock.acquire(root).close();
                    throw new AssertionError("expected IllegalStateException");
                } catch (IllegalStateException expected) {}
                other.start();
                long end = System.currentTimeMillis() + 5000;
                while (other.getState() != Thread.State.WAITING && System.currentTimeMillis() < end) Thread.onSpinWait();
                Expect.equal(other.getState(), Thread.State.WAITING, "the other thread waits for the outer lock");
                Expect.isFalse(got.get(), "the other thread got the lock while the outer one was held");
            } finally {
                outer.close();
            }
            other.join();
            if (failure.get() != null) throw new AssertionError("other failed: " + failure.get(), failure.get());
            Expect.isTrue(got.get(), "the other thread got the lock after the outer one closed");
        }));

        Harness.test("a corrupt store file is an IOException that names the file and the key", () -> inTemp(root -> {
            Body load = r -> StoreRecord.load(r);
            corrupt(root, "store", "installed.count=x\n", load, "installed.count");
            corrupt(root, "store", "installed.count=1\ninstalled.0.kind=NOPE\ninstalled.0.id=a\ninstalled.0.version=1\n",
                    load, "installed.0.");
            corrupt(root, "store", "installed.count=1\ninstalled.0.kind=ZAP_ADDON\ninstalled.0.version=1\n",
                    load, "installed.0.");
            corrupt(root, "store", "installed.count=1\ninstalled.0.kind=ZAP_ADDON\ninstalled.0.id=a\n",
                    load, "installed.0.");
            corrupt(root, "store", "pending.count=1\npending.0.action=EXPLODE\npending.0.id=a\n", load, "pending.0.");
            corrupt(root, "store", "pending.count=1\npending.0.action=REMOVE\npending.0.id=a\n"
                    + "pending.0.target.id=a\npending.0.target.kind=ZAP_ADDON\npending.0.target.version=1\n"
                    + "pending.0.target.status=release\npending.0.target.size=big\n", load, "pending.0.");
            corrupt(root, "store", "removedBundled.count=1\n", load, "removedBundled.0");
            corrupt(root, "store", "installed.count=\\u12\n", load, "store");
        }));

        Harness.test("a corrupt sources file is an IOException that names the file and the key", () -> inTemp(root -> {
            Body load = r -> SourceList.load(r, "2.17");
            corrupt(root, "sources", "count=x\n", load, "count");
            corrupt(root, "sources", "count=1\nsource.0.id=src-1\nsource.0.type=NOPE\n", load, "source.0.type");
            corrupt(root, "sources", "count=1\nsource.0.type=FEED_URL\n", load, "source.0.id");
            corrupt(root, "sources", "count=1\nsource.0.id=src-1\n", load, "source.0.type");
            corrupt(root, "sources", "next=-3\ncount=-1\n", load, "count");
        }));
    }
}
