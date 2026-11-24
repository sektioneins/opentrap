package opentrap.engine;

import opentrap.core.Debug;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;
import opentrap.extensions.store.CoreAddOns;
import opentrap.extensions.store.Store;
import opentrap.spider.Cdp.JsonValue;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Live passive scanning of proxy traffic. When enabled, in-scope
 * requests are routed through the ZAP engine's own proxy, which passively scans
 * everything that flows through it; a background poller turns ZAP's alerts into
 * OpenTRAP issues. Routing is fail-safe: if the engine is not reachable the proxy
 * falls back to a direct connection, so browsing is never broken.
 *
 * <p>The scanner follows the engine's state: each engine that comes up gives it
 * a new proxy port and API, and while the engine is stopped or failed traffic
 * goes direct instead of to a dead port.
 */
public final class LivePassiveScanner {

    /** The source every finding the live poller raises carries. */
    public static final String SOURCE = "Live audit";

    /**
     * What the scanner uses of one running engine: its proxy, its API, and the
     * alert ids already mapped from it. Alert ids are numbered per engine
     * process, so a restarted engine starts with an empty set. Swapped as a
     * whole, so a poll never pairs one engine's API with another's ids.
     */
    private record Live(UpstreamProxy proxy, ZapApi api, Set<String> seen) {}

    private final SuiteContext ctx;
    private final ZapEngine engine;   // null when no scan engine is installed
    private final Supplier<Store> store;   // the add-on store to check, or null for none
    private volatile boolean enabled;
    private volatile Live live;       // null while no engine runs: traffic goes direct
    private volatile boolean paused;  // the engine went away while the live audit routed through it
    private volatile Thread poller;

    public LivePassiveScanner(SuiteContext ctx) { this(ctx, ZapEngine.shared()); }

    /**
     * A scanner on {@code engine}. It hears the engine's states from here on, so
     * it is made before the engine starts, as the dashboard does.
     */
    LivePassiveScanner(SuiteContext ctx, ZapEngine engine) { this(ctx, engine, Store::sharedIfSet); }

    /** A scanner on {@code engine} that checks its add-ons in the store {@code store} gives. */
    LivePassiveScanner(SuiteContext ctx, ZapEngine engine, Supplier<Store> store) {
        this.ctx = ctx;
        this.engine = engine;
        this.store = store;
        if (engine != null) engine.addStateListener(this::engineChanged);
    }

    public boolean isEnabled() { return enabled; }

    /** The upstream to route a request through, or null for a direct connection. */
    public UpstreamProxy routeFor(HttpService svc) {
        if (!enabled || svc == null) return null;
        Live l = live;
        if (l == null) return null;
        try {
            if (!ctx.scope().isEmpty() && !ctx.scope().isInScope(svc, "/")) return null;
        } catch (Exception _) {}
        return l.proxy;
    }

    /** Turn live passive scanning on or off. Starts the engine lazily on first enable. */
    public synchronized void setEnabled(boolean on) {
        enabled = on;
        // Another tool may have started the engine already; the start below
        // then only reports the live audit active and starts the poller.
        if (on && poller == null) startEngine();
        // Disabling stops the poller: without this it kept polling all engine
        // alerts every 3 s for the rest of the session, whether live scanning
        // was on or not.
        if (!on) stopPoller();
    }

    /** Whether a poller thread runs, raising the engine's alerts as issues (for tests). */
    boolean polling() {
        Thread p = poller;
        return p != null && p.isAlive();
    }

    private void stopPoller() {
        Thread p = poller;
        poller = null;
        if (p != null) p.interrupt();
    }

    /**
     * Follow the engine. Runs on the thread that changes its state, possibly
     * with the engine's monitor held, so it only swaps fields and logs.
     */
    private void engineChanged(EngineState s) {
        switch (s) {
            case RUNNING -> {
                live = follow(engine.api());
                if (paused) {
                    paused = false;
                    // The restarted engine may run without an add-on the live audit needs.
                    String why = enabled ? missingAddOns() : null;
                    if (why != null) {
                        ctx.alert("scanner", why);
                        enabled = false;
                        stopPoller();
                    } else if (enabled) {
                        ctx.alert("scanner", "Live audit resumed: traffic routed through the engine again");
                    }
                }
            }
            case STOPPED, FAILED -> {
                // Only a live audit that was routing, or is already paused, is
                // paused; an engine that never came up is reported by its start.
                paused = enabled && (paused || live != null);
                live = null;
                if (paused) ctx.alert("scanner", s == EngineState.STOPPED
                        ? "Live audit paused: the engine is restarting"
                        : "Live audit paused: the engine failed: " + engine.lastError());
            }
            default -> {}
        }
    }

