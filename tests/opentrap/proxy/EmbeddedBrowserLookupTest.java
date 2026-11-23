package opentrap.proxy;

import java.nio.file.Path;
import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Where the testing browser is looked for, what is said when it is missing, and
 * the command line it is started with.
 */
public final class EmbeddedBrowserLookupTest {

    public void run() {
        Harness.suite("Testing browser lookup");

        Harness.test("on macOS the installed Chromium app is a candidate, the system one before the user's", () -> {
            List<String> c = EmbeddedBrowser.candidates("Mac OS X", "/Users/x");
            String system = "/Applications/Chromium.app/Contents/MacOS/Chromium";
            String user = "/Users/x/Applications/Chromium.app/Contents/MacOS/Chromium";
            Expect.isTrue(c.contains(system), "system app path");
            Expect.isTrue(c.contains(user), "user app path");
            Expect.isTrue(c.indexOf(system) < c.indexOf(user), "system app first");
        });

        Harness.test("on Linux the macOS app paths are not candidates", () -> {
            List<String> c = EmbeddedBrowser.candidates("Linux", "/home/x");
            for (String s : c) Expect.isTrue(!s.contains(".app/"), "no app path: " + s);
            Expect.equal(c, List.of("/usr/bin/chromium", "/usr/bin/chromium-browser",
                    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                    "/usr/bin/brave-browser", "/snap/bin/chromium"), "linux candidates");
        });

        Harness.test("on macOS the missing-browser error names the Homebrew install", () -> {
            Expect.isTrue(EmbeddedBrowser.notFoundMessage("Mac OS X")
                    .contains("brew install ungoogled-chromium"), "brew hint");
        });

        Harness.test("on Linux the missing-browser error keeps the apt hint", () -> {
            Expect.equal(EmbeddedBrowser.notFoundMessage("Linux"),
                    "No Chromium-based browser found. Install chromium (apt install chromium).",
                    "linux message");
        });

        Path profile = Path.of("/home/x/.opentrap/browser");

        Harness.test("on Linux the browser gets its own window class and runs as an X11 client", () -> {
            List<String> c = EmbeddedBrowser.command("/usr/bin/chromium", "Linux", profile, 8080, "abc", true);
            Expect.isTrue(c.contains("--class=" + BrowserDesktopEntry.WM_CLASS), "window class");
            Expect.isTrue(c.contains("--ozone-platform=x11"), "x11");
        });

        Harness.test("on macOS the browser gets no window class and no X11 platform flag", () -> {
            List<String> c = EmbeddedBrowser.command("/Applications/Chromium.app/Contents/MacOS/Chromium",
                    "Mac OS X", profile, 8080, "abc", true);
            for (String s : c) {
                Expect.isTrue(!s.startsWith("--class"), "no window class: " + s);
                Expect.isTrue(!s.startsWith("--ozone-platform"), "no platform flag: " + s);
            }
        });

        Harness.test("the browser starts with no URL and no new-window flag, so the profile chooser can show", () -> {
            for (String os : List.of("Linux", "Mac OS X")) {
                List<String> c = EmbeddedBrowser.command("/b", os, profile, 8080, "abc", false);
                Expect.equal(c.get(0), "/b", "binary first on " + os);
                Expect.isTrue(!c.contains("--new-window"), "no --new-window on " + os);
                for (String s : c.subList(1, c.size())) {
                    Expect.isTrue(s.startsWith("--"), "only flags after the binary on " + os + ": " + s);
                }
                Expect.isTrue(c.contains("--proxy-server=http://127.0.0.1:8080"), "proxy on " + os);
                Expect.isTrue(c.contains("--user-data-dir=" + profile), "profile on " + os);
            }
        });
    }
}
