package opentrap.ui;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** A project copy narrowed to the scope holds no out-of-scope data in any section. */
public final class ProjectCopyScopeTest {

    private static final HttpService IN = new HttpService("a.test", 443, true);
    private static final HttpService OUT = new HttpService("b.test", 443, true);

    /** A tool with a fixed state; the narrowed save is either the default or the given function. */
    private static final class Stub implements ToolModule {
        final AtomicReference<Serializable> received = new AtomicReference<>();
        final Function<SaveFilter, Serializable> narrowed;

        Stub(Function<SaveFilter, Serializable> narrowed) { this.narrowed = narrowed; }

        @Override public String name() { return "stub"; }
        @Override public Tab[] tabs() { return new Tab[0]; }
        @Override public Serializable saveState() { return "secret"; }
        @Override public Serializable saveState(SaveFilter f) {
            return narrowed == null ? ToolModule.super.saveState(f) : narrowed.apply(f);
        }
        @Override public void restoreState(Serializable s) { received.set(s); }
    }

    private static Serializable roundTrip(boolean inScopeOnly, Function<SaveFilter, Serializable> narrowed) throws Exception {
        SuiteContext ctx = new SuiteContext();
        ctx.scope().includeUrl(IN, "/", true);
        ctx.tools().add(new Stub(narrowed));
        StateIO.SaveOptions o = new StateIO.SaveOptions();
        o.inScopeOnly = inScopeOnly;
        File f = Files.createTempFile("opentrap-copyscope", ".otrap").toFile();
        try {
            StateIO.save(ctx, f, o);
            SuiteContext back = new SuiteContext();
            Stub s = new Stub(null);
            back.tools().add(s);
            StateIO.restore(back, f);
            return s.received.get();
        } finally {
            f.delete();
        }
    }

    private static SuiteContext copyAndReopen(SuiteContext ctx, StateIO.SaveOptions o) throws Exception {
        File f = Files.createTempFile("opentrap-copyscope", ".otrap").toFile();
        try {
            StateIO.save(ctx, f, o);
            SuiteContext back = new SuiteContext();
            StateIO.restore(back, f);
            return back;
        } finally {
            f.delete();
        }
    }

