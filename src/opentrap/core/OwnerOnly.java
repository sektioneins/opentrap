package opentrap.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Files only their owner may read: directories 0700, files 0600.
 *
 * <p>The mode is given when a file or directory is created, the way a umask of
 * 077 would, so there is no moment in which the content is readable by others.
 * A file is written to a temporary file in its own directory, created 0600, and
 * moved over the target in one step, so a reader never sees half of it and the
 * mode travels with it. On a file system without POSIX permissions the modes
 * are skipped and the rest still happens.
 */
public final class OwnerOnly {

    private OwnerOnly() {}

    public static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    public static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    /** Writes the content of a file to the stream it is given. */
    public interface Content { void writeTo(OutputStream out) throws Exception; }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Create {@code dir} and any missing parents 0700, and set {@code dir}
     * itself to 0700 if it already existed with a wider mode. Parents that
     * already existed are left as they are.
     */
    public static Path directory(Path dir) throws IOException {
        if (!posix()) return Files.createDirectories(dir);
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR));
        restrict(dir, DIR);
        return dir;
    }

    /** Write {@code file} 0600 from the start, replacing it in one step. Its directory is made with {@link #directory}. */
    public static void write(Path file, Content content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        directory(dir);
        FileAttribute<?>[] attrs = posix()
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(FILE)}
                : new FileAttribute<?>[0];
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp", attrs);
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                content.writeTo(out);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Set an existing file to 0600, for a file this suite did not create or
     * that an older build left wider. False, with an error on the console, when
     * the mode could not be set; true when it is set or the file system has no
     * POSIX permissions.
     */
    public static boolean restrictFile(Path file) {
        return restrict(file, FILE);
    }

    private static boolean restrict(Path p, Set<PosixFilePermission> mode) {
        if (!posix()) return true;
        try {
            if (!Files.getPosixFilePermissions(p).equals(mode)) Files.setPosixFilePermissions(p, mode);
            return true;
        } catch (Exception e) {
            Debug.error("could not make " + p + " owner-only: " + e.getMessage(), null);
            return false;
        }
    }
}
