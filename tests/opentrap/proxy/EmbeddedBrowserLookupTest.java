package opentrap.proxy;

import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/** Where the testing browser is looked for, and what is said when it is missing. */
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
    }
}
