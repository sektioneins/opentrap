package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The places that keep HTML on purpose (VULN-004): links are plain underlined
 * labels, and what the remaining HTML interpolates is escaped and themed.
 */
public final class HtmlSitesTest {

    public void run() {
        Harness.suite("HTML sites");

        Harness.test("a link label is plain underlined text in the theme's link colour", () -> {
            JLabel l = Ui.link("<html><b>x", () -> {});
            Expect.equal(l.getText(), "<html><b>x", "the text");
            Expect.isNull(l.getClientProperty("html"), "html view");
            Expect.equal(l.getFont().getAttributes().get(TextAttribute.UNDERLINE),
                    TextAttribute.UNDERLINE_ON, "underline");
            Expect.equal(l.getForeground(), Theme.LINK, "colour");
        });

        Harness.test("clicking a link label runs its action", () -> {
            AtomicBoolean ran = new AtomicBoolean();
            JLabel l = Ui.link("go", () -> ran.set(true));
            l.dispatchEvent(new MouseEvent(l, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false));
            Expect.isTrue(ran.get(), "the action ran");
        });

        Harness.test("Theme.css writes a colour as #RRGGBB", () ->
                Expect.equal(Theme.css(new Color(0x1A, 0x5F, 0xB4)), "#1A5FB4", "css"));

        Harness.test("a command palette row escapes the action name and hotkey", () -> {
            String h = CommandPalette.rowHtml("Go to <b>x</b> > <i>y", "<u>K", false);
            Expect.isTrue(h.contains("&lt;b&gt;x&lt;/b&gt;"), "name half before the separator");
            Expect.isTrue(h.contains("&lt;i&gt;y"), "name half after the separator");
            Expect.isTrue(h.contains("&lt;u&gt;K"), "hotkey");
            Expect.isFalse(h.contains("<b>x"), "no live bold tag");
            Expect.isFalse(h.contains("<u>K"), "no live underline tag");
            Expect.isTrue(h.contains(Theme.css(Theme.MUTED)), "muted colour");
        });
    }
}
