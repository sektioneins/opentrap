package opentrap.dashboard;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/** The phase badges in the dashboard's task table (VULN-004). */
public final class PhaseHtmlTest {

    public void run() {
        Harness.suite("phase badges");

        Harness.test("a phase badge escapes its value", () -> {
            String h = DashboardTool.phaseHtml("1 <b>2</b>");
            Expect.isTrue(h.contains("&lt;b&gt;"), "escaped");
            Expect.isFalse(h.contains("<b>"), "no live tag");
        });

        Harness.test("the phase renderer still renders its badges as HTML with the guard installed", () -> {
            opentrap.ui.theme.HtmlGuard.install();
            javax.swing.JTable t = new javax.swing.JTable(1, 1);
            java.awt.Component c = new DashboardTool.PhaseRenderer()
                    .getTableCellRendererComponent(t, "1 2 ", false, false, 0, 0);
            Expect.notNull(((javax.swing.JComponent) c).getClientProperty("html"), "html view");
        });
    }
}
