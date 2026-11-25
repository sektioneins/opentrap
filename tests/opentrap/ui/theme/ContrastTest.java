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

        Harness.test("every shipped theme passes every contrast pair", () -> {
            Expect.isTrue(ThemeCatalog.SHIPPED.size() >= 5, "light, dark, high-contrast and at least two more ship: " + ThemeCatalog.SHIPPED);
            ThemeCatalog c = new ThemeCatalog(java.nio.file.Path.of("/dev/null/themes"));
            StringBuilder bad = new StringBuilder();
            for (String id : ThemeCatalog.SHIPPED) {
                try {
                    for (Contrast.Failure x : Contrast.failures(c.resolve(id).values())) {
                        bad.append(String.format(java.util.Locale.ROOT, "\n  %s: %s on %s is %.2f, needs %.1f",
                            id, x.pair().fg(), x.pair().bg(), x.ratio(), x.pair().min()));
                    }
                } catch (ThemeException | java.io.IOException e) {
                    bad.append("\n  ").append(id).append(" does not resolve: ").append(e.getMessage());
                }
            }
            Expect.isTrue(bad.length() == 0, "contrast pairs below their minimum:" + bad);
        });

        Harness.test("the high-contrast theme reaches 7 to 1 for text", () -> {
            Expect.isTrue(ThemeCatalog.SHIPPED.contains("high-contrast"), "high-contrast ships");
            ThemeCatalog c = new ThemeCatalog(java.nio.file.Path.of("/dev/null/themes"));
            Map<String, Object> values;
            try {
                values = c.resolve("high-contrast").values();
            } catch (ThemeException | java.io.IOException e) {
                throw new AssertionError(e);
            }
            StringBuilder bad = new StringBuilder();
            for (Contrast.Pair p : Contrast.PAIRS) {
                double need = p.min() >= 4.5 ? 7.0 : 4.5;
                double got = Contrast.ratio((Color) values.get(p.fg()), (Color) values.get(p.bg()));
                if (got < need) {
                    bad.append(String.format(java.util.Locale.ROOT, "\n  %s on %s is %.2f, needs %.1f",
                        p.fg(), p.bg(), got, need));
                }
            }
            Expect.isTrue(bad.length() == 0, "pairs below the high-contrast target:" + bad);
        });

        Harness.test("every badge fill has a readable on-colour in every shipped theme", () -> {
            ThemeCatalog c = new ThemeCatalog(java.nio.file.Path.of("/dev/null/themes"));
            StringBuilder bad = new StringBuilder();
            for (String id : ThemeCatalog.SHIPPED) {
                Map<String, Object> v;
                try {
                    v = c.resolve(id).values();
                } catch (ThemeException | java.io.IOException e) {
                    throw new AssertionError(e);
                }
                double need = id.equals("high-contrast") ? 7.0 : 4.5;
                Color light = (Color) v.get("TEXT_ON_BADGE"), dark = (Color) v.get("TEXT_ON_BADGE_DARK");
                Expect.isTrue(dark != null, id + " has TEXT_ON_BADGE_DARK");
                for (String f : Contrast.BADGE_FILLS) {
                    Color fill = (Color) v.get(f);
                    double best = Math.max(Contrast.ratio(light, fill), Contrast.ratio(dark, fill));
                    if (best < need) bad.append(String.format(java.util.Locale.ROOT, "\n  %s: %s is %.2f, needs %.1f", id, f, best, need));
                }
            }
            Expect.isTrue(bad.length() == 0, "badge fills without a readable on-colour:" + bad);
        });

        Harness.test("a badge on amber takes the dark on-colour and one on the dialog red keeps white", () -> {
            try {
                Theme.configure(false);
                Expect.equal(Theme.onBadge(Theme.WARN), Theme.TEXT_ON_BADGE_DARK, "amber gets the dark glyph");
                Expect.equal(Theme.onBadge(Theme.DIALOG_ERROR), Theme.TEXT_ON_BADGE, "the dialog red keeps white");
                Expect.equal(Contrast.onBadge(new Color(255, 255, 0), Color.WHITE, Color.BLACK), Color.BLACK, "yellow");
                Expect.equal(Contrast.onBadge(new Color(0, 0, 80), Color.WHITE, Color.BLACK), Color.WHITE, "navy");
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("see-through text, error and severity colours fail the check on adding a theme", () -> {
            java.nio.file.Path dir = null;
            try {
                dir = java.nio.file.Files.createTempDirectory("opentrap-contrast");
                java.nio.file.Path file = dir.resolve("faint.theme");
                java.nio.file.Files.writeString(file, "name = Faint\nbase = light\nappearance = light\n"
                    + "TEXT = #1A1A1A00\nERROR = alpha(#CC0000, 0%)\nSEVERITY_HIGH = #D51F3D10\n");
                java.util.Set<String> failed = new java.util.TreeSet<>();
                for (Contrast.Failure f : new ThemeCatalog(dir.resolve("store")).check(file).contrast()) {
                    if (f.pair().bg().equals("BG")) failed.add(f.pair().fg());
                }
                Expect.equal(failed.toString(), "[ERROR, SEVERITY_HIGH, TEXT]", "the see-through colours on BG");
            } catch (ThemeException | java.io.IOException e) {
                throw new AssertionError(e);
            } finally {
                if (dir != null) {
                    dir.resolve("faint.theme").toFile().delete();
                    dir.toFile().delete();
                }
            }
        });

        Harness.test("a half-transparent colour is measured as it is painted over the page", () -> {
            Color white = new Color(255, 255, 255), black = new Color(0, 0, 0);
            Map<String, Object> v = Map.of("TEXT", new Color(0, 0, 0, 128), "BG", white);
            Contrast.Failure f = Contrast.failures(v).get(0);
            Expect.equal(String.format(java.util.Locale.ROOT, "%.2f", f.ratio()),
                String.format(java.util.Locale.ROOT, "%.2f", Contrast.ratio(new Color(127, 127, 127), white)), "black at half over white");
            Expect.isTrue(Contrast.failures(Map.of("TEXT", black, "BG", new Color(255, 255, 255, 0))).isEmpty(),
                "a see-through page is taken as opaque");
        });

        Harness.test("a theme that leaves a badge unreadable is reported, and a badge weak in the base alone is not", () -> {
            Color white = new Color(255, 255, 255), black = new Color(0, 0, 0), amber = new Color(0xE0, 0x8A, 0x1E);
            Map<String, Object> base = Map.of("BG", white, "TEXT_ON_BADGE", white, "TEXT_ON_BADGE_DARK", black, "WARN", amber);
            Expect.isTrue(Contrast.failures(base).isEmpty(), "black reads on amber");
            Map<String, Object> theme = Map.of("BG", white, "TEXT_ON_BADGE", white, "TEXT_ON_BADGE_DARK", white, "WARN", amber);
            var failed = Contrast.changed(theme, base);
            Expect.equal(failed.size(), 1, "white is the only on-colour left: " + failed);
            Expect.equal(failed.get(0).pair().fg(), Contrast.BADGE_TEXT, "the badge pair");
            Expect.equal(failed.get(0).pair().bg(), "WARN", "on amber");
            Map<String, Object> moved = new java.util.HashMap<>(theme);
            moved.put("ACCENT", black);
            Expect.isTrue(Contrast.changed(moved, theme).isEmpty(), "the same weak badge in the base alone is not blamed");
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

        Harness.test("text is drawn in the checked text colours, never in the accent or severity fills", () -> {
            // ACCENT and SEVERITY_HIGH are fills, checked only against their on-colours;
            // text in them went unchecked and fell under 4.5 to 1 in the dark themes.
            java.util.regex.Pattern fill = java.util.regex.Pattern.compile(
                "setForeground\\((?:opentrap\\.ui\\.)?(?:Ui|Theme)\\.(?:ACCENT|SEV_HIGH|SEVERITY_HIGH)\\)"
                + "|css\\((?:opentrap\\.ui\\.)?Theme\\.ACCENT\\)|Theme\\.ACCENT\\.getRGB\\(");
            java.util.TreeMap<String, Integer> found = new java.util.TreeMap<>();
            try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src"))) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    var m = fill.matcher(java.nio.file.Files.readString(f));
                    int n = 0;
                    while (m.find()) n++;
                    if (n > 0) found.put(java.nio.file.Path.of("src").relativize(f).toString().replace('\\', '/'), n);
                }
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            Expect.equal(found.toString(), "{}", "text in a fill colour");
        });
    }
}
