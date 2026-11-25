package opentrap.ui.theme;

import opentrap.core.Debug;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * The themes that ship in the jar and the ones the user put in a folder, and
 * the rule that picks the active one at startup. Everything read from the folder
 * is untrusted: it is size-checked before it is parsed, and a file that does not
 * work is left out or refused, never allowed to stop the application.
 */
public final class ThemeCatalog {

    /**
     * The built-in theme ids, in the order they are listed. Light and dark are the
     * bases; the others name one of them as their base and set only what differs.
     */
    public static final List<String> SHIPPED = List.of("light", "dark", "high-contrast", "linen", "deep-sea", "ember", "s1-light", "s1-dark", "phosphor");

    /** The theme the app starts in when no theme has been chosen. */
    public static final String DEFAULT = "linen";

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");
    /** The most of an error's text a startup notice quotes. */
    private static final int MAX_REASON = 200;
    private static final String EXT = ".theme";

    /** One theme in the list. */
    public record Entry(String id, String name, String base, String appearance, boolean shipped) { }

    /** What checking a file found. {@code replaces} is true when a user theme of that id exists. */
    public record Report(String id, String name, String base, String appearance, String author, String description,
                         List<String> ignoredHeader, List<String> leftToBase, List<String> unknownKeys,
                         List<String> clamped, List<Contrast.Failure> contrast, boolean replaces) { }

    /** The theme picked at startup, and the sentence to show the user when it is a fallback. */
    public record Choice(String id, ThemeResolver.Resolved resolved, String notice) { }

    private final Path userDir;

    public ThemeCatalog(Path userDir) {
        this.userDir = userDir;
    }

