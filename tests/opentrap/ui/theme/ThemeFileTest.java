package opentrap.ui.theme;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Pins the theme file syntax, its limits and the value evaluator. */
public final class ThemeFileTest {

    private static final String HEAD = "name = T\nappearance = dark\nbase = dark\n";

    private static final KeyType COLOUR = new KeyType.Colour();

    private static String hex(Color c) {
        return c.getAlpha() == 255
            ? String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue())
            : String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static final Map<String, Object> CONSTANTS = Map.of(
        "radius", 6, "accent", new Color(0xDB, 0x61, 0x2F));

    private static final Function<String, Object> LOOKUP = CONSTANTS::get;

    private static Object eval(String text, KeyType type) throws ThemeException {
        return ThemeExpr.eval("KEY", text, type, LOOKUP, 3);
    }

    private static void eq(String expr, String expected) {
        try {
            Expect.equal(hex((Color) eval(expr, COLOUR)), expected, expr);
        } catch (ThemeException e) {
            throw new AssertionError(expr + ": " + e.getMessage(), e);
        }
    }

    private static String evalError(String text, KeyType type) {
        try {
            eval(text, type);
        } catch (ThemeException e) {
            return e.getMessage();
        }
        throw new AssertionError("no error for " + text);
    }

    private static String parseError(String text) {
        try {
            ThemeFile.parse(text);
        } catch (ThemeException e) {
            return e.getMessage();
        }
        throw new AssertionError("no error for " + text);
    }

