package opentrap.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import opentrap.extensions.store.Fetcher;
import opentrap.extensions.store.Store;

/** Add-on stores in a fresh temp folder, brought in line with a fake ZAP 2.17.0 install. */
public final class StoreFixtures {

    private StoreFixtures() {}

    public interface Body { void run(Store store, Path engine) throws Exception; }

    /**
     * Runs {@code body} with a store whose engine install bundles {@code bundled},
     * after an engine start, and a second one once {@code disabled} were queued
     * for disabling. The store is never opened before the first start, as the
     * engine's start hook finds it. The temp folder is deleted afterwards.
     */
    public static void started(List<String> bundled, List<String> disabled, Body body) {
        inTemp(bundled, (store, engine) -> {
            store.startHook().beforeLaunch(engine, "2.17.0");
            for (String id : disabled) store.disable(id);
            if (!disabled.isEmpty()) store.startHook().beforeLaunch(engine, "2.17.0");
            body.run(store, engine);
        });
    }

    /** Runs {@code body} with a store not used yet and an engine install that bundles {@code bundled}. */
    public static void inTemp(List<String> bundled, Body body) {
        // every source is the engine's plugin folder, so the fetcher is never used
        inTemp(bundled, new Fetcher(null, host -> null), body);
    }

    /** As {@link #inTemp(List, Body)}, with a store that fetches through {@code fetcher}. */
    public static void inTemp(List<String> bundled, Fetcher fetcher, Body body) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("opentrap-store");
            Path engine = tmp.resolve("engine");
            Files.createDirectories(engine.resolve("lib"));
            Path plugin = Files.createDirectories(engine.resolve("plugin"));
            Files.write(engine.resolve("zap-2.17.0.jar"), new byte[0]);
            for (String id : bundled) ZapFixtures.zap(plugin, id, "1.0.0", "release");
            body.run(new Store(tmp.resolve("addons"), fetcher), engine);
        } catch (RuntimeException | AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) delete(tmp);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }
}
