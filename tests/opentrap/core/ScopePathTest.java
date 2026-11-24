package opentrap.core;

import java.util.List;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Scope reads a path the way the server does: dot segments removed and
 * escapes decoded. An include rule must hold for every reading, an exclude
 * rule for any.
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

        Harness.test("a path has its raw, canonical and decoded forms, without repeats", () -> {
            Expect.equal(ScopePath.forms("/plain"), List.of("/plain"), "a plain path");
            Expect.equal(ScopePath.forms("/app/%2e%2e/admin"), List.of("/app/%2e%2e/admin", "/admin"), "encoded dots");
            Expect.equal(ScopePath.forms("/%6cogout"), List.of("/%6cogout", "/logout"), "an encoded letter");
            Expect.equal(ScopePath.forms("/app/..%2fadmin"), List.of("/app/..%2fadmin", "/admin"), "an encoded slash only in the decoded form");
            Expect.equal(ScopePath.forms("/a?x=%6c/../"), List.of("/a?x=%6c/../", "/a?x=l/../"), "the query is decoded but not normalised");
            Expect.equal(ScopePath.forms("/a%3f/../b"), List.of("/a%3f/../b", "/b"), "an encoded question mark stays in the path");
            Expect.equal(ScopePath.forms("/caf%C3%A9"), List.of("/caf%C3%A9", "/café"), "UTF-8");
            Expect.equal(ScopePath.forms("/a%zz%4"), List.of("/a%zz%4"), "invalid escapes stay");
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

        Harness.test("an exclude matches any form of the path", () -> {
            Scope s = new Scope();   // default excludes: logout, logoff, exit, signout
            s.include().add(Scope.Rule.prefix("https://a.test", false));
            HttpService a = new HttpService("a.test", 443, true);
            Expect.isFalse(s.isInScope(a, "/%6cogout"), "an encoded letter");
            Expect.isFalse(s.isInScope(a, "/app/%2E%2E/logout"), "encoded dots, upper case");
            Expect.isTrue(s.isInScope(a, "/login"), "a path no exclude matches");
        });

        Harness.test("an include must match every form, so an escaped prefix character is out of scope", () -> {
            Scope s = new Scope();
            s.include().add(Scope.Rule.prefix("https://a.test/app", false));
            Expect.isFalse(s.isInScope(new HttpService("a.test", 443, true), "/%61pp/x"),
                    "the raw form does not start with /app");
        });
    }
}
