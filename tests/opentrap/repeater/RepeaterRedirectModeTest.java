package opentrap.repeater;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/** Repeater's redirect modes decide what the client follows and the status says why it stopped. */
public final class RepeaterRedirectModeTest {

    private static final HttpService A = new HttpService("a.test", 443, true);
    private static final HttpService B = new HttpService("b.test", 443, true);

    private static HttpMessage req(String path) {
        return new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: a.test\r\n\r\n"));
    }

    private static HttpClient.Options applied(String mode, Scope scope) {
        HttpClient.Options opt = new HttpClient.Options();
        RepeaterTool.applyFollowMode(opt, mode, scope);
        return opt;
    }

    public void run() {
        Harness.suite("repeater redirect modes");

        Harness.test("a tab with no mode of its own follows the global setting", () -> {
            Expect.equal(RepeaterTool.followMode(null, true), RepeaterTool.FOLLOW_ALWAYS, "global on");
            Expect.equal(RepeaterTool.followMode(null, false), RepeaterTool.FOLLOW_NEVER, "global off");
            Expect.equal(RepeaterTool.followMode(RepeaterTool.FOLLOW_ON_SITE, false),
                    RepeaterTool.FOLLOW_ON_SITE, "tab mode wins");
        });

        Harness.test("each mode sets following and the matching policy", () -> {
            Scope scope = new Scope();
            scope.includeUrl(A, "/", true);

            Expect.isFalse(applied(RepeaterTool.FOLLOW_NEVER, scope).followRedirects, "never follows");

            HttpClient.Options always = applied(RepeaterTool.FOLLOW_ALWAYS, scope);
            Expect.isTrue(always.followRedirects, "always follows");
            Expect.isNull(always.redirectPolicy, "always has no policy");

            HttpClient.Options onSite = applied(RepeaterTool.FOLLOW_ON_SITE, scope);
            Expect.isTrue(onSite.followRedirects, "on-site follows");
            Expect.equal(onSite.redirectPolicy.refusal(A, B, req("/x")), "off-site", "other host refused");
            Expect.isNull(onSite.redirectPolicy.refusal(A, A, req("/x")), "same origin allowed");

            HttpClient.Options inScope = applied(RepeaterTool.FOLLOW_IN_SCOPE, scope);
            Expect.isTrue(inScope.followRedirects, "in-scope follows");
            Expect.isNull(inScope.redirectPolicy.refusal(A, A, req("/x")), "in scope allowed");
            Expect.equal(inScope.redirectPolicy.refusal(A, B, req("/x")), "out of scope", "out of scope refused");

            Expect.isFalse(applied("Sometimes", scope).followRedirects, "unknown mode counts as never");
        });

        Harness.test("the status names followed redirects and the reason a redirect was not followed", () -> {
            Expect.equal(RepeaterTool.redirectNote(0, ""), "", "nothing to say");
            Expect.equal(RepeaterTool.redirectNote(1, ""), " (followed 1 redirect)", "singular");
            Expect.equal(RepeaterTool.redirectNote(2, ""), " (followed 2 redirects)", "plural");
            Expect.equal(RepeaterTool.redirectNote(0, "off-site"),
                    " (redirect not followed: off-site)", "refused at once");
            Expect.equal(RepeaterTool.redirectNote(1, "out of scope"),
                    " (followed 1 redirect, next not followed: out of scope)", "refused after a hop");
        });
    }
}
