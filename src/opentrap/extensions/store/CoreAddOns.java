package opentrap.extensions.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The add-ons each OpenTRAP feature needs in the engine. Each set was checked
 * against ZAP 2.17.0 by starting it without one add-on at a time: the
 * feature's API stopped answering, or its rules were gone.
 */
public final class CoreAddOns {

    private CoreAddOns() {}

    public enum Feature {
        CRAWL("Crawl", "spider", "network", "commonlib", "database"),
        AJAX_CRAWL("AJAX crawl", "spiderAjax", "selenium", "network", "commonlib"),
        AUDIT("Audit", "ascanrules", "oast", "pscan", "network", "commonlib", "database"),
        LIVE_AUDIT("Live audit", "pscan", "pscanrules", "network", "commonlib");

        private final String label;
        private final Set<String> needs;

        Feature(String label, String... needs) {
            this.label = label;
            this.needs = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(needs)));
        }

        public String label() { return label; }
    }

    /** The add-on ids the feature needs, in a fixed order. */
    public static Set<String> needs(Feature f) { return f.needs; }

    /** The features that stop working without {@code id}. */
    public static List<Feature> stoppedBy(String id) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : Feature.values()) if (f.needs.contains(id)) out.add(f);
        return out;
    }

    /** The add-ons the feature needs that are not installed or not active. */
    public static List<String> missing(Feature f, Map<String, Installed> installed) {
        List<String> out = new ArrayList<>();
        for (String id : f.needs) {
            Installed in = installed.get(id);
            if (in == null || !in.active()) out.add(id);
        }
        return out;
    }

    /** What a feature says where it is started when {@code ids} are not active. */
    public static String missingMessage(Feature f, List<String> ids) {
        return f.label() + " needs the add-on(s) " + String.join(", ", ids)
                + ", which are not active. Enable them in Extensions > Installed.";
    }
}
