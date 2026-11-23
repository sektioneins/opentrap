package opentrap.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Named scan configurations saved on disk, one properties file each under
 * {@code ~/.opentrap/scan-configs}. The New scan wizard saves and loads them,
 * and Settings > Configuration library lists and removes them.
 */
public final class ScanConfigStore {

    private ScanConfigStore() {}

    private static final String EXT = ".properties";

    static Path dir() {
        return Path.of(System.getProperty("user.home"), ".opentrap", "scan-configs");
    }

    /** A file-safe form of the name, keeping it readable. */
    private static String fileName(String name) {
        return name.trim().replaceAll("[^A-Za-z0-9 ._-]", "_") + EXT;
    }

    /** The saved names, sorted, ignoring case. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) return out;
        try (Stream<Path> s = Files.list(dir())) {
            s.filter(p -> p.getFileName().toString().endsWith(EXT))
             .forEach(p -> {
                 String n = p.getFileName().toString();
                 out.add(n.substring(0, n.length() - EXT.length()));
             });
        } catch (IOException _) {}
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static boolean exists(String name) {
        return Files.exists(dir().resolve(fileName(name)));
    }

    public static void save(String name, ScanConfig c) throws IOException {
        Files.createDirectories(dir());
        Properties p = new Properties();
        p.setProperty("name", name.trim());
        p.setProperty("crawlDepth", String.valueOf(c.crawlDepth()));
        p.setProperty("crawlDurationMin", String.valueOf(c.crawlDurationMin()));
        p.setProperty("submitForms", String.valueOf(c.submitForms()));
        p.setProperty("ajaxSpider", String.valueOf(c.ajaxSpider()));
        p.setProperty("ajaxMaxDurationMin", String.valueOf(c.ajaxMaxDurationMin()));
        p.setProperty("ajaxMaxDepth", String.valueOf(c.ajaxMaxDepth()));
        p.setProperty("ajaxMaxStates", String.valueOf(c.ajaxMaxStates()));
        p.setProperty("attackStrength", c.attackStrength());
        p.setProperty("alertThreshold", c.alertThreshold());
        p.setProperty("injectableMask", String.valueOf(c.injectableMask()));
        p.setProperty("rpcMask", String.valueOf(c.rpcMask()));
        p.setProperty("delayMs", String.valueOf(c.delayMs()));
        p.setProperty("scanDurationMin", String.valueOf(c.scanDurationMin()));
        try (OutputStream out = Files.newOutputStream(dir().resolve(fileName(name)))) {
            p.store(out, "OpenTRAP scan configuration");
        }
    }

    public static ScanConfig load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir().resolve(fileName(name)))) {
            p.load(in);
        }
        ScanConfig d = ScanConfig.preset("Balanced");   // defaults for anything missing
        return new ScanConfig(
                p.getProperty("name", name),
                num(p, "crawlDepth", d.crawlDepth()),
                num(p, "crawlDurationMin", d.crawlDurationMin()),
                bool(p, "submitForms", d.submitForms()),
                bool(p, "ajaxSpider", d.ajaxSpider()),
                num(p, "ajaxMaxDurationMin", d.ajaxMaxDurationMin()),
                num(p, "ajaxMaxDepth", d.ajaxMaxDepth()),
                num(p, "ajaxMaxStates", d.ajaxMaxStates()),
                strength(p.getProperty("attackStrength"), d.attackStrength()),
                threshold(p.getProperty("alertThreshold"), d.alertThreshold()),
                num(p, "injectableMask", d.injectableMask()),
                num(p, "rpcMask", d.rpcMask()),
                num(p, "delayMs", d.delayMs()),
                num(p, "scanDurationMin", d.scanDurationMin()));
    }

    private static final Set<String> STRENGTHS = Set.of("DEFAULT", "LOW", "MEDIUM", "HIGH", "INSANE");
    private static final Set<String> THRESHOLDS = Set.of("OFF", "DEFAULT", "LOW", "MEDIUM", "HIGH");

    /** The engine's attack strength for {@code v}, or {@code def} when the engine would not accept it. */
    static String strength(String v, String def) { return oneOf(v, STRENGTHS, def); }

    /** The engine's alert threshold for {@code v}, or {@code def} when the engine would not accept it. */
    static String threshold(String v, String def) { return oneOf(v, THRESHOLDS, def); }

    private static String oneOf(String v, Set<String> allowed, String def) {
        if (v == null) return def;
        String s = v.strip().toUpperCase(Locale.ROOT);
        return allowed.contains(s) ? s : def;
    }

    public static boolean delete(String name) {
        try { return Files.deleteIfExists(dir().resolve(fileName(name))); }
        catch (IOException e) { return false; }
    }

    private static int num(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(def)).trim());
    }
}
