package opentrap.session;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Tests the public suffix lookup over the JDK's list and over lists in its format. */
public final class PublicSuffixesTest {

    private static void is(PublicSuffixes p, String domain, boolean expected) {
        Expect.equal(p.isPublicSuffix(domain), expected, domain + " is a public suffix");
    }

    public void run() {
        Harness.suite("public suffixes");

        Harness.test("the JDK's list knows multi-label and private suffixes", () -> {
            PublicSuffixes p = PublicSuffixes.jdk();
            is(p, "com", true);
            is(p, "co.uk", true);
            is(p, "github.io", true);
            is(p, "example.com", false);
            is(p, "example.co.uk", false);
            is(p, "user.github.io", false);
        });

        Harness.test("wildcard and exception rules decide as the list says", () -> {
            PublicSuffixes p = PublicSuffixes.jdk();
            is(p, "anything.ck", true);
            is(p, "www.ck", false);
            is(p, "foo.sch.uk", true);
            is(p, "school.foo.sch.uk", false);
        });

        Harness.test("a TLD the list does not name is a public suffix on its own, and punycode is looked up in Unicode", () -> {
            PublicSuffixes p = PublicSuffixes.jdk();
            is(p, "localhost", true);
            is(p, "test", true);
            is(p, "a.test", false);
            is(p, "corp.internal", false);
            is(p, "xn--p1ai", true);
            is(p, "shop.xn--p1ai", false);
        });

        Harness.test("a list in the JDK's format is read with its type bytes", () -> {
            Path file = tempFile();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                    zip.putNextEntry(new ZipEntry("zz"));
                    zip.write(new byte[] {0, 'z', 'z', '\n', 1, '*', '.', 'p', '.', 'z', 'z', '\n',
                            0, '!', 'q', '.', 'p', '.', 'z', 'z', '\n'});
                    zip.closeEntry();
                }
                Files.write(file, bos.toByteArray());
                PublicSuffixes p = PublicSuffixes.load(file);
                is(p, "zz", true);
                is(p, "p.zz", false);
                is(p, "x.p.zz", true);
                is(p, "q.p.zz", false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            }
        });

        Harness.test("without a readable list only a single label is a public suffix", () -> {
            Path file = tempFile();
            try {
                Files.delete(file);
                check(PublicSuffixes.load(file));
                Files.write(file, "not a zip".getBytes(StandardCharsets.UTF_8));
                check(PublicSuffixes.load(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            }
        });
    }

    private static void check(PublicSuffixes p) {
        is(p, "uk", true);
        is(p, "co.uk", false);
        is(p, "example.com", false);
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("opentrap-psl", ".dat");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
