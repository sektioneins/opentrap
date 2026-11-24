package opentrap.extensions.store;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.ZapFixtures;

/** Add-on files and local folder sources: what a file says about itself, and which files are refused. */
public final class ZapAddOnFileTest {

    private static Path tmp() {
        try { return Files.createTempDirectory("opentrap-zapfile"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Path zipOf(Path file, String entryName, String content) {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return file;
    }

    private static void refused(Path file, String why) {
        try {
            ZapAddOnFile.read(file);
            Expect.isTrue(false, why + ": read should have thrown");
        } catch (IOException expected) {
            Expect.isTrue(expected.getMessage().contains(file.getFileName().toString()),
                    why + ": message names the file: " + expected.getMessage());
        }
    }

    private static Listing scan(Path dir, Source src) {
        try { return ZapAddOnFile.scanFolder(dir, src); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void run() {
        Harness.suite("Extension store add-on files");

        Harness.test("an add-on file gives its id from the name and its details from the manifest", () -> {
            Path dir = tmp();
            Path f = ZapFixtures.zap(dir, "spider", "0.20.0", "release", "commonlib", "");
            ZapAddOnFile.AddOnInfo i;
            try { i = ZapAddOnFile.read(f); } catch (IOException e) { throw new UncheckedIOException(e); }
            Expect.equal(i.id(), "spider", "id from the name");
            Expect.equal(i.name(), "Add-on spider", "name from the manifest");
            Expect.equal(i.version(), Version.parse("0.20.0"), "version");
            Expect.equal(i.status(), Status.RELEASE, "status");
            Expect.equal(i.dependencies().size(), 1, "one dependency");
            Expect.equal(i.dependencies().get(0).id(), "commonlib", "dependency id");
            Expect.isTrue(i.dependencies().get(0).range().matches(Version.parse("99")), "plain dependency takes any version");
        });

        Harness.test("a manifest dependency with a version range keeps the range", () -> {
            Path dir = tmp();
            Path f = zipOf(dir.resolve("a-beta-1.0.zap"), "ZapAddOn.xml",
                    "<zapaddon><name>A</name><version>1.0</version><status>beta</status><dependencies><addons>"
                    + "<addon><id>commonlib</id><version>&gt;= 1.0 &amp; &lt; 2.0</version></addon>"
                    + "</addons></dependencies></zapaddon>");
            ZapAddOnFile.AddOnInfo i;
            try { i = ZapAddOnFile.read(f); } catch (IOException e) { throw new UncheckedIOException(e); }
            Dependency d = i.dependencies().get(0);
            Expect.isTrue(d.range().matches(Version.parse("1.5")), "inside the range");
            Expect.isFalse(d.range().matches(Version.parse("2.0")), "outside the range");
        });

        Harness.test("a zip without ZapAddOn.xml is not an add-on", () ->
                refused(zipOf(tmp().resolve("spider-release-1.0.zap"), "readme.txt", "hi"), "no manifest"));

        Harness.test("a file name that is not id-status-version.zap is refused", () ->
                refused(zipOf(tmp().resolve("spider.zap"), "ZapAddOn.xml", "<zapaddon/>"), "bad name"));

        Harness.test("a manifest whose version differs from the file name is refused", () ->
                refused(zipOf(tmp().resolve("spider-release-1.0.zap"), "ZapAddOn.xml",
                        "<zapaddon><name>S</name><version>2.0</version><status>release</status></zapaddon>"), "version"));

        Harness.test("a manifest whose status differs from the file name is refused", () ->
                refused(zipOf(tmp().resolve("spider-release-1.0.zap"), "ZapAddOn.xml",
                        "<zapaddon><name>S</name><version>1.0</version><status>alpha</status></zapaddon>"), "status"));

        Harness.test("a manifest with a DOCTYPE is refused", () ->
                refused(zipOf(tmp().resolve("spider-release-1.0.zap"), "ZapAddOn.xml",
                        "<!DOCTYPE zapaddon [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<zapaddon><name>&x;</name><version>1.0</version><status>release</status></zapaddon>"), "doctype"));

        Harness.test("a folder source lists each add-on with its checksum", () -> {
            Path dir = tmp();
            Path a = ZapFixtures.zap(dir, "spider", "0.20.0", "release");
            Path b = ZapFixtures.zap(dir, "retire", "0.30.0", "beta");
            Source src = new Source("folder1", "My folder", SourceType.LOCAL_FOLDER, dir.toString(), "", true);
            Listing l = scan(dir, src);
            Expect.equal(l.rejected().size(), 0, "nothing rejected");
            Expect.equal(l.entries().size(), 2, "two entries");
            for (Entry e : l.entries()) {
                Path f = e.id().equals("spider") ? a : b;
                Expect.equal(e.sha256(), ZapFixtures.sha256(f), e.id() + " checksum");
                Expect.equal(e.sourceId(), "folder1", e.id() + " source id");
                Expect.equal(e.location(), f.toAbsolutePath().toString(), e.id() + " location");
                Expect.equal(e.file(), f.getFileName().toString(), e.id() + " file");
                Expect.equal(e.kind(), Kind.ZAP_ADDON, e.id() + " kind");
                try { Expect.equal(e.size(), Files.size(f), e.id() + " size"); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            }
        });

        Harness.test("a broken file in a folder source is named, the others still listed", () -> {
            Path dir = tmp();
            ZapFixtures.zap(dir, "spider", "0.20.0", "release");
            try { Files.writeString(dir.resolve("broken-release-1.0.zap"), "not a zip"); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            Source src = new Source("folder1", "My folder", SourceType.LOCAL_FOLDER, dir.toString(), "", true);
            Listing l = scan(dir, src);
            Expect.equal(l.entries().size(), 1, "the good file is listed");
            Expect.equal(l.rejected().size(), 1, "the bad file is rejected");
            Expect.isTrue(l.rejected().get(0).startsWith("broken-release-1.0.zap"), "named: " + l.rejected().get(0));
        });

        Harness.test("a folder scan skips other files and subfolders", () -> {
            Path dir = tmp();
            try {
                Files.writeString(dir.resolve("notes.txt"), "x");
                Path sub = Files.createDirectory(dir.resolve("sub"));
                ZapFixtures.zap(sub, "spider", "0.20.0", "release");
            } catch (IOException e) { throw new UncheckedIOException(e); }
            Source src = new Source("folder1", "My folder", SourceType.LOCAL_FOLDER, dir.toString(), "", true);
            Listing l = scan(dir, src);
            Expect.equal(l.entries().size(), 0, "nothing listed");
            Expect.equal(l.rejected().size(), 0, "nothing rejected");
        });

        Harness.test("a downloaded file for another add-on fails the check", () -> {
            Path dir = tmp();
            Path spiderFile = ZapFixtures.zap(dir, "spider", "0.20.0", "release");
            Path retireFile = ZapFixtures.zap(dir, "retire", "0.30.0", "release");
            Source src = new Source("folder1", "My folder", SourceType.LOCAL_FOLDER, dir.toString(), "", true);
            Entry retire = scan(dir, src).entries().stream().filter(e -> e.id().equals("retire")).findFirst().orElseThrow();
            try {
                ZapAddOnFile.check(retireFile, retire);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            try {
                ZapAddOnFile.check(spiderFile, retire);
                Expect.isTrue(false, "check should have thrown");
            } catch (IOException expected) {
                Expect.isTrue(expected.getMessage().contains("spider"), "names the file's add-on: " + expected.getMessage());
            }
        });
    }
}
