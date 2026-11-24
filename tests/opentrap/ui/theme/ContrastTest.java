package opentrap.ui.theme;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.Theme;

import java.awt.Color;
import java.util.Map;

/** Pins the contrast ratio and which failures a theme is blamed for. */
public final class ContrastTest {

    public void run() {
        Harness.suite("theme contrast");

        Harness.test("black on white is 21 to 1", () -> {
            Expect.equal(String.format(java.util.Locale.ROOT, "%.2f", Contrast.ratio(new Color(0, 0, 0), new Color(255, 255, 255))),
                "21.00", "black on white");
        });

        Harness.test("white on the light accent measures 4.77 to 1", () -> {
            Theme.configure(false);
            Expect.equal(String.format(java.util.Locale.ROOT, "%.2f", Contrast.ratio(new Color(255, 255, 255), Theme.ACCENT)),
                "4.77", "white on the light accent");
        });

        Harness.test("the built-in themes pass every contrast pair", () -> {
            StringBuilder bad = new StringBuilder();
            try {
                for (boolean dark : new boolean[] {false, true}) {
                    Theme.configure(dark);
                    Map<String, Object> values = new java.util.HashMap<>();
                    for (java.lang.reflect.Field f : Theme.class.getDeclaredFields()) {
                        if (f.getType() == Color.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            try {
                                values.put(f.getName(), f.get(null));
                            } catch (IllegalAccessException e) {
                                throw new AssertionError(e);
                            }
                        }
                    }
                    for (Contrast.Failure x : Contrast.failures(values)) {
                        bad.append(String.format(java.util.Locale.ROOT, "\n  %s: %s on %s is %.2f, needs %.1f",
                            dark ? "dark" : "light", x.pair().fg(), x.pair().bg(), x.ratio(), x.pair().min()));
                    }
                }
            } finally {
                Theme.configure(false);
            }
            Expect.isTrue(bad.length() == 0, "contrast pairs below their minimum:" + bad);
        });

        Harness.test("a failing pair the theme did not change is not reported", () -> {
            Color grey = new Color(120, 120, 120), white = new Color(255, 255, 255);
            Map<String, Object> base = Map.of("TEXT", grey, "BG", white, "MUTED", grey);
            Expect.equal(Contrast.failures(base).size(), 1, "the base fails TEXT on BG alone");
            Expect.isTrue(Contrast.changed(base, base).isEmpty(), "nothing changed, nothing reported");
            Map<String, Object> theme = Map.of("TEXT", new Color(130, 130, 130), "BG", white, "MUTED", grey);
            Expect.equal(Contrast.changed(theme, base).size(), 1, "TEXT changed and still fails");
            Expect.equal(Contrast.changed(theme, base).get(0).pair().fg(), "TEXT", "the changed pair");
        });
    }
}
