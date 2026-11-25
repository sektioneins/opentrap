package opentrap.extensions.store;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/** Feed text reduced to plain text: stays linear on hostile input and keeps the usual structure. */
public final class PlainTextTest {

    public void run() {
        Harness.suite("feed plain text");

        Harness.test("text with many unclosed tags is reduced in linear time", () -> {
            for (String unit : new String[] {"<", "<p", "<li"}) {
                String s = unit.repeat(20_000);
                long t0 = System.nanoTime();
                String out = PlainText.of(s);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Expect.equal(out, s, "no tag matches, nothing is stripped for unit " + unit);
                Expect.isTrue(ms < 250, "unit '" + unit + "' took " + ms + " ms");
            }
        });

        Harness.test("a stray angle bracket keeps the text up to the next tag", () -> {
            Expect.equal(PlainText.of("a < b <i>c</i>"), "a < b c", "stray bracket");
            // Accepted cost: a '<' inside an attribute value ends the tag early, so its tail shows.
            Expect.equal(PlainText.of("<a title=\"1<2\">link</a>"), "<a title=\"1link", "bracket in attribute");
        });

        Harness.test("lists, paragraphs, tags and entities reduce as before", () -> {
            Expect.equal(PlainText.of("<h3>Added</h3><ul><li>Access</li><li class=\"x\">Two &amp; three</li></ul>"
                    + "<p>Done&nbsp;now &#x41;</p>"), "Added\n- Access\n- Two & three\n\nDone now A", "lists");
            Expect.equal(PlainText.of("one<br/>two<BR>three<div class='a'>four</div>"),
                    "one\ntwo\nthree\nfour", "line breaks");
            Expect.equal(PlainText.of("Use &lt;b&gt; for bold."), "Use <b> for bold.", "escaped tag stays");
        });
    }
}
