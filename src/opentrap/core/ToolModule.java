package opentrap.core;

import javax.swing.JMenu;

/**
 * The contract every tool implements. The shell knows nothing else about a
 * tool: it asks for a name, some tabs, and optionally some menus, then renders
 * whatever it is given. Adding a tenth tool costs one registration call.
 */
public interface ToolModule {

    /** Lower-case display name used on the tab and in "send to" menus. */
    String name();

    /** Tabs shown inside this tool. One entry means no inner tab bar. */
    Tab[] tabs();

    /** Menus contributed to the suite menu bar, or null for none. */
    default JMenu[] menus() { return null; }

    /** Force an inner tab bar even with a single tab. */
    default boolean alwaysTabbed() { return false; }

    /** Called once after every tool is registered and the context is live. */
    default void init(SuiteContext ctx) {}

    /** Called when the suite is shutting down. */
    default void shutdown() {}

    /**
     * A serialisable snapshot of this tool's working state, saved with the
     * project. Return null if the tool has nothing worth persisting.
     * Only the classes {@code ProjectSerial} allows can be restored: strings,
     * boxed primitives, Date, primitive arrays, ArrayList, LinkedList, HashMap,
     * LinkedHashMap, TreeMap, HashSet, LinkedHashSet, TreeSet, CopyOnWriteArrayList,
     * List.of/Set.of/Map.of, and the suite's own model types. Any other class
     * (Collections.emptyList, Arrays.asList, Vector, BigInteger, UUID, ...) fails
     * the whole project open.
     */
    default java.io.Serializable saveState() { return null; }

    /**
     * The snapshot for a project copy narrowed to the scope, which calls this
     * instead of {@link #saveState()}. A tool whose state names request targets
     * overrides it and keeps only what {@code filter.keeps}; a tool whose state
     * holds no captured traffic returns {@link #saveState()}. The default leaves
     * the state out, because the suite cannot tell what it holds.
     */
    default java.io.Serializable saveState(SaveFilter filter) {
        return filter.keepsAll() ? saveState() : null;
    }

    /** Restore a snapshot previously produced by {@link #saveState()}. */
    default void restoreState(java.io.Serializable state) {}

    /** A tab inside a tool: a title and the component to show. */
    record Tab(String title, javax.swing.JComponent component) {}
}
