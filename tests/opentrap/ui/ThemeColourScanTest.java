package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Colours live in the theme: no source file outside the not-themed list writes one as a literal. */
public final class ThemeColourScanTest {

    /** Brand marks, what leaves the app, and the theme machinery itself (the design's "Not themed"). */
    private static final List<String> ALLOWED = List.of(
            "ui/Theme.java", "ui/theme/ThemeExpr.java", "ui/AppIcon.java", "ui/Wordmark.java",
            "proxy/BrowserDesktopEntry.java", "report/Export.java", "proxy/ProxyServer.java");

    /** The fully transparent colour that switches painting off, allowed by its exact text. */
    private static final String TRANSPARENT = "new Color(0, 0, 0, 0)";

    private static final Pattern LITERAL = Pattern.compile(
            "new Color\\(|Color\\.decode|\\bColor\\.(?!class\\b)(?:[A-Z][A-Z_]*|[a-z]+)\\b(?!\\s*\\()|\"#[0-9A-Fa-f]{6}\"");

    public void run() {
        Harness.suite("theme colour scan");

        Harness.test("no colour is written as a literal outside the theme", () -> {
            TreeMap<String, Integer> found = new TreeMap<>();
            try (Stream<Path> files = Files.walk(Path.of("src"))) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String rel = Path.of("src", "opentrap").relativize(f).toString().replace('\\', '/');
                    if (ALLOWED.contains(rel)) continue;
                    String text = Files.readString(f).replace(TRANSPARENT, "");
                    Matcher m = LITERAL.matcher(text);
                    int n = 0;
                    while (m.find()) n++;
                    if (n > 0) found.put(rel, n);
                }
            } catch (IOException e) { throw new RuntimeException(e); }
            Expect.equal(found.toString(), "{}", "files");
        });

        Harness.test("a stored highlight name maps to its theme colour and none or unknown maps to nothing", () -> {
            Expect.equal(Theme.highlight("red"), Theme.HIGHLIGHT_RED, "red");
            Expect.equal(Theme.highlight("grey"), Theme.HIGHLIGHT_GREY, "grey");
            Expect.equal(Theme.highlight(""), null, "empty");
            Expect.equal(Theme.highlight("none"), null, "none");
            Expect.equal(Theme.highlight("mauve"), null, "unknown");
        });
    }
}
