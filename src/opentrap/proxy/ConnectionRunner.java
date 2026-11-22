package opentrap.proxy;

import opentrap.core.Debug;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;

/**
 * Runs each connection on its own virtual thread, at most {@code max} at a
 * time. A caller past the limit waits for a slot, so the accept loop slows down
 * instead of running a connection itself and ceasing to accept.
 */
final class ConnectionRunner {

    private final Semaphore slots;
    private final int max;
    private final String namePrefix;
    private final ThreadFactory factory;
    private final BiConsumer<String, Throwable> onFailure;

    ConnectionRunner(int max, String namePrefix) {
        this(max, namePrefix, Debug::error);
    }

    /** {@code onFailure} receives a message and the Throwable a task ended with. */
    ConnectionRunner(int max, String namePrefix, BiConsumer<String, Throwable> onFailure) {
        this.onFailure = onFailure;
        this.max = max;
        this.slots = new Semaphore(max);
        this.namePrefix = namePrefix;
        // The builder itself is not thread safe; its factory is, and every
        // listener's accept thread calls run().
        this.factory = Thread.ofVirtual().name(namePrefix, 0).factory();
    }

    /** Blocks while {@code max} tasks are running, then starts {@code task}. */
    void run(Runnable task) throws InterruptedException {
        slots.acquire();
        try {
            factory.newThread(() -> {
                try { task.run(); }
                catch (Throwable t) { onFailure.accept(namePrefix + "task failed", t); }
                finally { slots.release(); }
            }).start();
        } catch (Throwable t) {
            // The thread never started, so nothing else will free the slot.
            slots.release();
            throw t;
        }
    }

    /** Tasks currently holding a slot. */
    int running() { return max - slots.availablePermits(); }
}
