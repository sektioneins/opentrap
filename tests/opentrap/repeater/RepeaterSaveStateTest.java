package opentrap.repeater;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.List;

/** A narrowed project copy keeps only the Repeater tabs aimed at the scope. */
public final class RepeaterSaveStateTest {

    private static RepeaterTool.TabState tab(String host, byte[] req) {
        RepeaterTool.TabState s = new RepeaterTool.TabState();
        s.title = host;
        s.host = host;
        s.port = 443;
        s.secure = true;
        s.req = req;
        return s;
    }

    private static byte[] get(String host) {
        return Bytes.bytes("GET /x HTTP/1.1\r\nHost: " + host + "\r\n\r\n");
    }

    private static Scope scope(String path, boolean wholeHost) {
        Scope s = new Scope();
        s.includeUrl(new HttpService("a.test", 443, true), path, wholeHost);
        return s;
    }

    public void run() {
        Harness.suite("repeater saved state");

        Harness.test("an in-scope copy keeps only the Repeater tabs aimed at the scope", () -> {
            List<RepeaterTool.TabState> tabs = List.of(tab("a.test", get("a.test")), tab("b.test", get("b.test")));
            List<RepeaterTool.TabState> kept = RepeaterTool.kept(tabs, SaveFilter.inScope(scope("/", true)));
            Expect.equal(kept.size(), 1, "tabs kept");
            Expect.equal(kept.get(0).host, "a.test", "host");
        });

        Harness.test("a Repeater tab with no parsable request is judged by its target alone", () -> {
            List<RepeaterTool.TabState> tabs = List.of(
                    tab("a.test", new byte[0]), tab("a.test", "garbage".getBytes()));
            Expect.equal(RepeaterTool.kept(tabs, SaveFilter.inScope(scope("/", true))).size(), 2, "kept in host scope");
            Expect.equal(RepeaterTool.kept(tabs, SaveFilter.inScope(scope("/app", false))).size(), 0, "kept in path scope");
        });

        Harness.test("a full copy keeps every Repeater tab", () -> {
            List<RepeaterTool.TabState> tabs = List.of(tab("a.test", get("a.test")), tab("b.test", get("b.test")));
            Expect.equal(RepeaterTool.kept(tabs, SaveFilter.ALL).size(), 2, "tabs kept");
        });
    }
}
