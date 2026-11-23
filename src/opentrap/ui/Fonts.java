package opentrap.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.prefs.Preferences;

/**
 * Resolves the UI font and the monospace font from the saved settings and the
 * system. The UI font defaults to the system font of the desktop, the monospace
 * font to the system monospace font; a saved family that is not installed falls
 * back to the default. The result is resolved once per JVM, from the preferences
 * as they were at start-up, so a changed font setting takes effect on restart.
 */
public final class Fonts {
    private Fonts() {}

    public static final int DEFAULT_SIZE = 12;
    private static final int MIN_SIZE = 6, MAX_SIZE = 40;

    public static final String KEY_UI_FAMILY = "ui.font";
    public static final String KEY_UI_SIZE = "ui.fontSize";
    public static final String KEY_MONO = "editor.font";

    /** What the resolver needs to know about the machine; {@code os} is mac, windows or linux. */
    record Env(String os, String desktopUiFamily, String systemMonoFamily, Predicate<String> installed) {}

    record Choice(String family, int size) {}

    static Choice resolveUi(Env env, String savedFamily, String savedSize) {
        String family = null;
        if (savedFamily != null && env.installed().test(savedFamily)) family = savedFamily;
        if (family == null) {
            String system = switch (env.os()) {
                case "mac" -> ".AppleSystemUIFont";
                default -> env.desktopUiFamily();
            };
            if (system != null && env.installed().test(system)) family = system;
            else if (env.installed().test("Noto Sans")) family = "Noto Sans";
            else family = "Dialog";
        }
        return new Choice(family, parseSize(savedSize, DEFAULT_SIZE));
    }

    static Choice resolveMono(Env env, String savedEditorFont) {
        String def = Font.MONOSPACED;
        if ("mac".equals(env.os())) {
            if (env.installed().test("Menlo")) def = "Menlo";
        } else if (env.systemMonoFamily() != null && env.installed().test(env.systemMonoFamily())) {
            def = env.systemMonoFamily();
        }
        if (savedEditorFont == null) return new Choice(def, DEFAULT_SIZE);
        String s = savedEditorFont.strip();
        if (!s.endsWith("pt")) return new Choice(def, DEFAULT_SIZE);
        int sp = s.lastIndexOf(' ');
        if (sp <= 0) return new Choice(def, DEFAULT_SIZE);
        String family = s.substring(0, sp).strip();
        String n = s.substring(sp + 1, s.length() - 2);
        if (n.isEmpty() || family.isEmpty()) return new Choice(def, DEFAULT_SIZE);
        return new Choice(env.installed().test(family) ? family : def, parseSize(n, DEFAULT_SIZE));
    }

    private static int parseSize(String s, int def) {
        if (s == null) return def;
        try {
            int n = Integer.parseInt(s.strip());
            return n >= MIN_SIZE && n <= MAX_SIZE ? n : def;
        } catch (NumberFormatException e) { return def; }
    }

    /** The family part of a desktop font name such as {@code Cantarell 11} or {@code 'Source Code Pro 10'}. */
    static String familyOf(String desktopFontName) {
        if (desktopFontName == null) return null;
        String s = desktopFontName.strip();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1).strip();
        s = s.replaceFirst("\\s+\\d+(\\.\\d+)?$", "");
        return s.isBlank() ? null : s;
    }

    /** A base size scaled by the active UI size, where 12 is the unscaled design size. */
    static int scaled(float baseSize, int activeSize) {
        return Math.round(baseSize * activeSize / 12f);
    }

    /** First stdout line of a command, stripped; null when it is missing, fails, times out or prints nothing. */
    static String firstLine(long timeoutMs, String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            final Process proc = p;
            final String[] line = {null};
            // Read on a helper thread so a silent or chatty child cannot block past the deadline.
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    var out = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (out.size() < 65536) out.write(buf, 0, n);
                    }
                    String text = out.toString(StandardCharsets.UTF_8);
                    int nl = text.indexOf('\n');
                    line[0] = (nl < 0 ? text : text.substring(0, nl)).strip();
                } catch (IOException _) {}
            });
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            reader.join(timeoutMs);
            if (p.exitValue() != 0) return null;
            String s = line[0];
            return s == null || s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static boolean isInstalled(String name) {
        return name != null && name.equalsIgnoreCase(new Font(name, Font.PLAIN, 12).getFamily(Locale.ROOT));
    }

    static Env liveEnv() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "mac" : osName.contains("win") ? "windows" : "linux";
        String ui = null;
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                if ("windows".equals(os)) {
                    Object f = Toolkit.getDefaultToolkit().getDesktopProperty("win.messagebox.font");
                    if (f instanceof Font font) ui = font.getFamily(Locale.ROOT);
                } else if ("linux".equals(os)) {
                    Object f = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Gtk/FontName");
                    if (f != null) ui = familyOf(f.toString());
                }
            } catch (Exception _) { ui = null; }
        }
        String mono = "linux".equals(os)
                ? familyOf(firstLine(1000, "gsettings", "get", "org.gnome.desktop.interface", "monospace-font-name"))
                : null;
        return new Env(os, ui, mono, Fonts::isInstalled);
    }

    /** The active choices, resolved once from the system and the preferences. */
    private static final class Active {
        static final Choice UI, MONO;
        static {
            Env env = liveEnv();
            String fam = null, size = null, mono = null;
            try {
                Preferences p = Preferences.userRoot().node("opentrap.v1");
                fam = p.get(KEY_UI_FAMILY, null);
                size = p.get(KEY_UI_SIZE, null);
                mono = p.get(KEY_MONO, null);
            } catch (Exception _) {}
            UI = resolveUi(env, fam, size);
            MONO = resolveMono(env, mono);
        }
    }

    public static String uiFamily() { return Active.UI.family(); }
    public static int uiSize() { return Active.UI.size(); }
    public static String monoFamily() { return Active.MONO.family(); }
    public static int monoSize() { return Active.MONO.size(); }

    public static Font ui(int style, float baseSize) {
        return new Font(uiFamily(), style, scaled(baseSize, uiSize()));
    }

    public static Font mono(int style, float baseSize) {
        return new Font(monoFamily(), style, scaled(baseSize, monoSize()));
    }

    /** {@code Family Npt}, the {@code editor.font} format and the label text. */
    public static String describe(String family, int size) {
        return family + " " + size + "pt";
    }
}
