package opentrap.ui;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.LogEntry;
import opentrap.core.SuiteContext;
import opentrap.proxy.HistoryModel;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.theme.HtmlGuard;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Text that starts with {@code <html>} must reach the screen literally, and
 * its image tags must never be fetched (VULN-004). The guard stays installed
 * for the rest of the run, as it is in the app.
 */
public final class HtmlGuardTest {

    private static final String M = "<html><b>bold</b>";

    private static String plain(JComponent c) {
        Expect.isNull(c.getClientProperty("html"), c.getClass().getSimpleName() + " html view");
        return c instanceof JLabel l ? l.getText() : null;
    }

    private static JComponent cell(JTable t, int row, int col) {
        return (JComponent) t.prepareRenderer(t.getCellRenderer(row, col), row, col);
    }

    private static void collect(Component c, List<JTable> tables, List<JLabel> labels) {
        if (c instanceof JTable t) tables.add(t);
        if (c instanceof JLabel l) labels.add(l);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, tables, labels);
    }

    public void run() {
        Harness.suite("Swing HTML guard");
        HtmlGuard.install();

        Harness.test("a label shows text that starts with <html> literally", () -> {
            JLabel l = new JLabel(M);
            plain(l);
            Expect.equal(l.getText(), M, "label text");
        });

        Harness.test("a table's default renderer shows an <html> cell literally", () -> {
            JTable t = new JTable(new Object[][]{{M}}, new Object[]{"a"});
            JComponent c = cell(t, 0, 0);
            Expect.isTrue(c instanceof JLabel, "a label renderer");
            Expect.equal(plain(c), M, "cell text");
        });

        Harness.test("a table styled by Ui shows an <html> cell literally", () -> {
            JTable t = new JTable(new Object[][]{{M}}, new Object[]{M});
            Ui.styleTable(t);
            Expect.equal(plain(cell(t, 0, 0)), M, "cell text");
            Component h = t.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(t, M, false, false, -1, 0);
            plain((JComponent) h);
        });

        Harness.test("list, tree and combo box cells show <html> text literally", () -> {
            JList<String> list = new JList<>(new String[]{M});
            plain((JComponent) list.getCellRenderer()
                    .getListCellRendererComponent(list, M, 0, false, false));
            JTree tree = new JTree(new javax.swing.tree.DefaultMutableTreeNode(M));
            plain((JComponent) tree.getCellRenderer()
                    .getTreeCellRendererComponent(tree, M, false, false, true, 0, false));
            JComboBox<String> combo = new JComboBox<>(new String[]{M});
            plain((JComponent) combo.getRenderer()
                    .getListCellRendererComponent(new JList<>(new String[]{M}), M, 0, false, false));
        });

        Harness.test("a tooltip shows <html> text literally", () -> {
            JLabel l = new JLabel("x");
            l.setToolTipText(M);
            JToolTip tip = l.createToolTip();
            tip.setTipText(M);
            plain(tip);
        });

        Harness.test("a tab title that starts with <html> is not rendered as HTML", () -> {
            JTabbedPane p = new JTabbedPane();
            p.addTab(M, new JPanel());
            Expect.equal(p.getClientProperty("html.disable"), Boolean.TRUE, "html.disable");
        });

        Harness.test("buttons and menu items show <html> text literally", () -> {
            JComponent[] all = {new JButton(M), new JToggleButton(M), new JCheckBox(M),
                new JRadioButton(M), new JMenu(M), new JMenuItem(M),
                new JCheckBoxMenuItem(M), new JRadioButtonMenuItem(M)};
            for (JComponent c : all) plain(c);
        });

        Harness.test("an option pane message that starts with <html> shows literally", () -> {
            JOptionPane pane = new JOptionPane(M);
            List<JTable> tables = new ArrayList<>();
            List<JLabel> labels = new ArrayList<>();
            collect(pane, tables, labels);
            JLabel found = null;
            for (JLabel l : labels) if (M.equals(l.getText())) found = l;
            Expect.notNull(found, "the message label");
            plain(found);
        });

        Harness.test("the tab pane inside a message editor does not render a tab title as HTML", () -> {
            MessageEditor ed = new MessageEditor(new SuiteContext(), MessageEditor.RESPONSE, false);
            List<javax.swing.JTabbedPane> panes = new ArrayList<>();
            findTabs(ed, panes);
            Expect.isTrue(!panes.isEmpty(), "found a tab pane");
            for (javax.swing.JTabbedPane p : panes) {
                Expect.equal(p.getClientProperty("html.disable"), Boolean.TRUE, "html.disable");
            }
        });

        Harness.test("a component that allows HTML still renders it", () -> {
            Expect.notNull(HtmlGuard.allowHtml(new JLabel(M)).getClientProperty("html"), "label view");
            Expect.notNull(HtmlGuard.allowHtml(new JToggleButton(M)).getClientProperty("html"), "button view");
        });

        Harness.test("a proxied page's <html> title shows literally in the history table", () -> {
            LogEntry e = new LogEntry("proxy", new HttpService("a.test", 80, false),
                    new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: a.test\r\n\r\n")));
            e.setResponse(new HttpMessage(Bytes.bytes(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<title><html><b>bold</b></title>")));
            HistoryModel model = new HistoryModel(null);
            model.add(e);
            JTable t = new JTable(model);
            Ui.styleTable(t);
            Expect.equal(plain(cell(t, 0, 10)), M, "title cell");
        });

        Harness.test("an <html> image in a table cell is never fetched", () -> {
            try { imageIsNotFetched(); } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a response header that starts with <html> shows literally in the Inspector", () -> {
            try { inspectorShowsHeaderLiterally(); } catch (Exception e) { throw new RuntimeException(e); }
        });

        // Last: with install() missing from run() this case would install the guard for the cases after it.
        Harness.test("installing the guard twice does not wrap it in itself", () -> {
            HtmlGuard.install();
            plain(new JLabel(M));
            Expect.equal(UIManager.get("LabelUI"), HtmlGuard.class.getName(), "LabelUI");
        });
    }

    private static void imageIsNotFetched() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicReference<String> first = new AtomicReference<>();
            Thread acceptor = new Thread(() -> {
                // Only the first connection is recorded; every one is closed at once so a
                // retrying fetch fails fast instead of waiting for an answer.
                boolean recorded = false;
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        if (!recorded) {
                            recorded = true;
                            first.set(new BufferedReader(new InputStreamReader(
                                    s.getInputStream(), StandardCharsets.ISO_8859_1)).readLine());
                        }
                    } catch (Exception ignored) { }
                }
            }, "html-guard-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            int port = server.getLocalPort();
            JTable t = new JTable(new Object[][]{{"<html><img src='http://127.0.0.1:" + port + "/x'>"}},
                    new Object[]{"a"});
            Ui.styleTable(t);
            cell(t, 0, 0).getPreferredSize();
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                OutputStream o = s.getOutputStream();
                o.write("MARK\n".getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (first.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
            }
            Expect.equal(first.get(), "MARK", "first line the server saw");
        }
    }

    /** Press the strip button that opens the Inspector, so its tables are built into the tree. */
    private static void openInspector(Component c) {
        if (c instanceof VerticalTab tab && "Inspector".equals(tab.getText())) { tab.doClick(); return; }
        if (c instanceof Container k) for (Component x : k.getComponents()) openInspector(x);
    }

    private static void findTabs(java.awt.Component c, List<javax.swing.JTabbedPane> out) {
        if (c instanceof javax.swing.JTabbedPane p) out.add(p);
        if (c instanceof java.awt.Container k) for (java.awt.Component ch : k.getComponents()) findTabs(ch, out);
    }

    private static void inspectorShowsHeaderLiterally() throws Exception {
        HttpMessage response = new HttpMessage(Bytes.bytes(
                "HTTP/1.1 200 OK\r\nX-T: <html><i>x</i>\r\nContent-Length: 0\r\n\r\n"));
        MessageEditor[] ed = new MessageEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            ed[0] = new MessageEditor(new SuiteContext(), MessageEditor.RESPONSE, false);
            ed[0].setMessage(new HttpService("a.test", 80, false), response);
        });
        SwingUtilities.invokeAndWait(() -> {
            openInspector(ed[0]);
        });
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {
            List<JTable> tables = new ArrayList<>();
            collect(ed[0], tables, new ArrayList<>());
            boolean literal = false;
            for (JTable t : tables) {
                for (int r = 0; r < t.getRowCount(); r++) {
                    for (int c = 0; c < t.getColumnCount(); c++) {
                        JComponent comp = cell(t, r, c);
                        Expect.isNull(comp.getClientProperty("html"), "html view in an Inspector cell");
                        if (comp instanceof JLabel l && "<html><i>x</i>".equals(l.getText())) literal = true;
                    }
                }
            }
            Expect.isTrue(literal, "a cell with the literal header value");
        });
    }
}
