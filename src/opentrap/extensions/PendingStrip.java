package opentrap.extensions;

import opentrap.core.SuiteContext;
import opentrap.extensions.store.Store;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * The line above the Store and Installed lists while add-on changes are
 * queued: how many take effect when the engine restarts, and a Restart engine
 * button. Hidden while nothing is queued. It follows the store by itself, so
 * any tab can show one.
 */
public final class PendingStrip extends JPanel {

    private final Store store;
    private final JLabel text = Ui.label("");

    public PendingStrip(SuiteContext ctx, Store store) {
        super(new BorderLayout(8, 0));
        this.store = store;
        setOpaque(true);
        setBackground(Theme.CONTROL_TINT);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Ui.GRID), Ui.pad(4, 10, 4, 6)));
        JButton restart = Ui.button("Restart engine", "restart the scan engine now, so the queued changes take effect");
        restart.addActionListener(e -> EngineRestart.confirmAndRun(this, ctx));
        add(text, BorderLayout.CENTER);
        add(restart, BorderLayout.EAST);
        // a store listener may run on any thread; the strip changes on the EDT only
        store.addListener(() -> SwingUtilities.invokeLater(this::update));
        update();
    }

    /** What the strip says with {@code n} changes queued. */
    static String message(int n) {
        return n == 1 ? "1 change takes effect when the engine restarts"
                : n + " changes take effect when the engine restarts";
    }

    /** The strip's text, for tests. */
    String text() { return text.getText(); }

    private void update() {
        int n = store.pendingCount();
        text.setText(n == 0 ? "" : message(n));
        if (isVisible() != (n > 0)) {
            setVisible(n > 0);
            revalidate();
        }
    }
}
