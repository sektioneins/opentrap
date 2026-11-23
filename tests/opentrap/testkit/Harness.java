package opentrap.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A very small test runner.
 *
 * <p>OpenTRAP vendors only BouncyCastle and a Brotli decoder, and adding a
 * whole test framework to carry the suite is not a trade worth making.
 * This gives the two things the tests actually need: a named case that is
 * isolated from its neighbours, and a report that names what failed.
 *
 * <p>It also keeps the run honest about time. A case slower than
 * {@code -Dopentrap.test.slowMs} (500 ms by default) is named with its time, so
 * a slow case is noticed when it is added, not years later. A case still
 * running after {@code -Dopentrap.test.dumpAfterMs} (60 s by default) gets
 * every thread's stack printed once, so a hang names where it is stuck
 * instead of leaving a run that never ends and says nothing.
 *
 * <p>A case that needs a display is declared with {@link #displayTest}. A
 * sandboxed agent session has none: its JVM must run headless, where such a
 * case only throws HeadlessException. {@code -Dopentrap.test.cases} picks the
 * cases to run: {@code all} (the default), {@code sandbox} (every case but
 * those) or {@code nosandbox} (only those).
 *
 * <p>Not thread-safe, and deliberately so. Tests run in order on one thread so
 * that a failure report reads the same way twice. The hang watchdog only reads
 * the two volatile fields below.
 */
public final class Harness {

    private Harness() {}

    private static final long SLOW_MS = Long.getLong("opentrap.test.slowMs", 500);
    private static final long DUMP_AFTER_MS = Long.getLong("opentrap.test.dumpAfterMs", 60_000);
    private static final long RUN_START = System.nanoTime();
    private static final String CASES = System.getProperty("opentrap.test.cases", "all");

    static {
        if (!CASES.equals("all") && !CASES.equals("sandbox") && !CASES.equals("nosandbox")) {
            System.err.println("opentrap.test.cases must be all, sandbox or nosandbox, not '" + CASES + "'");
            System.exit(2);
        }
    }

    private static final List<String> failures = new ArrayList<>();
    private static String currentSuite = "";
    /** Whether the current suite's name is printed yet; it waits for its first selected case. */
    private static boolean suiteShown;
    private static int passed;
    private static int failed;
    private static int skipped;
    private static int suites;

    /** The case now running and when it started, read by the hang watchdog. */
    private static volatile String runningCase;
    private static volatile long runningSince;

    static {
        Thread.ofPlatform().daemon().name("test-hang-watchdog").start(Harness::watchForHangs);
    }

    /** Begin a group of cases. It is listed and counted only if one of them runs. */
    public static void suite(String name) {
        currentSuite = name;
        suiteShown = false;
    }

    /**
     * Run one case. An {@link AssertionError} fails the case; any other
     * exception fails it too and is reported as an error, because a module that
     * throws is a module that found nothing.
     */
    public static void test(String name, Runnable body) {
        run(name, body, !CASES.equals("nosandbox"));
    }

    /**
     * Run one case that needs a display: it builds real windows or asks the
     * toolkit for something a headless JVM refuses, such as the menu shortcut
     * key. Left out of the sandbox selection.
     */
    public static void displayTest(String name, Runnable body) {
        run(name, body, !CASES.equals("sandbox"));
    }

    private static void run(String name, Runnable body, boolean selected) {
        if (!selected) { skipped++; return; }
        if (!suiteShown) {
            suiteShown = true;
            suites++;
            System.out.println("  " + currentSuite);
        }
        long start = System.nanoTime();
        runningSince = start;
        runningCase = currentSuite + " / " + name;
        try {
            body.run();
            passed++;
        } catch (AssertionError e) {
            failed++;
            record(name, e.getMessage() == null ? "assertion failed" : e.getMessage());
        } catch (Throwable t) {
            failed++;
            record(name, "threw " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                failures.add("        at " + st[i]);
            }
        } finally {
            runningCase = null;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms >= SLOW_MS) System.out.println("    SLOW  " + name + " (" + ms + " ms)");
    }

    private static void record(String name, String detail) {
        failures.add("    " + currentSuite + " / " + name);
        failures.add("        " + detail);
        System.out.println("    FAIL  " + name);
    }

    /** Print every thread's stack once for a case that runs past the limit. */
    private static void watchForHangs() {
        String dumped = null;
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            String name = runningCase;
            long since = runningSince;
            if (name == null || name.equals(dumped)) continue;
            long ms = (System.nanoTime() - since) / 1_000_000;
            if (ms < DUMP_AFTER_MS) continue;
            dumped = name;
            StringBuilder sb = new StringBuilder();
            sb.append("\n    HUNG? ").append(name).append(" still running after ").append(ms).append(" ms\n");
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                sb.append("      \"").append(t.getName()).append("\" ").append(t.getState()).append('\n');
                for (StackTraceElement el : e.getValue()) sb.append("          at ").append(el).append('\n');
            }
            System.out.print(sb);
            System.out.flush();
        }
    }

    /** Print the summary and return the process exit code. */
    public static int report() {
        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            for (String f : failures) System.out.println(f);
            System.out.println();
        }
        long secs10 = (System.nanoTime() - RUN_START) / 100_000_000;
        System.out.println(suites + " suites, " + (passed + failed) + " cases, "
                + passed + " passed, " + failed + " failed, "
                + (skipped == 0 ? "" : skipped + " not selected (" + CASES + "), ") + "in "
                + (secs10 / 10) + "." + (secs10 % 10) + " s");
        return failed == 0 ? 0 : 1;
    }

    public static int failedCount() { return failed; }
}
