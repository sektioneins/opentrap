package opentrap.ui;

import opentrap.core.ProjectOptions;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferencesFactory;

import javax.swing.SwingUtilities;
import java.util.Map;

/**
 * The scope editor follows the "advanced scope control" project setting. It is
 * built before a project is restored, so a restore must reach it, both when it
 * is already on screen and when it is shown only afterwards.
 */
public final class ScopeEditorTest {

    private static void settle() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }

    public void run() {
        Harness.suite("scope editor");

        Harness.test("an editor on screen switches to advanced mode when a project is restored", () -> {
            MemoryPreferencesFactory.reset();
            try {
                SuiteContext ctx = new SuiteContext();
                ScopeEditor editor = new ScopeEditor(ctx, ctx.scope());
                Expect.isFalse(editor.advancedShown(), "mode before the restore");
                editor.addNotify();
                ctx.settings().restoreProject(Map.of(ProjectOptions.SCOPE_ADVANCED, "true"));
                settle();
                Expect.isTrue(editor.advancedShown(), "mode after the restore");
                editor.removeNotify();
                ctx.store().closeStore();
                ctx.wsStore().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an editor shown only after a project was restored starts in advanced mode", () -> {
            MemoryPreferencesFactory.reset();
            try {
                SuiteContext ctx = new SuiteContext();
                ScopeEditor editor = new ScopeEditor(ctx, ctx.scope());
                ctx.settings().restoreProject(Map.of(ProjectOptions.SCOPE_ADVANCED, "true"));
                editor.addNotify();
                settle();
                Expect.isTrue(editor.advancedShown(), "mode once shown");
                editor.removeNotify();
                ctx.store().closeStore();
                ctx.wsStore().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an editor taken off screen stops following the setting", () -> {
            MemoryPreferencesFactory.reset();
            try {
                SuiteContext ctx = new SuiteContext();
                ScopeEditor editor = new ScopeEditor(ctx, ctx.scope());
                editor.addNotify();
                editor.removeNotify();
                ctx.settings().restoreProject(Map.of(ProjectOptions.SCOPE_ADVANCED, "true"));
                settle();
                Expect.isFalse(editor.advancedShown(), "mode of a hidden editor");
                ctx.store().closeStore();
                ctx.wsStore().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
