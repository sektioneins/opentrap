package opentrap.session;

import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * Decides whether a domain is a public suffix (a name nobody owns, such as
 * {@code com}, {@code co.uk} or {@code github.io}), using the list the JDK
 * ships in {@code lib/security/public_suffix_list.dat}. That file is a zip with
 * one entry per TLD, named in Unicode; each line of an entry is a type byte
 * (0 ICANN, 1 private), a rule ({@code co.uk}, {@code *.sch.uk},
 * {@code !www.ck}) and a newline. Rules are read per TLD on first use and
 * cached, except after a failed read, which is tried again. When the file is
 * missing or unreadable only the list's default rule applies: a single label
 * is a public suffix.
 */
final class PublicSuffixes {

    private record Rule(String[] labels, boolean exception) { }

    private static final List<Rule> NONE = List.of();
    private static volatile PublicSuffixes shared;

    private final Path file;
    private final ConcurrentHashMap<String, List<Rule>> rulesByTld = new ConcurrentHashMap<>();

    private PublicSuffixes(Path file) { this.file = file; }

    /** The shared instance over the JDK's own list. */
    static PublicSuffixes jdk() {
        PublicSuffixes p = shared;
        if (p == null) {
            synchronized (PublicSuffixes.class) {
                p = shared;
                if (p == null) {
                    p = new PublicSuffixes(Path.of(System.getProperty("java.home"),
                            "lib", "security", "public_suffix_list.dat"));
                    shared = p;
                }
            }
        }
        return p;
    }

    /** An instance over {@code file}; never throws, a bad file means the default rule only. */
    static PublicSuffixes load(Path file) { return new PublicSuffixes(file); }

    /** True when {@code domain} (lower case, no leading dot) is itself a public suffix. */
    boolean isPublicSuffix(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        String[] labels = domain.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) labels[i] = unicode(labels[i]);
        int n = labels.length;
        int suffix = 1; // default rule
        int best = 0;
        for (Rule r : rulesFor(labels[n - 1])) {
            int len = r.labels.length;
            if (len > n || !matches(r, labels)) continue;
            if (r.exception) return n == len - 1;
            best = Math.max(best, len);
        }
        if (best > 0) suffix = best;
        return suffix == n;
    }

    private static boolean matches(Rule r, String[] labels) {
        int off = labels.length - r.labels.length;
        for (int i = 0; i < r.labels.length; i++) {
            String rl = r.labels[i];
            if (!rl.equals("*") && !rl.equals(labels[off + i])) return false;
        }
        return true;
    }

    private static String unicode(String label) {
        try {
            return IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED);
        } catch (RuntimeException e) {
            return label;
        }
    }

    private List<Rule> rulesFor(String tld) {
        List<Rule> rules = rulesByTld.get(tld);
        if (rules != null) return rules;
        rules = read(tld);
        if (rules == null) return NONE;   // a failed read is not cached, so a later lookup may succeed
        List<Rule> raced = rulesByTld.putIfAbsent(tld, rules);
        return raced != null ? raced : rules;
    }

    /** The rules of {@code tld}, empty when the list lacks it, or null when reading failed. */
    private List<Rule> read(String tld) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry(tld);
            if (entry == null) return NONE;
            String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            List<Rule> rules = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (line.length() < 2) continue;
                String rule = line.substring(1); // drop the type byte
                boolean exception = rule.startsWith("!");
                if (exception) rule = rule.substring(1);
                rules.add(new Rule(rule.split("\\.", -1), exception));
            }
            return rules;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
