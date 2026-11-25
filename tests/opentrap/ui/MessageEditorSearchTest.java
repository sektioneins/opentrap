package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import java.util.List;

/**
 * The message editor's search must highlight exactly the text it found. The
 * Pretty view is a text pane, whose getText() gives back the message's CRLF
 * line ends while its document holds LF only, so offsets taken from getText()
 * landed one character further right for every line above the match.
 */
public final class MessageEditorSearchTest {

    private static String at(JTextPane p, int off, int len) {
        try { return p.getDocument().getText(off, len); }
        catch (BadLocationException e) { return "bad location " + off; }
    }

    public void run() {
        Harness.suite("message editor search");

        Harness.test("a match below CRLF lines is found at its place in the document", () -> {
            JTextPane p = new JTextPane();
            p.setText("GET / HTTP/1.1\r\nHost: x\r\nCookie: needle=1\r\n\r\n");
            List<Integer> hits = MessageEditor.matchOffsets(p, "needle");
            Expect.equal(hits.size(), 1, "matches");
            Expect.equal(at(p, hits.get(0), 6), "needle", "the highlighted text");
        });

        Harness.test("the search ignores case without moving matches after a letter whose lower case is longer", () -> {
            JTextPane p = new JTextPane();
            // U+0130 lower-cases to two chars, which shifted every later match.
            p.setText("İx NEEDLE needle");
            List<Integer> hits = MessageEditor.matchOffsets(p, "Needle");
            Expect.equal(hits.size(), 2, "matches");
            Expect.equal(at(p, hits.get(0), 6), "NEEDLE", "first match");
            Expect.equal(at(p, hits.get(1), 6), "needle", "second match");
        });

        Harness.test("an empty term finds nothing", () ->
            Expect.equal(MessageEditor.matchOffsets(new JTextPane(), "").size(), 0, "matches"));
    }
}
