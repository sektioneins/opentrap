package opentrap.engine;

import java.util.function.Supplier;
import opentrap.core.SuiteContext;
import opentrap.extensions.store.Store;

/**
 * Engines for tests outside this package. Each launch starts a stand-in for
 * ZAP's API on loopback, as in {@link EngineRestartTest}.
 */
public final class FakeEngines {

    private FakeEngines() {}

    /** An engine not started yet; every launch succeeds. */
    public static ZapEngine engine() { return engine(Integer.MAX_VALUE); }

    /** An engine not started yet whose launches past {@code limit} throw "launch refused by the test". */
    public static ZapEngine engine(int limit) {
        return ZapEngine.forLauncher("zap-0.0.0.jar", new EngineRestartTest.FakeLauncher(limit));
    }

    /** A live audit on {@code engine} that checks its add-ons in the store {@code store} gives. */
    public static LivePassiveScanner liveAudit(SuiteContext ctx, ZapEngine engine, Supplier<Store> store) {
        return new LivePassiveScanner(ctx, engine, store);
    }
}
