package opentrap.core;

import opentrap.engine.LoginStore;
import opentrap.proxy.CertificateAuthority;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Secrets the suite keeps on disk, the CA key and the saved application
 * logins, are readable by their owner only: files 0600 and their directories
 * 0700 from the moment they are created, whatever the umask, and files or
 * directories an older build left wider are narrowed when they are used.
 */
public final class OwnerOnlyTest {

    private interface Body { void run(Path dir) throws Exception; }

    private static void inTemp(Body body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("opentrap-owner");
            body.run(dir);
        } catch (RuntimeException | AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (dir != null) delete(dir);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }

    private static String mode(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    private static void widen(Path p, String mode) throws Exception {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
    }

    public void run() {
        Harness.suite("owner-only files");

        Harness.test("a file written owner-only is 0600 and the directories made for it 0700", () -> inTemp(dir -> {
            Path f = dir.resolve("a").resolve("b").resolve("secret.txt");
            OwnerOnly.write(f, out -> out.write("x".getBytes(StandardCharsets.UTF_8)));
            Expect.equal(mode(f), "rw-------", "file");
            Expect.equal(mode(f.getParent()), "rwx------", "its directory");
            Expect.equal(mode(f.getParent().getParent()), "rwx------", "a parent made for it");
        }));

        Harness.test("writing a file again replaces it and leaves no temporary file behind", () -> inTemp(dir -> {
            Path f = dir.resolve("secret.txt");
            OwnerOnly.write(f, out -> out.write("one".getBytes(StandardCharsets.UTF_8)));
            OwnerOnly.write(f, out -> out.write("two".getBytes(StandardCharsets.UTF_8)));
            Expect.equal(Files.readString(f), "two", "content");
            try (var ls = Files.list(dir)) { Expect.equal(ls.count(), 1L, "files in the directory"); }
        }));

        Harness.test("a failed write leaves the old file and no temporary file", () -> inTemp(dir -> {
            Path f = dir.resolve("secret.txt");
            OwnerOnly.write(f, out -> out.write("old".getBytes(StandardCharsets.UTF_8)));
            boolean threw = false;
            try { OwnerOnly.write(f, out -> { out.write("half".getBytes(StandardCharsets.UTF_8)); throw new IllegalStateException("boom"); }); }
            catch (java.io.IOException e) { threw = true; }
            Expect.isTrue(threw, "the failure is reported");
            Expect.equal(Files.readString(f), "old", "content");
            try (var ls = Files.list(dir)) { Expect.equal(ls.count(), 1L, "files in the directory"); }
        }));

        Harness.test("a new CA store is 0600 in a 0700 directory", () -> inTemp(dir -> {
            Path ca = dir.resolve("ca");
            CertificateAuthority authority = new CertificateAuthority(ca);
            Expect.equal(mode(authority.storePath()), "rw-------", "ca.p12");
            Expect.equal(mode(ca), "rwx------", "ca directory");
        }));

        Harness.test("a CA store and directory an older build left readable are narrowed on load", () -> inTemp(dir -> {
            Path ca = dir.resolve("ca");
            Path store = new CertificateAuthority(ca).storePath();
            widen(store, "rw-r--r--");
            widen(ca, "rwxr-xr-x");
            new CertificateAuthority(ca);
            Expect.equal(mode(store), "rw-------", "ca.p12");
            Expect.equal(mode(ca), "rwx------", "ca directory");
        }));

        Harness.test("an imported CA store readable by others is 0600 once imported", () -> inTemp(dir -> {
            // The bytes are only copied here, so any file stands in for an exported store.
            Path other = dir.resolve("exported.p12");
            Files.write(other, new byte[]{1, 2, 3});
            widen(other, "rw-r--r--");
            Path store = dir.resolve("ca").resolve("ca.p12");
            CertificateAuthority.importStore(other, store);
            Expect.equal(mode(store), "rw-------", "ca.p12");
            Expect.isTrue(java.util.Arrays.equals(Files.readAllBytes(store), Files.readAllBytes(other)), "the imported bytes");
        }));

        Harness.test("a saved login is 0600 in a 0700 folder and reads back", () -> inTemp(dir -> {
            String was = System.getProperty("user.home");
            System.setProperty("user.home", dir.toString());
            try {
                LoginStore.save(new LoginStore.Credentials("Admin ü", "root", "pässword"));
                Path folder = dir.resolve(".opentrap").resolve("library").resolve("login");
                Path f = folder.resolve("Admin _.txt");
                Expect.equal(mode(f), "rw-------", "login file");
                Expect.equal(mode(folder), "rwx------", "login folder");
                LoginStore.Credentials c = (LoginStore.Credentials) LoginStore.load("Admin ü");
                Expect.equal(c.password, "pässword", "password");
            } finally { System.setProperty("user.home", was); }
        }));

        Harness.test("a login folder and file an older build left readable are narrowed when used", () -> inTemp(dir -> {
            String was = System.getProperty("user.home");
            System.setProperty("user.home", dir.toString());
            try {
                Path folder = Files.createDirectories(dir.resolve(".opentrap").resolve("library").resolve("login"));
                Path f = folder.resolve("old.txt");
                Files.writeString(f, "kind=credentials\nlabel=old\nusername=u\npassword=p\n");
                widen(folder, "rwxr-xr-x");
                widen(f, "rw-r--r--");
                Expect.equal(LoginStore.names(), java.util.List.of("old"), "names");
                Expect.equal(mode(folder), "rwx------", "login folder after listing");
                LoginStore.load("old");
                Expect.equal(mode(f), "rw-------", "login file after loading");
            } finally { System.setProperty("user.home", was); }
        }));
    }
}
