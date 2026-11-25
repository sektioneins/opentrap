package opentrap.decoder;

import opentrap.core.Bytes;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * A decode that reaches the output limit shows what it produced and says it
 * was cut off, and the stages below keep working on that first part.
 */
public final class DecoderLimitTest {

    private static final int LIMIT = 1024;
    private static final String NOTE = "output truncated at 1024 bytes";

    private static void settle() {
        try { SwingUtilities.invokeAndWait(() -> {}); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void onEdt(Runnable r) {
        try { SwingUtilities.invokeAndWait(r); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static <T extends Component> List<T> all(Container c, Class<T> type, List<T> out) {
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Container k) all(k, type, out);
        }
        return out;
    }

    /** The stage panel holding this editor: area, viewport, scroll pane, stage. */
    private static Container stageOf(JTextArea area) {
        return SwingUtilities.getAncestorOfClass(JScrollPane.class, area).getParent();
    }

    /** Whether the stage shows the truncation note. */
    private static boolean hasNote(Container stage, String text) {
        for (JLabel l : all(stage, JLabel.class, new ArrayList<>())) {
            if (l.getText() != null && l.getText().startsWith("output truncated")) {
                Expect.equal(l.getText(), text, "the note");
                return true;
            }
        }
        return false;
    }

    private static String as(int n) {
        return "a".repeat(n);
    }

    /** A decoder with a 1 KB limit and a gzip of {@code n} bytes typed into the top pane. */
    private static JComponent typed(int n, boolean smart) {
        JComponent root = new DecoderTool(LIMIT).tabs()[0].component();
        JTextArea top = all(root, JTextArea.class, new ArrayList<>()).get(0);
        byte[] gz;
        try {
            gz = Transforms.encode("gzip", Bytes.bytes(as(n)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        onEdt(() -> top.replaceSelection(Bytes.str(gz)));
        settle();
        Container stage = stageOf(top);
        if (smart) {
            JButton button = null;
            for (JButton b : all(stage, JButton.class, new ArrayList<>())) {
                if ("Smart decode".equals(b.getText())) button = b;
            }
            JButton click = button;
            onEdt(click::doClick);
        } else {
            @SuppressWarnings("unchecked")
            JComboBox<String> decode = all(stage, JComboBox.class, new ArrayList<>()).get(0);
            onEdt(() -> decode.setSelectedItem(" gzip"));
        }
        settle();
        return root;
    }

    private static void expectTruncated(JComponent root) {
        List<JTextArea> areas = all(root, JTextArea.class, new ArrayList<>());
        Expect.equal(areas.size(), 2, "stages");
        Expect.equal(areas.get(1).getText(), as(LIMIT), "the decoded first part");
        Expect.isTrue(hasNote(stageOf(areas.get(0)), NOTE), "the first stage has the note");
        Expect.isFalse(hasNote(stageOf(areas.get(1)), NOTE), "the second stage has no note");
    }

    public void run() {
        Harness.suite("decoder limit");

        Harness.test("a decode over the output limit shows its first part and says it was truncated", () -> {
            expectTruncated(typed(4096, false));
        });

        Harness.test("smart decode on a gzip over the output limit decodes it as gzip and says it was truncated", () -> {
            expectTruncated(typed(4096, true));
        });

        Harness.test("a decode at the output limit shows no truncation note", () -> {
            JComponent root = typed(LIMIT, false);
            List<JTextArea> areas = all(root, JTextArea.class, new ArrayList<>());
            Expect.equal(areas.get(1).getText(), as(LIMIT), "the decoded text");
            for (JTextArea a : areas) {
                Expect.isFalse(hasNote(stageOf(a), NOTE), "a stage has the note");
            }
        });

        Harness.test("editing the top pane after a truncated decode keeps the note", () -> {
            JComponent root = typed(4096, false);
            JTextArea top = all(root, JTextArea.class, new ArrayList<>()).get(0);
            onEdt(() -> top.setText(top.getText()));
            settle();
            expectTruncated(root);
        });
    }
}
