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
            w.offer("mine", "aa", "1.0.0", "release");
            w.offer("mine", "bb", "1.0.0", "release");
            Catalogue c = w.catalogue();
            Entry bb = needing(offered(c, "bb", "mine"), "aa", VersionRange.ANY);
            Entry aa = needing(offered(c, "aa", "mine"), "bb", VersionRange.ANY);
            Resolver r = new Resolver(c, Map.of());
            Expect.equal(ids(plan(r, aa)), List.of("bb", "aa"), "plan");
            Expect.equal(ids(plan(r, bb)), List.of("aa", "bb"), "other way");
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

        Harness.test("enabling an add-on names the disabled add-ons it needs", () -> {
            World w = new World("mine");
            w.have("commonlib", "1.30.0", "release", "mine", false, ZERO);
            w.have("retire", "1.0.0", "release", "mine", false, ZERO);
            Resolver r = new Resolver(w.catalogue(), w.installed, in ->
                    in.id().equals("retire") ? List.of(new Dependency("commonlib", COMMON)) : List.of());
            Expect.equal(r.needs("retire"), List.of("commonlib"), "needs");
            Expect.equal(r.needs("commonlib"), List.of(), "none");
        });

        Harness.test("same line means equal sources or ZAP's feed with the bundled set", () -> {
            Expect.isTrue(Resolver.sameLine("zap", "bundled"), "zap and bundled");
            Expect.isTrue(Resolver.sameLine("mine", "mine"), "equal");
            Expect.isTrue(!Resolver.sameLine("mine", "zap"), "custom and zap");
        });
    }
}
