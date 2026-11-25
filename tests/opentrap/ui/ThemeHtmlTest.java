package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.theme.HtmlGuard;

import javax.swing.*;
import java.awt.Color;

/**
 * The app's theme install must leave the HTML guard in place: the theme
 * registers its own button and tab UIs, which would otherwise replace it
 * (VULN-004). This suite installs the full theme, so it runs last.
 */
public final class ThemeHtmlTest {

    private static final String M = "<html><b>bold</b>";

    private static void withTheme(String name, Runnable body) {
        System.setProperty("opentrap.theme", name);
        try {
            Theme.install();
            body.run();
        } finally {
            System.clearProperty("opentrap.theme");
            Theme.configure(false);
        }
    }

    private static void checkGuarded() {
        for (String id : HtmlGuard.UI_IDS) {
            Expect.equal(String.valueOf(UIManager.get(id)), HtmlGuard.class.getName(), id);
        }
        Expect.isNull(new JLabel(M).getClientProperty("html"), "label html view");
        Expect.isNull(new JButton(M).getClientProperty("html"), "button html view");
        JTabbedPane p = new JTabbedPane();
        p.addTab(M, new JPanel());
        Expect.equal(p.getClientProperty("html.disable"), Boolean.TRUE, "tab html.disable");
    }

    public void run() {
        Harness.suite("theme and Swing HTML");

        Harness.test("the installed theme turns HTML off for every guarded component, in light and in dark", () -> {
            withTheme("light", ThemeHtmlTest::checkGuarded);
            withTheme("dark", ThemeHtmlTest::checkGuarded);
        });

        Harness.test("a link label follows the dark theme's link colour", () -> {
            Color[] light = new Color[1];
            withTheme("light", () -> light[0] = Theme.LINK);
            withTheme("dark", () -> {
                Color fg = Ui.link("x", () -> {}).getForeground();
                Expect.equal(fg, Theme.LINK, "dark link foreground");
                Expect.isFalse(fg.equals(light[0]), "differs from the light link colour");
            });
        });

        Harness.test("an HTML pane's page colours stay out of HTML labels and buttons", () -> withTheme("dark", () -> {
            javax.swing.text.html.StyleSheet own = Theme.htmlKit().getStyleSheet();
            Expect.equal(own.getBackground(own.getRule("body")), Theme.BG, "the pane's page background");
            // Swing's HTML labels and buttons copy the shared default sheet the
            // first time one is built, so a page background set there paints a
            // block behind every HTML label's text.
            javax.swing.text.html.StyleSheet shared = new javax.swing.text.html.HTMLEditorKit().getStyleSheet();
            Expect.isFalse(Theme.BG.equals(shared.getBackground(shared.getRule("body"))), "the shared sheet's page background");
        }));

        Harness.test("an HTML pane's links take the theme's link colour, not the accent", () -> withTheme("dark", () -> {
            javax.swing.text.html.StyleSheet own = Theme.htmlKit().getStyleSheet();
            Expect.equal(own.getForeground(own.getRule("a")), Theme.LINK, "the link colour");
        }));
    }
}
