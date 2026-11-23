package opentrap.core;

import java.util.List;
import java.util.regex.Pattern;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * The scope handed to the scan engine is a set of whole-URL regexes built from
 * the suite's rules. The engine matches them with {@code matches()} over the
 * full URL, after cutting it at the first {@code ?}, so that is what is
 * checked here: a URL the rule covers must match, and one it does not must
 * not. The reference is the rule's own in-app check.
 */
public final class ScopeUrlRegexTest {

    private static boolean hits(Scope.Rule r, String url) {
        return engineMatches(r.includeRegex(), url);
    }

    private static boolean excludeHits(Scope.Rule r, String url) {
        return engineMatches(r.excludeRegex(), url);
    }

    private static boolean engineMatches(String re, String url) {
        int q = url.indexOf('?');
        String cut = q < 0 ? url : url.substring(0, q);
        return re != null && Pattern.compile(re).matcher(cut).matches();
    }

    /** What the suite itself decides for the URL. */
    private static boolean inApp(Scope.Rule r, String url) {
        String path = java.net.URI.create(url).getRawPath();
        return r.matches(HttpService.parse(url), path == null || path.isEmpty() ? "/" : path);
    }

    private static boolean hasUserinfo(String url) {
        return java.net.URI.create(url).getRawUserInfo() != null;
    }

    private static Scope.Rule host(String host) { return new Scope.Rule("any", host, "", ""); }

    private static final List<String> CORPUS = List.of(
            "http://other.test/x.example.com",
            "http://other.test/.example.com/",
            "http://a.example.com/",
            "https://a.example.com:8443/x",
            "http://example.com/",
            "http://example.com",
            "http://example.com/admin/users",
            "http://example.com/x/admin",
            "http://a.example.com@evil.test/",
            "http://www.example.com.evil/",
            "http://y.com/",
            "http://evil.test/a.com",
            "http://b.test/",
            "http://c.test/b.test",
            "https://h.test:8443/",
            "https://h.test/",
            "http://h.test/",
            "http://h.test:80/",
            "http://h.test:8/x",
            "http://h.test:81/admin",
            "http://h.test/x?q=/admin");

