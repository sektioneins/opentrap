package opentrap.testkit;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Hands out in-memory preference roots that live as long as the JVM. Selected
 * by {@code RunTests} through the {@code java.util.prefs.PreferencesFactory}
 * system property, which {@link Preferences} reads once.
 */
public final class MemoryPreferencesFactory implements PreferencesFactory {

    private static final MemoryPreferences USER = new MemoryPreferences(null, "");
    private static final MemoryPreferences SYSTEM = new MemoryPreferences(null, "");

    public MemoryPreferencesFactory() { }

    @Override public Preferences userRoot() { return USER; }
    @Override public Preferences systemRoot() { return SYSTEM; }

    /**
     * Forgets every stored value. No node is removed, so a Settings built
     * before the reset keeps a valid node and writes to it again afterwards.
     */
    public static void reset() {
        clearTree(USER);
    }

    private static void clearTree(Preferences node) {
        try {
            node.clear();
            for (String child : node.childrenNames()) clearTree(node.node(child));
        } catch (BackingStoreException e) {
            throw new IllegalStateException(e);
        }
    }
}
