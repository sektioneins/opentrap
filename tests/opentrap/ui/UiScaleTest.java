package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Pins where interface scaling is offered: not on macOS, where Java takes the
 * scale only as the Retina pixel density, so a relaunch at a new scale changed
 * nothing; everywhere else as before.
 */
public final class UiScaleTest {

    public void run() {
        Harness.suite("interface scale");

        Harness.test("macOS does not offer interface scaling, Linux and Windows do", () -> {
            Expect.isTrue(!UiScale.supported("Mac OS X"), "macOS");
            Expect.isTrue(UiScale.supported("Linux"), "Linux");
            Expect.isTrue(UiScale.supported("Windows 11"), "Windows");
        });

        Harness.test("on macOS the scale is 1.0 whatever the launch flag says, so no relaunch follows", () -> {
            String was = System.getProperty("opentrap.scale");
            try {
                System.setProperty("opentrap.scale", "2");
                Expect.equal(UiScale.desired("Mac OS X"), 1.0, "macOS");
                Expect.equal(UiScale.desired("Linux"), 2.0, "Linux keeps the flag");
            } finally {
                if (was == null) System.clearProperty("opentrap.scale");
                else System.setProperty("opentrap.scale", was);
            }
        });
    }
}
