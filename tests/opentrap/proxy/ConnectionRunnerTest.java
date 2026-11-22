package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Connections run on virtual threads under a limit; past it the caller waits. */
public final class ConnectionRunnerTest {

    private static final long DEADLINE_MS = 2000;

    public void run() {
        Harness.suite("proxy connection threads");

        Harness.test("a connection runs on a virtual thread named for the proxy", () -> {
            ConnectionRunner runner = new ConnectionRunner(4, "proxy-conn-");
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean virtual = new AtomicBoolean();
            AtomicReference<String> name = new AtomicReference<>();
            submit(runner, () -> {
                virtual.set(Thread.currentThread().isVirtual());
                name.set(Thread.currentThread().getName());
                done.countDown();
            });
            Expect.isTrue(passed(done), "the task ran");
            Expect.isTrue(virtual.get(), "runs on a virtual thread");
            Expect.isTrue(name.get().startsWith("proxy-conn-"), "thread name: " + name.get());
        });

        Harness.test("a connection past the limit waits until one ends", () -> {
            ConnectionRunner runner = new ConnectionRunner(2, "proxy-conn-");
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch thirdRan = new CountDownLatch(1);
            AtomicBoolean thirdStarted = new AtomicBoolean();
            try {
                submit(runner, () -> await(hold));
                submit(runner, () -> await(hold));
                Thread caller = new Thread(() -> {
                    submit(runner, () -> { thirdStarted.set(true); thirdRan.countDown(); });
                }, "third-caller");
                caller.setDaemon(true);
                caller.start();
                long end = System.nanoTime() + DEADLINE_MS * 1_000_000L;
                while (caller.getState() != Thread.State.WAITING && System.nanoTime() < end) Thread.onSpinWait();
                Expect.equal(caller.getState(), Thread.State.WAITING, "third caller waits for a slot");
                Expect.equal(runner.running(), 2, "both slots held");
                Expect.isFalse(thirdStarted.get(), "third task has not started");
            } finally {
                hold.countDown();
            }
            Expect.isTrue(passed(thirdRan), "third task ran after a slot freed");
        });

        Harness.test("a connection that throws still frees its slot", () -> {
            AtomicReference<String> message = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch reported = new CountDownLatch(1);
            ConnectionRunner runner = new ConnectionRunner(1, "proxy-conn-", (m, t) -> {
                message.set(m); failure.set(t); reported.countDown();
            });
            CountDownLatch second = new CountDownLatch(1);
            RuntimeException thrown = new RuntimeException("expected by the test");
            submit(runner, () -> { throw thrown; });
            Thread caller = new Thread(() -> {
                submit(runner, second::countDown);
            }, "second-caller");
            caller.setDaemon(true);
            caller.start();
            Expect.isTrue(passed(second), "second task ran");
            Expect.isTrue(passed(reported), "the failure was reported");
            Expect.isTrue(failure.get() == thrown, "the thrown exception is passed on");
            Expect.equal(message.get(), "proxy-conn-task failed", "failure message");
        });
    }

    private static void submit(ConnectionRunner runner, Runnable task) {
        try { runner.run(task); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    /** True when the latch opens within the deadline. */
    private static boolean passed(CountDownLatch l) {
        try { return l.await(DEADLINE_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
    }
}
