package opentrap.extensions.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.TestTls;
import opentrap.testkit.ZapFixtures;

/** Dependencies, which source an add-on comes from, and updates. */
public final class ResolverTest {

    private static final Version ENGINE = Version.parse("2.17.0");
    private static final VersionRange COMMON = VersionRange.parse(">= 1.29.0 & < 2.0.0");
    private static final String ZERO = "0".repeat(64);

    /** Local folders stand in for sources; the source id is the folder's name. */
    private static final class World {
        final Map<String, Path> folders = new HashMap<>();
        final List<Source> sources = new ArrayList<>();
        final Map<String, Installed> installed = new HashMap<>();

        World(String... sourceIds) {
            for (String id : sourceIds) {
                Path dir = tmp();
                folders.put(id, dir);
                sources.add(new Source(id, id, SourceType.LOCAL_FOLDER, dir.toString(), "", true));
            }
        }

        Path offer(String source, String id, String version, String status) {
            return ZapFixtures.zap(folders.get(source), id, version, status);
        }

        Catalogue catalogue() {
            Catalogue c = new Catalogue(tmp(), ENGINE, new Fetcher(TestTls.pair("127.0.0.1").client(), h -> null));
            c.loadCache(sources);
            return c;
        }

        void have(String id, String version, String status, String source, boolean active, String sha) {
            installed.put(id, new Installed(Kind.ZAP_ADDON, id, Version.parse(version),
                    id + "-" + status + "-" + version + ".zap", source, "", active, sha));
        }

        Resolver resolver(Catalogue c) { return new Resolver(c, installed); }
    }

