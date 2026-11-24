package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferences;
import opentrap.ui.theme.ThemeCatalog;
import opentrap.ui.theme.ThemeException;
import opentrap.ui.theme.ThemeResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.prefs.Preferences;

/**
 * Pins the theme catalogue (adding, listing, refusing) and the startup rule
 * that picks the active theme. It calls {@code Theme.configure}, so it runs
 * just before the suite that installs the full theme, and restores light.
 */
public final class ThemeCatalogTest {

    private interface Body { void run(Path dir) throws Exception; }

    private static void inFolder(Body body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("opentrap-themes");
            body.run(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (dir != null) rm(dir);
        }
    }

    private static void rm(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    private static String theme(String name, String base, String body) {
        return "name = " + name + "\nbase = " + base + "\nappearance = " + base + "\n" + body;
    }

    private static Path write(Path dir, String file, String text) throws IOException {
        Path p = dir.resolve(file);
        Files.writeString(p, text);
        return p;
    }

    private static Preferences prefs() {
        return new MemoryPreferences(null, "");
    }

    public void run() {
        Harness.suite("theme catalogue");

        Harness.test("a file named My Theme is added as my-theme", () -> inFolder(dir -> {
            Path src = write(dir, "My Theme.theme", theme("Mine", "light", "ACCENT = #112233\n"));
            Path store = dir.resolve("store");
            ThemeCatalog c = new ThemeCatalog(store);
            ThemeCatalog.Entry e = c.add(src);
            Expect.equal(e.id(), "my-theme", "id");
            Expect.isTrue(Files.isRegularFile(store.resolve("my-theme.theme")), "stored under its id");
            Expect.equal(c.list().get(c.list().size() - 1).id(), "my-theme", "listed after the shipped ones");
            Expect.equal(c.list().get(0).id(), "light", "shipped first");
        }));

        Harness.test("a file dropped in the folder under a name that is no id is left out", () -> inFolder(dir -> {
            write(dir, "Not An Id.theme", theme("Odd", "light", ""));
            write(dir, "good.theme", theme("Good", "light", ""));
            write(dir, "dark.theme", theme("Fake", "light", ""));
            ThemeCatalog c = new ThemeCatalog(dir);
            Expect.equal(c.list().size(), ThemeCatalog.SHIPPED.size() + 1, "only the good file is added");
            Expect.equal(c.list().get(c.list().size() - 1).id(), "good", "the good one");
        }));

        Harness.test("a theme named like a shipped id is refused", () -> inFolder(dir -> {
            Path src = write(dir, "Dark.theme", theme("Fake", "dark", ""));
            try {
                new ThemeCatalog(dir.resolve("store")).add(src);
                throw new AssertionError("expected a refusal");
            } catch (ThemeException e) {
                Expect.isTrue(e.getMessage().contains("dark"), e.getMessage());
            }
        }));

        Harness.test("adding the same id again reports that it replaces", () -> inFolder(dir -> {
            Path src = write(dir, "again.theme", theme("Again", "light", ""));
            ThemeCatalog c = new ThemeCatalog(dir.resolve("store"));
            Expect.isFalse(c.check(src).replaces(), "first time");
            c.add(src);
            Expect.isTrue(c.check(src).replaces(), "second time");
            c.add(src);
            Expect.equal(c.list().size(), ThemeCatalog.SHIPPED.size() + 1, "one entry");
        }));

        Harness.test("a file over 64 KB is refused before it is parsed", () -> inFolder(dir -> {
            Path src = write(dir, "big.theme", "x".repeat(70_000));
            try {
                new ThemeCatalog(dir.resolve("store")).check(src);
                throw new AssertionError("expected a refusal");
            } catch (ThemeException e) {
                Expect.isTrue(e.getMessage().contains("larger than"), e.getMessage());
            }
        }));

        Harness.test("a theme of 2,000 chained constants is refused, not a crash", () -> inFolder(dir -> {
            StringBuilder sb = new StringBuilder(theme("Deep", "light", ""));
            int n = 1_900;
            for (int i = 0; i < n; i++) sb.append("@c").append(i).append(" = @c").append(i + 1).append('\n');
            sb.append("@c").append(n).append(" = #112233\n");
            Path src = write(dir, "deep.theme", sb.toString());
            try {
                new ThemeCatalog(dir.resolve("store")).check(src);
                throw new AssertionError("expected a refusal");
            } catch (ThemeException e) {
                Expect.isTrue(e.line() > 0, "the error names a line: " + e.getMessage());
            }
        }));

        Harness.test("removing a shipped theme is refused", () -> inFolder(dir -> {
            try {
                new ThemeCatalog(dir).remove("light");
                throw new AssertionError("expected a refusal");
            } catch (IOException e) {
                Expect.isTrue(e.getMessage().contains("light"), e.getMessage());
            }
        }));

        Harness.test("the system property wins over the saved name", () -> inFolder(dir -> {
            ThemeCatalog c = new ThemeCatalog(dir);
            write(dir, "saved.theme", theme("Saved", "light", ""));
            Preferences p = prefs();
            p.put("ui.theme", "light");
            p.put("ui.theme.name", "saved");
            ThemeCatalog.Choice s = ThemeCatalog.pick(c, "dark", p);
            Expect.equal(s.id(), "dark", "system property");
            Expect.isNull(s.notice(), "no notice");
            Expect.equal(ThemeCatalog.pick(c, null, p).id(), "saved", "saved name without the property");
            p.remove("ui.theme.name");
            p.put("ui.theme", "dark");
            Expect.equal(ThemeCatalog.pick(c, null, p).id(), "dark", "ui.theme");
            Expect.equal(ThemeCatalog.pick(c, null, prefs()).id(), "light", "default");
        }));

        Harness.test("an unknown system property theme falls back to the built-in with a notice", () -> inFolder(dir -> {
            Preferences p = prefs();
            p.put("ui.theme", "dark");
            ThemeCatalog.Choice s = ThemeCatalog.pick(new ThemeCatalog(dir), "nope", p);
            Expect.equal(s.id(), "dark", "the built-in ui.theme names");
            Expect.isTrue(s.notice().startsWith("The theme nope could not be loaded ("), s.notice());
            Expect.isTrue(s.notice().endsWith("). Using Dark."), s.notice());
        }));

        Harness.test("a broken saved theme falls back to the built-in of its appearance with a notice", () -> inFolder(dir -> {
            write(dir, "broken.theme", "this is not a theme\n");
            Preferences p = prefs();
            p.put("ui.theme", "light");
            p.put("ui.theme.name", "broken");
            ThemeCatalog.Choice s = ThemeCatalog.pick(new ThemeCatalog(dir), null, p);
            Expect.equal(s.id(), "light", "fallback");
            Expect.isTrue(s.notice().endsWith("Using Light."), s.notice());
            Expect.equal(s.resolved().appearance(), "light", "appearance");
        }));

        Harness.test("a saved name whose appearance disagrees with ui.theme gives way to ui.theme and is cleared", () -> inFolder(dir -> {
            write(dir, "night.theme", theme("Night", "dark", ""));
            Preferences p = prefs();
            p.put("ui.theme", "light");
            p.put("ui.theme.name", "night");
            ThemeCatalog.Choice s = ThemeCatalog.pick(new ThemeCatalog(dir), null, p);
            Expect.equal(s.id(), "light", "ui.theme wins");
            Expect.isNull(s.notice(), "no notice");
            Expect.isNull(p.get("ui.theme.name", null), "the saved name is cleared");
        }));

        Harness.test("a built-in theme never reads the user folder", () -> {
            Path nowhere = Path.of("/dev/null/themes");
            ThemeCatalog c = new ThemeCatalog(nowhere);
            Preferences p = prefs();
            p.put("ui.theme", "dark");
            ThemeCatalog.Choice s = ThemeCatalog.pick(c, null, p);
            Expect.equal(s.id(), "dark", "dark");
            Expect.isNull(s.notice(), "no notice");
            Expect.equal(ThemeCatalog.pick(c, "light", p).id(), "light", "light");
        });

        Harness.test("every shipped theme parses and resolves", () -> {
            ThemeCatalog c = new ThemeCatalog(Path.of("/dev/null/themes"));
            Expect.isTrue(ThemeCatalog.SHIPPED.size() >= 5, "light, dark, high-contrast and at least two more ship: " + ThemeCatalog.SHIPPED);
            boolean onLight = false, onDark = false;
            for (String id : ThemeCatalog.SHIPPED) {
                opentrap.ui.theme.ThemeFile f;
                ThemeResolver.Resolved r;
                try {
                    f = opentrap.ui.theme.ThemeKeys.builtIn(id);
                    r = c.resolve(id);
                } catch (ThemeException | IOException e) {
                    throw new AssertionError(id + " does not load: " + e.getMessage(), e);
                }
                Expect.isTrue(id.matches("[a-z0-9_-]{1,64}"), id + " is a valid id");
                Expect.isFalse(f.name() == null || f.name().isBlank(), id + " has a name");
                Expect.equal(f.author(), id.equals("light") || id.equals("dark") ? f.author() : "OpenTRAP-S1", id + " author");
                Expect.isTrue(r.unknownKeys().isEmpty(), id + " has unknown keys " + r.unknownKeys());
                Expect.isTrue(r.ignoredHeader().isEmpty(), id + " has unknown header fields " + r.ignoredHeader());
                Expect.isTrue(r.clamped().isEmpty(), id + " has clamped values " + r.clamped());
                if (!id.equals("light") && !id.equals("dark")) {
                    Expect.isTrue("light".equals(f.base()) || "dark".equals(f.base()), id + " names light or dark as its base");
                    Expect.equal(r.appearance(), f.appearance(), id + " appearance");
                    onLight |= "light".equals(f.base());
                    onDark |= "dark".equals(f.base());
                }
                String text = String.join(" ", f.name(), String.valueOf(f.description()), String.valueOf(f.author()));
                Expect.isFalse(text.indexOf('\u2014') >= 0 || text.indexOf('\u2013') >= 0 || text.indexOf('<') >= 0,
                    id + " header has no dash or markup");
            }
            Expect.isTrue(onLight && onDark, "a shipped theme sits on each base");
        });

        Harness.test("the app icon and wordmark follow the theme's appearance, not its base", () -> inFolder(dir -> {
            Path src = write(dir, "night.theme",
                "name = Night\nbase = light\nappearance = dark\n");
            ThemeCatalog c = new ThemeCatalog(dir.resolve("store"));
            c.add(src);
            try {
                ThemeResolver.Resolved r = c.resolve("night");
                Theme.configure(r);
                Expect.isTrue(Theme.dark, "Theme.dark follows appearance");
            } finally {
                Theme.configure(false);
            }
            Expect.isFalse(Theme.dark, "light again");
        }));
    }
}
