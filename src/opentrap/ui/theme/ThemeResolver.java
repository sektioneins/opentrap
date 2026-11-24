package opentrap.ui.theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines a theme with its light or dark base into the value of every key in
 * the key spec. The theme's constants replace or add to the base's, every key
 * is evaluated from the base's definition with them, and the keys the theme sets
 * itself then replace those values.
 */
public final class ThemeResolver {

    /**
     * The outcome of a resolve.
     *
     * @param values      the value of every spec key, in spec order
     * @param leftToBase  the spec keys the theme does not set
     * @param unknownKeys the theme's keys that the spec does not have
     * @param clamped     one note for each base-derived number moved into its range
     */
    public record Resolved(Map<String, Object> values, String appearance, List<String> leftToBase,
                           List<String> unknownKeys, List<String> clamped, List<String> ignoredHeader) { }

    /** Carries a failure of a constant through the evaluator's lookup function. */
    private static final class ConstantFailure extends RuntimeException {
        final ThemeException cause;

        ConstantFailure(ThemeException cause) {
            super(cause.getMessage(), cause, false, false);
            this.cause = cause;
        }
    }

    private static final KeyType.Number UNBOUNDED = new KeyType.Number(Integer.MIN_VALUE, Integer.MAX_VALUE);

    private final Map<String, ThemeFile.Def> defs = new LinkedHashMap<>();
    private final Map<String, Boolean> fromTheme = new HashMap<>();
    private final Map<String, Object> done = new HashMap<>();
    private final List<String> visiting = new ArrayList<>();
    /** The theme constant looked up last, so that an error can be pointed at it. */
    private ThemeFile.Def lastThemeConstant;

    private ThemeResolver() { }

    public static Resolved resolve(ThemeFile base, ThemeFile theme, Map<String, KeyType> spec)
            throws ThemeException {
        if (theme != null && theme.base() == null) throw new ThemeException(0, "theme has no base");
        return new ThemeResolver().run(base, theme, spec);
    }

    private Resolved run(ThemeFile base, ThemeFile theme, Map<String, KeyType> spec) throws ThemeException {
        defs.putAll(base.constants());
        for (String name : base.constants().keySet()) fromTheme.put(name, false);
        if (theme != null) {
            defs.putAll(theme.constants());
            for (String name : theme.constants().keySet()) fromTheme.put(name, true);
        }
        for (String name : defs.keySet()) constant(name);

        Map<String, Object> values = new LinkedHashMap<>();
        List<String> clamped = new ArrayList<>();
        List<String> leftToBase = new ArrayList<>();
        for (Map.Entry<String, KeyType> e : spec.entrySet()) {
            String key = e.getKey();
            KeyType type = e.getValue();
            ThemeFile.Def own = theme == null ? null : theme.keys().get(key);
            if (own != null) {
                values.put(key, eval(key, own, type));
                continue;
            }
            if (theme != null) leftToBase.add(key);
            ThemeFile.Def def = base.keys().get(key);
            if (def == null) throw new ThemeException(0, base.name() + " does not define " + key);
            lastThemeConstant = null;
            Object v;
            try {
                v = eval(key, def, type instanceof KeyType.Number ? UNBOUNDED : type);
            } catch (ThemeException ex) {
                if (lastThemeConstant == null) throw ex;
                throw new ThemeException(lastThemeConstant.line(), stripLine(ex) + " (from the "
                    + base.appearance() + " base)");
            }
            if (type instanceof KeyType.Number n) {
                int raw = (Integer) v;
                int fixed = Math.max(n.min(), Math.min(n.max(), raw));
                if (fixed != raw) clamped.add(key + " " + raw + " clamped to " + fixed);
                v = fixed;
            }
            values.put(key, v);
        }
        List<String> unknown = new ArrayList<>();
        if (theme != null) {
            for (String key : theme.keys().keySet()) if (!spec.containsKey(key)) unknown.add(key);
        }
        return new Resolved(values, theme != null ? theme.appearance() : base.appearance(), leftToBase,
            unknown, clamped, theme != null ? theme.ignoredHeader() : List.of());
    }

    private Object eval(String key, ThemeFile.Def def, KeyType type) throws ThemeException {
        try {
            return ThemeExpr.eval(key, def.text(), type, this::lookup, def.line());
        } catch (ConstantFailure f) {
            throw f.cause;
        }
    }

    private Object lookup(String name) {
        if (!defs.containsKey(name)) return null;
        try {
            Object v = constant(name);
            if (fromTheme.get(name)) lastThemeConstant = defs.get(name);
            return v;
        } catch (ThemeException e) {
            throw new ConstantFailure(e);
        }
    }

    /** Evaluates one constant on first use. */
    private Object constant(String name) throws ThemeException {
        Object known = done.get(name);
        if (known != null) return known;
        ThemeFile.Def def = defs.get(name);
        int at = visiting.indexOf(name);
        if (at >= 0) {
            StringBuilder cycle = new StringBuilder("cycle: ");
            for (int i = at; i < visiting.size(); i++) cycle.append('@').append(visiting.get(i)).append(" -> ");
            throw new ThemeException(def.line(), cycle.append('@').append(name).toString());
        }
        visiting.add(name);
        try {
            Object v = ThemeExpr.evalConstant(def.text(), this::lookup, def.line());
            done.put(name, v);
            return v;
        } catch (ConstantFailure f) {
            throw f.cause;
        } finally {
            visiting.remove(visiting.size() - 1);
        }
    }

    private static String stripLine(ThemeException e) {
        String m = e.getMessage();
        return e.line() > 0 ? m.substring(m.indexOf(": ") + 2) : m;
    }
}
