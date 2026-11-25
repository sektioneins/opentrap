package opentrap.testkit;

import java.lang.management.ManagementFactory;

/** Measures how many bytes a piece of code allocates, to pin that an operation stays bounded. */
public final class Allocation {
    private Allocation() {}

    public interface Body {
        void run() throws Exception;
    }

    /** Bytes the current thread allocates while {@code body} runs, measured on a fresh platform thread. */
    public static long during(Body body) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] result = {-2};
        Throwable[] failure = {null};
        Thread t = new Thread(() -> {
            long before = mx.getCurrentThreadAllocatedBytes();
            try {
                body.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
            long after = mx.getCurrentThreadAllocatedBytes();
            result[0] = (before < 0 || after < 0) ? -1 : after - before;
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        Expect.isTrue(result[0] >= 0, "thread allocation counter available");
        return result[0];
    }
}
