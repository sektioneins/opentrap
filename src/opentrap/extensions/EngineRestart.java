package opentrap.extensions;

import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.engine.EngineState;
import opentrap.engine.ZapEngine;
import opentrap.extensions.store.CoreAddOns;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Restart engine, as the add-on store offers it: the engine is stopped and
 * started again within this session, so queued add-on changes take effect. A
 * crawl or an active audit cannot survive that and is stopped; the live audit
 * pauses while the engine is down and resumes after it.
 */
public final class EngineRestart {

    /** What a scan stopped by the restart logs as its last line. */
    static final String STOPPED = "Stopped by engine restart";

    private EngineRestart() {}

    /**
     * The warning to show before a restart while {@code running} crawls or
     * audits run and the live audit is on or not, or null when nothing would
     * be stopped or paused.
     */
    public static String warning(List<ScanTask> running, boolean liveAudit) {
        if (running.isEmpty() && !liveAudit) return null;
        StringBuilder b = new StringBuilder();
        if (!running.isEmpty()) {
            b.append("Restarting the engine stops these scans:\n");
            for (ScanTask t : running) b.append("  ").append(t.title()).append('\n');
        }
        if (liveAudit) {
            if (b.length() > 0) b.append('\n');
            b.append("Live audit pauses during the restart and resumes after it.\n");
        }
        return b.toString().stripTrailing();
    }

    /**
     * Crawls and active audits on the dashboard that a restart would stop. A
     * live task over proxy traffic, such as a live passive crawl, does not run
     * on the engine and goes on through the restart.
     */
    static List<ScanTask> stoppable(SuiteContext ctx) {
        List<ScanTask> out = new ArrayList<>();
        for (ScanTask t : ctx.tasks()) {
            boolean going = t.state() == ScanTask.State.RUNNING || t.state() == ScanTask.State.PAUSED;
            if (t.engineScan() && going && !t.cancelled()) out.add(t);
        }
        return out;
    }

    /** Whether a live audit task is capturing, so the restart pauses it. */
    static boolean liveAuditOn(SuiteContext ctx) {
        for (ScanTask t : ctx.tasks()) {
            if (t.type() == ScanTask.Type.LIVE_PASSIVE && t.state() == ScanTask.State.RUNNING) return true;
        }
        return false;
    }

    /**
     * Stops every running or paused crawl and audit, saying why on its log,
     * then restarts {@code ze} on a thread of its own, since a restart takes
     * as long as the engine takes to answer. A restart that fails goes to the
     * event log; the engine then reports itself failed to its listeners.
     */
    public static void run(SuiteContext ctx, ZapEngine ze) {
        for (ScanTask t : stoppable(ctx)) {
            t.cancel();
            t.log(STOPPED);
            ctx.taskChanged(t);
        }
        Thread th = new Thread(() -> {
            try {
                ze.restart();
            } catch (Exception e) {
                String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ctx.alert("extensions", "The engine restart failed: " + why);
            }
        }, "engine-restart");
        th.setDaemon(true);
        th.start();
    }

    /**
     * Restart engine as the user asks for it, on the EDT: with nothing to
     * restart it says when the changes apply; with scans running it names them
     * and asks first.
     */
    public static void confirmAndRun(Component parent, SuiteContext ctx) {
        ZapEngine ze = ZapEngine.shared();
        if (ze == null || !ze.available() || ze.state() == EngineState.NOT_STARTED) {
            JOptionPane.showMessageDialog(parent, "The changes apply when the engine first starts.",
                    "Restart engine", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String w = warning(stoppable(ctx), liveAuditOn(ctx));
        if (w != null) {
            JTextArea text = Ui.note(w);
            Ui.sizeNote(text, 420);
            String[] options = {"Restart", "Cancel"};
            int r = JOptionPane.showOptionDialog(parent, text, "Restart engine",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (r != 0) return;
        }
        run(ctx, ze);
    }

    /** What a feature says where it is started when the add-ons {@code ids} it needs are not active. */
    public static String missingMessage(CoreAddOns.Feature f, List<String> ids) {
        return CoreAddOns.missingMessage(f, ids);
    }
}
