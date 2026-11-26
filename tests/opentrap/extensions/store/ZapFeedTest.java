package opentrap.extensions.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.ZapFixtures;

/** ZAP's add-on feed: what an entry carries, and which entries are left out and named. */
public final class ZapFeedTest {

    private static final String HASH = "a".repeat(64);
    private static final String URL = "https://github.com/zaproxy/zap-extensions/releases/download/x/x.zap";
    private static final Version ENGINE = Version.parse("2.17.0");
    private static final Source SRC = Source.zapFeed("2.17", true);

    private static Listing parse(String xml) throws IOException {
        return ZapFeed.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), SRC, ENGINE);
    }

    private static Listing listing0(String xml, Source src) {
        try {
            return ZapFeed.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), src, ENGINE);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** The feed's entries for tests whose document is meant to be readable. */
    private static Listing listing(String xml) {
        try { return parse(xml); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String spider() {
        return ZapFixtures.addon("spider", "0.20.0", "release", URL, "SHA-256:" + HASH,
                "commonlib", ">= 1.29.0 & < 2.0.0", "database", "");
    }

    private static void rejectedAs(String block, String why) {
        Listing l = listing(ZapFixtures.feed(block));
        Expect.equal(l.entries().size(), 0, why + ": no entry");
        Expect.equal(l.rejected().size(), 1, why + ": one rejected line");
        Expect.isTrue(l.rejected().get(0).startsWith("spider: "), why + ": line names the id: " + l.rejected().get(0));
    }

    /** Like {@link #rejectedAs}, and the line says the download is outside the source's prefix. */
    private static void rejectedOutside(String block, Source src, String why) {
        Listing l = listing0(ZapFixtures.feed(block), src);
        Expect.equal(l.entries().size(), 0, why + ": no entry");
        Expect.equal(l.rejected().size(), 1, why + ": one rejected line");
        Expect.isTrue(l.rejected().get(0).startsWith("spider: ") && l.rejected().get(0).contains("download is outside"),
                why + ": line names the id and the cause: " + l.rejected().get(0));
    }

    public void run() {
        Harness.suite("Extension store feed");

        Harness.test("a feed entry carries the fields the store shows", () -> {
            Listing l = listing(ZapFixtures.feed(spider()));
            Expect.equal(l.rejected().size(), 0, "nothing rejected");
            Expect.equal(l.entries().size(), 1, "one entry");
            Entry e = l.entries().get(0);
            Expect.equal(e.kind(), Kind.ZAP_ADDON, "kind");
            Expect.equal(e.sourceId(), "zap", "source id");
            Expect.equal(e.id(), "spider", "id");
            Expect.equal(e.name(), "Add-on spider", "name");
            Expect.equal(e.version(), Version.parse("0.20.0"), "version");
            Expect.equal(e.status(), Status.RELEASE, "status");
            Expect.equal(e.size(), 1201993L, "size");
            Expect.equal(e.infoUrl(), "https://www.zaproxy.org/docs/desktop/addons/spider/", "info URL");
            Expect.equal(e.file(), "spider-release-0.20.0.zap", "file");
            Expect.equal(e.location(), URL, "location");
            Expect.equal(e.sha256(), HASH, "hash");
            Expect.equal(e.dependencies().size(), 2, "dependencies");
            Dependency lib = e.dependencies().get(0);
            Expect.equal(lib.id(), "commonlib", "first dependency");
            Expect.isTrue(lib.range().matches(Version.parse("1.30.0")), "commonlib range takes 1.30.0");
            Expect.isFalse(lib.range().matches(Version.parse("2.0.0")), "commonlib range refuses 2.0.0");
            Expect.isTrue(e.dependencies().get(1).range().matches(Version.parse("99")), "no range takes any");
        });

        Harness.test("changes are plain text, not HTML", () -> {
            Entry e = listing(ZapFixtures.feed(spider())).entries().get(0);
            Expect.isTrue(e.changes().contains("Added"), "heading text kept: " + e.changes());
            Expect.isTrue(e.changes().contains("- Access"), "list item as a dash line: " + e.changes());
            Expect.isFalse(e.changes().contains("<"), "no markup: " + e.changes());
        });

        Harness.test("a download outside the source's prefix is left out and named", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", "https://example.com/x.zap", "SHA-256:" + HASH),
                        "foreign host"));

        Harness.test("a download that leaves the prefix through dot segments or escapes is left out and named", () -> {
            for (String url : new String[] {
                    "https://github.com/zaproxy/../someone/repo/releases/download/v1/x.zap",
                    "https://github.com/zaproxy/%2e%2e/someone/x.zap",
                    "https://github.com/zaproxy/%2E%2e/someone/x.zap",
                    "https://github.com/zaproxy/./zap-extensions/x.zap",
                    "https://github.com/zaproxy/..",
                    "https://github.com/zaproxy/a%2F..%2Fb/x.zap",
                    "https://github.com/zaproxy/..%5Csomeone/x.zap",
                    "https://github.com/zaproxy/..\\someone/x.zap",
                    "https://github.com/zaproxy/..;/someone/x.zap",
                    "https://github.com/zaproxy/..%3B/someone/x.zap"}) {
                rejectedOutside(ZapFixtures.addon("spider", "1", "release", url, "SHA-256:" + HASH), SRC, url);
            }
        });

        Harness.test("a download beside the prefix's last folder is left out", () -> {
            rejectedOutside(ZapFixtures.addon("spider", "1", "release", "https://github.com/zaproxy-other/x.zap",
                    "SHA-256:" + HASH), SRC, "beside the folder");
            Source bare = new Source("mine", "Mine", SourceType.FEED_URL, "https://host.example/feed.xml",
                    "https://host.example/addons", true);
            for (String url : new String[] {"https://host.example/addons-other/x.zap", "https://host.example/addons"}) {
                rejectedOutside(ZapFixtures.addon("spider", "1", "release", url, "SHA-256:" + HASH), bare, url);
            }
            Listing ok = listing0(ZapFixtures.feed(ZapFixtures.addon("spider", "1", "release",
                    "https://host.example/addons/x.zap", "SHA-256:" + HASH)), bare);
            Expect.equal(ok.entries().size(), 1, "inside the folder lists");
            Expect.equal(ok.rejected().size(), 0, "nothing rejected");
        });

        Harness.test("a download on another host, port or with a user name is left out", () -> {
            for (String url : new String[] {"https://github.com.evil.example/zaproxy/x.zap",
                    "https://github.com:8443/zaproxy/x.zap", "https://evil@github.com/zaproxy/x.zap"}) {
                rejectedOutside(ZapFixtures.addon("spider", "1", "release", url, "SHA-256:" + HASH), SRC, url);
            }
            for (String url : new String[] {"https://GitHub.com/zaproxy/zap-extensions/x.zap",
                    "https://github.com:443/zaproxy/zap-extensions/x.zap"}) {
                Listing l = listing(ZapFixtures.feed(
                        ZapFixtures.addon("spider", "1", "release", url, "SHA-256:" + HASH)));
                Expect.equal(l.entries().size(), 1, url + ": listed");
                Expect.equal(l.rejected().size(), 0, url + ": nothing rejected");
            }
        });

        Harness.test("a plain HTTP download is left out and named", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", "http://github.com/zaproxy/x.zap",
                        "SHA-256:" + HASH), "http"));

        Harness.test("a file name for another id is left out", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:" + HASH)
                        .replace("<file>spider-release-1.zap", "<file>other-release-1.zap"), "other id"));

        Harness.test("a path in the file name is left out", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:" + HASH)
                        .replace("<file>spider-release-1.zap", "<file>../spider-release-1.zap"), "path"));

        Harness.test("a missing or malformed hash is left out", () -> {
            rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "MD5:" + "a".repeat(32)), "md5");
            rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:xyz"), "short hash");
        });

        Harness.test("an add-on for a later ZAP is left out", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:" + HASH)
                        .replace("<not-before-version>2.17.0", "<not-before-version>2.18.0"), "too new"));

        Harness.test("an add-on that stops before this ZAP is left out", () ->
                rejectedAs(ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:" + HASH)
                        .replace("</not-before-version>", "</not-before-version><not-from-version>2.17.0</not-from-version>"),
                        "too old"));

        Harness.test("an unparsable version range leaves out only that entry", () -> {
            String good = ZapFixtures.addon("good", "1", "release", URL, "SHA-256:" + HASH);
            Listing l = listing(ZapFixtures.feed(
                    ZapFixtures.addon("spider", "1", "release", URL, "SHA-256:" + HASH, "commonlib", "~1.2"), good));
            Expect.equal(l.entries().size(), 1, "the good entry stays");
            Expect.equal(l.entries().get(0).id(), "good", "which one");
            Expect.isTrue(l.rejected().get(0).startsWith("spider: "), "the bad one is named");
        });

        Harness.test("an entry id with a path in it is left out", () -> {
            Listing l = listing(ZapFixtures.feed("<addon>../../x</addon>"));
            Expect.equal(l.entries().size(), 0, "no entry");
            Expect.isTrue(l.rejected().get(0).startsWith("../../x: "), "named: " + l.rejected());
        });

        Harness.test("a repeated add-on id is listed once", () -> {
            Listing l = listing(ZapFixtures.feed(spider(), "<addon>spider</addon>"));
            Expect.equal(l.entries().size(), 1, "one entry");
            Expect.equal(l.rejected().size(), 1, "one rejected line");
            Expect.equal(l.rejected().get(0), "spider: listed twice", "the repeat is named");
        });

        Harness.test("a source without a prefix takes no downloads", () -> {
            Source none = new Source("local", "Local", SourceType.LOCAL_FOLDER, "/x", "", true);
            Listing l = listing0(ZapFixtures.feed(spider()), none);
            Expect.equal(l.entries().size(), 0, "no entry");
            Expect.isTrue(l.rejected().get(0).startsWith("spider: "), "named: " + l.rejected());
        });

        Harness.test("a feed with a DOCTYPE is refused", () -> {
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE ZAP [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><ZAP>&x;</ZAP>";
            boolean threw = false;
            try { parse(xml); } catch (IOException e) { threw = true; }
            Expect.isTrue(threw, "IOException for a DOCTYPE");
        });

        Harness.test("a document that is not XML is refused", () -> {
            boolean threw = false;
            try { parse("not xml at all"); } catch (IOException e) { threw = true; }
            Expect.isTrue(threw, "IOException for non-XML");
        });

        Harness.test("the ZAP feed's download prefix is fixed", () -> {
            Source s = Source.zapFeed("2.17", true);
            Expect.equal(s.prefix(), "https://github.com/zaproxy/", "prefix");
            Expect.isTrue(s.location().endsWith("ZapVersions-2.17.xml"), "location: " + s.location());
            Expect.isTrue(s.builtIn(), "built in");
        });
    }
}
