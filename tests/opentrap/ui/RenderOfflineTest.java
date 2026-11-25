package opentrap.ui;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.*;
import javax.swing.text.EditorKit;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.View;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A themed HTML pane shows a page without loading anything the page names
 * (VULN-017): no image, style sheet, frame or object, and a form does not
 * submit. Runs after HtmlGuardTest, which installs the guard as the app does.
 */
public final class RenderOfflineTest {

    public void run() {
        Harness.suite("HTML panes load nothing");

        Harness.test("an image, an object, a search field and an image button get placeholders", () -> {
            try (Loopback srv = new Loopback()) {
                String h = srv.base;
                JEditorPane p = render(Theme.htmlKit(), "<img alt=\"logo\" src=\"" + h + "/a.png\">"
                        + "<img src=\"" + h + "/b.png\"><img><input type=\"image\" src=\"" + h + "/c.png\">"
                        + "<object classid=\"javax.swing.JButton\"></object><isindex>");
                Expect.equal(placeholders(p), List.of("[image: logo]", "[image: " + h + "/b.png]", "[image]",
                        "[image button: " + h + "/c.png]", "[object]", "[search field]"), "placeholders");
                for (View v : views(p)) {
                    String name = v.getClass().getName();
                    for (String stock : List.of("ImageView", "FrameView", "ObjectView", "IsindexView")) {
                        Expect.isFalse(name.equals("javax.swing.text.html." + stock), "a view of class " + name);
                    }
                }
            }
        });

        Harness.test("no resource in a rendered page is fetched", () -> {
            try (Loopback srv = new Loopback()) {
                String h = srv.base;
                List<String> pages = List.of(
                        "<img src='" + h + "/1'>",
                        "<IMG SRC=" + h + "/2>",
                        "<base href=\"" + h + "/\"><img src=\"3\">",
                        "<link rel=\"stylesheet\" href=\"" + h + "/4.css\">",
                        "<style>@import url(" + h + "/5.css);</style>",
                        "<style>p { background-image: url(" + h + "/6) }</style><p>x</p>",
                        "<div style=\"background-image:url(" + h + "/7)\">x</div>",
                        "<body background=\"" + h + "/8\">x</body>",
                        "<table background=\"" + h + "/9\"><tr><td background=\"" + h + "/10\">x</td></tr></table>",
                        "<ul style=\"list-style-image:url(" + h + "/11)\"><li>x</li></ul>",
                        "<frameset rows=\"*\"><frame src=\"" + h + "/12\"></frameset>",
                        "<input type=\"image\" src=\"" + h + "/13\">",
                        "<object data=\"" + h + "/14\" classid=\"javax.swing.JButton\"></object>",
                        "<iframe src=\"" + h + "/15\"></iframe>",
                        "<div style=\"background:url(" + h + "/16)\">x</div>");
                for (String page : pages) render(Theme.htmlKit(), page);
                Expect.equal(srv.firstLineAfterMark(), "MARK", "first line the server saw");
            }
        });

        Harness.test("the stock HTML kit does fetch a linked stylesheet, so the fetch check sees a fetch", () -> {
            try (Loopback srv = new Loopback()) {
                render(new HTMLEditorKit(), "<link rel=\"stylesheet\" href=\"" + srv.base + "/control.css\">");
                String first = srv.firstLineAfterMark();
                Expect.isTrue(first != null && first.startsWith("GET /control.css"),
                        "a fetch of the style sheet, but the first line was <" + first + ">");
            }
        });

        Harness.test("pressing a form's submit button in a rendered page sends nothing", () -> {
            try (Loopback srv = new Loopback()) {
                JEditorPane p = render(Theme.htmlKit(), "<form action=\"" + srv.base + "/f\" method=\"get\">"
                        + "<input name=\"q\" value=\"1\"><input type=\"submit\" value=\"go\"></form>");
                onEdt(() -> {
                    JButton go = null;
                    for (JButton b : find(p, JButton.class)) if ("go".equals(b.getText())) go = b;
                    Expect.notNull(go, "the submit button");
                    go.doClick();
                    return null;
                });
                // The kit hands a submission to the event queue; let it run.
                onEdt(() -> null);
                onEdt(() -> null);
                Expect.equal(srv.firstLineAfterMark(), "MARK", "first line the server saw");
            }
            Expect.isFalse(Theme.htmlKit().isAutoFormSubmission(), "auto form submission");
        });

        Harness.test("background and list images are hidden from the painters", () -> {
            StyleSheet css = new StyleSheet();
            SimpleAttributeSet set = new SimpleAttributeSet();
            css.addCSSAttribute(set, CSS.Attribute.BACKGROUND_IMAGE, "url(http://127.0.0.1:1/b)");
            css.addCSSAttribute(set, CSS.Attribute.LIST_STYLE_IMAGE, "url(http://127.0.0.1:1/l)");
            css.addCSSAttribute(set, CSS.Attribute.BACKGROUND_COLOR, "red");
            Expect.notNull(set.getAttribute(CSS.Attribute.BACKGROUND_IMAGE), "the image in the set itself");
            javax.swing.text.AttributeSet seen = OfflineHtmlKit.withoutImages(set);
            for (CSS.Attribute image : List.of(CSS.Attribute.BACKGROUND_IMAGE, CSS.Attribute.LIST_STYLE_IMAGE)) {
                Expect.isNull(seen.getAttribute(image), image + " value");
                Expect.isFalse(seen.isDefined(image), image + " defined");
            }
            Expect.notNull(seen.getAttribute(CSS.Attribute.BACKGROUND_COLOR), "the background colour");
            Expect.equal(seen.getAttributeCount(), 1, "attribute count");
            Expect.equal(Collections.list(seen.getAttributeNames()), List.of(CSS.Attribute.BACKGROUND_COLOR), "names");
            SimpleAttributeSet child = new SimpleAttributeSet();
            child.setResolveParent(set);
            Expect.isNull(OfflineHtmlKit.withoutImages(child).getAttribute(CSS.Attribute.BACKGROUND_IMAGE),
                    "the parent's image through the child");
        });

        Harness.test("a frameset without rows or columns renders without an error", () -> {
            JEditorPane p = render(Theme.htmlKit(), "<frameset><frame src=\"" + NOWHERE + "/f\"></frameset>");
            Expect.equal(placeholders(p), List.of("[frame: " + NOWHERE + "/f]"), "placeholders");
        });

        Harness.test("a placeholder shows <html> alt text literally, cut to 80 characters", () -> {
            JEditorPane p = render(Theme.htmlKit(),
                    "<img alt=\"<html><b>x</b>\"><img alt=\"" + "a".repeat(200) + "\">");
            List<JLabel> labels = onEdt(() -> {
                List<JLabel> out = new ArrayList<>();
                for (View v : views(p)) {
                    if (v instanceof OfflineHtmlKit.Placeholder ph) out.add((JLabel) ph.getComponent());
                }
                return out;
            });
            Expect.equal(labels.size(), 2, "placeholder labels");
            Expect.equal(labels.get(0).getText(), "[image: <html><b>x</b>]", "first label");
            Expect.isNull(labels.get(0).getClientProperty("html"), "html view of the first label");
            Expect.equal(labels.get(1).getText(), "[image: " + "a".repeat(80) + "...]", "second label");
        });

        Harness.test("an HTML pane keeps the theme's page colours", () -> {
            HTMLDocument d = (HTMLDocument) Theme.htmlKit().createDefaultDocument();
            StyleSheet s = d.getStyleSheet();
            Expect.isTrue(s instanceof OfflineHtmlKit.OfflineStyleSheet, "an offline style sheet, was " + s.getClass());
            Expect.equal(s.getForeground(s.getRule("body")), Theme.TEXT, "text colour");
            Expect.equal(s.getBackground(s.getRule("body")), Theme.BG, "background");
        });

        Harness.test("a page with 2,000 images lays out in under a second", () -> {
            String page = "<img src=\"x\">".repeat(2000);
            long start = System.nanoTime();
            render(Theme.htmlKit(), page);
            long ms = (System.nanoTime() - start) / 1_000_000;
            Expect.isTrue(ms < 1000, "a layout under 1000 ms, took " + ms + " ms");
        });

        Harness.test("an image in a response shown in Render is not fetched", () -> {
            try (Loopback srv = new Loopback()) {
                MessageEditor ed = responseEditor("<img src='" + srv.base + "/a'>");
                JEditorPane p = showRender(ed);
                Expect.equal(placeholders(p), List.of("[image: " + srv.base + "/a]"), "placeholders");
                Expect.equal(srv.firstLineAfterMark(), "MARK", "first line the server saw");
            }
        });

        Harness.test("a response of 300 KB of unclosed <link, <script or src= renders within a second", () -> {
            for (String unit : List.of("<link", "<script", "src=\"http://")) {
                MessageEditor ed = responseEditor(unit.repeat(300 * 1024 / unit.length()));
                long start = System.nanoTime();
                showRender(ed);
                long ms = (System.nanoTime() - start) / 1_000_000;
                Expect.isTrue(ms < 1000, "a render of " + unit + " under 1000 ms, took " + ms + " ms");
            }
        });

        Harness.test("a script's text does not show in Render", () -> {
            MessageEditor ed = responseEditor("<p>a</p><script>var hiddenScriptText = 1;</script><p>b</p>");
            JEditorPane p = showRender(ed);
            String text = onEdt(() -> {
                try {
                    return p.getDocument().getText(0, p.getDocument().getLength());
                } catch (javax.swing.text.BadLocationException e) { throw new RuntimeException(e); }
            });
            Expect.isTrue(text.contains("a") && text.contains("b"), "the page text, was <" + text + ">");
            Expect.isFalse(text.contains("hiddenScriptText"), "script text in <" + text + ">");
        });
    }

