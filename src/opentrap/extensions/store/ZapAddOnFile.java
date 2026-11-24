package opentrap.extensions.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

/**
 * A ZAP add-on file ({@code <id>-<status>-<version>.zap}). The file is
 * untrusted: id, status and version come from the name and must agree with
 * {@code ZapAddOn.xml} inside, so a file cannot pass for another add-on.
 */
public final class ZapAddOnFile {

    private ZapAddOnFile() {}

    /** What an add-on file says about itself. */
    public record AddOnInfo(String id, String name, Version version, Status status, String description,
                            String author, List<Dependency> dependencies) {}

    private static final Pattern FILE_NAME =
            Pattern.compile("([A-Za-z0-9_]+)-(release|beta|alpha)-([A-Za-z0-9._]+)\\.zap");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final String MANIFEST = "ZapAddOn.xml";
    private static final int MAX_MANIFEST = 1 << 20;

    public static AddOnInfo read(Path zap) throws IOException {
        return read(zap, zap.getFileName().toString());
    }

    /** Reads {@code zap} as if it were named {@code fileName}, for a download not yet renamed into place. */
    static AddOnInfo read(Path zap, String fileName) throws IOException {
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches()) throw new IOException(fileName + ": file name is not <id>-<status>-<version>.zap");
        try (ZipFile zip = new ZipFile(zap.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null) throw new IOException(fileName + ": no " + MANIFEST + " inside");
            byte[] xml;
            try (InputStream in = zip.getInputStream(entry)) {
                // a manifest is a few KB; the cap keeps a zip bomb from filling memory
                xml = in.readNBytes(MAX_MANIFEST + 1);
            }
            if (xml.length > MAX_MANIFEST) throw new IOException(fileName + ": " + MANIFEST + " is too large");
            Element root;
            try {
                root = ZapFeed.read(new ByteArrayInputStream(xml)).getDocumentElement();
            } catch (IOException e) {
                throw new IOException(fileName + ": " + MANIFEST + " is refused: " + e.getMessage(), e);
            }
            try {
                Version version = Version.parse(m.group(3));
                if (!Version.parse(ZapFeed.text(root, "version")).equals(version)) {
                    throw new IOException(fileName + ": version in " + MANIFEST + " differs from the file name");
                }
                Status status = Status.parse(m.group(2));
                if (Status.parse(ZapFeed.text(root, "status")) != status) {
                    throw new IOException(fileName + ": status in " + MANIFEST + " differs from the file name");
                }
                return new AddOnInfo(m.group(1), ZapFeed.text(root, "name"), version, status,
                        PlainText.of(ZapFeed.text(root, "description")), ZapFeed.text(root, "author"),
                        dependencies(root));
            } catch (IllegalArgumentException e) {
                throw new IOException(fileName + ": " + e.getMessage(), e);
            }
        } catch (ZipException e) {
            throw new IOException(fileName + ": not a readable zip file: " + e.getMessage(), e);
        }
    }

    /** One entry per readable {@code .zap} directly in the folder; a file {@link #read} refuses is named in rejected. */
    public static Listing scanFolder(Path folder, Source src) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".zap") && Files.isRegularFile(p))
                    .sorted().toList();
        }
        List<Entry> entries = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Path p : files) {
            try {
                AddOnInfo i = read(p);
                entries.add(new Entry(Kind.ZAP_ADDON, src.id(), i.id(), i.name(), i.description(), i.author(),
                        i.version(), i.status(), "", "", "", Files.size(p), p.getFileName().toString(),
                        p.toAbsolutePath().toString(), sha256(p), i.dependencies()));
            } catch (IOException e) {
                rejected.add(p.getFileName() + ": " + e.getMessage());
            }
        }
        return new Listing(List.copyOf(entries), List.copyOf(rejected));
    }

    /** Refuses a file that is not the add-on, version and status the entry promised. */
    public static void check(Path file, Entry expected) throws IOException {
        check(file, file.getFileName().toString(), expected);
    }

    /** {@link #check(Path, Entry)} for a file that is to be stored as {@code fileName}. */
    static void check(Path file, String fileName, Entry expected) throws IOException {
        AddOnInfo i = read(file, fileName);
        if (!i.id().equals(expected.id()) || !i.version().equals(expected.version()) || i.status() != expected.status()) {
            throw new IOException("The file is " + i.id() + " " + i.version() + " (" + i.status().label()
                    + "), not " + expected.id() + " " + expected.version() + " (" + expected.status().label() + ")");
        }
    }

    private static List<Dependency> dependencies(Element root) {
        List<Dependency> out = new ArrayList<>();
        for (Element deps : ZapFeed.children(root)) {
            if (!deps.getTagName().equals("dependencies")) continue;
            for (Element addons : ZapFeed.children(deps)) {
                if (!addons.getTagName().equals("addons")) continue;
                for (Element a : ZapFeed.children(addons)) {
                    if (!a.getTagName().equals("addon")) continue;
                    // ZAP writes <addon>id</addon>, or <addon><id>..</id><version>..</version></addon>
                    boolean nested = !ZapFeed.text(a, "id").isEmpty();
                    String id = nested ? ZapFeed.text(a, "id") : a.getTextContent().trim();
                    if (!ID.matcher(id).matches()) throw new IllegalArgumentException("dependency has no valid id");
                    out.add(new Dependency(id,
                            nested ? VersionRange.parse(ZapFeed.text(a, "version")) : VersionRange.ANY));
                }
            }
        }
        return List.copyOf(out);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