    /** The file name without {@code .theme}, reduced to {@code [a-z0-9_-]}, at most 64 characters. */
    public static String idFor(Path file) {
        String n = file.getFileName().toString();
        if (n.toLowerCase(Locale.ROOT).endsWith(EXT)) n = n.substring(0, n.length() - EXT.length());
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (n.length() > ThemeFile.MAX_NAME) n = n.substring(0, ThemeFile.MAX_NAME).replaceAll("-+$", "");
        return n;
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (String id : SHIPPED) {
            try {
                ThemeFile f = ThemeKeys.builtIn(id);
                out.add(new Entry(id, f.name(), f.base(), f.appearance(), true));
            } catch (ThemeException e) {
                Debug.log("built-in theme " + id + " left out: " + e.getMessage());
            }
        }
        List<Entry> user = new ArrayList<>();
        if (Files.isDirectory(userDir)) {
            try (var files = Files.list(userDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String file = p.getFileName().toString();
                    if (!file.endsWith(EXT) || !Files.isRegularFile(p)) continue;
                    String id = file.substring(0, file.length() - EXT.length());
                    if (!ID.matcher(id).matches() || SHIPPED.contains(id)) {
                        Debug.log("theme file " + file + " left out: its name is not a theme id");
                        continue;
                    }
                    try {
                        ThemeFile f = ThemeFile.parse(readLimited(p));
                        user.add(new Entry(id, f.name(), f.base(), f.appearance(), false));
                    } catch (ThemeException | IOException e) {
                        Debug.log("theme file " + file + " left out: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Debug.log("cannot list the theme folder: " + e.getMessage());
            }
        }
        user.sort(Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT)).thenComparing(Entry::id));
        out.addAll(user);
        return out;
    }

    /** The values of a theme by id: a built-in from the jar, anything else from the folder. */
    public ThemeResolver.Resolved resolve(String id) throws ThemeException, IOException {
        if (SHIPPED.contains(id)) {
            ThemeFile f = ThemeKeys.builtIn(id);
            return f.base() == null ? ThemeResolver.resolve(f, null, ThemeKeys.spec()) : resolveOnBase(f);
        }
        if (!ID.matcher(id).matches()) throw new ThemeException(0, "no theme named " + id);
        Path file = userDir.resolve(id + EXT);
        if (!Files.isRegularFile(file)) throw new ThemeException(0, "no theme named " + id);
        ThemeFile theme = ThemeFile.parse(readLimited(file));
        return resolveOnBase(theme);
    }

    private static ThemeResolver.Resolved resolveOnBase(ThemeFile theme) throws ThemeException {
        if (theme.base() == null) throw new ThemeException(0, "theme has no base");
        return ThemeResolver.resolve(ThemeKeys.builtIn(theme.base()), theme, ThemeKeys.spec());
    }

    /** Reads, parses and resolves a file for the user to look at before it is added. */
    public Report check(Path file) throws ThemeException, IOException {
        return check(file, readLimited(file));
    }

    private Report check(Path file, String text) throws ThemeException {
        String id = idFor(file);
        if (id.isEmpty()) throw new ThemeException(0, "the file name gives no theme id");
        if (SHIPPED.contains(id)) throw new ThemeException(0, id + " is the id of a built-in theme");
        ThemeFile theme = ThemeFile.parse(text);
        ThemeResolver.Resolved r = resolveOnBase(theme);
        ThemeResolver.Resolved baseAlone =
            ThemeResolver.resolve(ThemeKeys.builtIn(theme.base()), null, ThemeKeys.spec());
        return new Report(id, theme.name(), theme.base(), r.appearance(), theme.author(), theme.description(),
            r.ignoredHeader(), r.leftToBase(), r.unknownKeys(), r.clamped(), Contrast.changed(r.values(), baseAlone.values()),
            Files.exists(userDir.resolve(id + EXT)));
    }

    /** Checks the file and stores it in the folder under its id, replacing a theme of that id. */
    public Entry add(Path file) throws ThemeException, IOException {
        String text = readLimited(file);
        Report r = check(file, text);
        Files.createDirectories(userDir);
        Path tmp = Files.createTempFile(userDir, ".theme-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Path target = userDir.resolve(r.id() + EXT);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Entry(r.id(), r.name(), r.base(), r.appearance(), false);
    }

    /** Deletes a user theme. The built-in themes cannot be removed. */
    public void remove(String id) throws IOException {
        if (SHIPPED.contains(id)) throw new IOException("The built-in theme " + id + " cannot be removed");
        if (!ID.matcher(id).matches()) throw new IOException("no theme named " + id);
        Files.deleteIfExists(userDir.resolve(id + EXT));
    }

    /** Reads a theme file, refusing one over the size limit before decoding it. */
    private static String readLimited(Path file) throws IOException, ThemeException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(ThemeFile.MAX_BYTES + 1);
            if (b.length > ThemeFile.MAX_BYTES) {
                throw new ThemeException(0, "theme is larger than " + ThemeFile.MAX_BYTES + " bytes");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    /**
     * The startup rule: the system property {@code opentrap.theme}, else the
     * saved {@code ui.theme.name}, else {@code ui.theme}, else {@link #DEFAULT}
     * when neither is saved. A theme that cannot be loaded falls back to the
     * built-in that {@code ui.theme} names (light when absent), with a notice.
     * Never throws for a user theme or a bad setting; a broken theme in the jar
     * is a build error, which the tests catch, and throws.
     */
    public static Choice pick(ThemeCatalog c, String sysprop, Preferences prefs) {
        String fallback = "light";
        String savedName = null;
        boolean nothingSaved = false;
        try {
            String saved = prefs.get("ui.theme", null);
            fallback = "dark".equalsIgnoreCase(saved) ? "dark" : "light";
            savedName = prefs.get("ui.theme.name", null);
            nothingSaved = saved == null && (savedName == null || savedName.isBlank());
        } catch (RuntimeException e) {
            Debug.log("cannot read the theme preferences: " + e);
        }
        if (savedName != null && savedName.isBlank()) savedName = null;
        boolean fromSaved = false;
        String id = fallback;
        if (sysprop != null && !sysprop.isBlank()) {
            id = sysprop.strip().toLowerCase(Locale.ROOT);
        } else if (savedName != null) {
            id = savedName.strip();
            fromSaved = true;
        } else if (nothingSaved) {
            id = DEFAULT;
        }
        String reason;
        try {
            ThemeResolver.Resolved r = c.resolve(id);
            if (!fromSaved || r.appearance().equals(fallback)) return new Choice(id, r, null);
            // The saved appearance and the theme's disagree: the appearance was changed
            // elsewhere, or the theme file was edited. ui.theme wins, and the user is told.
            try {
                prefs.remove("ui.theme.name");
            } catch (RuntimeException e) {
                Debug.log("cannot clear ui.theme.name: " + e);
            }
            return builtIn(c, fallback, "The theme " + id + " is a " + r.appearance() + " theme, but the saved appearance is "
                + fallback + ". Using " + label(fallback) + "; choose the theme again in Settings to use it.");
        } catch (ThemeException | IOException | RuntimeException | StackOverflowError e) {
            String m = e.getMessage();
            reason = m == null || m.isBlank() ? e.getClass().getSimpleName() : ThemeException.excerpt(m, MAX_REASON);
        }
        Debug.log("theme " + id + " not loaded: " + reason);
        return builtIn(c, fallback, "The theme " + ThemeException.excerpt(id) + " could not be loaded (" + reason + "). Using "
            + label(fallback) + ".");
    }

    private static String label(String builtIn) {
        return builtIn.equals("dark") ? "Dark" : "Light";
    }

    private static Choice builtIn(ThemeCatalog c, String id, String notice) {
        try {
            return new Choice(id, c.resolve(id), notice);
        } catch (ThemeException | IOException e) {
            throw new IllegalStateException("built-in theme is broken: " + e.getMessage(), e);
        }
    }
}
