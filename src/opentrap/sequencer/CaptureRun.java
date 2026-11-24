package opentrap.sequencer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One live capture run. It owns its threads, stop flag, tokens and counts, so a
 * thread that is still inside a request when the run is stopped can only drop
 * its result and end; it cannot reach a later run. Stop never waits for the
 * threads, because a blocking socket read does not end on an interrupt.
 */
final class CaptureRun {

    /** One request. Returns the token, null or empty when the response had none, and throws when the request failed. */
    interface Sampler {
        String next() throws Exception;
    }

    private final int threadCount;
    private final long throttleMs;
    private final Supplier<Sampler> perThread;
    private final Runnable onChange;
    private final List<Thread> threads = new ArrayList<>();
    private final List<String> tokens = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private volatile boolean stopped;

    CaptureRun(int threads, long throttleMs, Supplier<Sampler> perThread, Runnable onChange) {
        this.threadCount = Math.max(1, threads);
        this.throttleMs = throttleMs;
        this.perThread = perThread;
        this.onChange = onChange;
    }

    /** Starts the threads. Call once. */
    synchronized void start() {
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(this::loop, "sequencer-capture-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    /** Sets this run's flag and interrupts its threads. Returns at once; safe to call twice. */
    synchronized void stop() {
        stopped = true;
        for (Thread t : threads) t.interrupt();
    }

    boolean stopped() { return stopped; }

    /** Joins the threads within the timeout. True when none is alive. */
    boolean awaitStopped(long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        List<Thread> copy;
        synchronized (this) { copy = new ArrayList<>(threads); }
        for (Thread t : copy) {
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left > 0) t.join(left);
        }
        return aliveThreads() == 0;
    }

    synchronized int aliveThreads() {
        int n = 0;
        for (Thread t : threads) if (t.isAlive()) n++;
        return n;
    }

    List<String> tokens() {
        synchronized (tokens) { return new ArrayList<>(tokens); }
    }

    int tokenCount() { return tokens.size(); }

    int requests() { return requests.get(); }

    int errors() { return errors.get(); }

    private void loop() {
        Sampler s = perThread.get();
        while (!stopped) {
            String token;
            boolean failed = false;
            try { token = s.next(); }
            catch (Exception e) { token = null; failed = true; }
            if (stopped) break;                 // a result that arrives after Stop belongs to no run
            requests.incrementAndGet();
            if (failed) errors.incrementAndGet();
            else if (token != null && !token.isEmpty()) tokens.add(token);
            onChange.run();
            if (throttleMs > 0) {
                try { Thread.sleep(throttleMs); } catch (InterruptedException e) { break; }
            }
        }
    }
}
