package opentrap.decoder;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Typing in the decoder's top pane re-runs the chain on every keystroke. Swing
 * moves the focus on from a component that is taken out of its container, so
 * the re-run must leave the stages that stay in the chain where they are, or
 * each typed character sends the focus to the next control.
 */
public final class DecoderFocusTest {

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

    public void run() {
        Harness.suite("decoder focus");

        Harness.test("typing in the top pane leaves it in the chain", () -> {
            DecoderTool tool = new DecoderTool();
            JComponent root = tool.tabs()[0].component();
            JTextArea top = all(root, JTextArea.class, new ArrayList<>()).get(0);
            Container stage = stageOf(top);
            Container column = stage.getParent();
            AtomicInteger removed = new AtomicInteger();
            column.addContainerListener(new ContainerAdapter() {
                @Override public void componentRemoved(ContainerEvent e) { removed.incrementAndGet(); }
            });

            onEdt(() -> top.replaceSelection("a"));
            settle();
            onEdt(() -> top.replaceSelection("b"));
            settle();

            Expect.equal(removed.get(), 0, "stages taken out of the column while typing");
            Expect.isTrue(stage.getParent() == column, "the top stage is still in the column");
        });

        Harness.test("typing above an applied transform updates the result without moving the top pane", () -> {
            DecoderTool tool = new DecoderTool();
            JComponent root = tool.tabs()[0].component();
            JTextArea top = all(root, JTextArea.class, new ArrayList<>()).get(0);
            Container stage = stageOf(top);
            Container column = stage.getParent();
            @SuppressWarnings("unchecked")
            JComboBox<String> encode = all(stage, JComboBox.class, new ArrayList<>()).get(1);

            onEdt(() -> top.replaceSelection("hi"));
            settle();
            onEdt(() -> encode.setSelectedItem(" base64"));
            settle();
            Expect.equal(column.getComponentCount(), 2, "stages after applying base64");
            Component result = column.getComponent(1);

            AtomicInteger removed = new AtomicInteger();
            column.addContainerListener(new ContainerAdapter() {
                @Override public void componentRemoved(ContainerEvent e) { removed.incrementAndGet(); }
            });
            onEdt(() -> top.replaceSelection("!"));
            settle();

            Expect.equal(removed.get(), 0, "stages taken out of the column while typing");
            Expect.isTrue(column.getComponent(0) == stage, "the top stage is still first");
            Expect.isTrue(column.getComponent(1) == result, "the result stage is kept");
            JTextArea out = all((Container) result, JTextArea.class, new ArrayList<>()).get(0);
            Expect.equal(out.getText(), "aGkh", "base64 of the edited input");
        });
    }
}
