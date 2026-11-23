package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.swing.JEditorPane;
import javax.swing.text.View;
import javax.swing.text.html.HTMLDocument;

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

        Harness.test("the shared fonts are the resolved UI and monospace fonts", () -> {
            for (Font f : new Font[] {Ui.PLAIN, Ui.BOLD, Theme.FONT, Theme.FONT_BOLD}) {
                Expect.equal(f.getName(), Fonts.uiFamily(), "ui family");
                Expect.equal(f.getSize(), Fonts.uiSize(), "ui size");
            }
            Expect.equal(Ui.HEADING.getName(), Fonts.uiFamily(), "heading family");
            Expect.equal(Ui.HEADING.getSize(), Fonts.scaled(13, Fonts.uiSize()), "heading size");
            Expect.equal(Ui.MONO.getName(), Fonts.monoFamily(), "mono family");
            Expect.equal(Ui.MONO.getSize(), Fonts.monoSize(), "mono size");
        });

        Harness.test("an HTML pane renders a family with spaces or a leading dot", () -> {
            for (String family : new String[] {"Noto Sans", ".AppleSystemUIFont"}) {
                Expect.isFalse(Fonts.css(family).contains("'"), "no single quote in " + Fonts.css(family));
                Expect.equal(renderedFont("<html><body style='font-family:" + Fonts.css(family) + "'>x</body></html>"),
                        family, "inline " + family);
            }
            Expect.equal(renderedFont("<html><body>x</body></html>"), Fonts.uiFamily(), "body rule");
        });

        Harness.test("the advisory uses the UI family for text and the monospace family for evidence", () -> {
            String html = Advisory.render(opentrap.core.Issue.builder("Finding").high().certain()
                    .path("/p").evidence("proof").build());
            Expect.isTrue(html.contains(Fonts.css(Fonts.uiFamily())), "ui family");
            Expect.isTrue(html.contains(Fonts.css(Fonts.monoFamily())), "mono family");
            Expect.isFalse(html.contains("sans-serif"), "no sans-serif");
            Expect.isFalse(html.contains("font-family:monospace"), "no monospace keyword");
        });

        Harness.test("the resolved fonts can draw every character the Dialog font can", () -> {
            Font dialog = new Font(Font.DIALOG, Font.PLAIN, 12);
            Font[] fonts = {Fonts.ui(Font.PLAIN, 12), Fonts.mono(Font.PLAIN, 12), Theme.FONT, Ui.MONO,
                    Fonts.ui(Font.BOLD, 14).deriveFont(20f)};
            for (char c : new char[]{'\u4e2d', '\u0416', '\u03a9', '\u0628', '\u05d0'}) {
                if (!dialog.canDisplay(c)) continue;
                for (Font f : fonts)
                    Expect.isTrue(f.canDisplay(c), "U+" + Integer.toHexString(c) + " in " + f);
            }
        });

        Harness.test("a built font keeps its family and size and is not a UIResource", () -> {
            Font f = Fonts.build("Menlo", Font.BOLD, 15);
            Expect.equal(f.getSize(), 15, "size");
            Expect.equal(f.getStyle(), Font.BOLD, "style");
            Expect.isFalse(f instanceof javax.swing.plaf.UIResource, "not a UIResource");
            Expect.isFalse(Fonts.ui(Font.PLAIN, 12) instanceof javax.swing.plaf.UIResource, "ui not a UIResource");
            Expect.equal(Fonts.ui(Font.PLAIN, 12).getFamily(Locale.ROOT).equalsIgnoreCase(Fonts.uiFamily())
                    || Fonts.uiFamily().equals("Dialog"), true, "ui family kept");
        });

        Harness.test("no text font is built from the Dialog family outside icon drawing", () -> {
            TreeMap<String, Integer> found = new TreeMap<>();
            try (Stream<Path> files = Files.walk(Path.of("src"))) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String text = Files.readString(f);
                    int n = text.split("new Font\\(\"Dialog\"", -1).length - 1;
                    if (n > 0) found.put(f.getFileName().toString(), n);
                }
            } catch (IOException e) { throw new RuntimeException(e); }
            Expect.equal(found.toString(),
                    "{DashboardTool.java=1, DialogIcon.java=1, Glyphs.java=2, MessageEditor.java=1, Ui.java=1}", "files");
        });
    }

    /** The font a laid-out pane gives the text "x", from the style the view resolves (page rules included). */
    private static String renderedFont(String html) {
        JEditorPane pane = new JEditorPane();
        pane.setEditorKit(Theme.htmlKit());
        pane.setSize(300, 300);
        pane.setText(html);
        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        try {
            View v = pane.getUI().getRootView(pane);
            int at = doc.getText(0, doc.getLength()).indexOf('x');
            for (boolean down = true; down; ) {
                down = false;
                for (int i = 0; i < v.getViewCount(); i++) {
                    View c = v.getView(i);
                    if (c.getStartOffset() <= at && at < c.getEndOffset()) { v = c; down = true; break; }
                }
            }
            return doc.getFont(v.getAttributes()).getName();
        } catch (javax.swing.text.BadLocationException e) { throw new RuntimeException(e); }
    }
}
