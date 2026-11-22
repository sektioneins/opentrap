package opentrap.testkit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/**
 * A preferences node that keeps its keys and children in memory. The test run
 * installs it through {@link MemoryPreferencesFactory}, so nothing a suite
 * stores reaches the developer's real preferences.
 */
public final class MemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, MemoryPreferences> children = new LinkedHashMap<>();

    public MemoryPreferences(MemoryPreferences parent, String name) {
        super(parent, name);
    }

    @Override protected void putSpi(String key, String value) { values.put(key, value); }
    @Override protected String getSpi(String key) { return values.get(key); }
    @Override protected void removeSpi(String key) { values.remove(key); }
    @Override protected void removeNodeSpi() {
        MemoryPreferences p = (MemoryPreferences) parent();
        if (p != null) p.children.remove(name());
    }
    @Override protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
    @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
    @Override protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n));
    }
    @Override protected void syncSpi() { }
    @Override protected void flushSpi() { }
}
