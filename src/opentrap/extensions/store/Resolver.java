package opentrap.extensions.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what an install or an update needs, and from which source. A
 * dependency comes from the source it is installed from, else from ZAP's feed,
 * else from the source of the add-on that needs it; never from a third source.
 * An update comes only from the installed add-on's own line, and never with a
 * lower stability than the installed one.
 */
public final class Resolver {

    private static final Pattern FILE_STATUS = Pattern.compile("^[A-Za-z0-9_]+-(release|beta|alpha)-.*\\.zap$");

    /** A dependency no allowed source can satisfy. */
    public static final class Unresolvable extends Exception {
        public Unresolvable(String message) { super(message); }
    }

    private final Catalogue cat;
    private final Map<String, Installed> installed;
    private final Function<Installed, List<Dependency>> depsOf;

    /** Dependencies of an installed add-on are read from the catalogue's matching offer. */
    public Resolver(Catalogue cat, Map<String, Installed> installed) {
        this.cat = cat;
        this.installed = installed;
        this.depsOf = this::offeredDependencies;
    }

    public Resolver(Catalogue cat, Map<String, Installed> installed, Function<Installed, List<Dependency>> depsOf) {
        this.cat = cat;
        this.installed = installed;
        this.depsOf = depsOf;
    }

    /** Equal, or both in ZAP's feed and the bundled set, which count as one source. */
    public static boolean sameLine(String sourceA, String sourceB) {
        if (sourceA.equals(sourceB)) return true;
        return isZapLine(sourceA) && isZapLine(sourceB);
    }

    private static boolean isZapLine(String s) { return s.equals(Source.ZAP_ID) || s.equals(Source.BUNDLED_ID); }

    /** The chosen entry and every missing or too-old dependency, dependencies first, each id once. */
    public List<Entry> plan(Entry chosen) throws Unresolvable {
        List<Entry> out = new ArrayList<>();
        visit(chosen, chosen, new HashMap<>(), new HashMap<>(), out);
        return out;
    }

    /** {@code planned} holds every id met so far (also those still being walked) and {@code neededBy} who asked first. */
    private void visit(Entry e, Entry chosen, Map<String, Entry> planned, Map<String, String> neededBy,
                       List<Entry> out) throws Unresolvable {
        planned.put(e.id(), e);
        for (Dependency d : e.dependencies()) {
            Entry already = planned.get(d.id());
            if (already != null) {
                if (!d.range().matches(already.version())) {
                    throw new Unresolvable(e.id() + " needs " + d.id() + rangeText(d) + ", but "
                            + d.id() + " " + already.version() + " is already planned"
                            + (neededBy.containsKey(d.id()) ? " for " + neededBy.get(d.id()) : ""));
                }
                continue;
            }
            Installed have = installed.get(d.id());
            if (have != null && d.range().matches(have.version())) continue;
            Entry pick = best(d, have, chosen);
            if (pick == null) {
                throw new Unresolvable(e.id() + " needs " + d.id() + rangeText(d)
                        + ", which no allowed source offers");
            }
            neededBy.put(d.id(), e.id());
            visit(pick, chosen, planned, neededBy, out);
        }
        out.add(e);
    }

    private static String rangeText(Dependency d) {
        String range = d.range().toString();
        return range.isBlank() ? "" : " " + range;
    }

    private Entry best(Dependency d, Installed have, Entry chosen) {
        List<Entry> matching = new ArrayList<>();
        for (Entry o : cat.offers(d.id())) if (d.range().matches(o.version())) matching.add(o);
        List<Entry> allowed = new ArrayList<>();
        if (have != null) {
            for (Entry o : matching) if (sameLine(have.sourceId(), o.sourceId())) allowed.add(o);
        } else {
            for (Entry o : matching) if (sameLine(Source.ZAP_ID, o.sourceId())) allowed.add(o);
            if (allowed.isEmpty()) {
                for (Entry o : matching) if (sameLine(chosen.sourceId(), o.sourceId())) allowed.add(o);
            }
        }
        return allowed.stream().max(Comparator
                .comparingInt((Entry o) -> o.status().rank()).thenComparing(Entry::version)).orElse(null);
    }

    /** One entry per installed id that has a newer version from its own line, plus new builds; ordered by id. */
    public List<Entry> updates() {
        Map<String, Entry> out = new TreeMap<>();
        for (Installed in : installed.values()) {
            int floor = statusOf(in).rank();
            List<Entry> newer = new ArrayList<>();
            Entry build = null;
            for (Entry o : cat.offers(in.id())) {
                if (!sameLine(in.sourceId(), o.sourceId()) || o.status().rank() < floor) continue;
                int c = o.version().compareTo(in.version());
                if (c > 0) newer.add(o);
                else if (c == 0 && build == null && isNewBuild(o)) build = o;
            }
            Entry pick = newer.isEmpty() ? build : newer.stream().max(Comparator.comparing(Entry::version)).get();
            if (pick != null) out.put(in.id(), pick);
        }
        return List.copyOf(out.values());
    }

    /** Same version as installed, another hash, from a local folder: the file was rebuilt. */
    public boolean isNewBuild(Entry e) {
        Installed in = installed.get(e.id());
        if (in == null || in.sha256() == null || !in.version().equals(e.version())) return false;
        if (in.sha256().equalsIgnoreCase(e.sha256()) || !sameLine(in.sourceId(), e.sourceId())) return false;
        return cat.source(e.sourceId()).map(s -> s.type() == SourceType.LOCAL_FOLDER).orElse(false);
    }

    /** Active installed add-ons whose dependencies name {@code id}. */
    public List<String> dependents(String id) {
        List<String> out = new ArrayList<>();
        for (Installed in : installed.values()) {
            if (in.active() && !in.id().equals(id) && names(in, id)) out.add(in.id());
        }
        out.sort(null);
        return out;
    }

    /** Installed but disabled add-ons that {@code id} depends on. */
    public List<String> needs(String id) {
        Installed in = installed.get(id);
        if (in == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Dependency d : depsOf.apply(in)) {
            Installed dep = installed.get(d.id());
            if (dep != null && !dep.active() && !out.contains(d.id())) out.add(d.id());
        }
        out.sort(null);
        return out;
    }

    private boolean names(Installed in, String id) {
        for (Dependency d : depsOf.apply(in)) if (d.id().equals(id)) return true;
        return false;
    }

    List<Dependency> offeredDependencies(Installed in) {
        String sha = in.sha256() == null ? "" : in.sha256();
        Entry match = null;
        for (Entry o : cat.offers(in.id())) {
            if (!o.version().equals(in.version())) continue;
            if (o.sha256().equalsIgnoreCase(sha)) return o.dependencies();
            if (match == null) match = o;
        }
        return match == null ? List.of() : match.dependencies();
    }

    /** Stability of the installed file: from its name, else from the offer with its version, else the lowest. */
    private Status statusOf(Installed in) {
        Matcher m = in.file() == null ? null : FILE_STATUS.matcher(in.file());
        if (m != null && m.matches()) return Status.parse(m.group(1));
        for (Entry o : cat.offers(in.id())) if (o.version().equals(in.version())) return o.status();
        return Status.ALPHA;
    }
}