    /** What the scanner uses of the engine whose API is {@code api}; its proxy is on the API's port. */
    private static Live follow(ZapApi api) {
        if (api == null) return null;
        URI u = URI.create(api.base());   // http://127.0.0.1:<port>
        UpstreamProxy p = new UpstreamProxy();
        p.type = UpstreamProxy.Type.HTTP;
        p.host = u.getHost();
        p.port = u.getPort();
        return new Live(p, api, ConcurrentHashMap.newKeySet());
    }

    private void startEngine() {
        Thread t = new Thread(() -> {
            ZapEngine ze = engine;
            if (ze == null || !ze.available()) {
                ctx.alert("scanner", "Live passive scanning needs the scan engine (run 'just engine')");
                enabled = false;
                return;
            }
            try {
                ze.start();   // the engine's RUNNING state gives this scanner its proxy
                // Checked once the engine is up: its start reads its add-ons into the store's record.
                String why = missingAddOns();
                if (why != null) {
                    ctx.alert("scanner", why);
                    enabled = false;
                    return;
                }
                // Switched off while the engine started: neither active nor polling.
                if (startPoller()) {
                    ctx.alert("scanner", "Live passive scanning is active (traffic routed through the engine)");
                }
            } catch (Exception e) {
                ctx.alert("scanner", "Could not start live passive scanning: " + e.getMessage());
                enabled = false;
            }
        }, "live-passive-start");
        t.setDaemon(true);
        t.start();
    }

    /** What the event log says when the engine lacks an add-on the live audit needs, or null. */
    private String missingAddOns() {
        Store s = store.get();
        if (s == null) return null;
        List<String> ids = s.notActive(CoreAddOns.Feature.LIVE_AUDIT);
        return ids.isEmpty() ? null : CoreAddOns.missingMessage(CoreAddOns.Feature.LIVE_AUDIT, ids);
    }

    /**
     * Start the poller unless the live audit is off or one already runs.
     * Returns whether it started one.
     */
    private synchronized boolean startPoller() {
        if (!enabled || poller != null) return false;
        poller = new Thread(() -> {
            try {
                while (enabled) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    try {
                        pollOnce();
                    } catch (Exception e) {
                        // A dead or restarting engine must not kill the poller, but
                        // the failure is traced rather than dropped: with the loop
                        // running every 3 s, a permanently failing poll is the only
                        // sign the live audit quietly stopped finding anything.
                        Debug.log("live passive scan poll failed: " + e);
                    }
                }
            } finally {
                // A poller that ends on its own must not stay in the field, or
                // the next switch-on would take it for a running one and start
                // none. One already replaced by a newer poller leaves that be.
                synchronized (LivePassiveScanner.this) {
                    if (poller == Thread.currentThread()) poller = null;
                }
            }
        }, "live-passive-poll");
        poller.setDaemon(true);
        poller.start();
        return true;
    }

    /** Map the running engine's alerts not mapped yet into issues; nothing while no engine runs. */
    void pollOnce() throws Exception {
        Live l = live;
        if (l == null) return;
        // One cache per poll: findings the engine raised on one message share
        // its evidence, and the cache never outlives the listing it was built
        // from.
        java.util.Map<String, ZapScanner.Evidence> evidence = new java.util.HashMap<>();
        for (JsonValue a : l.api.alerts("", 0, 100000)) {
            String id = ZapApi.str(a, "id");
            if (id != null && !l.seen.add(id)) continue;   // already mapped
            Issue is = ZapScanner.mapAlert(l.api, a, evidence);
            if (is != null) { is.setSource(SOURCE); ctx.addIssue(is); }
        }
    }
}
