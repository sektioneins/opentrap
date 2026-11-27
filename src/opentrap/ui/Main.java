package opentrap.ui;

import opentrap.core.SuiteContext;
import opentrap.core.Debug;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;

public class Main {
    /**
     * The build identifier, the UTC date and short id of the commit it was built from
     * (YYYYMMDD-abc1234, with "-dirty" for a tree with uncommitted changes), stamped into the
     * jar manifest by the release recipe (just release), so the running app reports exactly
     * which source it is. A dev build has none, and reports "dev".
     */
    public static final String VERSION = buildVersion();
    /** The app's visible name: window titles, the menu, the About box, the dock. */
    public static final String NAME = "OpenTRAP-S1";
    public static final String TITLE = NAME + " " + VERSION;

    private static String buildVersion() {
        try {
            var res = Main.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (res.hasMoreElements()) {
                try (var in = res.nextElement().openStream()) {
                    var mf = new java.util.jar.Manifest(in);
                    var at = mf.getMainAttributes();
                    if ("opentrap.ui.Main".equals(at.getValue("Main-Class"))) {
                        String v = at.getValue("Implementation-Version");
                        if (v != null && !v.isBlank()) return v;
                    }
                } catch (Exception _) {}
            }
        } catch (Exception _) {}
        return "dev";
    }

    public static volatile String[] launchArgs = new String[0];

    public static void main(String[] args) {
        // The JDK's HTTP client never sends Basic credentials when it opens a
        // CONNECT tunnel through a proxy, so the extension store could not use an
        // upstream proxy that needs a login. The suite's own client already sends
        // Basic on CONNECT. java.net.http reads this once, so it must come first.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        // Headless probe: print the scale this display should use and exit. The
        // AppImage launcher calls this to choose a scale before the real JVM
        // starts, so it never relaunches inside the AppImage (relaunching there
        // would tear down the AppImage mount and kill the app).
        for (String a : args) {
            if ("--print-scale".equals(a)) {
                System.out.println(UiScale.desired());
                return;
            }
        }

        launchArgs = args;

        // Capture uncaught exceptions on every thread even without --debug, so
        // a failure in a worker thread is labelled and traced rather than a
        // bare stack dump. With --debug it also lands in the log file.
        Debug.installUncaughtHandler();

        for (String a : args) {
            if ("--debug".equals(a)) { Debug.enable(); break; }
        }
        Debug.log("main() starting, args=" + Arrays.toString(args));

        // If a non-default interface scale is saved and this JVM has not had it
        // applied yet, relaunch with Java's real HiDPI scaling so the whole UI
        // scales uniformly (fonts, columns, borders), then exit.
        if (!UiScale.applied()) {
            double want = UiScale.desired();
            Debug.log("scale not yet applied; desired=" + want);
            if (Math.abs(want - 1.0) > 0.001) {
                Debug.log("relaunching JVM with uiScale=" + want);
                if (UiScale.relaunchWith(want, args)) {
                    Debug.log("relaunched child process; parent exiting");
                    return;
                }
                Debug.log("relaunch did not happen; continuing in this process");
            }
        } else {
            Debug.log("scale already applied; no relaunch needed");
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        MacMenuBar.request();
        Theme.install();

        // Scale the whole interface before any window is built. The factor comes
        // from -Dopentrap.scale, else the saved preference, else 1.0.
        UiScale.installDefaults();

        Path home = Path.of(System.getProperty("user.home"), ".opentrap");

        Debug.log("entering EDT to build the UI");
        SwingUtilities.invokeLater(() -> {
          try {
            // Build the whole UI and show the main window FIRST, then ask which
            // project to open. A modal dialog shown as the very first window with
            // no owner fails to map on some Linux/Wayland desktops (it blocks
            // invisibly and the process hangs); giving it a visible parent frame
            // makes it appear reliably.
            Debug.log("creating SuiteContext and SuiteFrame");
            SuiteContext ctx = new SuiteContext();
            // EXPERIMENTAL (issue #37 keystone): keep recorded traffic bodies
            // and WebSocket frame payloads on disk, not in the heap, so a long
            // proxying session stays bounded.
            ctx.store().useDiskStore(home.resolve("traffic"));
            ctx.wsStore().useDiskStore(home.resolve("ws-traffic"));
            SuiteFrame frame = new SuiteFrame(ctx, TITLE);
            // Under --debug, report what the interface was doing whenever it
            // stops answering, so a freeze comes with its reason attached.
            EdtWatchdog.install();
            if (Debug.enabled()) {
                Runtime.getRuntime().addShutdownHook(new Thread(() ->
                        Debug.log("interface stalls this session:\n" + EdtWatchdog.summary())));
            }
            Debug.log("registering tools");
            Bootstrap.registerTools(ctx, frame, home);
            Debug.log("building UI (frame.buildUi)");
            frame.buildUi();

            // Size to the scaled ideal, but never larger than the screen's work
            // area, so the window always fits even on a small laptop display.
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int w = Math.min(UiScale.px(1280), screen.width);
            int h = Math.min(UiScale.px(860), screen.height);
            frame.setSize(w, h);
            frame.setLocationRelativeTo(null);
            // If even the fitted size fills the screen, start maximised.
            if (w >= screen.width - 8 && h >= screen.height - 8) {
                frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
            Debug.log("showing main window (" + w + "x" + h + ")");
            frame.setVisible(true);
            if (Debug.enabled()) {
                Debug.log("main window shown after " + ManagementFactory.getRuntimeMXBean().getUptime() + " ms");
            }
            frame.toFront();
            frame.requestFocus();
            Debug.log("main window is visible");
            // A theme that could not be loaded was replaced by a built-in one;
            // say so once, now that there is a window to own the dialog.
            String themeNotice = Theme.startupNotice();
            if (themeNotice != null) {
                JOptionPane.showMessageDialog(frame, themeNotice, "Theme", JOptionPane.WARNING_MESSAGE);
            }

            // A project path on the command line (as a .otrap file association
            // passes when one is double-clicked) opens or creates that project
            // directly, skipping the startup dialog.
            java.io.File argProject = projectArg(launchArgs);
            if (argProject != null) {
                Debug.log("opening project from the command line: " + argProject);
                openProjectFromArg(ctx, frame, argProject);
                return;
            }

            // Otherwise choose the project in a dialog owned by the visible window.
            Debug.log("showing startup project dialog");
            ProjectStartupDialog startup = new ProjectStartupDialog(frame, TITLE, ctx.settings());
            // The dialog is modal, so setVisible returns only once it closes. The
            // start-up time is taken when the dialog reports it is open, once only.
            if (Debug.enabled()) {
                startup.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowOpened(java.awt.event.WindowEvent e) {
                        Debug.log("start-up window shown after " + ManagementFactory.getRuntimeMXBean().getUptime() + " ms");
                        startup.removeWindowListener(this);
                    }
                });
            }
            startup.setVisible(true);
            Debug.log("startup dialog closed: proceeded=" + startup.proceeded()
                    + " mode=" + startup.mode());
            if (!startup.proceeded()) {
                System.exit(0);
                return;
            }
            ctx.alert("suite", TITLE + " started");

            // Resolve the chosen project now that the window and its tools
            // (whose listeners repopulate on restore) are live.
            java.io.File projectFile = startup.file();
            switch (startup.mode()) {
                case NEW -> {
                    try {
                        // Create the single-file project and bind the session to it,
                        // so every exchange streams to disk and saves stay cheap.
                        StateIO.bindNew(ctx, projectFile, StateIO.SaveOptions.fromSettings(ctx));
                        ctx.setProjectFile(projectFile);
                        RecentProjects.record(startup.projectName(), projectFile);
                        ProjectTrust.trust(projectFile);
                        ctx.alert("suite", "New project: " + projectFile.getName());
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(frame,
                                "Could not create the project file:\n" + e.getMessage(),
                                "New project", JOptionPane.ERROR_MESSAGE);
                    }
                }
                case OPEN -> {
                    // Off the event thread with a progress dialog: a large
                    // project must never freeze the window while it loads.
                    // The dialog's "Trust this project file" is the answer to
                    // ProjectTrust's question, so no second prompt is shown.
                    final String projectName = startup.projectName();
                    final boolean trusted = startup.trustProject();
                    StateIO.restoreAsync(ctx, frame, projectFile, trusted, n -> {
                        ctx.setProjectFile(projectFile);
                        RecentProjects.record(projectName, projectFile);
                        if (trusted) ProjectTrust.trust(projectFile);
                        else ctx.alert("suite", ProjectTrust.heldBackNote(projectFile));
                        ctx.alert("suite", "Opened project " + projectFile.getName() + " (" + n + " items)");
                    });
                }
                default -> { /* temporary in-memory project: nothing to load */ }
            }
          } catch (Throwable t) {
            Debug.error("startup failed while building the UI", t);
            handleStartupFailure(t);
          }
        });
    }

