package opentrap.ui;

import opentrap.core.*;
import opentrap.oast.Collaborator;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferencesFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A project file carries the project's own settings and none of the user's: a
 * file saved by an older build holds both, and opening it must apply only the
 * first, while a file written now must hold none of the second.
 */
public final class ProjectSettingsFileTest {

    private static final String RULE = "true\tHTTP\tevil.invalid\t8080\t\t\t\n";
    private static final String PASS = "s3cr3t-upstream-pass-7f";
    private static final String TOKEN = "oast-token-7f";

    private static Preferences node() { return Preferences.userRoot().node("opentrap.v1"); }

    private static Map<String, String> hostile() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope.advanced", "true");
        m.put("upstream.proxies", RULE);
        m.put("oast.server", "https://example.invalid");
        m.put("oast.token", "evil");
        m.put("browser.relaxSecurity", "true");
        m.put("ui.theme", "dark");
        return m;
    }

    private static File tmp() throws Exception {
        File f = Files.createTempFile("opentrap-projsettings", ".otrap").toFile();
        f.delete();
        return f;
    }

    /** A v7 file whose only record is a session snapshot carrying {@code settings}. */
    private static File v7Snapshot(Map<String, String> settings) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new Scope());
            oos.writeObject(settings);
            for (int i = 0; i < 5; i++) oos.writeObject(null);   // session, organizer, issues, tool states, tasks
        }
        File f = tmp();
        try (ProjectStore s = new ProjectStore(f.toPath(), 1 << 20)) {
            s.append(ProjectStore.SNAPSHOT, baos.toByteArray());
            s.sync();
        }
        return f;
    }

    /** A project in the previous zip format (version 6) with an empty history. */
    private static File v6(Map<String, String> settings) throws Exception {
        File f = tmp();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            zos.putNextEntry(new ZipEntry("opentrap"));
            ObjectOutputStream oos = new ObjectOutputStream(zos);
            oos.writeInt(6);
            oos.writeObject(new ArrayList<LogEntry>());
            oos.writeObject(new Scope());
            oos.writeObject(settings);
            for (int i = 0; i < 5; i++) oos.writeObject(null);   // session, organizer, issues, tool states, tasks
            oos.flush();
            zos.closeEntry();
        }
        return f;
    }

    private static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) if (hay[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    private static void closeStores(SuiteContext c) {
        c.store().closeStore();
        c.wsStore().closeStore();
    }

    private static void drop(File... files) {
        for (File f : files) if (f != null) f.delete();
    }

    /** A context holding one user upstream rule with a password, a Collaborator token and a project key. */
    private static SuiteContext withUserSecrets() {
        SuiteContext ctx = new SuiteContext();
        UpstreamProxy p = new UpstreamProxy();
        p.host = "mine.example";
        p.user = "me";
        p.pass = PASS;
        ctx.upstreamProxies().rules().add(p);
        ctx.upstreamProxies().save();
        ctx.settings().set("oast.token", TOKEN);
        ctx.settings().set("scope.advanced", "true");
        return ctx;
    }

    private static void expectNoSecrets(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        String b64 = Base64.getEncoder().encodeToString(PASS.getBytes(StandardCharsets.UTF_8));
        Expect.isFalse(contains(bytes, PASS), "upstream password in the file");
        Expect.isFalse(contains(bytes, b64), "upstream password, encoded, in the file");
        Expect.isFalse(contains(bytes, TOKEN), "Collaborator token in the file");
    }

    private static void expectOnlyProjectSettings(SuiteContext ctx) throws Exception {
        Expect.equal(ctx.settings().get("scope.advanced", "<sentinel>"), "true", "project key");
        Settings restarted = new Settings();
        Expect.equal(new UpstreamProxyManager(restarted).rules().size(), 0, "upstream rules after a restart");
        Expect.equal(new Collaborator(restarted).serverUrl(), Collaborator.DEFAULT_SERVER, "Collaborator server");
        for (String k : node().keys())
            Expect.isFalse(k.equals("upstream.proxies") || k.startsWith("oast."), "preferences key " + k);
    }

    public void run() {
        Harness.suite("project settings in the file");

        Harness.test("opening a project whose snapshot carries user settings applies only its project settings", () -> {
            MemoryPreferencesFactory.reset();
            File f = null;
            SuiteContext ctx = new SuiteContext();
            try {
                f = v7Snapshot(hostile());
                StateIO.restore(ctx, f);
                expectOnlyProjectSettings(ctx);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(ctx); drop(f); }
        });

        Harness.test("opening an old-format project whose settings carry user settings applies only its project settings", () -> {
            MemoryPreferencesFactory.reset();
            File f = null;
            SuiteContext ctx = new SuiteContext();
            try {
                f = v6(hostile());
                StateIO.restore(ctx, f);
                expectOnlyProjectSettings(ctx);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(ctx); drop(f); }
        });

        Harness.test("a new project file holds none of the user's settings", () -> {
            MemoryPreferencesFactory.reset();
            File f = null;
            SuiteContext ctx = withUserSecrets();
            SuiteContext back = new SuiteContext();
            try {
                f = tmp();
                StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
                closeStores(ctx);
                expectNoSecrets(f);
                StateIO.restore(back, f);
                Expect.equal(back.settings().get("scope.advanced", "<sentinel>"), "true", "project key after reopening");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(ctx); closeStores(back); drop(f); }
        });

        Harness.test("a saved copy holds none of the user's settings", () -> {
            MemoryPreferencesFactory.reset();
            File f = null;
            SuiteContext ctx = withUserSecrets();
            SuiteContext back = new SuiteContext();
            try {
                f = tmp();
                StateIO.save(ctx, f, null);
                closeStores(ctx);
                expectNoSecrets(f);
                StateIO.restore(back, f);
                Expect.equal(back.settings().get("scope.advanced", "<sentinel>"), "true", "project key after reopening");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(ctx); closeStores(back); drop(f); }
        });
    }
}
