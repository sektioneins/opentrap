package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Font;
import java.util.Set;

/** Pins how the UI and monospace fonts are chosen from settings and the system. */
public final class FontsTest {

    private static Fonts.Env env(String os, String ui, String mono, String... installed) {
        Set<String> have = Set.of(installed);
        return new Fonts.Env(os, ui, mono, have::contains);
    }

    public void run() {
        Harness.suite("fonts");

        Harness.test("the macOS default UI font is the system font when it is installed", () ->
                Expect.equal(Fonts.resolveUi(env("mac", null, null, ".AppleSystemUIFont", "Noto Sans", "Dialog"), null, null),
                        new Fonts.Choice(".AppleSystemUIFont", 12), "choice"));

        Harness.test("the Linux default UI font is the desktop font family", () ->
                Expect.equal(Fonts.resolveUi(env("linux", "Cantarell", null, "Cantarell", "Noto Sans", "Dialog"), null, null),
                        new Fonts.Choice("Cantarell", 12), "choice"));

        Harness.test("the Windows default UI font is the message box font family", () ->
                Expect.equal(Fonts.resolveUi(env("windows", "Segoe UI", null, "Segoe UI", "Dialog"), null, null).family(),
                        "Segoe UI", "family"));

        Harness.test("an uninstalled system UI font falls back to Noto Sans, then Dialog", () -> {
            Expect.equal(Fonts.resolveUi(env("linux", "Cantarell", null, "Noto Sans", "Dialog"), null, null).family(),
                    "Noto Sans", "noto");
            Expect.equal(Fonts.resolveUi(env("linux", "Cantarell", null, "Dialog"), null, null).family(),
                    "Dialog", "dialog");
            Expect.equal(Fonts.resolveUi(env("linux", null, null, "Dialog"), null, null).family(),
                    "Dialog", "no desktop font");
        });

        Harness.test("a saved UI family that is not installed falls back to the default", () -> {
            Fonts.Env e = env("linux", "Cantarell", null, "Cantarell", "Noto Sans", "Dialog");
            Expect.equal(Fonts.resolveUi(e, "Gone Font", null).family(), "Cantarell", "gone");
            Expect.equal(Fonts.resolveUi(e, "Noto Sans", null).family(), "Noto Sans", "saved");
        });

        Harness.test("a UI size outside 6 to 40 or not a number falls back to 12", () -> {
            Fonts.Env e = env("linux", null, null, "Dialog");
            for (String bad : new String[]{"abc", "0", "999", "", null})
                Expect.equal(Fonts.resolveUi(e, null, bad).size(), 12, "size " + bad);
            Expect.equal(Fonts.resolveUi(e, null, "16").size(), 16, "16");
            Expect.equal(Fonts.resolveUi(e, null, "6").size(), 6, "6");
            Expect.equal(Fonts.resolveUi(e, null, "40").size(), 40, "40");
        });

        Harness.test("the macOS monospace default is Menlo", () -> {
            Expect.equal(Fonts.resolveMono(env("mac", null, null, "Menlo", "Monospaced"), null),
                    new Fonts.Choice("Menlo", 12), "menlo");
            Expect.equal(Fonts.resolveMono(env("mac", null, null, "Monospaced"), null).family(),
                    "Monospaced", "fallback");
        });

        Harness.test("elsewhere the monospace default is the system monospace font, else Monospaced", () -> {
            Expect.equal(Fonts.resolveMono(env("linux", null, "Source Code Pro", "Source Code Pro", "Monospaced"), null).family(),
                    "Source Code Pro", "installed");
            Expect.equal(Fonts.resolveMono(env("linux", null, "Source Code Pro", "Monospaced"), null).family(),
                    "Monospaced", "not installed");
            Expect.equal(Fonts.resolveMono(env("linux", null, null, "Monospaced"), null).family(),
                    "Monospaced", "null");
            Expect.equal(Fonts.resolveMono(env("windows", null, null, "Monospaced"), null).family(),
                    "Monospaced", "windows");
        });

        Harness.test("a saved monospace font is used when installed and well formed", () -> {
            Fonts.Env e = env("mac", null, null, "Menlo", "Fira Code", "Monospaced");
            Expect.equal(Fonts.resolveMono(e, "Fira Code 14pt"), new Fonts.Choice("Fira Code", 14), "good");
            Expect.equal(Fonts.resolveMono(e, "Gone 14pt"), new Fonts.Choice("Menlo", 14), "gone");
            Expect.equal(Fonts.resolveMono(e, "Menlo"), new Fonts.Choice("Menlo", 12), "no size");
            Expect.equal(Fonts.resolveMono(e, ""), new Fonts.Choice("Menlo", 12), "empty");
            Expect.equal(Fonts.resolveMono(e, "Menlo 99pt"), new Fonts.Choice("Menlo", 12), "out of range");
        });

        Harness.test("desktop font names lose their size and quotes", () -> {
            Expect.equal(Fonts.familyOf("Cantarell 11"), "Cantarell", "plain");
            Expect.equal(Fonts.familyOf("'Source Code Pro 10'"), "Source Code Pro", "quoted");
            Expect.equal(Fonts.familyOf("Noto Sans"), "Noto Sans", "unchanged");
            Expect.equal(Fonts.familyOf(null), null, "null");
            Expect.equal(Fonts.familyOf("  "), null, "blank");
        });

        Harness.test("scaled sizes keep their proportion to the UI size", () -> {
            Expect.equal(Fonts.scaled(12, 12), 12, "12/12");
            Expect.equal(Fonts.scaled(18, 16), 24, "18/16");
            Expect.equal(Fonts.scaled(13, 12), 13, "13/12");
        });

        Harness.test("a command that does not finish in time gives no answer", () -> {
            Expect.isTrue(Fonts.firstLine(100, "/bin/sh", "-c", "sleep 5") == null, "timeout");
            Expect.isTrue(Fonts.firstLine(1000, "no-such-binary-opentrap") == null, "missing");
            Expect.equal(Fonts.firstLine(1000, "/bin/sh", "-c", "echo hi"), "hi", "output");
        });

        Harness.test("the active fonts are installed families", () -> {
            Fonts.Env live = Fonts.liveEnv();
            Expect.isTrue(live.installed().test(Fonts.uiFamily()), "ui " + Fonts.uiFamily());
            Expect.isTrue(live.installed().test(Fonts.monoFamily()), "mono " + Fonts.monoFamily());
            Expect.equal(Fonts.ui(Font.BOLD, 18).getSize(), Fonts.scaled(18, Fonts.uiSize()), "scaled");
        });
    }
}