    private static ThemeFile parse(String text) {
        try {
            return ThemeFile.parse(text);
        } catch (ThemeException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static List<String> texts(Map<String, ThemeFile.Def> defs) {
        return defs.values().stream().map(ThemeFile.Def::text).toList();
    }

    public void run() {
        Harness.suite("theme file");

        Harness.test("a theme file parses its header, constants and keys with their lines", () -> {
            ThemeFile f = parse("# a comment\nformat = 1\nname = Night\nbase = dark\n"
                + "appearance = dark\nauthor = Me\ndescription = Dim and calm\n\n"
                + "@radius = 6\n@brand = #DB612F\nACCENT = @brand\nRADIUS = @radius + 2\n");
            Expect.equal(f.format(), 1, "format");
            Expect.equal(f.name(), "Night", "name");
            Expect.equal(f.base(), "dark", "base");
            Expect.equal(f.appearance(), "dark", "appearance");
            Expect.equal(f.author(), "Me", "author");
            Expect.equal(f.description(), "Dim and calm", "description");
            Expect.equal(List.copyOf(f.constants().keySet()), List.of("radius", "brand"), "constant names");
            Expect.equal(f.constants().get("brand"), new ThemeFile.Def("#DB612F", 10), "constant def");
            Expect.equal(List.copyOf(f.keys().keySet()), List.of("ACCENT", "RADIUS"), "key names");
            Expect.equal(f.keys().get("RADIUS"), new ThemeFile.Def("@radius + 2", 12), "key def");
        });

        Harness.test("a built-in base has no base line and format defaults to 1", () -> {
            ThemeFile f = parse("name = Light\nappearance = light\n");
            Expect.isNull(f.base(), "base");
            Expect.equal(f.format(), 1, "format");
            Expect.isNull(f.author(), "author");
        });

        Harness.test("a BOM, CRLF line endings and tabs around the equals sign parse like plain LF", () -> {
            String lf = HEAD + "@radius = 6\nACCENT = #112233\nRADIUS = @radius\n";
            String odd = "﻿" + HEAD.replace("\n", "\r\n") + "@radius\t=\t6  \r\n"
                + "ACCENT\t = #112233\t\r\nRADIUS= @radius\r\n";
            ThemeFile a = parse(lf);
            ThemeFile b = parse(odd);
            Expect.equal(b.name(), "T", "name survives the BOM");
            Expect.equal(b.constants(), a.constants(), "constants");
            Expect.equal(b.keys(), a.keys(), "keys");
        });

        Harness.test("the colour functions round each channel to the nearest value", () -> {
            eq("darker(#DB612F, 10%)", "#C5572A");
            eq("lighter(#000000, 50%)", "#808080");
            eq("mix(#FFFFFF, #000000, 25%)", "#404040");
            eq("alpha(#112233, 50%)", "#11223380");
            eq("darker(lighter(#000000, 50%), 50%)", "#404040");
            eq("darker(#808080, 12.5%)", "#707070");
            eq("#11223344", "#11223344");
            eq("@accent", "#DB612F");
            eq("lighter(alpha(#102030, 50%), 0%)", "#10203080");
        });

        Harness.test("numbers add and subtract constants left to right", () -> {
            try {
                Expect.equal(eval("@radius + 2 - 1", new KeyType.Number(0, 12)), 7, "sum");
                Expect.equal(eval("10 - 2 - 3", new KeyType.Number(0, 12)), 5, "left to right");
            } catch (ThemeException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });

        Harness.test("a choice key takes one of its words", () -> {
            try {
                Expect.equal(eval("pill", new KeyType.Choice(List.of("underline", "pill"))), "pill", "word");
            } catch (ThemeException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });

        Harness.test("a constant takes its type from its value", () -> {
            try {
                Expect.equal(hex((Color) ThemeExpr.evalConstant("darker(#808080, 50%)", LOOKUP, 4)),
                    "#404040", "colour");
                Expect.equal(ThemeExpr.evalConstant("@radius + 1", LOOKUP, 4), 7, "number");
                Expect.equal(hex((Color) ThemeExpr.evalConstant("@accent", LOOKUP, 4)),
                    "#DB612F", "alias");
            } catch (ThemeException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });

        Harness.test("a duplicate key names both lines", () -> {
            Expect.equal(parseError(HEAD + "ACCENT = #111111\n\n\nACCENT = #222222\n"),
                "line 7: ACCENT is already set on line 4", "message");
        });

        Harness.test("a duplicate constant names both lines", () -> {
            Expect.equal(parseError(HEAD + "@a = 1\n@a = 2\n"),
                "line 5: @a is already set on line 4", "message");
        });

        Harness.test("an unknown constant is named", () -> {
            Expect.equal(evalError("@acent", COLOUR), "line 3: unknown constant @acent", "message");
        });

        Harness.test("an unknown function is named", () -> {
            Expect.equal(evalError("brighter(#000000, 10%)", COLOUR),
                "line 3: unknown function brighter", "message");
        });

        Harness.test("a value of the wrong type names the key and the value", () -> {
            Expect.equal(evalError("6", COLOUR), "line 3: KEY needs a colour, got 6", "literal");
            Expect.equal(evalError("@radius", COLOUR),
                "line 3: KEY needs a colour, @radius is a number", "constant");
            Expect.equal(evalError("@accent", new KeyType.Number(0, 12)),
                "line 3: KEY needs a number, @accent is a colour", "colour constant");
            Expect.equal(evalError("#112233", new KeyType.Number(0, 12)),
                "line 3: KEY needs a number, got #112233", "colour literal");
        });

        Harness.test("a number outside its range is reported with the bound", () -> {
            Expect.equal(evalError("@radius + 14", new KeyType.Number(0, 12)),
                "line 3: KEY is 20, above 12", "above");
            Expect.equal(evalError("2 - 5", new KeyType.Number(0, 12)),
                "line 3: KEY is -3, below 0", "below");
        });

        Harness.test("a choice outside its words lists them", () -> {
            Expect.equal(evalError("box", new KeyType.Choice(List.of("underline", "pill"))),
                "line 3: KEY takes underline or pill, got box", "message");
        });

        Harness.test("a bad percent is rejected", () -> {
            Expect.equal(evalError("darker(#000000, 150%)", COLOUR),
                "line 3: KEY needs a percent from 0% to 100% with at most one decimal, got 150%", "range");
            Expect.equal(evalError("darker(#000000, 1.25%)", COLOUR),
                "line 3: KEY needs a percent from 0% to 100% with at most one decimal, got 1.25%", "decimals");
        });

        Harness.test("function calls nest 8 deep and no further", () -> {
            String ok = "#808080";
            for (int i = 0; i < 8; i++) ok = "darker(" + ok + ", 1%)";
            String deep = "darker(" + ok + ", 1%)";
            try {
                eval(ok, COLOUR);
            } catch (ThemeException e) {
                throw new AssertionError("depth 8: " + e.getMessage(), e);
            }
            Expect.equal(evalError(deep, COLOUR), "line 3: KEY nests functions deeper than 8", "depth 9");
        });

        Harness.test("a format above 1 asks for a newer OpenTRAP", () -> {
            Expect.equal(parseError("format = 2\n" + HEAD),
                "line 1: needs a newer OpenTRAP (format 2)", "message");
        });

        Harness.test("a theme above 65536 bytes is rejected", () -> {
            String big = HEAD + "# " + "x".repeat(65536) + "\n";
            Expect.equal(parseError(big),
                "theme is " + big.getBytes(StandardCharsets.UTF_8).length + " bytes, above 65536", "message");
        });

        Harness.test("a theme above 2000 lines is rejected", () -> {
            String many = HEAD + "\n".repeat(2000);
            Expect.equal(parseError(many), "theme has 2003 lines, above 2000", "message");
        });

        Harness.test("a theme without a name or appearance is rejected", () -> {
            Expect.equal(parseError("appearance = dark\nbase = dark\n"), "theme needs a name", "name");
            Expect.equal(parseError("name = T\nbase = dark\n"), "theme needs an appearance", "appearance");
        });

        Harness.test("base and appearance take light or dark only", () -> {
            Expect.equal(parseError("name = T\nappearance = dark\nbase = blue\n"),
                "line 3: base takes light or dark, got blue", "base");
            Expect.equal(parseError("name = T\nappearance = grey\n"),
                "line 2: appearance takes light or dark, got grey", "appearance");
        });

        Harness.test("a malformed line is reported with its number", () -> {
            Expect.equal(parseError(HEAD + "nonsense\n"), "line 4: expected name = value", "no equals");
            Expect.equal(parseError(HEAD + "Accent = #000000\n"), "line 4: bad name Accent", "bad name");
            Expect.equal(parseError(HEAD + "ACCENT =\n"), "line 4: ACCENT has no value", "no value");
            Expect.equal(parseError(HEAD + "A".repeat(65) + " = 1\n"),
                "line 4: name is longer than 64 characters", "long name");
        });

        Harness.test("an unknown header field is ignored and listed", () -> {
            ThemeFile f = parse(HEAD + "website = example.org\nlicense = MIT\n");
            Expect.equal(f.ignoredHeader(), List.of("website", "license"), "ignored");
        });

        Harness.test("exceptions format their line only when there is one", () -> {
            Expect.equal(new ThemeException(5, "x").getMessage(), "line 5: x", "with line");
            Expect.equal(new ThemeException(0, "x").getMessage(), "x", "without line");
            Expect.equal(new ThemeException(5, "x").line(), 5, "line");
        });
    }
}
