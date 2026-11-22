package opentrap.proxy;

import opentrap.core.Debug;

import java.util.concurrent.Semaphore;

/**
 * Runs each connection on its own virtual thread, at most {@code max} at a
 * time. A caller past the limit waits for a slot, so the accept loop slows down
 * instead of running a connection itself and ceasing to accept.
 */
final class ConnectionRunner {

    private final Semaphore slots;
    private final int max;
    private final String namePrefix;
    private final Thread.Builder builder;

    ConnectionRunner(int max, String namePrefix) {
        this.max = max;
        this.slots = new Semaphore(max);
        this.namePrefix = namePrefix;
        this.builder = Thread.ofVirtual().name(namePrefix, 0);
    }

    /** Blocks while {@code max} tasks are running, then starts {@code task}. */
    void run(Runnable task) throws InterruptedException {
        slots.acquire();
        try {
            builder.start(() -> {
                try { task.run(); }
                catch (Throwable t) { Debug.error(namePrefix + "task failed", t); }
                finally { slots.release(); }
            });
        } catch (Throwable t) {
            // The thread never started, so nothing else will free the slot.
            slots.release();
            throw t;
        }
    }

    /** Tasks currently holding a slot. */
    int running() { return max - slots.availablePermits(); }
}
