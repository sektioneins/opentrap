package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * The toolbar's response rewrites, pinned by example so that how they are
 * implemented can change without the output changing.
 */
public final class HtmlRewriteTest {

    public void run() {
        Harness.suite("proxy response modification");

        Harness.test("hidden fields become text fields in any quoting and case", () -> {
            Expect.equal(HtmlRewrite.unhideHiddenFields("<input type=\"hidden\" name=a>"),
                    "<input type=\"text\" name=a>", "double quotes");
            Expect.equal(HtmlRewrite.unhideHiddenFields("<input TYPE = 'hidden'>"),
                    "<input type='text'>", "single quotes, spaces, upper case");
            Expect.equal(HtmlRewrite.unhideHiddenFields("<input type=hidden>"),
                    "<input type=text>", "unquoted");
        });

        Harness.test("disabled attributes go with their value and their leading whitespace", () -> {
            Expect.equal(HtmlRewrite.enableDisabledFields("<input disabled name=a>"),
                    "<input name=a>", "bare");
            Expect.equal(HtmlRewrite.enableDisabledFields("<input name=a DISABLED=\"disabled\">"),
                    "<input name=a>", "with value");
            Expect.equal(HtmlRewrite.enableDisabledFields("<input disabled  name=a>"),
                    "<input  name=a>", "only the whitespace before it");
        });

        Harness.test("length limits go with the whole whitespace run before them", () -> {
            Expect.equal(HtmlRewrite.removeLengthLimits("<input \t\r\n MaxLength = '8' name=a>"),
                    "<input name=a>", "whitespace run");
            Expect.equal(HtmlRewrite.removeLengthLimits("<input maxlength=1 maxlength=\"2\">"),
                    "<input>", "repeated");
            Expect.equal(HtmlRewrite.removeLengthLimits(" maxlength=3"), "", "whole input");
        });

        Harness.test("onsubmit handlers are removed from forms", () -> {
            Expect.equal(HtmlRewrite.removeJsValidation("<form onsubmit=\"return check()\" action=x>"),
                    "<form action=x>", "onsubmit");
        });

        Harness.test("object spans and embed tags are removed across lines and case", () -> {
            Expect.equal(HtmlRewrite.removeObjectTags("<p><object data=x>\ninner</Object></p><EMBED src=y>"),
                    "<p></p>", "object and embed");
        });

        Harness.test("script spans end at the first close and an unclosed one stays", () -> {
            Expect.equal(HtmlRewrite.removeAllJavaScript("<script>a<script>b</script>c</script>"),
                    "c</script>", "first close wins");
            Expect.equal(HtmlRewrite.removeAllJavaScript("<b>x</b><script>never closed"),
                    "<b>x</b><script>never closed", "unclosed");
        });

        Harness.test("every on-handler attribute is removed with the scripts", () -> {
            Expect.equal(HtmlRewrite.removeAllJavaScript(
                    "<SCRIPT src=y>\n</SCRIPT><b onclick=\"x()\" ONMOUSEOVER = \"y()\">t</b>"),
                    "<b>t</b>", "script and handlers");
        });
    }
}