    /** The first non-flag argument that names a project file, or null. */
    private static java.io.File projectArg(String[] args) {
        for (String a : args) {
            if (a == null || a.startsWith("--")) continue;
            if (a.toLowerCase().endsWith(StateIO.EXT)) return new java.io.File(a);
        }
        return null;
    }

    /** Open an existing project file, or create one if it does not exist yet. */
    private static void openProjectFromArg(SuiteContext ctx, SuiteFrame frame, java.io.File projectFile) {
        String name = projectFile.getName();
        if (projectFile.exists()) {
            // A double-clicked file is the easiest way to be handed a foreign
            // project, so ask before loading what would act on its own.
            ProjectTrust.Choice choice = ProjectTrust.ask(frame, ctx.settings(), projectFile);
            if (choice == ProjectTrust.Choice.CANCEL) return;
            boolean trusted = choice == ProjectTrust.Choice.TRUST;
            StateIO.restoreAsync(ctx, frame, projectFile, trusted, n -> {
                ctx.setProjectFile(projectFile);
                RecentProjects.record(name, projectFile);
                if (trusted) ProjectTrust.trust(projectFile);
                else ctx.alert("suite", ProjectTrust.heldBackNote(projectFile));
                ctx.alert("suite", "Opened project " + name + " (" + n + " items)");
            });
        } else {
            try {
                StateIO.bindNew(ctx, projectFile, StateIO.SaveOptions.fromSettings(ctx));
                ctx.setProjectFile(projectFile);
                RecentProjects.record(name, projectFile);
                ProjectTrust.trust(projectFile);
                ctx.alert("suite", "New project: " + name);
            } catch (Exception e) {
                Debug.error("could not create the project from the command line", e);
            }
        }
    }

    private static void handleStartupFailure(Throwable t) {
        JOptionPane.showMessageDialog(null,
                "Failed to start: " + t, "error", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
