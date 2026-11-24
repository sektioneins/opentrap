package opentrap.core;

import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Scope reads a path the way the server does: dot segments removed and
 * escapes decoded. Each reading is compared with the rule in the same form;
 * an include rule must hold for the raw and canonical readings (a prefix also
 * the decoded one), an exclude rule for any.
 */
public final class ScopePathTest {

    public void run() {
        Harness.suite("scope path forms");

        Harness.test("dot segments are removed as RFC 3986 says", () -> {
            Expect.equal(ScopePath.removeDotSegments("/a/b/c/./../../g"), "/a/g", "the RFC example");
            Expect.equal(ScopePath.removeDotSegments("/app/../admin"), "/admin", "up one");
            Expect.equal(ScopePath.removeDotSegments("/app/.."), "/", "up to the root");
            Expect.equal(ScopePath.removeDotSegments("/./x"), "/x", "a single dot");
            Expect.equal(ScopePath.removeDotSegments("/../x"), "/x", "above the root");
            Expect.equal(ScopePath.removeDotSegments("/app/"), "/app/", "a trailing slash stays");
            Expect.equal(ScopePath.removeDotSegments(""), "", "empty");
        });

        Harness.test("a path has a canonical and a decoded form", () -> {
            Expect.equal(ScopePath.canonical("/plain"), "/plain", "a plain path, canonical");
            Expect.equal(ScopePath.decoded("/plain"), "/plain", "a plain path, decoded");
            Expect.isNull(ScopePath.canonical(null), "null stays null");
            Expect.equal(ScopePath.canonical("/app/%2e%2e/admin"), "/admin", "encoded dots");
            Expect.equal(ScopePath.decoded("/app/%2e%2e/admin"), "/admin", "encoded dots, decoded");
            Expect.equal(ScopePath.canonical("/%6cogout"), "/logout", "an encoded letter");
            Expect.equal(ScopePath.canonical("/app/..%2fadmin"), "/app/..%2fadmin", "an encoded slash stays in the canonical form");
            Expect.equal(ScopePath.decoded("/app/..%2fadmin"), "/admin", "an encoded slash is decoded");
            Expect.equal(ScopePath.canonical("/a?x=%6c/../"), "/a?x=l/../", "the query is decoded but not normalised");
            Expect.equal(ScopePath.canonical("/a%3f/../b"), "/b", "an encoded question mark stays in the path");
            Expect.equal(ScopePath.canonical("/my%20docs"), "/my%20docs", "a space stays escaped in the canonical form");
            Expect.equal(ScopePath.decoded("/caf%C3%A9"), "/café", "UTF-8");
            Expect.equal(ScopePath.canonical("/a%zz%4"), "/a%zz%4", "invalid escapes stay");
            Expect.equal(ScopePath.decoded("/a%zz%4"), "/a%zz%4", "invalid escapes stay, decoded");
            Expect.equal(ScopePath.decoded("/a%\uff11\uff11"), "/a%\uff11\uff11", "a fullwidth digit is no hex digit");
        });

        Harness.test("a path prefix does not admit a path that leaves it through dot segments or escapes", () -> {
            Scope s = new Scope();
            s.include().add(Scope.Rule.prefix("https://a.test/app", false));
            HttpService a = new HttpService("a.test", 443, true);
            Expect.isFalse(s.isInScope(a, "/app/../admin"), "dot segments");
            Expect.isFalse(s.isInScope(a, "/app/%2e%2e/admin"), "encoded dots");
            Expect.isFalse(s.isInScope(a, "/app/..%2fadmin"), "an encoded slash");
            Expect.isTrue(s.isInScope(a, "/app/./login"), "a dot segment that stays inside");
            Expect.isTrue(s.isInScope(a, "/app/login"), "a plain path");
        });

        Harness.test("a path in scope as sent is kept even when its normalised form leaves the scope", () -> {
            Scope s = new Scope();   // default excludes: logout, logoff, exit, signout
            s.include().add(Scope.Rule.prefix("https://a.test/app", false));
            HttpService a = new HttpService("a.test", 443, true);
            for (String p : List.of("/app/../admin", "/app/%2e%2e/admin", "/app/%6cogout")) {
                Expect.isTrue(s.isInScopeAsSent(a, p), p + " as sent");
                Expect.isFalse(s.isInScope(a, p), p + " normalised");
            }
            Expect.isFalse(s.isInScopeAsSent(a, "/admin"), "outside as sent");
            Expect.isFalse(s.isInScope(a, "/admin"), "outside normalised");
            Expect.isFalse(s.isInScopeAsSent(a, "/app/logout"), "a raw exclude still applies");
            Expect.isFalse(s.isInScopeAsSent(null, "/app"), "no service");
        });

        Harness.test("an exclude matches any form of the path", () -> {
            Scope s = new Scope();   // default excludes: logout, logoff, exit, signout
            s.include().add(Scope.Rule.prefix("https://a.test", false));
            HttpService a = new HttpService("a.test", 443, true);
            Expect.isFalse(s.isInScope(a, "/%6cogout"), "an encoded letter");
            Expect.isFalse(s.isInScope(a, "/%4cOGOUT"), "an encoded letter, upper case");
            Expect.isTrue(s.isInScope(a, "/login"), "a path no exclude matches");
        });

        Harness.test("an include prefix written plainly does not cover an escaped spelling of it", () -> {
            Scope s = new Scope();
            s.include().add(Scope.Rule.prefix("https://a.test/app", false));
            Expect.isFalse(s.isInScope(new HttpService("a.test", 443, true), "/%61pp/x"),
                    "the raw form does not start with /app");
        });

        Harness.test("an escaped prefix covers the paths under it", () -> {
            HttpService a = new HttpService("a.test", 443, true);
            Scope s = new Scope();
            s.include().add(Scope.Rule.prefix("https://a.test/my%20docs", false));
            Expect.isTrue(s.isInScope(a, "/my%20docs/x"), "a space");
            Scope t = new Scope();
            t.include().add(Scope.Rule.prefix("https://a.test/caf%C3%A9", false));
            Expect.isTrue(t.isInScope(a, "/caf%C3%A9/menu"), "UTF-8");
        });

        Harness.test("a regex include written with an escape still matches", () -> {
            Scope s = new Scope();
            s.include().add(new Scope.Rule("any", "", "", "^/my%20docs"));
            Expect.isTrue(s.isInScope(new HttpService("a.test", 443, true), "/my%20docs/x"), "as sent");
        });

        Harness.test("an escaped exclude prefix also holds for another spelling", () -> {
            Scope s = new Scope();
            s.include().add(Scope.Rule.prefix("https://a.test", false));
            s.exclude().add(Scope.Rule.prefix("https://a.test/my%20docs", false));
            HttpService a = new HttpService("a.test", 443, true);
            Expect.isFalse(s.isInScope(a, "/my%20docs/x"), "the spelling of the rule");
            Expect.isFalse(s.isInScope(a, "/my%20doc%73/x"), "another spelling");
            Expect.isTrue(s.isInScope(a, "/other"), "an unrelated path");
        });

        Harness.test("a very long path is judged in linear time", () -> {
            HttpService a = new HttpService("a.test", 443, true);
            Scope host = new Scope();
            host.include().add(Scope.Rule.prefix("https://a.test/", false));
            Scope app = new Scope();
            app.include().add(Scope.Rule.prefix("https://a.test/a", false));
            long t0 = System.nanoTime();
            Expect.isTrue(host.isInScope(a, "/".repeat(1_000_000) + "x.js"), "many slashes");
            Expect.isTrue(host.isInScope(a, "/a".repeat(500_000)), "many segments");
            Expect.isFalse(app.isInScope(a, "/a".repeat(250_000) + "/%2e%2e".repeat(250_000) + "/b"), "forms built, leaves the prefix");
            Expect.isTrue(app.isInScope(a, "/a".repeat(250_000) + "/%2e%2e"), "forms built, stays inside");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Expect.isTrue(ms < 2000, "took " + ms + " ms");
        });
    }
}
