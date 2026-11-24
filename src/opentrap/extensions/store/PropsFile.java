package opentrap.extensions.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import opentrap.core.OwnerOnly;

/** Reads and writes the store's {@link Properties} files; a missing file is empty. */
final class PropsFile {

    private PropsFile() {}

    static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IllegalArgumentException e) {
                throw bad(file, "", e.getMessage());
            }
        }
        return p;
    }

    static void save(Path file, Properties p) throws IOException {
        OwnerOnly.write(file, out -> p.store(out, null));
    }

    static IOException bad(Path file, String key, String why) {
        return new IOException(file + (key.isEmpty() ? "" : ": " + key) + ": " + why);
    }

    /** A value the file must have. */
    static String req(Path file, Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null) throw bad(file, key, "missing");
        return v;
    }

    static int count(Path file, Properties p, String key) throws IOException {
        String v = p.getProperty(key, "0");
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw bad(file, key, "not a count: " + v);
        }
    }

    static void put(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }

    static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }
}
