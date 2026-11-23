package opentrap.ui;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;

/**
 * The about box: the logo, the build, what it runs on, that this is a fork,
 * and where both this fork and the original project live. Links open in the
 * desktop browser.
 */
public final class AboutDialog {

    private AboutDialog() {}

    /** This fork's repository; releases are published here. */
    private static final String FORK_REPO = "https://github.com/sektioneins/opentrap";
    private static final String SEKTIONEINS = "https://sektioneins.de";
    private static final String SITE = "https://opentrap.github.io/opentrap/";

    public static void show(Component parent) {
        JPanel panel = new JPanel(new BorderLayout(18, 0));
        panel.setOpaque(false);
        panel.setBorder(Ui.pad(6, 6, 6, 10));

        JLabel logo = new JLabel(AppIcon.icon(96));
        logo.setVerticalAlignment(SwingConstants.TOP);
        panel.add(logo, BorderLayout.WEST);

        JEditorPane text = new JEditorPane("text/html", html());
        text.setEditable(false);
        text.setOpaque(false);
        text.setFont(Ui.PLAIN);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        text.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) open(e.getURL().toString());
        });
        panel.add(text, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(parent, panel, "about " + Main.NAME, JOptionPane.PLAIN_MESSAGE);
    }

    private static String html() {
        String accent = String.format("#%06x", Theme.ACCENT.getRGB() & 0xFFFFFF);
        String muted = String.format("#%06x", Theme.MUTED.getRGB() & 0xFFFFFF);
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:").append(Fonts.css(Fonts.uiFamily()))
          .append(";font-size:12pt;width:380px'>");
        sb.append("<div style='font-size:16pt;font-weight:bold'>").append(Main.NAME).append("</div>");
        sb.append("<div style='color:").append(muted).append("'>Open Traffic Replay and Analysis Proxy</div>");
        sb.append("<p>An open-source web security testing suite built around an intercepting proxy.</p>");
        sb.append("<p style='margin-bottom:10px'>").append(Main.NAME).append(" is a fork maintained by <a href='").append(SEKTIONEINS).append("' style='color:")
          .append(accent).append("'>SektionEins</a>.</p>");
        sb.append("<table cellpadding='1' cellspacing='0'>");
        row(sb, "Build", Main.VERSION);
        row(sb, "Scan engine", engineVersion());
        row(sb, "Java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        row(sb, "System", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        row(sb, "Licence", "GNU AGPL v3.0");
        sb.append("</table>");
        sb.append("<p><b>").append(Main.NAME).append("</b><br>");
        link(sb, accent, FORK_REPO, "Source and issues on GitHub");
        link(sb, accent, FORK_REPO + "/releases", "Releases");
        sb.append("</p>");
        sb.append("<p><b>Original OpenTRAP</b><br>");
        link(sb, accent, Upstream.URL, "Source and issues on GitHub");
        link(sb, accent, SITE, "Project site");
        link(sb, accent, Upstream.URL + "/blob/main/CONTRIBUTING.md", "How to contribute");
        sb.append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><td style='padding-right:14px'><b>").append(k).append("</b></td><td>")
          .append(v == null ? "-" : v).append("</td></tr>");
    }

    private static void link(StringBuilder sb, String colour, String url, String label) {
        sb.append("<a href='").append(url).append("' style='color:").append(colour)
          .append("'>").append(label).append("</a><br>");
    }

    private static String engineVersion() {
        try { return opentrap.engine.ZapEngine.describe(); }
        catch (Exception e) { return "OWASP ZAP"; }
    }

    private static void open(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (Exception _) {}
        // Desktop.browse is frequently a no-op on Linux (especially Wayland), so
        // fall back to the system URL opener.
        for (String[] cmd : new String[][]{{"xdg-open", url}, {"gio", "open", url}, {"x-www-browser", url}}) {
            try { new ProcessBuilder(cmd).start(); return; } catch (Exception _) {}
        }
    }
}