    public void run() {
        Harness.suite("Scope rules as engine URL regexes");

        Harness.test("a host prefix covers the host on either default port, not other hosts", () -> {
            Scope.Rule r = Scope.Rule.prefix("http://testaspnet.vulnweb.com", false);
            Expect.isTrue(hits(r, "http://testaspnet.vulnweb.com/"), "the root");
            Expect.isTrue(hits(r, "http://testaspnet.vulnweb.com/ReadNews.aspx?id=2"), "a page with a query");
            Expect.isTrue(hits(r, "http://testaspnet.vulnweb.com:80/x"), "the default port spelled out");
            Expect.isFalse(hits(r, "https://testaspnet.vulnweb.com/"), "another scheme");
            Expect.isFalse(hits(r, "http://example.com/"), "another host");
            Expect.isFalse(hits(r, "http://testaspnet.vulnweb.com.evil.example/"), "a host that starts the same");
            Expect.isFalse(hits(r, "http://www.testaspnet.vulnweb.com/"), "a subdomain when not asked for");
        });

        Harness.test("include subdomains admits them and a path prefix stays a prefix", () -> {
            Scope.Rule r = Scope.Rule.prefix("https://example.com/app", true);
            Expect.isTrue(hits(r, "https://example.com/app"), "the prefix itself");
            Expect.isTrue(hits(r, "https://example.com/app/login"), "below it");
            Expect.isTrue(hits(r, "https://example.com/application"), "a plain string prefix");
            Expect.isTrue(hits(r, "https://api.example.com/app/x"), "a subdomain");
            Expect.isFalse(hits(r, "https://example.com/admin"), "another path");
            Expect.isFalse(hits(r, "https://example.com/"), "the root");
        });

        Harness.test("a scheme-less host prefix takes any scheme and port", () -> {
            Scope s = new Scope();
            s.includeHost(HttpService.parse("https://shop.example:8443/"));
            Scope.Rule r = s.include().get(0);
            Expect.isTrue(hits(r, "https://shop.example:8443/cart"), "the port it was seen on");
            Expect.isTrue(hits(r, "http://shop.example/"), "plain http");
            Expect.isFalse(hits(r, "http://shop.example.evil/"), "another host");
        });

        Harness.test("the default logout excludes match anywhere in the path, on any host", () -> {
            Scope s = new Scope();
            Scope.Rule logout = s.exclude().get(0);
            Expect.isTrue(hits(logout, "http://testaspnet.vulnweb.com/logout.aspx"), "a logout page");
            Expect.isTrue(hits(logout, "https://a.example/account/Logout?next=/"), "deeper, other case, with a query");
            Expect.isFalse(hits(logout, "http://testaspnet.vulnweb.com/login.aspx"), "a login page");
            Expect.isFalse(hits(logout, "http://logout.example/"), "the word in the host only");
        });

        Harness.test("an advanced rule keeps a literal host and an anchored file pattern", () -> {
            Scope.Rule r = new Scope.Rule("https", "^\\Qexample.com\\E$", "", "^/admin");
            Expect.isTrue(hits(r, "https://example.com/admin/users"), "under the path");
            Expect.isFalse(hits(r, "https://example.com/x/admin"), "the path elsewhere, anchored");
            Expect.isFalse(hits(r, "http://example.com/admin"), "the wrong scheme");
            Expect.isFalse(hits(r, "https://other.example/admin"), "another host");
        });

        Harness.test("a prefix with a non-default port does not admit the default port", () -> {
            Scope.Rule r = Scope.Rule.prefix("https://example.com:8443", false);
            Expect.isTrue(hits(r, "https://example.com:8443/x"), "the port itself");
            Expect.isFalse(hits(r, "https://example.com/x"), "the default port, left out");
            Expect.isFalse(hits(r, "https://example.com:443/x"), "the default port, spelled out");
        });

        Harness.test("a subdomain prefix cannot be satisfied by a fragment or a backslash", () -> {
            Scope.Rule r = Scope.Rule.prefix("https://example.com", true);
            Expect.isFalse(hits(r, "https://evil.test#.example.com/"), "a fragment");
            Expect.isFalse(hits(r, "https://evil.test\\.example.com/"), "a backslash");
            Expect.isTrue(hits(r, "https://a.b.example.com/"), "a real subdomain");
        });

        Harness.test("a disabled rule yields nothing", () -> {
            Scope.Rule r = Scope.Rule.prefix("https://example.com", false);
            r.enabled = false;
            Expect.isNull(r.includeRegex(), "include regex of a disabled rule");
            Expect.isNull(r.excludeRegex(), "exclude regex of a disabled rule");
        });

        Harness.test("an advanced host pattern cannot reach into the path", () -> {
            Scope.Rule r = host(".*\\.example\\.com");
            Expect.isFalse(hits(r, "http://other.test/x.example.com"), "the pattern in the path");
            Expect.isFalse(hits(r, "http://other.test/.example.com/"), "a path segment");
            Expect.isTrue(hits(r, "http://a.example.com/"), "a real subdomain");
        });

        Harness.test("a host pattern cannot match through userinfo", () -> {
            Expect.isFalse(hits(host("example\\.com"), "http://a.example.com@evil.test/"), "userinfo");
        });

        Harness.test("the engine regex of every advanced rule agrees with the in-app check", () -> {
            String h4 = "^\\Qexample.com\\E$";
            List<Scope.Rule> rules = List.of(
                    host(".*\\.example\\.com"),
                    host("example\\.com"),
                    host("(^|\\.)\\Qexample.com\\E$"),
                    host(h4),
                    host("[^x]+\\.com"),
                    host("^(?:a|b)\\.test$"),
                    host("exa.ple"),
                    host("example("),
                    new Scope.Rule("any", "", "^8443$", ""),
                    new Scope.Rule("any", "", "8.", ""),
                    new Scope.Rule("any", "", "^443$", ""),
                    new Scope.Rule("any", h4, "", "^/admin"),
                    new Scope.Rule("any", "", "", "admin$"),
                    new Scope.Rule("any", h4, "", "^/$"));
            for (Scope.Rule r : rules) {
                for (String u : CORPUS) {
                    // No engine regex matches a URL with userinfo, as "@" ends
                    // the host. That is safe: no include scans such a URL, so
                    // no exclude has to match it.
                    boolean want = inApp(r, u) && !hasUserinfo(u);
                    Expect.equal(hits(r, u), want, r.describe() + " on " + u);
                    Expect.equal(excludeHits(r, u), want, "exclude " + r.describe() + " on " + u);
                }
                // An exclude with a host pattern also covers every bracketed
                // host, so on an IPv6 literal it is only a superset.
                String v6 = "http://[::1]:8080/logout";
                Expect.equal(hits(r, v6), inApp(r, v6), r.describe() + " on " + v6);
                if (inApp(r, v6)) Expect.isTrue(excludeHits(r, v6), "exclude " + r.describe() + " on " + v6);
            }
        });

        Harness.test("the default logout exclude matches an IPv6-literal host", () -> {
            Scope.Rule logout = new Scope().exclude().get(0);
            Expect.isTrue(excludeHits(logout, "http://[::1]:8080/logout"), "the logout page");
        });

        Harness.test("an exclude with an anchored file matches an IPv6-literal host", () -> {
            Scope.Rule r = new Scope.Rule("any", "", "", "^/logout");
            Expect.isTrue(excludeHits(r, "http://[::1]:8080/logout"), "the logout page");
        });

        Harness.test("an exclude with a host pattern also matches an IPv6-literal host, as it is wider by design", () -> {
            Scope.Rule r = new Scope.Rule("any", "^\\Qexample.com\\E$", "", "^/logout");
            Expect.isTrue(excludeHits(r, "http://[::1]:8080/logout"), "a bracketed host");
            Expect.isFalse(excludeHits(r, "http://other.test/logout"), "another named host");
        });

        Harness.test("an include with a blank host matches an IPv6-literal host", () -> {
            Scope.Rule r = new Scope.Rule("any", "", "", "^/app");
            Expect.isTrue(hits(r, "http://[::1]:8080/app/x"), "under the path");
            Expect.isFalse(hits(r, "http://[::1]:8080/other"), "another path");
        });

        Harness.test("an include with a host pattern does not match an IPv6-literal host", () -> {
            Expect.isFalse(hits(host("^\\Qexample.com\\E$"), "http://[::1]:8080/"), "a bracketed host");
        });

        Harness.test("a prefix include on an IPv6-literal host still matches below it", () -> {
            Expect.isTrue(hits(Scope.Rule.prefix("http://[::1]:8080", false), "http://[::1]:8080/x"), "a page");
        });

        Harness.test("a pattern the engine cannot match exactly is left out of includes and widened in excludes", () -> {
            Scope.Rule spaced = host("(?x) example \\. com");
            Expect.isNull(spaced.includeRegex(), "include regex of a comments-mode host");
            Expect.isFalse(spaced.engineExact(), "engine exact");
            for (String u : CORPUS) {
                if (inApp(spaced, u)) Expect.isTrue(excludeHits(spaced, u), "exclude covers " + u);
            }
            Expect.isTrue(excludeHits(spaced, "http://y.com/"), "the host widened to any host");

            Scope.Rule bounded = new Scope.Rule("any", "^\\Qexample.com\\E$", "", "\\badmin");
            Expect.isNull(bounded.includeRegex(), "include regex of a word-boundary file");
            Expect.isTrue(excludeHits(bounded, "http://example.com/anything"), "the path widened");
            Expect.isFalse(excludeHits(bounded, "http://other.test/admin"), "the host still holds");
        });

        Harness.test("a path anchor holds only at the start of the path, not after a double slash", () -> {
            Scope.Rule r = new Scope.Rule("any", "", "", "^/admin");
            for (String u : List.of("http://h.test/x//y/admin", "http://h.test//admin", "http://h.test/admin/x")) {
                Expect.equal(hits(r, u), inApp(r, u), "include on " + u);
                Expect.equal(excludeHits(r, u), inApp(r, u), "exclude on " + u);
            }
            Expect.isFalse(hits(r, "http://h.test/x//y/admin"), "after a double slash in the path");
        });

        Harness.test("a quantifier after a quoted run applies to its last character only", () -> {
            Scope.Rule h = host("\\Qa/\\E*");
            Scope.Rule p = new Scope.Rule("any", "", "\\Q8a\\E?", "");
            for (String u : CORPUS) {
                if (hasUserinfo(u)) continue;   // no include matches userinfo
                Expect.equal(hits(h, u), inApp(h, u), h.describe() + " on " + u);
                Expect.equal(hits(p, u), inApp(p, u), p.describe() + " on " + u);
            }
            Expect.isTrue(hits(h, "http://a.example.com/"), "a host with an a");
            Expect.isFalse(hits(p, "https://h.test/"), "port 443 has no 8");
        });

        Harness.test("a pattern that refers to a group is left out of includes", () -> {
            Expect.notNull(ScopeRegex.confine("(a)\\1", ScopeRegex.Segment.HOST), "a backreference confines alone");
            Expect.isNull(host("(a)\\1").includeRegex(), "a backreference, repeated per scheme");
            Expect.isNull(host("(?<n>a)").includeRegex(), "a named group, repeated per scheme");
            Expect.notNull(host("(a)\\.test").includeRegex(), "a plain group");
        });

        Harness.test("a lookbehind in the path pattern is refused, as it would see the host", () -> {
            Expect.isNull(ScopeRegex.confine("(?<![a-z])/admin", ScopeRegex.Segment.PATH), "a lookbehind in the path");
            Expect.notNull(ScopeRegex.confine("(?<![a-z])api", ScopeRegex.Segment.HOST), "a lookbehind in the host");
        });

        Harness.test("the rewriter refuses syntax it cannot confine", () -> {
            for (ScopeRegex.Segment seg : ScopeRegex.Segment.values()) {
                Expect.isNull(ScopeRegex.confine("(?x)a", seg), "comments mode in " + seg);
                Expect.isNull(ScopeRegex.confine("\\Ga", seg), "\\G in " + seg);
            }
            Expect.isNull(ScopeRegex.confine("\\badmin", ScopeRegex.Segment.PATH), "a word boundary in the path");
            Expect.notNull(ScopeRegex.confine("\\bapi\\b", ScopeRegex.Segment.HOST), "a word boundary in the host");
        });
    }
}