    private static Path tmp() {
        try { return Files.createTempDirectory("opentrap-resolver"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Entry offered(Catalogue c, String id, String source) {
        return c.offers(id).stream().filter(e -> e.sourceId().equals(source)).findFirst().orElseThrow();
    }

    private static Entry needing(Entry e, String depId, VersionRange range) {
        return new Entry(e.kind(), e.sourceId(), e.id(), e.name(), e.description(), e.author(), e.version(),
                e.status(), e.date(), e.changes(), e.infoUrl(), e.size(), e.file(), e.location(), e.sha256(),
                List.of(new Dependency(depId, range)));
    }

    private static Entry withNeeds(Entry e, String... depIds) {
        List<Dependency> deps = new ArrayList<>();
        for (String d : depIds) deps.add(new Dependency(d, VersionRange.ANY));
        return new Entry(e.kind(), e.sourceId(), e.id(), e.name(), e.description(), e.author(), e.version(),
                e.status(), e.date(), e.changes(), e.infoUrl(), e.size(), e.file(), e.location(), e.sha256(),
                List.copyOf(deps));
    }

    private static List<Entry> plan(Resolver r, Entry e) {
        try { return r.plan(e); }
        catch (Resolver.Unresolvable x) { throw new AssertionError(x.getMessage(), x); }
    }

    private static List<String> ids(List<Entry> l) { return l.stream().map(Entry::id).toList(); }

    public void run() {
        Harness.suite("Extension store dependencies");

        Harness.test("an install pulls in a missing dependency before the add-on", () -> {
            World w = new World("mine");
            w.offer("mine", "retire", "1.0.0", "release");
            w.offer("mine", "commonlib", "1.29.1", "release");
            Catalogue c = w.catalogue();
            Entry retire = needing(offered(c, "retire", "mine"), "commonlib", COMMON);
            Expect.equal(ids(plan(w.resolver(c), retire)), List.of("commonlib", "retire"), "plan");
        });

        Harness.test("a dependency already installed at a matching version is not fetched again", () -> {
            World w = new World("mine");
            w.offer("mine", "retire", "1.0.0", "release");
            w.offer("mine", "commonlib", "1.30.0", "release");
            w.have("commonlib", "1.29.0", "release", "mine", true, ZERO);
            Catalogue c = w.catalogue();
            Entry retire = needing(offered(c, "retire", "mine"), "commonlib", COMMON);
            Expect.equal(ids(plan(w.resolver(c), retire)), List.of("retire"), "plan");
        });

        Harness.test("an installed dependency that is too old is upgraded from its own line", () -> {
            World w = new World("zap", "mine");
            w.offer("mine", "retire", "1.0.0", "release");
            w.offer("mine", "commonlib", "1.30.0", "release");
            w.offer("zap", "commonlib", "1.31.0", "release");
            w.have("commonlib", "1.20.0", "release", "mine", true, ZERO);
            Catalogue c = w.catalogue();
            Entry retire = needing(offered(c, "retire", "mine"), "commonlib", COMMON);
            List<Entry> plan = plan(w.resolver(c), retire);
            Expect.equal(plan.get(0).sourceId(), "mine", "source");
            Expect.equal(plan.get(0).version(), Version.parse("1.30.0"), "version");
        });

        Harness.test("a dependency comes from ZAP's feed before the add-on's own source", () -> {
            World w = new World("zap", "mine");
            w.offer("mine", "retire", "1.0.0", "release");
            w.offer("mine", "commonlib", "1.40.0", "release");
            w.offer("zap", "commonlib", "1.30.0", "release");
            Catalogue c = w.catalogue();
            Entry retire = needing(offered(c, "retire", "mine"), "commonlib", COMMON);
            Expect.equal(plan(w.resolver(c), retire).get(0).sourceId(), "zap", "source");
        });

        Harness.test("a dependency only a third source offers is unresolvable", () -> {
            World w = new World("zap", "mine", "other");
            w.offer("mine", "retire", "1.0.0", "release");
            w.offer("other", "commonlib", "1.30.0", "release");
            Catalogue c = w.catalogue();
            Entry retire = needing(offered(c, "retire", "mine"), "commonlib", COMMON);
            try {
                w.resolver(c).plan(retire);
                Expect.isTrue(false, "should not resolve");
            } catch (Resolver.Unresolvable e) {
                Expect.equal(e.getMessage(),
                        "retire needs commonlib >= 1.29.0 & < 2.0.0, which no allowed source offers", "message");
            }
        });

        Harness.test("a dependency cycle ends the plan instead of looping", () -> {
            World w = new World("mine");
            ZapFixtures.zap(w.folders.get("mine"), "aa", "1.0.0", "release", "bb", "");
            ZapFixtures.zap(w.folders.get("mine"), "bb", "1.0.0", "release", "aa", "");
            Catalogue c = w.catalogue();
            Resolver r = new Resolver(c, Map.of());
            Expect.equal(ids(plan(r, offered(c, "aa", "mine"))), List.of("bb", "aa"), "plan");
            Expect.equal(ids(plan(r, offered(c, "bb", "mine"))), List.of("aa", "bb"), "other way");
        });

        Harness.test("a chain of dependencies is planned deepest first", () -> {
            World w = new World("mine");
            ZapFixtures.zap(w.folders.get("mine"), "top", "1.0.0", "release", "mid", "");
            ZapFixtures.zap(w.folders.get("mine"), "mid", "1.0.0", "release", "low", ">= 1.0.0");
            w.offer("mine", "low", "1.2.0", "release");
            Catalogue c = w.catalogue();
            Expect.equal(ids(plan(w.resolver(c), offered(c, "top", "mine"))), List.of("low", "mid", "top"), "plan");
        });

        Harness.test("a dependency two add-ons share is planned once", () -> {
            World w = new World("mine");
            ZapFixtures.zap(w.folders.get("mine"), "top", "1.0.0", "release", "left", "", "right", "");
            ZapFixtures.zap(w.folders.get("mine"), "left", "1.0.0", "release", "base", "");
            ZapFixtures.zap(w.folders.get("mine"), "right", "1.0.0", "release", "base", "");
            w.offer("mine", "base", "1.0.0", "release");
            Catalogue c = w.catalogue();
            Expect.equal(ids(plan(w.resolver(c), offered(c, "top", "mine"))),
                    List.of("base", "left", "right", "top"), "plan");
        });

        Harness.test("a planned dependency that a later add-on's range rejects is unresolvable", () -> {
            World w = new World("mine");
            ZapFixtures.zap(w.folders.get("mine"), "top", "1.0.0", "release", "left", "", "right", "");
            ZapFixtures.zap(w.folders.get("mine"), "left", "1.0.0", "release", "base", "< 2.0.0");
            ZapFixtures.zap(w.folders.get("mine"), "right", "1.0.0", "release", "base", ">= 2.0.0");
            w.offer("mine", "base", "1.0.0", "release");
            Catalogue c = w.catalogue();
            try {
                plan(w.resolver(c), offered(c, "top", "mine"));
                Expect.isTrue(false, "should not resolve");
            } catch (AssertionError e) {
                Expect.equal(e.getMessage(),
                        "right needs base >= 2.0.0, but base 1.0.0 is already planned for left", "message");
            }
        });

        Harness.test("a dependency of a dependency comes from the chosen add-on's source when ZAP lacks it", () -> {
            World w = new World("zap", "mine");
            ZapFixtures.zap(w.folders.get("mine"), "top", "1.0.0", "release", "aa", "");
            ZapFixtures.zap(w.folders.get("zap"), "aa", "1.0.0", "release", "bb", "");
            w.offer("mine", "bb", "1.0.0", "release");
            Catalogue c = w.catalogue();
            List<Entry> plan = plan(w.resolver(c), offered(c, "top", "mine"));
            Expect.equal(ids(plan), List.of("bb", "aa", "top"), "order");
            Expect.equal(plan.get(0).sourceId(), "mine", "bb source");
            Expect.equal(plan.get(1).sourceId(), "zap", "aa source");
        });

        Harness.test("a dependency from the bundled set counts as ZAP's feed", () -> {
            World w = new World("mine");
            w.offer("mine", "top", "1.0.0", "release");
            w.offer("mine", "base", "1.0.0", "release");
            Catalogue c = w.catalogue();
            Entry bundled = new Entry(Kind.ZAP_ADDON, Source.BUNDLED_ID, "base", "", "", "", Version.parse("1.0.0"),
                    Status.RELEASE, "", "", "", 0, "base-release-1.0.0.zap", "/x", ZERO, List.of());
            c.setBundled(new Listing(List.of(bundled), List.of()));
            Entry top = needing(offered(c, "top", "mine"), "base", VersionRange.ANY);
            Expect.equal(plan(w.resolver(c), top).get(0).sourceId(), Source.BUNDLED_ID, "source");
        });

        Harness.test("a bundled add-on is updated from ZAP's feed without a source switch", () -> {
            World w = new World("zap");
            w.offer("zap", "retire", "1.1.0", "release");
            w.have("retire", "1.0.0", "release", Source.BUNDLED_ID, true, ZERO);
            Entry old = new Entry(Kind.ZAP_ADDON, Source.BUNDLED_ID, "retire", "", "", "", Version.parse("1.0.0"),
                    Status.RELEASE, "", "", "", 0, "retire-release-1.0.0.zap", "/x", ZERO, List.of());
            Catalogue c = w.catalogue();
            c.setBundled(new Listing(List.of(old), List.of()));
            List<Entry> up = w.resolver(c).updates();
            Expect.equal(ids(up), List.of("retire"), "update");
            Expect.equal(up.get(0).sourceId(), "zap", "from zap");
            Expect.equal(up.get(0).version(), Version.parse("1.1.0"), "version");
        });

        Harness.test("an add-on from a custom source is never updated from ZAP's feed", () -> {
            World w = new World("zap", "mine");
            Path f = w.offer("mine", "retire", "1.0.0", "release");
            w.offer("zap", "retire", "2.0.0", "release");
            w.have("retire", "1.0.0", "release", "mine", true, ZapFixtures.sha256(f));
            Expect.equal(w.resolver(w.catalogue()).updates().size(), 0, "no update");
        });

        Harness.test("an update never moves a release add-on to beta", () -> {
            World w = new World("zap");
            Path f = w.offer("zap", "retire", "1.0.0", "release");
            w.offer("zap", "retire", "2.0.0", "beta");
            w.have("retire", "1.0.0", "release", "zap", true, ZapFixtures.sha256(f));
            Expect.equal(w.resolver(w.catalogue()).updates().size(), 0, "no update");
            w.offer("zap", "retire", "1.5.0", "release");
            Expect.equal(ids(w.resolver(w.catalogue()).updates()), List.of("retire"), "release update is offered");
        });

        Harness.test("a rebuilt file with the installed version is a new build", () -> {
            World w = new World("mine");
            Path f = w.offer("mine", "retire", "1.0.0", "release");
            w.have("retire", "1.0.0", "release", "mine", true, ZERO);
            Catalogue c = w.catalogue();
            Resolver r = w.resolver(c);
            Expect.equal(ids(r.updates()), List.of("retire"), "listed");
            Expect.isTrue(r.isNewBuild(r.updates().get(0)), "new build");
            w.have("retire", "1.0.0", "release", "mine", true, ZapFixtures.sha256(f));
            Expect.equal(w.resolver(c).updates().size(), 0, "same file is no update");
        });

        Harness.test("updates come back ordered by id", () -> {
            World w = new World("zap");
            for (String id : List.of("zeta", "alpha", "mid")) {
                w.offer("zap", id, "2.0.0", "release");
                w.have(id, "1.0.0", "release", "zap", true, ZERO);
            }
            Expect.equal(ids(w.resolver(w.catalogue()).updates()), List.of("alpha", "mid", "zeta"), "order");
        });

        Harness.test("disabling commonlib names the active add-ons that need it", () -> {
            World w = new World("mine");
            w.have("commonlib", "1.30.0", "release", "mine", true, ZERO);
            w.have("retire", "1.0.0", "release", "mine", true, ZERO);
            w.have("oast", "1.0.0", "release", "mine", false, ZERO);
            w.have("other", "1.0.0", "release", "mine", true, ZERO);
            Resolver r = new Resolver(w.catalogue(), w.installed, in ->
                    in.id().equals("other") ? List.of() : List.of(new Dependency("commonlib", COMMON)));
            Expect.equal(r.dependents("commonlib"), List.of("retire"), "active dependents only");
        });

        Harness.test("installed dependencies default to the offer with the same version and hash", () -> {
            World w = new World("mine", "other");
            ZapFixtures.zap(w.folders.get("mine"), "retire", "1.0.0", "release", "commonlib", "");
            Path theirs = ZapFixtures.zap(w.folders.get("other"), "retire", "1.0.0", "release", "oast", "");
            w.have("commonlib", "1.0.0", "release", "mine", true, ZERO);
            w.have("oast", "1.0.0", "release", "mine", true, ZERO);
            w.have("retire", "1.0.0", "release", "other", true, ZapFixtures.sha256(theirs));
            Resolver r = w.resolver(w.catalogue());
            Expect.equal(r.dependents("oast"), List.of("retire"), "same hash wins");
            Expect.equal(r.dependents("commonlib"), List.of(), "other offer ignored");
        });

        Harness.test("installed dependencies are empty when no offer has the installed version", () -> {
            World w = new World("mine");
            ZapFixtures.zap(w.folders.get("mine"), "retire", "2.0.0", "release", "commonlib", "");
            w.have("commonlib", "1.0.0", "release", "mine", false, ZERO);
            w.have("retire", "1.0.0", "release", "mine", true, ZERO);
            Resolver r = w.resolver(w.catalogue());
            Expect.equal(r.dependents("commonlib"), List.of(), "dependents");
            Expect.equal(r.needs("retire"), List.of(), "needs");
        });

        Harness.test("enabling an add-on names the disabled add-ons it needs", () -> {
            World w = new World("mine");
            w.have("commonlib", "1.30.0", "release", "mine", false, ZERO);
            w.have("retire", "1.0.0", "release", "mine", false, ZERO);
            Resolver r = new Resolver(w.catalogue(), w.installed, in ->
                    in.id().equals("retire") ? List.of(new Dependency("commonlib", COMMON)) : List.of());
            Expect.equal(r.needs("retire"), List.of("commonlib"), "needs");
            Expect.equal(r.needs("commonlib"), List.of(), "none");
        });

        Harness.test("an install names the add-ons it needs that would not run", () -> {
            World w = new World("mine");
            w.offer("mine", "foo", "1.0.0", "release");
            w.have("lib", "1.0.0", "release", "mine", false, ZERO);
            w.have("gone", "1.0.0", "release", "mine", true, ZERO);
            w.have("fine", "1.0.0", "release", "mine", true, ZERO);
            w.have("deep", "1.0.0", "release", "mine", false, ZERO);
            Catalogue c = w.catalogue();
            Resolver r = new Resolver(c, w.installed, in ->
                    in.id().equals("lib") ? List.of(new Dependency("deep", VersionRange.ANY)) : List.of());
            Entry foo = withNeeds(offered(c, "foo", "mine"), "lib", "gone", "fine");
            Expect.equal(r.keptOff(List.of(foo), List.of(new Pending(Pending.Action.REMOVE, "gone", null, null))),
                    List.of("lib", "gone", "deep"), "disabled, queued to go, and what a disabled one needs");
        });

        Harness.test("an update names a disabled dependency it updates, but not for a disabled add-on", () -> {
            World w = new World("mine");
            w.offer("mine", "lib", "2.0.0", "release");
            w.offer("mine", "foo", "2.0.0", "release");
            w.offer("mine", "bar", "2.0.0", "release");
            w.have("lib", "1.0.0", "release", "mine", false, ZERO);
            w.have("foo", "1.0.0", "release", "mine", true, ZERO);
            w.have("bar", "1.0.0", "release", "mine", false, ZERO);
            Catalogue c = w.catalogue();
            Resolver r = w.resolver(c);
            Entry lib = offered(c, "lib", "mine");
            Expect.equal(r.keptOff(List.of(lib, withNeeds(offered(c, "foo", "mine"), "lib")), List.of()),
                    List.of("lib"), "an update keeps lib disabled, and foo runs");
            Expect.equal(r.keptOff(List.of(lib, withNeeds(offered(c, "bar", "mine"), "lib")), List.of()),
                    List.of(), "bar stays disabled, so lib need not run");
        });

        Harness.test("same line means equal sources or ZAP's feed with the bundled set", () -> {
            Expect.isTrue(Resolver.sameLine("zap", "bundled"), "zap and bundled");
            Expect.isTrue(Resolver.sameLine("mine", "mine"), "equal");
            Expect.isTrue(!Resolver.sameLine("mine", "zap"), "custom and zap");
        });
    }
}
