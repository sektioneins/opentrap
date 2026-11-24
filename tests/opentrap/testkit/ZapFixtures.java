package opentrap.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builders for ZAP add-on feeds and {@code .zap} files in tests. */
public final class ZapFixtures {

    private ZapFixtures() {}

    /** A {@code <ZAP>} feed document around add-on blocks from {@link #addon}. */
    public static String feed(String... addonXmlBlocks) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ZAP>\n" + String.join("\n", addonXmlBlocks) + "\n</ZAP>\n";
    }

    /**
     * One feed entry: the {@code <addon>} line and its {@code <addon_id>} block. The
     * file is {@code <id>-<status>-<version>.zap}. {@code depIdRange} is pairs of
     * dependency id and range; an empty or null range means any version.
     */
    public static String addon(String id, String version, String status, String url, String sha256,
                               String... depIdRange) {
        StringBuilder deps = new StringBuilder();
        for (int i = 0; i + 1 < depIdRange.length; i += 2) {
            deps.append("<addon><id>").append(depIdRange[i]).append("</id>");
            String range = depIdRange[i + 1];
            if (range != null && !range.isEmpty()) deps.append("<version>").append(escape(range)).append("</version>");
            deps.append("</addon>");
        }
        return "<addon>" + id + "</addon>\n<addon_" + id + ">"
                + "<name>Add-on " + id + "</name>"
                + "<description>The " + id + " add-on.</description>"
                + "<author>ZAP Dev Team</author>"
                + "<version>" + version + "</version>"
                + "<file>" + id + "-" + status + "-" + version + ".zap</file>"
                + "<status>" + status + "</status>"
                + "<changes>&lt;h3&gt;Added&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;Access&lt;/li&gt;&lt;/ul&gt;</changes>"
                + "<url>" + url + "</url>"
                + "<hash>" + sha256 + "</hash>"
                + "<info>https://www.zaproxy.org/docs/desktop/addons/" + id + "/</info>"
                + "<date>2026-04-02</date>"
                + "<size>1201993</size>"
                + "<not-before-version>2.17.0</not-before-version>"
                + "<dependencies><addons>" + deps + "</addons></dependencies>"
                + "</addon_" + id + ">";
    }

    /** Writes {@code <dir>/<id>-<status>-<version>.zap} holding a {@code ZapAddOn.xml}; returns its path. */
    public static Path zap(Path dir, String id, String version, String status, String... depIdRange) {
        StringBuilder deps = new StringBuilder();
        for (int i = 0; i + 1 < depIdRange.length; i += 2) {
            deps.append("<addon>").append(depIdRange[i]).append("</addon>");
        }
        String xml = "<zapaddon><name>Add-on " + id + "</name><version>" + version + "</version>"
                + "<status>" + status + "</status>"
                + (deps.length() == 0 ? "" : "<dependencies><addons>" + deps + "</addons></dependencies>")
                + "</zapaddon>";
        Path file = dir.resolve(id + "-" + status + "-" + version + ".zap");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("ZapAddOn.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** Lower-case hex SHA-256 of a file. */
    public static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
