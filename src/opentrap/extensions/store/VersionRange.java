package opentrap.extensions.store;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A dependency range as ZAP's feed writes it: terms joined by {@code &}, all of
 * which must hold. A term is {@code >=}, {@code >}, {@code <=}, {@code <} or
 * {@code =} (or none, meaning exact) followed by a version, or a wildcard such
 * as {@code 13.*} or {@code 2.3.*} that fixes the leading parts.
 */
public final class VersionRange {

    /** No bounds: matches every version. */
    public static final VersionRange ANY = new VersionRange("", List.of());

    private final String text;
    private final List<Predicate<Version>> terms;

    private VersionRange(String text, List<Predicate<Version>> terms) {
        this.text = text;
        this.terms = terms;
    }

    /** Null or blank is {@link #ANY}; an unrecognised form is refused. */
    public static VersionRange parse(String s) {
        if (s == null || s.isBlank()) return ANY;
        List<Predicate<Version>> terms = new ArrayList<>();
        for (String raw : s.split("&", -1)) terms.add(term(raw.trim(), s));
        return new VersionRange(s.trim(), List.copyOf(terms));
    }

    private static Predicate<Version> term(String t, String whole) {
        if (t.isEmpty()) throw new IllegalArgumentException("Not a version range: " + whole);
        if (t.endsWith(".*")) {
            String[] lead = t.substring(0, t.length() - 2).split("\\.", -1);
            if (lead.length > 2) throw new IllegalArgumentException("Not a version range: " + whole);
            int[] fixed = new int[lead.length];
            for (int i = 0; i < lead.length; i++) fixed[i] = Version.parse(lead[i]).part(0);
            return v -> {
                for (int i = 0; i < fixed.length; i++) if (v.part(i) != fixed[i]) return false;
                return true;
            };
        }
        String op = "";
        for (String o : new String[] {">=", "<=", ">", "<", "="}) {
            if (t.startsWith(o)) { op = o; break; }
        }
        Version bound = Version.parse(t.substring(op.length()).trim());
        return switch (op) {
            case ">=" -> v -> v.compareTo(bound) >= 0;
            case ">" -> v -> v.compareTo(bound) > 0;
            case "<=" -> v -> v.compareTo(bound) <= 0;
            case "<" -> v -> v.compareTo(bound) < 0;
            default -> v -> v.compareTo(bound) == 0;
        };
    }

    public boolean matches(Version v) {
        for (Predicate<Version> t : terms) if (!t.test(v)) return false;
        return true;
    }

    @Override public String toString() { return text; }
}
