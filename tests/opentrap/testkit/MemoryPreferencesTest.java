package opentrap.testkit;

import opentrap.core.Settings;

import java.util.prefs.Preferences;

/**
 * Pins the test run's preference store: it lives in memory, so no suite reads or
 * writes the developer's real preferences, and a new Settings stands in for a restart.
 */
public final class MemoryPreferencesTest {

    public void run() {
        Harness.suite("test preferences");

        Harness.test("the test run keeps preferences in memory", () ->
            Expect.isTrue(Preferences.userRoot() instanceof MemoryPreferences,
                "Preferences.userRoot() is " + Preferences.userRoot().getClass().getName()));

        Harness.test("a value written through one Settings reads back through the next", () -> {
            MemoryPreferencesFactory.reset();
            new Settings().set("ui.zebra", "x");
            Expect.equal(new Settings().get("ui.zebra", "d"), "x", "value read by a new Settings");
        });

        Harness.test("a reset forgets every value and leaves the store usable", () -> {
            Settings s = new Settings();
            s.set("ui.zebra", "x");
            MemoryPreferencesFactory.reset();
            Expect.equal(new Settings().get("ui.zebra", "d"), "d", "value after the reset");
            s.set("ui.zebra", "y");
            Expect.equal(new Settings().get("ui.zebra", "d"), "y", "value written after the reset");
        });
    }
}
