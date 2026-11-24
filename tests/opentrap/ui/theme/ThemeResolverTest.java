package opentrap.ui.theme;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pins how a theme is laid over its base: constants, keys, clamping and errors. */
public final class ThemeResolverTest {

    private static final Map<String, KeyType> SPEC = new LinkedHashMap<>();

    static {
        SPEC.put("ACCENT", new KeyType.Colour());
        SPEC.put("ACCENT_DARK", new KeyType.Colour());
        SPEC.put("CARD_RADIUS", new KeyType.Number(0, 16));
    }

    private static final String BASE = "name = Dark\nappearance = dark\n"
        + "@accent = #3A6EA5\n@page = #202020\n@radius = 8\n"
        + "ACCENT = @accent\nACCENT_DARK = darker(@accent, 10%)\nCARD_RADIUS = @radius + 2\n";

    private static final String HEAD = "name = T\nappearance = dark\nbase = dark\n";

    private static ThemeFile parse(String text) {
        try {
            return ThemeFile.parse(text);
        } catch (ThemeException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static ThemeResolver.Resolved resolve(String base, String theme) {
        return resolve(base, theme, SPEC);
    }

    private static ThemeResolver.Resolved resolve(String base, String theme, Map<String, KeyType> spec) {
        try {
            return ThemeResolver.resolve(parse(base), theme == null ? null : parse(theme), spec);
        } catch (ThemeException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static String error(String base, String theme) {
        try {
            ThemeResolver.resolve(parse(base), theme == null ? null : parse(theme), SPEC);
        } catch (ThemeException e) {
            return e.getMessage();
        }
        throw new AssertionError("no error");
    }

    private static String hex(Object c) {
        Color k = (Color) c;
        return String.format("#%02X%02X%02X", k.getRed(), k.getGreen(), k.getBlue());
    }

    public void run() {
        Harness.suite("theme resolver");

        Harness.test("a theme's constant flows into a key it does not set", () -> {
            ThemeResolver.Resolved r = resolve(BASE, HEAD + "@accent = #DB612F\n");
            Expect.equal(hex(r.values().get("ACCENT")), "#DB612F", "accent");
            Expect.equal(hex(r.values().get("ACCENT_DARK")), "#C5572A", "derived");
        });

        Harness.test("a key the theme sets wins over the base's definition", () -> {
            ThemeResolver.Resolved r = resolve(BASE, HEAD + "ACCENT_DARK = #112233\n");
            Expect.equal(hex(r.values().get("ACCENT_DARK")), "#112233", "own key");
            Expect.equal(hex(r.values().get("ACCENT")), "#3A6EA5", "base key");
        });

        Harness.test("a key added only to the base follows an older theme's constants", () -> {
            Map<String, KeyType> spec = new LinkedHashMap<>(SPEC);
            spec.put("BADGE", new KeyType.Colour());
            String base = BASE + "BADGE = mix(@accent, @page, 20%)\n";
            ThemeResolver.Resolved r = resolve(base, HEAD + "@accent = #DB612F\n@page = #000000\n", spec);
            Expect.equal(hex(r.values().get("BADGE")), "#2C1309", "badge");
        });

        Harness.test("a derived number out of range is clamped and reported", () -> {
            ThemeResolver.Resolved r = resolve(BASE, HEAD + "@radius = 15\n");
            Expect.equal(r.values().get("CARD_RADIUS"), 16, "value");
            Expect.equal(r.clamped(), List.of("CARD_RADIUS 17 clamped to 16"), "clamped");
        });

        Harness.test("a number the theme sets out of range is an error at its line", () -> {
            Expect.equal(error(BASE, HEAD + "CARD_RADIUS = 20\n"),
                "line 4: CARD_RADIUS is 20, above 16", "message");
        });

        Harness.test("a key the spec does not know is ignored and listed", () -> {
            ThemeResolver.Resolved r = resolve(BASE, HEAD + "NOPE = #000000\n");
            Expect.equal(r.unknownKeys(), List.of("NOPE"), "unknown");
            Expect.isFalse(r.values().containsKey("NOPE"), "not resolved");
        });

        Harness.test("a cycle between constants is an error naming the cycle", () -> {
            Expect.equal(error(BASE, HEAD + "@a = @b\n@b = @a\n"),
                "line 4: cycle: @a -> @b -> @a", "message");
        });

        Harness.test("a base that misses a spec key is an error", () -> {
            Expect.equal(error("name = Dark\nappearance = dark\nACCENT = #000000\n", null),
                "Dark does not define ACCENT_DARK", "message");
        });

        Harness.test("the keys the theme leaves to its base are listed", () -> {
            ThemeResolver.Resolved r = resolve(BASE, HEAD + "ACCENT = #112233\n");
            Expect.equal(r.leftToBase(), List.of("ACCENT_DARK", "CARD_RADIUS"), "left");
            Expect.equal(r.appearance(), "dark", "appearance");
        });

        Harness.test("a theme constant of the wrong type is blamed on its line, naming the base", () -> {
            Expect.equal(error(BASE, HEAD + "@accent = 5\n"),
                "line 4: @accent is a number, ACCENT needs a colour (from the dark base)", "message");
        });

        Harness.test("a theme constant of the wrong type is blamed on its line when a base constant uses it", () -> {
            String base = "name = Dark\nappearance = dark\n@accent = #3A6EA5\n@accent_dark = darker(@accent, 10%)\n"
                + "ACCENT = @accent\nACCENT_DARK = @accent_dark\nCARD_RADIUS = 4\n";
            Expect.equal(error(base, HEAD + "@accent = 5\n"),
                "line 4: @accent is a number, @accent_dark needs a colour (from the dark base)", "message");
        });

        Harness.test("a theme without a base is refused and a missing theme resolves the base alone", () -> {
            Expect.equal(error(BASE, "name = T\nappearance = dark\n"), "theme has no base", "message");
            Expect.equal(resolve(BASE, null).leftToBase(), List.of(), "nothing left to base");
            Expect.equal(resolve(BASE, null).values().get("CARD_RADIUS"), 10, "base value");
        });
    }
}