    public void run() {
        Harness.suite("in-scope project copies");

        Harness.test("an in-scope copy leaves out the state of a tool that cannot filter it", () -> {
            try {
                Expect.isTrue(roundTrip(true, null) == null, "nothing restored");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a full copy keeps every tool's state", () -> {
            try {
                Expect.equal(roundTrip(false, null), "secret", "state kept");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a tool that filters its own state is handed the copy's scope", () -> {
            try {
                Expect.equal(roundTrip(true, f -> f.keeps(OUT, "/") ? "out" : "in"), "in", "scope applied");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("SaveFilter treats a missing target as out of scope and an empty path as the root", () -> {
            Scope scope = new Scope();
            scope.includeUrl(IN, "/", true);
            SaveFilter f = SaveFilter.inScope(scope);
            Expect.isFalse(f.keeps(null, "/"), "null service");
            Expect.isTrue(f.keeps(IN, ""), "empty path");
            Expect.isTrue(f.keeps(IN, null), "null path");
            Expect.isFalse(f.keeps(OUT, "/"), "other host");
            Expect.isTrue(SaveFilter.ALL.keeps(null, null), "ALL keeps everything");
        });

        Harness.test("an in-scope copy keeps the WebSocket frames of in-scope connections only", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.scope().includeUrl(IN, "/", true);
                WebSocketMessage.Direction d = WebSocketMessage.Direction.C2S;
                ctx.wsStore().add(new WebSocketMessage(1, IN, "/ws", d, WebSocketMessage.Op.TEXT, "in".getBytes(), false));
                ctx.wsStore().add(new WebSocketMessage(2, OUT, "/ws", d, WebSocketMessage.Op.TEXT, "out".getBytes(), false));
                ctx.wsStore().add(new WebSocketMessage(3, "a.test", d, WebSocketMessage.Op.TEXT, "unknown".getBytes(), false));
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.inScopeOnly = true;
                SuiteContext back = copyAndReopen(ctx, o);
                var frames = back.wsStore().all();
                Expect.equal(frames.size(), 1, "one frame");
                Expect.equal(frames.get(0).connectionId, 1, "the in-scope frame");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a WebSocket frame keeps its connection's target through a save and reopen", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.wsStore().add(new WebSocketMessage(1, IN, "/ws", WebSocketMessage.Direction.S2C,
                        WebSocketMessage.Op.TEXT, "x".getBytes(), false));
                SuiteContext back = copyAndReopen(ctx, new StateIO.SaveOptions());
                WebSocketMessage m = back.wsStore().all().get(0);
                Expect.equal(m.service, IN, "service");
                Expect.equal(m.path, "/ws", "path");
                Expect.equal(m.host, "a.test", "host");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an in-scope copy holds neither a WebSocket frame nor a cookie of an out-of-scope host", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.scope().includeUrl(IN, "/", true);
                ctx.store().add(new LogEntry("proxy", IN, new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: a.test\r\n\r\n"))));
                ctx.store().add(new LogEntry("proxy", OUT, new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: b.test\r\n\r\n"))));
                ctx.wsStore().add(new WebSocketMessage(2, OUT, "/ws", WebSocketMessage.Direction.C2S,
                        WebSocketMessage.Op.TEXT, "out".getBytes(), false));
                ctx.sessions().jar().set(new opentrap.session.Cookie("sid", "s3cr3t", "b.test", "/", false, 0));
                ctx.sessions().jar().set(new opentrap.session.Cookie("sid", "mine", "a.test", "/", false, 0));
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.inScopeOnly = true;
                File f = Files.createTempFile("opentrap-copyscope", ".otrap").toFile();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    Expect.equal(back.wsStore().all().size(), 0, "no frames");
                    var cookies = back.sessions().jar().all();
                    Expect.equal(cookies.size(), 1, "one cookie");
                    Expect.equal(cookies.get(0).domain, "a.test", "the in-scope cookie");
                    String bytes = new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1);
                    Expect.isFalse(bytes.contains("s3cr3t"), "the secret is not in the file");
                } finally {
                    f.delete();
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an in-scope copy leaves out the scan cards even when they are asked for", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.scope().includeUrl(IN, "/", true);
                ctx.addTask(new ScanTask(ScanTask.Type.AUDIT, "Audit", ""));
                StateIO.SaveOptions o = new StateIO.SaveOptions();
                o.inScopeOnly = true;
                o.includeTasks = true;
                File f = Files.createTempFile("opentrap-copyscope", ".otrap").toFile();
                try {
                    StateIO.save(ctx, f, o);
                    SuiteContext back = new SuiteContext();
                    StateIO.restore(back, f);
                    Expect.equal(back.tasks().size(), 0, "no scan cards");
                } finally {
                    f.delete();
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an in-scope copy aimed at the open project file is refused and the project keeps everything", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                ctx.scope().includeUrl(IN, "/", true);
                File f = Files.createTempFile("opentrap-copyscope", ".otrap").toFile();
                f.delete();   // bindNew creates it fresh
                try {
                    StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
                    ctx.store().add(new LogEntry("proxy", IN, new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: a.test\r\n\r\n"))));
                    ctx.store().add(new LogEntry("proxy", OUT, new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: b.test\r\n\r\n"))));
                    ctx.sessions().jar().set(new opentrap.session.Cookie("sid", "s3cr3t", "b.test", "/", false, 0));
                    StateIO.save(ctx, f);   // the project's own snapshot, with the cookie
                    StateIO.SaveOptions o = new StateIO.SaveOptions();
                    o.inScopeOnly = true;
                    boolean refused = false;
                    try { StateIO.save(ctx, f, o); } catch (java.io.IOException _) { refused = true; }
                    Expect.isTrue(refused, "the narrowed save is refused");
                    ctx.store().closeStore();
                    SuiteContext back = new SuiteContext();
                    Expect.equal(StateIO.restore(back, f), 2, "both entries are still in the project");
                    Expect.equal(back.sessions().jar().all().size(), 1, "the out-of-scope cookie is still in the project");
                    back.store().closeStore();
                } finally {
                    f.delete();
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
