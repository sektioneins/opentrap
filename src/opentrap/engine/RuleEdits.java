package opentrap.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opentrap.core.Debug;

/**
 * The passive and active rule edits made during this run, kept so they can be
 * sent again to a restarted engine. Each engine start gets a fresh temp home,
 * so an edit sent only to the engine is gone after a restart.
 *
 * <p>Only the latest value per rule and setting is kept. Replay sends them in
 * the order each rule and setting was first edited. Safe to use from any thread.
 */
public final class RuleEdits {

    private enum Setting { PASSIVE_ENABLED, PASSIVE_THRESHOLD, ACTIVE_ENABLED, ACTIVE_STRENGTH, ACTIVE_THRESHOLD }

    private record Key(String id, Setting setting) {}

    private final Map<Key, String> edits = new LinkedHashMap<>();   // a put on a held key keeps its place

    public void passiveEnabled(String id, boolean on) { record(id, Setting.PASSIVE_ENABLED, String.valueOf(on)); }
    public void passiveThreshold(String id, String t) { record(id, Setting.PASSIVE_THRESHOLD, t); }
    public void activeEnabled(String id, boolean on) { record(id, Setting.ACTIVE_ENABLED, String.valueOf(on)); }
    public void activeStrength(String id, String s) { record(id, Setting.ACTIVE_STRENGTH, s); }
    public void activeThreshold(String id, String t) { record(id, Setting.ACTIVE_THRESHOLD, t); }

    private synchronized void record(String id, Setting setting, String value) {
        edits.put(new Key(id, setting), value);
    }

    /**
     * Send every recorded edit to {@code api}. An edit the engine refuses (a
     * rule an add-on change removed, say) or that cannot be sent is skipped;
     * the ids of those rules are returned, each once, in replay order.
     */
    public List<String> replay(ZapApi api) {
        List<Map.Entry<Key, String>> todo;
        synchronized (this) { todo = new ArrayList<>(edits.entrySet()); }
        Set<String> failed = new LinkedHashSet<>();
        for (Map.Entry<Key, String> e : todo) {
            String id = e.getKey().id();
            String v = e.getValue();
            try {
                switch (e.getKey().setting()) {
                    case PASSIVE_ENABLED -> api.setPassiveEnabled(id, Boolean.parseBoolean(v));
                    case PASSIVE_THRESHOLD -> api.setPassiveThreshold(id, v);
                    case ACTIVE_ENABLED -> api.setActiveEnabled(id, Boolean.parseBoolean(v));
                    case ACTIVE_STRENGTH -> api.setActiveStrength(id, v);
                    case ACTIVE_THRESHOLD -> api.setActiveThreshold(id, v);
                }
            } catch (Exception ex) {
                Debug.log("rule edit replay, " + e.getKey().setting() + " of rule " + id + ": " + ex);
                failed.add(id);
            }
        }
        return new ArrayList<>(failed);
    }
}
