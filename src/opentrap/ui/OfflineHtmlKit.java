package opentrap.ui;

import javax.swing.JLabel;
import javax.swing.text.AttributeSet;
import javax.swing.text.ComponentView;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.BlockView;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Component;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * An HTML editor kit that shows a page without loading anything the page
 * names. The stock kit fetches images, style sheets and frames from the URLs
 * in the page, directly and not through the proxy, so a response shown in
 * Render would tell its server that it was looked at, and from where; it also
 * instantiates the class an {@code <object>} names (VULN-017). Every path the
 * stock kit loads through is closed here:
 *
 * <ul>
 * <li>{@code <img>}, {@code <input type=image>}, {@code <frame>},
 *     {@code <object>} and {@code <isindex>} get a {@link Placeholder}, a
 *     muted label that names the element, instead of the view that would load
 *     or run it. A {@code <frameset>} lays its frames out as a plain block.</li>
 * <li>{@code <link>} is hidden at the parser, so no tag reaches the stock
 *     style sheet loader (which also fails on an untitled alternate sheet),
 *     and {@code @import} reaches {@link OfflineStyleSheet#importStyleSheet},
 *     which does nothing.</li>
 * <li>CSS background images (also from the {@code background} attribute of
 *     {@code body}, {@code table} and {@code td}) and list images are hidden
 *     from the box and list painters, which would otherwise fetch them.</li>
 * <li>A form's submit button does not load its action: auto-submission is
 *     off, and no pane listens for the submit event that fires instead.</li>
 * </ul>
 *
 * The kit is for display only; editing a document with it is not supported.
 */
public final class OfflineHtmlKit extends HTMLEditorKit {

    /** Placeholder names longer than this are cut, and the cut marked. */
    private static final int MAX_NAME = 80;

    private static final ViewFactory FACTORY = new OfflineFactory();

    private final StyleSheet sheet;

    /** A kit whose pages take their default rules from {@code sheet}. */
    public OfflineHtmlKit(StyleSheet sheet) {
        this.sheet = sheet;
        setAutoFormSubmission(false);
    }

    @Override public StyleSheet getStyleSheet() { return sheet; }

    /** As the stock kit's, but the document's own sheet loads nothing. */
    @Override public Document createDefaultDocument() {
        StyleSheet ss = new OfflineStyleSheet();
        ss.addStyleSheet(getStyleSheet());
        HTMLDocument doc = new OfflineDocument(ss);
        doc.setParser(getParser());
        doc.setAsynchronousLoadPriority(4);
        doc.setTokenThreshold(100);
        return doc;
    }

    @Override public ViewFactory getViewFactory() { return FACTORY; }

    /**
     * The placeholder text for an element that would load or run something,
     * or null for any other element. An image is named by its alt text, else
     * by its source.
     */
    static String placeholder(Element e) {
        AttributeSet a = e.getAttributes();
        Object kind = a.getAttribute(StyleConstants.NameAttribute);
        if (kind == HTML.Tag.IMG) return labelled("image", imageName(a));
        if (kind == HTML.Tag.INPUT && "image".equalsIgnoreCase(string(a, HTML.Attribute.TYPE))) {
            return labelled("image button", imageName(a));
        }
        if (kind == HTML.Tag.FRAME) return labelled("frame", cut(string(a, HTML.Attribute.SRC)));
        if (kind == HTML.Tag.OBJECT) return "[object]";
        if (kind == HTML.Tag.ISINDEX) return "[search field]";
        return null;
    }

    private static String labelled(String what, String name) {
        return name == null ? "[" + what + "]" : "[" + what + ": " + name + "]";
    }

    private static String imageName(AttributeSet a) {
        String alt = cut(string(a, HTML.Attribute.ALT));
        return alt != null ? alt : cut(string(a, HTML.Attribute.SRC));
    }

    private static String string(AttributeSet a, HTML.Attribute key) {
        return a.getAttribute(key) instanceof String s ? s : null;
    }

    /** Trimmed and cut to {@link #MAX_NAME} characters, or null when blank. */
    private static String cut(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) + "..." : s;
    }

    /**
     * A read-only view of {@code a} without the CSS background and list
     * images. It is a view, not a copy, so values still resolve through the
     * parents and the style sheet as they do in {@code a}.
     */
    static AttributeSet withoutImages(AttributeSet a) {
        if (a == null || a instanceof WithoutImages) return a;
        return new WithoutImages(a);
    }

    private static final class WithoutImages implements AttributeSet {
        private final AttributeSet a;

        WithoutImages(AttributeSet a) { this.a = a; }

        private static boolean hidden(Object key) {
            return key == CSS.Attribute.BACKGROUND_IMAGE || key == CSS.Attribute.LIST_STYLE_IMAGE;
        }

        private List<Object> names() {
            List<Object> out = new ArrayList<>();
            for (Enumeration<?> e = a.getAttributeNames(); e.hasMoreElements(); ) {
                Object n = e.nextElement();
                if (!hidden(n)) out.add(n);
            }
            return out;
        }

        @Override public int getAttributeCount() { return names().size(); }

        @Override public boolean isDefined(Object name) { return !hidden(name) && a.isDefined(name); }

        @Override public boolean isEqual(AttributeSet attr) {
            return getAttributeCount() == attr.getAttributeCount() && containsAttributes(attr);
        }

        @Override public AttributeSet copyAttributes() {
            SimpleAttributeSet copy = new SimpleAttributeSet();
            for (Object n : names()) copy.addAttribute(n, getAttribute(n));
            return copy;
        }

        @Override public Object getAttribute(Object key) {
            if (hidden(key)) return null;
            if (key == StyleConstants.ResolveAttribute) return getResolveParent();
            return a.getAttribute(key);
        }

        @Override public Enumeration<?> getAttributeNames() { return Collections.enumeration(names()); }

        @Override public boolean containsAttribute(Object name, Object value) {
            return !hidden(name) && a.containsAttribute(name, value);
        }

        @Override public boolean containsAttributes(AttributeSet attrs) {
            for (Enumeration<?> e = attrs.getAttributeNames(); e.hasMoreElements(); ) {
                Object n = e.nextElement();
                if (!containsAttribute(n, attrs.getAttribute(n))) return false;
            }
            return true;
        }

        @Override public AttributeSet getResolveParent() { return withoutImages(a.getResolveParent()); }
    }

    /** A document whose parser callback hides {@code <link>} instead of loading the sheet it names. */
    static final class OfflineDocument extends HTMLDocument {
        OfflineDocument(StyleSheet ss) { super(ss); }

        @Override public HTMLEditorKit.ParserCallback getReader(int pos) {
            baseFromStream();
            return new OfflineReader(pos, 0, 0, null);
        }

        @Override public HTMLEditorKit.ParserCallback getReader(int pos, int popDepth, int pushDepth,
                                                                HTML.Tag insertTag) {
            baseFromStream();
            return new OfflineReader(pos, popDepth, pushDepth, insertTag);
        }

        /** As the stock readers' factories do: relative URLs resolve against the page's own URL. */
        private void baseFromStream() {
            if (getProperty(Document.StreamDescriptionProperty) instanceof URL u) setBase(u);
        }

        private final class OfflineReader extends HTMLReader {
            OfflineReader(int pos, int popDepth, int pushDepth, HTML.Tag insertTag) {
                super(pos, popDepth, pushDepth, insertTag);
                registerTag(HTML.Tag.LINK, new HiddenAction());
            }
        }
    }

    /**
     * A document style sheet that loads nothing: imports are dropped, and the
     * painters never see an image to fetch.
     */
    static final class OfflineStyleSheet extends StyleSheet {
        @Override public void importStyleSheet(URL url) { }

        @Override public BoxPainter getBoxPainter(AttributeSet a) { return super.getBoxPainter(withoutImages(a)); }

        @Override public ListPainter getListPainter(AttributeSet a) { return super.getListPainter(withoutImages(a)); }
    }

    /** The stock views, except for the elements that would load or run something. */
    private static final class OfflineFactory extends HTMLFactory {
        @Override public View create(Element elem) {
            // The stock frameset view throws without rows or cols, and splits the
            // pane into frames; its frames are placeholders, so a block will do.
            if (elem.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.FRAMESET) {
                return new BlockView(elem, View.Y_AXIS);
            }
            String text = placeholder(elem);
            return text != null ? new Placeholder(elem, text) : super.create(elem);
        }
    }

    /** A muted label in place of an element that would load or run something. */
    static final class Placeholder extends ComponentView {
        private final String text;

        Placeholder(Element e, String text) {
            super(e);
            this.text = text;
        }

        String text() { return text; }

        @Override protected Component createComponent() {
            // The text comes from the page: it must never be read as HTML.
            JLabel l = new JLabel();
            l.putClientProperty("html.disable", Boolean.TRUE);
            l.setText(text);
            l.setFont(Ui.PLAIN);
            l.setForeground(Theme.MUTED);
            return l;
        }
    }
}
