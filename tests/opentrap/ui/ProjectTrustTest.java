package opentrap.ui;

import opentrap.core.*;
import opentrap.session.Cookie;
import opentrap.session.Macro;
import opentrap.session.SessionRule;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.MemoryPreferencesFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A project file from someone else is opened in full only when it is trusted.
 * Its session handling rules, macros, cookies and project settings act on the
 * requests the user sends, so without trust they are left out, while the
 * history, issues and scope still load. A file is trusted once this
 * installation created, saved or opened it with trust.
 */
public final class ProjectTrustTest {

    private static final HttpService SVC = new HttpService("example.com", 443, true);

    private static File tmp(Path dir, String name) {
        return dir.resolve(name).toFile();
    }

    /** A saved project with one history entry, a session rule, a macro, a cookie, a project key, an issue and a scope rule. */
    private static File foreignProject(Path dir) throws Exception {
        SuiteContext ctx = new SuiteContext();
        File f = tmp(dir, "foreign.otrap");
        StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
        LogEntry e = new LogEntry("proxy", SVC,
                new HttpMessage(Bytes.bytes("GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n")));
        e.setResponse(new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nok")));
        ctx.store().add(e);
        SessionRule rule = new SessionRule();
        rule.name = "theirs";
        rule.macroName = "beacon";
        ctx.sessions().rules().add(rule);
        ctx.sessions().macros().add(new Macro("beacon"));
        ctx.sessions().jar().set(new Cookie("sid", "fixed", "example.com", "/", false, 0));
        ctx.settings().set("scope.advanced", "true");
        ctx.addIssue(new Issue("Finding", Issue.Severity.values()[0], Issue.Confidence.values()[0],
                SVC, "/a", "", "detail", "", "", null, null));
        ctx.scope().include().add(Scope.Rule.prefix("https://example.com/", false));
        StateIO.save(ctx, f);
        ctx.store().closeStore();
        ctx.wsStore().closeStore();
        return f;
    }

    private static void closeStores(SuiteContext c) {
        c.store().closeStore();
        c.wsStore().closeStore();
    }

    /** Run {@code body} with {@code user.home} pointed at a fresh directory, so the trust list starts empty. */
    private interface Body { void run(Path home) throws Exception; }

    private static void inHome(Body body) {
        String was = System.getProperty("user.home");
        try {
            Path home = Files.createTempDirectory("opentrap-trust");
            System.setProperty("user.home", home.toString());
            MemoryPreferencesFactory.reset();
            body.run(home);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setProperty("user.home", was);
        }
    }

    public void run() {
        Harness.suite("project trust");

        Harness.test("opening a project without trust leaves out its session handling state and project settings", () -> inHome(home -> {
            File f = foreignProject(home);
            SuiteContext back = new SuiteContext();
            try {
                int n = StateIO.restore(back, f, false);
                Expect.equal(n, 1, "the history still loads");
                Expect.equal(back.issues().size(), 1, "the issues still load");
                Expect.equal(back.scope().include().size(), 1, "the scope still loads");
                Expect.equal(back.sessions().rules().size(), 0, "session rules");
                Expect.equal(back.sessions().macros().size(), 0, "macros");
                Expect.equal(back.sessions().jar().size(), 0, "cookies");
                Expect.equal(back.settings().get("scope.advanced", "<unset>"), "<unset>", "project setting");
            } finally { closeStores(back); }
        }));

        Harness.test("opening a project with trust loads its session handling state and project settings", () -> inHome(home -> {
            File f = foreignProject(home);
            SuiteContext back = new SuiteContext();
            try {
                StateIO.restore(back, f, true);
                Expect.equal(back.sessions().rules().size(), 1, "session rules");
                Expect.equal(back.sessions().macros().size(), 1, "macros");
                Expect.equal(back.sessions().jar().size(), 1, "cookies");
                Expect.equal(back.settings().get("scope.advanced", "<unset>"), "true", "project setting");
            } finally { closeStores(back); }
        }));

        Harness.test("saving a project opened without trust keeps what was held back in the file", () -> inHome(home -> {
            File f = foreignProject(home);
            SuiteContext back = new SuiteContext();
            SuiteContext third = new SuiteContext();
            try {
                StateIO.restore(back, f, false);   // stays bound to f, as an opened project does
                SessionRule mine = new SessionRule();
                mine.name = "mine";
                back.sessions().rules().add(mine);
                StateIO.save(back, f);
                closeStores(back);
                StateIO.restore(third, f, true);
                Expect.equal(third.sessions().rules().size(), 2, "their rule and mine");
                Expect.equal(third.sessions().macros().size(), 1, "macros");
                Expect.equal(third.sessions().jar().size(), 1, "cookies");
                Expect.equal(third.settings().get("scope.advanced", "<unset>"), "true", "project setting");
            } finally { closeStores(back); closeStores(third); }
        }));

        Harness.test("an in-scope copy narrows what was held back like the rest of the session", () -> inHome(home -> {
            File f = foreignProject(home);
            File copy = tmp(home, "copy.otrap");
            SuiteContext back = new SuiteContext();
            SuiteContext third = new SuiteContext();
            try {
                StateIO.restore(back, f, false);
                back.scope().include().clear();
                back.scope().include().add(Scope.Rule.prefix("https://elsewhere.example/", false));
                StateIO.SaveOptions o = StateIO.SaveOptions.fromSettings(back);
                o.inScopeOnly = true;
                StateIO.save(back, copy, o);
                closeStores(back);
                StateIO.restore(third, copy, true);
                Expect.equal(third.sessions().jar().size(), 0, "an out-of-scope held-back cookie");
            } finally { closeStores(back); closeStores(third); }
        }));

        Harness.test("opening a project with trust clears what an earlier open held back", () -> inHome(home -> {
            File f = foreignProject(home);
            SuiteContext back = new SuiteContext();
            try {
                StateIO.restore(back, f, false);
                Expect.isTrue(back.heldBack() != null, "held back after an untrusted open");
                StateIO.restore(back, f, true);
                Expect.isTrue(back.heldBack() == null, "nothing held back after a trusted open");
            } finally { closeStores(back); }
        }));

        Harness.test("a project file is not trusted until this installation trusts it", () -> inHome(home -> {
            File f = tmp(home, "p.otrap");
            Settings s = new Settings();
            Expect.isFalse(ProjectTrust.trustedWithoutAsking(s, f), "unknown file, default setting");
            ProjectTrust.trust(f);
            Expect.isTrue(ProjectTrust.trustedWithoutAsking(s, f), "after trusting it");
            Expect.isTrue(ProjectTrust.recognised(new File(home.toFile(), "./p.otrap")), "the same file by another path");
        }));

        Harness.test("always trusting project files opens an unknown file without asking", () -> inHome(home -> {
            Settings s = new Settings();
            s.setBool(ProjectTrust.ALWAYS, true);
            Expect.isTrue(ProjectTrust.trustedWithoutAsking(s, tmp(home, "other.otrap")), "unknown file");
        }));

        Harness.test("a trusted project stays trusted when it is renamed, and its old name does not", () -> inHome(home -> {
            File from = tmp(home, "old.otrap"), to = tmp(home, "new.otrap");
            ProjectTrust.trust(from);
            ProjectTrust.moved(from, to);
            Expect.isTrue(ProjectTrust.recognised(to), "new name");
            Expect.isFalse(ProjectTrust.recognised(from), "old name");
        }));

        Harness.test("renaming an untrusted project does not trust it", () -> inHome(home -> {
            File from = tmp(home, "a.otrap"), to = tmp(home, "b.otrap");
            ProjectTrust.moved(from, to);
            Expect.isFalse(ProjectTrust.recognised(to), "new name");
        }));

        Harness.test("the first read trusts the recent projects once, and a later recent project is not trusted", () -> inHome(home -> {
            File earlier = tmp(home, "earlier.otrap"), later = tmp(home, "later.otrap");
            Files.writeString(earlier.toPath(), "x");
            Files.writeString(later.toPath(), "x");
            RecentProjects.record("earlier.otrap", earlier);
            Expect.isTrue(ProjectTrust.recognised(earlier), "recent before the trust list existed");
            RecentProjects.record("later.otrap", later);
            Expect.isFalse(ProjectTrust.recognised(later), "recent after the trust list existed");
        }));
    }
}
