package opentrap.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/** The original OpenTRAP project: its address, and opening it in a browser. */
public final class Upstream {

    private Upstream() {}

    /** The original project's repository. */
    public static final String URL = "https://github.com/opentrap/opentrap";

    /** Open the project page in a browser, or copy the URL if that is not possible. */
    public static void open(Component near) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(URL));
                return;
            }
        } catch (Exception _) {
            // fall through to the clipboard fallback
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(URL), null);
        JOptionPane.showMessageDialog(near,
                "Could not open a browser here.\nProject URL copied to the clipboard:\n" + URL,
                "Open project", JOptionPane.INFORMATION_MESSAGE);
    }
}
