package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The toolbar's response rewrites, pinned by example so that how they are
 * implemented can change without the output changing.
 */
public final class HtmlRewriteTest {

    private static final long DEADLINE_MS = 1000;

    private static final String[] TOKENS = {
        " ", "\t", "\n", "\r", "\f", "\u000b", "<script", "<SCRIPT>", "</script>", "</Script>",
        "<object", "</object>", "</OBJECT>", "<embed", "<Embed ", ">", "disabled", "DISABLED",
        "maxlength", "MaxLength", "onsubmit", "onclick", "on", "type", "hidden", "=", "\"", "'",
        " maxlength=\"", " onsubmit=\"", " onclick=\"", " disabled=", "type=", "type=\"hidden\"", " type = 'hidden'",
        " maxlength=\"7\"", " maxlength=42",
        "1", "42", "x", "\u00e9", "\u00ff", "\u00b5"
    };

    /** Runs a rewrite on a daemon thread so a runaway one fails the case instead of hanging the run. */
    private static String within(String what, Supplier<String> rewrite) {
        String[] result = new String[1];
        Throwable[] failure = new Throwable[1];
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                result[0] = rewrite.get();
            } catch (Throwable e) {
                failure[0] = e;
            } finally {
                done.countDown();
            }
        }, "rewrite-" + what);
        t.setDaemon(true);
        t.start();
        try {
            if (!done.await(DEADLINE_MS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError(what + " did not finish within " + DEADLINE_MS + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " was interrupted");
        }
        if (failure[0] != null) throw new AssertionError(what + " failed: " + failure[0]);
        return result[0];
    }

    private static String quoted(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') b.append(c);
            else b.append(String.format("\\u%04x", (int) c));
        }
        return b.append('"').toString();
    }

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

        Harness.test("a megabyte of whitespace is rewritten in linear time", () -> {
            String run = "<input" + " \t\n".repeat(350_000) + ">";
            Expect.isTrue(within("removeLengthLimits", () -> HtmlRewrite.removeLengthLimits(run)).equals(run),
                    "removeLengthLimits leaves it unchanged");
            Expect.isTrue(within("enableDisabledFields", () -> HtmlRewrite.enableDisabledFields(run)).equals(run),
                    "enableDisabledFields leaves it unchanged");
            Expect.isTrue(within("removeJsValidation", () -> HtmlRewrite.removeJsValidation(run)).equals(run),
                    "removeJsValidation leaves it unchanged");
            Expect.isTrue(within("removeAllJavaScript", () -> HtmlRewrite.removeAllJavaScript(run)).equals(run),
                    "removeAllJavaScript leaves it unchanged");
        });

        Harness.test("a megabyte of unclosed tags is rewritten in linear time", () -> {
            String scripts = "<script ".repeat(130_000);
            Expect.isTrue(within("removeAllJavaScript", () -> HtmlRewrite.removeAllJavaScript(scripts)).equals(scripts),
                    "unclosed script tags");
            String objects = "<object ".repeat(130_000);
            Expect.isTrue(within("removeObjectTags objects", () -> HtmlRewrite.removeObjectTags(objects)).equals(objects),
                    "unclosed object tags");
            String embeds = "<embed ".repeat(150_000);
            Expect.isTrue(within("removeObjectTags embeds", () -> HtmlRewrite.removeObjectTags(embeds)).equals(embeds),
                    "unclosed embed tags");
        });

        Harness.test("the linear rewrites give the same output as the original patterns", () -> {
            Random rnd = new Random(25);
            for (int i = 0; i < 3000; i++) {
                int n = rnd.nextInt(41);
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < n; k++) b.append(TOKENS[rnd.nextInt(TOKENS.length)]);
                String in = b.toString();
                String q = quoted(in);
                Expect.equal(HtmlRewrite.unhideHiddenFields(in),
                        in.replaceAll("(?i)type\\s*=\\s*([\"']?)hidden\\1", "type=$1text$1"),
                        "unhideHiddenFields on " + q);
                Expect.equal(HtmlRewrite.enableDisabledFields(in),
                        in.replaceAll("(?i)\\s+disabled(\\s*=\\s*[\"']?[^\"'>\\s]*[\"']?)?", ""),
                        "enableDisabledFields on " + q);
                Expect.equal(HtmlRewrite.removeLengthLimits(in),
                        in.replaceAll("(?i)\\s+maxlength\\s*=\\s*[\"']?\\d+[\"']?", ""),
                        "removeLengthLimits on " + q);
                Expect.equal(HtmlRewrite.removeJsValidation(in),
                        in.replaceAll("(?i)\\s+onsubmit\\s*=\\s*\"[^\"]*\"", ""),
                        "removeJsValidation on " + q);
                Expect.equal(HtmlRewrite.removeObjectTags(in),
                        in.replaceAll("(?is)<object.*?</object>", "").replaceAll("(?i)<embed[^>]*>", ""),
                        "removeObjectTags on " + q);
                String js = in.replaceAll("(?is)<script.*?</script>", "")
                        .replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*\"[^\"]*\"", "");
                Expect.equal(HtmlRewrite.removeAllJavaScript(in), js, "removeAllJavaScript on " + q);
            }
        });
    }
}
