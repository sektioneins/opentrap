package opentrap.extensions.store;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * The version and dependency range forms ZAP's add-on feed uses: integer and
 * dotted add-on versions, and ranges such as {@code >= 1.29.0 & < 2.0.0} or
 * {@code 13.*}.
 */
public final class VersionTest {

    private static Version v(String s) { return Version.parse(s); }

    private static void refused(String s) {
        boolean threw = false;
        try { Version.parse(s); } catch (IllegalArgumentException e) { threw = true; }
        Expect.isTrue(threw, "'" + s + "' is refused");
    }

    public void run() {
        Harness.suite("Extension store versions");

        Harness.test("an integer add-on version orders as a major version", () -> {
            Expect.isTrue(v("50").compareTo(v("9")) > 0, "50 above 9");
            Expect.equal(v("50"), v("50.0.0"), "50 is 50.0.0");
            Expect.equal(v("50").hashCode(), v("50.0.0").hashCode(), "hash agrees");
            Expect.equal(v("50").toString(), "50", "text as given");
        });

        Harness.test("a two-part version fills the patch with zero", () ->
            Expect.equal(v("0.13"), v("0.13.0"), "0.13 is 0.13.0"));

        Harness.test("a version that is not numeric is refused", () -> {
            refused("1.0-beta");
            refused("");
            refused("1.2.3.4");
            refused("1..2");
        });

        Harness.test("a range of two terms needs both", () -> {
            VersionRange r = VersionRange.parse(">= 1.29.0 & < 2.0.0");
            Expect.isTrue(r.matches(v("1.44.0")), "1.44.0 inside");
            Expect.isFalse(r.matches(v("2.0.0")), "2.0.0 above");
            Expect.isFalse(r.matches(v("1.28.9")), "1.28.9 below");
        });

        Harness.test("a range without spaces parses", () ->
            Expect.isTrue(VersionRange.parse(">=0.3.0").matches(v("0.33.0")), "0.33.0 matches"));

        Harness.test("a wildcard range matches its leading parts", () -> {
            VersionRange r = VersionRange.parse("13.*");
            Expect.isTrue(r.matches(v("13.5.1")), "13.5.1");
            Expect.isFalse(r.matches(v("14.0.0")), "14.0.0");
            VersionRange m = VersionRange.parse("2.3.*");
            Expect.isTrue(m.matches(v("2.3.9")), "2.3.9");
            Expect.isFalse(m.matches(v("2.4.0")), "2.4.0");
        });

        Harness.test("a bare version and the other operators compare as written", () -> {
            Expect.isTrue(VersionRange.parse("1.2").matches(v("1.2.0")), "exact");
            Expect.isFalse(VersionRange.parse("1.2").matches(v("1.2.1")), "exact misses");
            Expect.isTrue(VersionRange.parse("= 1.2.0").matches(v("1.2")), "equals");
            Expect.isFalse(VersionRange.parse("> 1.2.0").matches(v("1.2.0")), "strictly above");
            Expect.isTrue(VersionRange.parse("<= 1.2.0").matches(v("1.2.0")), "at most");
            Expect.isFalse(VersionRange.parse("< 1.2.0").matches(v("1.2.0")), "strictly below");
        });

        Harness.test("an empty range matches anything", () -> {
            Expect.isTrue(VersionRange.parse(null) == VersionRange.ANY, "null is ANY");
            Expect.isTrue(VersionRange.parse("  ") == VersionRange.ANY, "blank is ANY");
            Expect.isTrue(VersionRange.ANY.matches(v("0")), "matches 0");
        });

        Harness.test("a range that is not one of the feed forms is refused", () -> {
            for (String s : new String[] {">=", ">= x", "1.*.2", "*", "1.2.3.*", ">= 1.0 &"}) {
                boolean threw = false;
                try { VersionRange.parse(s); } catch (IllegalArgumentException e) { threw = true; }
                Expect.isTrue(threw, "'" + s + "' is refused");
            }
        });

        Harness.test("status ranks release above beta above alpha", () -> {
            int beta = Status.parse("beta").rank();
            Expect.isTrue(Status.parse("release").rank() > beta, "release above beta");
            Expect.isTrue(beta > Status.parse("alpha").rank(), "beta above alpha");
            Expect.equal(Status.parse("release").rank(), 3, "release rank");
            Expect.equal(Status.BETA.label(), "beta", "label");
            boolean threw = false;
            try { Status.parse("gamma"); } catch (IllegalArgumentException e) { threw = true; }
            Expect.isTrue(threw, "gamma refused");
        });

        Harness.test("the add-on kind has a label", () ->
            Expect.equal(Kind.ZAP_ADDON.label(), "ZAP add-on", "label"));
    }
}
