package opentrap.ui;

import opentrap.core.Settings;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which project files this installation trusts, and asking before it opens one
 * it does not.
 *
 * <p>A project file can come from someone else. Most of what it holds only
 * fills the views: history, issues, scope, organizer items, tool state. Two
 * parts act on their own: the session handling rules, macros and cookies, which
 * the proxy and the other tools apply to the requests they send, and the
 * project settings, which change how requests are sent. Opening a file without
 * trust leaves those two parts out.
 *
 * <p>A file is trusted once it was created here, saved here, or opened with
 * trust. The list is kept by canonical path in {@code ~/.opentrap}. The first
 * time the list is read it starts from the recent projects, which this
 * installation opened before the gate existed, so they do not all ask once.
 */
public final class ProjectTrust {

    /** The user setting that trusts every project file without asking. Off by default. */
    public static final String ALWAYS = "startup.trustProjects";

    /** What to do with a project file that is not trusted yet. */
    public enum Choice { TRUST, HOLD_BACK, CANCEL }

    private static final int MAX = 500;

    private ProjectTrust() {}

    private static Path store() {
        return Path.of(System.getProperty("user.home"), ".opentrap", "trusted-projects.txt");
    }

    private static String key(File f) {
        try { return f.getCanonicalPath(); }
        catch (Exception e) { return f.getAbsoluteFile().toPath().normalize().toString(); }
    }

    /**
     * The trusted paths, most recent first. Seeded from the recent projects when
     * the list does not exist yet, and written at once, so a file that is later
     * opened without trust and lands among the recents is never seeded.
     */
    private static synchronized Set<String> load() {
        Path p = store();
        Set<String> out = new LinkedHashSet<>();
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
                    if (!line.isBlank()) out.add(line);
            } catch (Exception _) {}
            return out;
        }
        Set<String> seeded = new LinkedHashSet<>();
        for (RecentProjects.Entry e : RecentProjects.load()) seeded.add(key(e.file()));
        // Keep the seed only when it could be written: an unwritable list would
        // be seeded again on every read, from recents that may hold untrusted files.
        return write(seeded) ? seeded : out;
    }

    private static boolean write(Set<String> paths) {
        try {
            Path p = store();
            Files.createDirectories(p.getParent());
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (String s : paths) {
                if (n++ >= MAX) break;
                b.append(s).append('\n');
            }
            Files.writeString(p, b.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    /** True when this installation created, saved or opened {@code f} with trust. */
    public static boolean recognised(File f) {
        return f != null && load().contains(key(f));
    }

    /** True when {@code f} may be opened in full without asking. */
    public static boolean trustedWithoutAsking(Settings s, File f) {
        return s.getBool(ALWAYS, false) || recognised(f);
    }

    /**
     * Remember {@code f} as trusted. Saving does this only when nothing was
     * held back, since a held-back part is written into the saved file too.
     */
    public static synchronized void trust(File f) {
        if (f == null) return;
        Set<String> paths = load();
        String k = key(f);
        paths.remove(k);
        Set<String> out = new LinkedHashSet<>();
        out.add(k);
        out.addAll(paths);
        write(out);
    }

    /** A trusted project renamed or moved from {@code from} to {@code to} stays trusted. */
    public static synchronized void moved(File from, File to) {
        if (from == null || to == null) return;
        Set<String> paths = load();
        if (!paths.remove(key(from))) return;
        write(paths);
        trust(to);
    }

    /**
     * Ask before opening {@code f}, unless it may be opened in full without
     * asking, in which case the answer is {@link Choice#TRUST}.
     */
    public static Choice ask(Component parent, Settings s, File f) {
        if (trustedWithoutAsking(s, f)) return Choice.TRUST;
        String[] options = {"Trust and open", "Open without them", "Cancel"};
        JTextArea text = Ui.note(f.getName() + " was not created on this computer or opened here "
                + "with trust before.\n\n"
                + "A project file can carry session handling rules, macros and cookies, which "
                + "act on the requests the proxy and the other tools send, and project settings "
                + "that change how requests are sent. Trust it only if you trust where it came from.\n\n"
                + "Open without them to load its history, issues, scope and tool state only. "
                + "The rest stays in the file, unused, and is there if you open it with trust later.");
        Ui.sizeNote(text, 460);
        int r = JOptionPane.showOptionDialog(parent, text, "Open project",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return switch (r) {
            case 0 -> Choice.TRUST;
            case 1 -> Choice.HOLD_BACK;
            default -> Choice.CANCEL;
        };
    }

    /** The note left after a file was opened without trust. */
    public static String heldBackNote(File f) {
        return "Opened " + f.getName() + " without its session handling rules, macros, cookies "
                + "and project settings. They stay in the file; open it with trust to use them";
    }
}
