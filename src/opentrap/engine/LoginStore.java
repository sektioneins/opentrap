package opentrap.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application logins a scan can use: username/password credentials, and
 * recorded login sequences (a script of steps). Saved to
 * {@code ~/.opentrap/library/login/<label>.txt} so they can be reused across
 * scans and projects. Passwords are stored as typed, so the folder is 0700 and
 * every file in it 0600, both from the moment they are created.
 */
public final class LoginStore {
    private LoginStore() {}

    public static final class Credentials implements java.io.Serializable {
        public String label = "", username = "", password = "";
        public Credentials() {}
        public Credentials(String label, String username, String password) { this.label = label; this.username = username; this.password = password; }
    }

    public static final class RecordedLogin implements java.io.Serializable {
        public String label = "", script = "", statusUrl = "", confirmationText = "";
        public RecordedLogin() {}
    }

    private static Path dir() { return Path.of(System.getProperty("user.home", "."), ".opentrap", "library", "login"); }

    public static void save(Credentials c) throws IOException {
        Properties p = new Properties();
        p.setProperty("kind", "credentials");
        p.setProperty("label", c.label);
        p.setProperty("username", c.username);
        p.setProperty("password", c.password);
        write(c.label, p);
    }

    public static void save(RecordedLogin r) throws IOException {
        Properties p = new Properties();
        p.setProperty("kind", "recorded");
        p.setProperty("label", r.label);
        p.setProperty("script", r.script);
        p.setProperty("statusUrl", r.statusUrl);
        p.setProperty("confirmationText", r.confirmationText);
        write(r.label, p);
    }

    private static void write(String label, Properties p) throws IOException {
        opentrap.core.OwnerOnly.write(dir().resolve(safe(label) + ".txt"), out -> {
            var w = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
            p.store(w, "OpenTRAP application login");
            w.flush();
        });
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) return out;
        // A folder an older build made is narrowed the first time it is listed.
        try { opentrap.core.OwnerOnly.directory(dir()); } catch (IOException _) { }
        try (var ds = Files.newDirectoryStream(dir(), "*.txt")) {
            for (Path f : ds) out.add(f.getFileName().toString().replaceAll("\\.txt$", ""));
        } catch (IOException _) { }
        java.util.Collections.sort(out);
        return out;
    }

    /** Load by name: a Credentials or a RecordedLogin, or null. */
    public static Object load(String name) {
        Path f = dir().resolve(safe(name) + ".txt");
        if (!Files.isRegularFile(f)) return null;
        opentrap.core.OwnerOnly.restrictFile(f);
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException e) { return null; }
        if ("recorded".equals(p.getProperty("kind"))) {
            RecordedLogin rl = new RecordedLogin();
            rl.label = p.getProperty("label", name); rl.script = p.getProperty("script", "");
            rl.statusUrl = p.getProperty("statusUrl", ""); rl.confirmationText = p.getProperty("confirmationText", "");
            return rl;
        }
        return new Credentials(p.getProperty("label", name), p.getProperty("username", ""), p.getProperty("password", ""));
    }

    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9 _.-]", "_"); }
}
