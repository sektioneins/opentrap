package opentrap.sequencer;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Tests that one capture run owns its threads, stop flag, tokens and counts. */
public final class CaptureRunTest {

    private static void waitFor(BooleanSupplier cond, long deadlineMs) {
        long end = System.nanoTime() + deadlineMs * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not met within " + deadlineMs + " ms");
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    private static boolean ended(CaptureRun run, long ms) {
        try { return run.awaitStopped(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("interrupted"); }
    }

    private static boolean reached(CountDownLatch latch) {
        try { return latch.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("interrupted"); }
    }

    public void run() {
        Harness.suite("sequencer capture runs");

        Harness.test("a run samples on its threads until it is stopped", () -> {
            AtomicInteger n = new AtomicInteger();
            CaptureRun run = new CaptureRun(3, 0, () -> () -> "t" + n.incrementAndGet(), () -> { });
            try {
                run.start();
                waitFor(() -> run.tokenCount() >= 30, 5000);
                run.stop();
                Expect.isTrue(ended(run, 1000), "threads ended");
                Expect.equal(run.aliveThreads(), 0, "alive threads");
                Expect.equal(run.errors(), 0, "errors");
                Expect.equal(run.requests(), run.tokenCount(), "requests equal tokens");
                Expect.isTrue(run.tokenCount() >= 30, "at least 30 tokens");
            } finally {
                run.stop();
            }
        });

        Harness.test("a request that returns after Stop adds nothing, and its thread ends without touching the next run", () -> {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            CaptureRun a = new CaptureRun(2, 0, () -> () -> {
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return "old";
            }, () -> { });
            CaptureRun b = null;
            try {
                a.start();
                Expect.isTrue(reached(entered), "both threads in a request");
                a.stop();
                b = new CaptureRun(2, 0, () -> () -> "new", () -> { });
                final CaptureRun nb = b;
                b.start();
                waitFor(() -> nb.tokenCount() >= 10, 5000);
                release.countDown();
                Expect.isTrue(ended(a, 1000), "old threads ended");
                Expect.isTrue(a.tokens().isEmpty(), "old run has no tokens");
                Expect.equal(a.requests(), 0, "old requests");
                for (String t : b.tokens()) Expect.equal(t, "new", "token of run B");
                b.stop();
                Expect.isTrue(ended(b, 1000), "new threads ended");
            } finally {
                release.countDown();
                a.stop();
                if (b != null) b.stop();
            }
        });

        Harness.test("a failed request counts as an error and the thread goes on", () -> {
            AtomicInteger calls = new AtomicInteger();
            CaptureRun run = new CaptureRun(1, 0, () -> () -> {
                int c = calls.incrementAndGet();
                if (c % 4 == 0) throw new IllegalStateException("boom");
                if (c % 2 == 1) throw new IOException("down");
                return "t" + c;
            }, () -> { });
            try {
                run.start();
                waitFor(() -> run.errors() >= 4 && run.tokenCount() >= 4, 5000);
                run.stop();
                Expect.isTrue(ended(run, 1000), "thread ended");
                Expect.equal(run.requests(), run.errors() + run.tokenCount(), "requests = errors + tokens");
            } finally {
                run.stop();
            }
        });

        Harness.test("Stop ends a thread that is waiting out the throttle", () -> {
            CaptureRun run = new CaptureRun(2, 10_000, () -> () -> "t", () -> { });
            try {
                run.start();
                waitFor(() -> run.requests() == 2, 5000);
                run.stop();
                Expect.isTrue(ended(run, 300), "threads ended at once");
            } finally {
                run.stop();
            }
        });

        Harness.test("a response without a token is a request but not a token", () -> {
            AtomicInteger n = new AtomicInteger();
            CaptureRun run = new CaptureRun(1, 0, () -> () -> switch (n.getAndIncrement() % 3) {
                case 0 -> null;
                case 1 -> "";
                default -> "t";
            }, () -> { });
            try {
                run.start();
                waitFor(() -> run.tokenCount() >= 3, 5000);
                run.stop();
                Expect.isTrue(ended(run, 1000), "thread ended");
                Expect.isTrue(run.requests() >= 9, "requests counted");
                List<String> tokens = run.tokens();
                for (String t : tokens) Expect.isTrue(t != null && !t.isEmpty(), "no empty token");
            } finally {
                run.stop();
            }
        });
    }
}
