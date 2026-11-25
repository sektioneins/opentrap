package opentrap.ui;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * What a project keeps. Audit probes are written headers only unless asked
 * otherwise, everything else whole; a cap cuts large bodies and says so; a
 * saved copy can be narrowed to the scope, to some tools and without the
 * scan cards.
 */
public final class ProjectSlimTest {

    private static final HttpService SVC = new HttpService("example.com", 443, true);
    private static final HttpService OTHER = new HttpService("other.example.net", 443, true);

    private static HttpMessage req(String path) {
        return new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: example.com\r\n\r\n"));
    }

    private static HttpMessage resp(int bodyLen) {
        String body = "x".repeat(bodyLen);
        return new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                + bodyLen + "\r\n\r\n" + body));
    }

    private static LogEntry entry(String tool, HttpService svc, String path, int bodyLen) {
        LogEntry e = new LogEntry(tool, svc, req(path));
        e.setResponse(resp(bodyLen));
        return e;
    }

    private static File tmp() throws Exception {
        return Files.createTempFile("opentrap-slim", ".otrap").toFile();
    }

    public void run() {
        Harness.suite("project file contents");

        Harness.test("audit probes are saved headers only; everything else and the findings stay whole", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.store().add(entry("proxy", SVC, "/", 5000));
                ctx.store().add(entry("crawl", SVC, "/about", 4000));
                LogEntry probe = entry("audit", SVC, "/login?u=1'", 3000);
                ctx.store().add(probe);
                ctx.addIssue(new Issue("SQL injection", Issue.Severity.HIGH, Issue.Confidence.FIRM,
                        SVC, "/login", "u", "", "", "", req("/login?u=1'"), resp(3000)));

                StateIO.SaveOptions o = new StateIO.SaveOptions();   // defaults: drop audit bodies, no cap
                File f = tmp();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    List<LogEntry> all = back.store().all();
                    Expect.equal(all.size(), 3, "three history entries");
                    LogEntry p = all.get(0), c = all.get(1), a = all.get(2);
                    Expect.equal(p.response().length(), 5000 + p.response().bodyOffset(), "proxy body kept");
                    Expect.isFalse(p.bodyDropped(), "proxy entry is whole");
                    Expect.equal(c.response().length(), 4000 + c.response().bodyOffset(), "crawl body kept");
                    Expect.isTrue(a.bodyDropped(), "audit probe body left out");
                    Expect.equal(a.response().body().length, 0, "audit response is headers only");
                    Expect.equal(a.responseLen(), probe.response().length(), "the full length is remembered");
                    Expect.equal(a.response().statusCode(), 200, "status survives");
                    Expect.equal(a.mimeType(), "HTML", "content type survives");
                    Expect.notNull(a.bodyNote(), "the viewer is told");
                    Expect.equal(a.number(), probe.number(), "numbering unchanged");
                    Issue i = back.issues().get(0);
                    Expect.equal(i.response().body().length, 3000, "the finding keeps its full response");
                    Expect.equal(probe.response().body().length, 3000, "the live entry was never touched");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("keeping audit bodies is a choice", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.store().add(entry("audit", SVC, "/a", 2000));
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.keepAuditBodies = true;
                File f = tmp();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    LogEntry a = back.store().all().get(0);
                    Expect.isFalse(a.bodyDropped(), "body kept when asked");
                    Expect.equal(a.response().body().length, 2000, "whole body");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a body cap cuts large responses and marks them", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.store().add(entry("proxy", SVC, "/big", 10_000));
                ctx.store().add(entry("proxy", SVC, "/small", 100));
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.bodyCapBytes = 1024;
                File f = tmp();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    LogEntry big = back.store().all().get(0), small = back.store().all().get(1);
                    Expect.isTrue(big.bodyTruncated(), "large body cut");
                    Expect.equal(big.response().body().length, 1024, "cut at the cap");
                    Expect.equal(big.responseLen(), 10_000 + big.response().bodyOffset(), "full length remembered");
                    Expect.notNull(big.bodyNote(), "the viewer is told");
                    Expect.isFalse(small.bodyTruncated(), "small body untouched");
                    Expect.equal(small.response().body().length, 100, "whole small body");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        final String unpackSentence = "Response body left compressed (gzip): it decodes to more than 16 MB, the unpack limit.";

        Harness.test("a response left compressed keeps its note through a saved project", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                LogEntry e = entry("proxy", SVC, "/packed", 100);
                e.setUnpackNote(unpackSentence);
                ctx.store().add(e);
                File f = tmp();
                try {
                    StateIO.save(ctx, f, new StateIO.SaveOptions());
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    LogEntry got = back.store().all().get(0);
                    Expect.equal(got.unpackNote(), unpackSentence, "the note is kept");
                    Expect.equal(got.bodyNote(), unpackSentence, "the viewer shows it");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a response left compressed and cut to the cap carries both notes", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                LogEntry e = entry("proxy", SVC, "/packed", 10_000);
                e.setUnpackNote(unpackSentence);
                ctx.store().add(e);
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.bodyCapBytes = 1024;
                File f = tmp();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    String note = back.store().all().get(0).bodyNote();
                    Expect.notNull(note, "a note is there");
                    Expect.isTrue(note.startsWith(unpackSentence + " Response body cut to the project's cap"),
                            "the unpack note comes first, one space, then the cap note");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an entry from a project saved before the unpack note reads as having none", () -> {
            try {
                LogEntry e = entry("proxy", SVC, "/old", 100);
                java.lang.reflect.Field fld = LogEntry.class.getDeclaredField("unpackNote");
                fld.setAccessible(true);
                fld.set(e, null);
                Expect.equal(e.unpackNote(), "", "null reads as empty");
                Expect.isTrue(e.bodyNote() == null, "no note to show");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("a saved copy can keep in-scope items only, some tools and no scan cards", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.scope().includeUrl(SVC, "/", true);
                ctx.store().add(entry("proxy", SVC, "/in", 10));
                ctx.store().add(entry("proxy", OTHER, "/out", 10));
                ctx.store().add(entry("repeater", SVC, "/rep", 10));
                ctx.addIssue(new Issue("In", Issue.Severity.LOW, Issue.Confidence.FIRM, SVC, "/in", null, "", "", "", req("/in"), resp(1)));
                ctx.addIssue(new Issue("Out", Issue.Severity.LOW, Issue.Confidence.FIRM, OTHER, "/out", null, "", "", "", req("/out"), resp(1)));
                ScanTask t = new ScanTask(ScanTask.Type.AUDIT, "Audit", "");
                ctx.addTask(t);

                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.inScopeOnly = true;
                o.tools = java.util.Set.of("proxy");
                o.includeTasks = false;
                File f = tmp();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    List<LogEntry> all = back.store().all();
                    Expect.equal(all.size(), 1, "only the in-scope proxy entry");
                    Expect.equal(all.get(0).pathAndQuery(), "/in", "the right one");
                    Expect.equal(back.issues().size(), 1, "only the in-scope finding");
                    Expect.equal(back.issues().get(0).title(), "In", "the right finding");
                    Expect.equal(back.tasks().size(), 0, "no scan cards");
                } finally { f.delete(); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
