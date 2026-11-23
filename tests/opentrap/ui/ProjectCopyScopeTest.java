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
    }
}
