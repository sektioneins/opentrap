package opentrap.discover;

import opentrap.core.ToolModule;
import opentrap.ui.AppIcon;
import opentrap.ui.Fonts;
import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;
import opentrap.ui.Upstream;

import javax.swing.*;
import java.awt.*;

/**
 * The Discover tool: a landing page with a hero band, resource links and a
 * gallery of features, then the getting-started guide.
 */
public class DiscoverTool implements ToolModule {

    @Override public String name() { return "discover"; }

    @Override public boolean alwaysTabbed() { return true; }

    @Override public Tab[] tabs() {
        return new Tab[]{
                new Tab("Getting started", Ui.scroll(landing())),
        };
    }

    /** Hero band, resource cards, "Discover more" gallery, then the four-step guide. */
    private JComponent landing() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(Theme.BG);

        // Hero band.
        JPanel hero = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x16, 0x18, 0x2E), getWidth(), getHeight(), new Color(0x0E, 0x10, 0x20)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xDB, 0x61, 0x2F, 90));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(getWidth() / 4, getHeight(), getWidth() / 2, 0);
                g2.dispose();
            }
        };
        hero.setPreferredSize(new Dimension(10, 260));
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        JPanel heroText = new JPanel();
        heroText.setLayout(new BoxLayout(heroText, BoxLayout.Y_AXIS));
        heroText.setOpaque(false);
        JLabel headline = opentrap.ui.theme.HtmlGuard.allowHtml(new JLabel("<html>" + opentrap.ui.Main.NAME + ", <span style='color:"
                + opentrap.ui.Theme.css(opentrap.ui.Theme.ACCENT) + "'>open.</span></html>"));
        headline.setFont(Fonts.ui(Font.BOLD, 36));
        headline.setForeground(Color.WHITE);
        JLabel blurb = opentrap.ui.theme.HtmlGuard.allowHtml(new JLabel("<html><div style='width:560px'>A free, open-source web security testing suite: proxy, crawler, scanner, "
                + "Intruder, Repeater and the rest, built on the OWASP ZAP engine and shaped by its community.</div></html>"));
        blurb.setFont(Fonts.ui(Font.PLAIN, 16));
        blurb.setForeground(new Color(0xE6, 0xE6, 0xEA));
        JLabel small = new JLabel("Free under the AGPL. No accounts, no licence keys.");
        small.setFont(Ui.PLAIN);
        small.setForeground(new Color(0xB0, 0xB4, 0xC0));
        JButton start = Ui.primaryButton("Get started", "jump to the four-step guide below");
        start.addActionListener(e -> {
            Container p = root.getParent();
            while (p != null && !(p instanceof JScrollPane)) p = p.getParent();
            if (p instanceof JScrollPane sp) sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
        });
        JButton learn = Ui.button("Learn more", "open the project page");
        learn.setIcon(Glyphs.help(14, Theme.ICON));
        learn.addActionListener(e -> Upstream.open(root));
        for (JComponent c : new JComponent[]{headline, blurb, small, Ui.row(start, learn)}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            heroText.add(c);
            heroText.add(Box.createVerticalStrut(12));
        }
        JPanel heroRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 0));
        heroRow.setOpaque(false);
        heroRow.add(new JLabel(AppIcon.icon(96)));
        heroRow.add(heroText);
        hero.add(heroRow, new GridBagConstraints());
        root.add(hero);

        // Resource cards.
        JPanel cards = new JPanel(new GridLayout(1, 4, 16, 0));
        cards.setOpaque(false);
        cards.setBorder(Ui.pad(16, 24, 16, 24));
        cards.add(resource("Documentation", "README and guides", Glyphs.book(28, Theme.ICON), Upstream.URL + "#readme"));
        cards.add(resource("Issues", "report a bug, ask for a feature", Glyphs.help(28, Theme.ICON), Upstream.URL + "/issues"));
        cards.add(resource("Releases", "download the latest build", Glyphs.export(28, Theme.ICON), Upstream.URL + "/releases"));
        cards.add(resource("Contributing", "how to send a pull request", Glyphs.github(28, Theme.ICON), Upstream.URL + "/blob/main/CONTRIBUTING.md"));
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        root.add(cards);

        // Discover more: feature tiles.
        JLabel more = new JLabel("Discover more");
        more.setFont(Fonts.ui(Font.BOLD, 22));
        more.setAlignmentX(Component.CENTER_ALIGNMENT);
        more.setBorder(Ui.pad(16, 0, 8, 0));
        root.add(more);
        JPanel gallery = new JPanel(new GridLayout(1, 4, 16, 0));
        gallery.setOpaque(false);
        gallery.setBorder(Ui.pad(8, 24, 24, 24));
        gallery.add(tile("Command palette", "Press Ctrl+K and type where you want to go: any tool, sub-tab or setting.", "proxy|\n> Go to Proxy\n> Go to Proxy > HTTP history\n> Go to Proxy > Intercept"));
        gallery.add(tile("Scripts", "Filters, custom actions and scan checks are small Java scripts in one library.", "boolean matches(HttpRequestResponse r,\n        Utilities u) {\n  return r.hasResponse();\n}"));
        gallery.add(tile("Intruder", "Payload positions, four attack types, resource pools and grep rules.", "POST /login HTTP/1.1\nHost: §target§\n\nuser=§admin§&pass=§x§"));
        gallery.add(tile("Inspector", "Every message editor carries an inspector of attributes, parameters and headers.", "Request attributes      2\nRequest query params    3\nRequest headers        19"));
        gallery.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        root.add(gallery);

        root.add(gettingStarted());
        return root;
    }

    private JComponent resource(String title, String sub, Icon icon, String url) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel ic = new JLabel(icon);
        ic.setOpaque(true);
        ic.setBackground(Theme.SURFACE);
        ic.setPreferredSize(new Dimension(56, 56));
        ic.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(ic, BorderLayout.WEST);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        JLabel t = Ui.bold(title);
        JLabel s = Ui.label(sub);
        s.setForeground(Theme.MUTED);
        text.add(t); text.add(s);
        p.add(text, BorderLayout.CENTER);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                try { Desktop.getDesktop().browse(java.net.URI.create(url)); } catch (Exception _) { }
            }
        });
        return p;
    }

    private JComponent tile(String title, String desc, String sample) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(Theme.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.GRID), Ui.pad(10, 12, 10, 12)));
        JTextArea code = new JTextArea(sample);
        code.setFont(Ui.MONO);
        code.setEditable(false);
        code.setBackground(Theme.SURFACE);
        code.setBorder(Ui.pad(8, 8, 8, 8));
        p.add(code, BorderLayout.CENTER);
        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        text.add(Ui.bold(title), BorderLayout.NORTH);
        JTextArea d = Ui.note(desc);
        Ui.sizeNote(d, 260);
        text.add(d, BorderLayout.CENTER);
        p.add(text, BorderLayout.SOUTH);
        return p;
    }

    private JComponent gettingStarted() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(Ui.pad(16, 24, 24, 24));
        root.setOpaque(false);

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);
        JLabel h = Ui.heading("Getting started");
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        JComponent intro = Ui.note("OpenTRAP is a free, open-source web security "
                + "testing suite. The usual workflow runs through these four steps.");
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        intro.setMaximumSize(new Dimension(900, intro.getPreferredSize().height));
        head.add(h);
        head.add(Box.createVerticalStrut(4));
        head.add(intro);
        root.add(head, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(2, 2, 12, 12));
        cards.setOpaque(false);
        cards.add(card("1. Set the target scope",
                "Choose which hosts and URLs are in scope on the Target > Scope tab, "
              + "so the tools only touch what you intend to test."));
        cards.add(card("2. Map the website",
                "Browse the target through the proxy, or run a crawl, to build the "
              + "site map on the Target tab and discover content and features."));
        cards.add(card("3. Analyse the attack surface",
                "Review the site map and proxy history to understand parameters, "
              + "endpoints and technologies worth testing."));
        cards.add(card("4. Test for vulnerabilities",
                "Run a scan from the Dashboard, and use Intruder, Repeater and the "
              + "other tools to probe findings by hand."));
        JPanel cardsWrap = new JPanel(new BorderLayout());
        cardsWrap.setOpaque(false);
        cardsWrap.add(cards, BorderLayout.NORTH);
        root.add(cardsWrap, BorderLayout.CENTER);
        root.setMaximumSize(new Dimension(Integer.MAX_VALUE, 360));
        return root;
    }

    private JComponent card(String title, String desc) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(12, 14, 12, 14)));
        p.setPreferredSize(new Dimension(420, 104));
        JLabel t = Ui.bold(title);
        JComponent d = Ui.note(desc);
        p.add(t, BorderLayout.NORTH);
        p.add(d, BorderLayout.CENTER);
        return p;
    }
}