    /** An editor holding a text/html response with the body; the Render tab is not selected yet. */
    static MessageEditor responseEditor(String body) {
        HttpMessage response = new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body));
        return onEdt(() -> {
            MessageEditor e = new MessageEditor(new SuiteContext(), MessageEditor.RESPONSE, false);
            e.setMessage(new HttpService("a.test", 80, false), response);
            return e;
        });
    }

    /** A base URL nothing listens on, for pages whose links are never followed. */
    private static final String NOWHERE = "http://127.0.0.1:1";

    /** Run on the event thread and return the result; an assertion that fails there fails the case. */
    static <T> T onEdt(Supplier<T> body) {
        if (SwingUtilities.isEventDispatchThread()) return body.get();
        AtomicReference<T> out = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> out.set(body.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new RuntimeException(e.getCause());
        }
        return out.get();
    }

    /** A pane with the kit and the page, laid out at 600 by 400 and painted once. */
    static JEditorPane render(EditorKit kit, String html) {
        return onEdt(() -> {
            JEditorPane p = new JEditorPane();
            p.setEditable(false);
            p.setEditorKit(kit);
            p.setText(html);
            p.setSize(600, 400);
            paint(p);
            return p;
        });
    }

    static void paint(JComponent c) {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try { c.paint(g); } finally { g.dispose(); }
    }

    /** Every view of the pane, the root first, in document order. */
    static List<View> views(JEditorPane p) {
        return onEdt(() -> {
            List<View> out = new ArrayList<>();
            walk(p.getUI().getRootView(p), out);
            return out;
        });
    }

    private static void walk(View v, List<View> out) {
        out.add(v);
        for (int i = 0; i < v.getViewCount(); i++) walk(v.getView(i), out);
    }

    /** The texts of the pane's placeholders, in view order. */
    static List<String> placeholders(JEditorPane p) {
        List<String> out = new ArrayList<>();
        for (View v : views(p)) if (v instanceof OfflineHtmlKit.Placeholder ph) out.add(ph.text());
        return out;
    }

    static <T> List<T> find(Component c, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(c, type, out);
        return out;
    }

    private static <T> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }

    /** Select the editor's Render tab and return its pane, laid out at 600 by 400 and painted. */
    static JEditorPane showRender(MessageEditor ed) {
        return onEdt(() -> {
            JTabbedPane tabs = null;
            for (JTabbedPane t : find(ed, JTabbedPane.class)) if (t.indexOfTab("Render") >= 0) tabs = t;
            Expect.notNull(tabs, "the tab pane with Render");
            tabs.setSelectedIndex(tabs.indexOfTab("Render"));
            JEditorPane pane = null;
            for (JEditorPane p : find(ed, JEditorPane.class)) if (p.getEditorKit() instanceof OfflineHtmlKit) pane = p;
            Expect.notNull(pane, "the Render pane");
            pane.setSize(600, 400);
            paint(pane);
            return pane;
        });
    }

    /**
     * A loopback server that records the first line of the first connection
     * only. Every connection is closed at once, so a fetch fails fast instead
     * of waiting for an answer.
     */
    static final class Loopback implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicReference<String> first = new AtomicReference<>();
        private final CountDownLatch recorded = new CountDownLatch(1);
        /** {@code http://127.0.0.1:PORT}. */
        final String base;

        Loopback() {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) { throw new RuntimeException(e); }
            base = "http://127.0.0.1:" + server.getLocalPort();
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        if (recorded.getCount() > 0) {
                            first.set(new BufferedReader(new InputStreamReader(
                                    s.getInputStream(), StandardCharsets.ISO_8859_1)).readLine());
                            recorded.countDown();
                        }
                    } catch (Exception ignored) { }
                }
            }, "render-offline-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /**
         * Connect and send {@code MARK}, then return the first line the server
         * saw, waiting up to 2 s for it. {@code MARK} means nothing reached the
         * server before this call.
         */
        String firstLineAfterMark() {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                OutputStream o = s.getOutputStream();
                o.write("MARK\n".getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                recorded.await(2, TimeUnit.SECONDS);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return first.get();
        }

        @Override public void close() {
            try { server.close(); } catch (IOException ignored) { }
        }
    }
}
