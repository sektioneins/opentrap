package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.desktop.QuitResponse;

/**
 * A macOS quit request (Cmd-Q, Dock > Quit, log-out) must go through the same
 * confirm-and-save path as closing the main window, and must always be
 * answered. Without a handler, Java's default called System.exit on the
 * AppKit thread: no confirmation, no final project save, and the application
 * froze until every shutdown hook finished. An unanswered request leaves
 * macOS waiting on it, so every later Cmd-Q is ignored.
 */
public final class QuitRequestTest {

    /** Records how a quit request was answered. */
    private static final class Answer implements QuitResponse {
        int performed, cancelled;
        @Override public void performQuit() { performed++; }
        @Override public void cancelQuit() { cancelled++; }
    }

    public void run() {
        Harness.suite("Quit request");

        Harness.test("a confirmed exit lets the quit go ahead", () -> {
            Answer a = new Answer();
            SuiteFrame.answerQuit(() -> true, a);
            Expect.equal(a.performed, 1, "performQuit calls");
            Expect.equal(a.cancelled, 0, "cancelQuit calls");
        });

        Harness.test("a declined exit cancels the quit, so the next Cmd-Q still works", () -> {
            Answer a = new Answer();
            SuiteFrame.answerQuit(() -> false, a);
            Expect.equal(a.performed, 0, "performQuit calls");
            Expect.equal(a.cancelled, 1, "cancelQuit calls");
        });

        Harness.test("a failure while preparing to exit still answers the request", () -> {
            Answer a = new Answer();
            try {
                SuiteFrame.answerQuit(() -> { throw new IllegalStateException("boom"); }, a);
            } catch (RuntimeException ignore) {
                // Whether the failure propagates is secondary; the answer is not.
            }
            Expect.equal(a.performed, 0, "performQuit calls");
            Expect.equal(a.cancelled, 1, "cancelQuit calls");
        });
    }
}
