package opentrap.extensions.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Reads ZAP's add-on feed ({@code ZapVersions-<minor>.xml}). The feed is
 * untrusted input: the parser refuses a DOCTYPE, and every entry is checked
 * on its own, so one bad entry is named in {@link Listing#rejected()} and the
 * rest still list.
 */
public final class ZapFeed {

    private ZapFeed() {}

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern HASH = Pattern.compile("SHA-256:([0-9A-Fa-f]{64})");

    public static Listing parse(InputStream in, Source src, Version engine) throws IOException {
        Document doc = read(in);
        List<Entry> entries = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Element root = doc.getDocumentElement();
        for (Element e : children(root)) {
            if (!e.getTagName().equals("addon")) continue;
            String id = e.getTextContent().trim();
            try {
                if (!ID.matcher(id).matches()) throw new IllegalArgumentException("not a valid add-on id");
                Element block = null;
                for (Element c : children(root)) if (c.getTagName().equals("addon_" + id)) { block = c; break; }
                if (block == null) throw new IllegalArgumentException("no details in the feed");
                entries.add(entry(block, id, src, engine));
            } catch (IllegalArgumentException ex) {
                rejected.add(id + ": " + ex.getMessage());
            }
        }
        return new Listing(List.copyOf(entries), List.copyOf(rejected));
    }

    private static Entry entry(Element b, String id, Source src, Version engine) {
        Status status = Status.parse(text(b, "status"));
        Version version = Version.parse(text(b, "version"));
        String file = text(b, "file");
        if (!Pattern.matches(Pattern.quote(id) + "-(release|beta|alpha)-[A-Za-z0-9._]+\\.zap", file)) {
            throw new IllegalArgumentException("file name does not match the add-on id");
        }
        String url = text(b, "url");
        if (src.prefix().isEmpty() || !url.startsWith("https://") || !url.startsWith(src.prefix())) {
            throw new IllegalArgumentException("download is outside " + src.prefix());
        }
        var hash = HASH.matcher(text(b, "hash"));
        if (!hash.matches()) throw new IllegalArgumentException("hash is not SHA-256");
        String notBefore = text(b, "not-before-version");
        if (!notBefore.isEmpty() && Version.parse(notBefore).compareTo(engine) > 0) {
            throw new IllegalArgumentException("needs ZAP " + notBefore + " or later");
        }
        String notFrom = text(b, "not-from-version");
        if (!notFrom.isEmpty() && Version.parse(notFrom).compareTo(engine) <= 0) {
            throw new IllegalArgumentException("not for ZAP " + notFrom + " or later");
        }
        long size = 0;
        String sizeText = text(b, "size");
        if (!sizeText.isEmpty()) {
            try { size = Long.parseLong(sizeText); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("size is not a number"); }
            if (size < 0) throw new IllegalArgumentException("size is negative");
        }
        return new Entry(Kind.ZAP_ADDON, src.id(), id, text(b, "name"), PlainText.of(text(b, "description")),
                text(b, "author"), version, status, text(b, "date"), PlainText.of(text(b, "changes")),
                text(b, "info"), size, file, url, hash.group(1).toLowerCase(java.util.Locale.ROOT),
                dependencies(b));
    }

    private static List<Dependency> dependencies(Element b) {
        List<Dependency> out = new ArrayList<>();
        for (Element deps : children(b)) {
            if (!deps.getTagName().equals("dependencies")) continue;
            for (Element addons : children(deps)) {
                if (!addons.getTagName().equals("addons")) continue;
                for (Element a : children(addons)) {
                    if (!a.getTagName().equals("addon")) continue;
                    String depId = text(a, "id");
                    if (!ID.matcher(depId).matches()) throw new IllegalArgumentException("dependency has no valid id");
                    out.add(new Dependency(depId, VersionRange.parse(text(a, "version"))));
                }
            }
        }
        return List.copyOf(out);
    }

    private static Document read(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder db = f.newDocumentBuilder();
            db.setErrorHandler(null);
            return db.parse(in);
        } catch (ParserConfigurationException e) {
            throw new IOException("XML parser cannot be configured safely: " + e.getMessage(), e);
        } catch (SAXException e) {
            throw new IOException("The feed is not valid XML: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    /** Text of the first child element with this name, trimmed; empty when there is none. */
    private static String text(Element parent, String name) {
        for (Element e : children(parent)) if (e.getTagName().equals(name)) return e.getTextContent().trim();
        return "";
    }
}
