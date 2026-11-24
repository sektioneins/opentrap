package opentrap.extensions.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import opentrap.core.OwnerOnly;

/**
 * Keeps two writers of the store files apart, in this process and across
 * OpenTRAP instances. Callers do every change as: acquire, load, change, save,
 * close.
 *
 * <p>A file lock is held per process, and a second {@code lock()} in the same
 * JVM throws {@code OverlappingFileLockException}, so a JVM-wide lock is taken
 * first and released last.
 */
public final class StoreLock implements AutoCloseable {

    private static final ReentrantLock JVM = new ReentrantLock();

    private final FileChannel channel;
    private final FileLock lock;

    private StoreLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Blocks until the store in {@code root} is free. */
    public static StoreLock acquire(Path root) throws IOException {
        JVM.lock();
        if (JVM.getHoldCount() > 1) {
            JVM.unlock();
            throw new IllegalStateException("The store lock is already held by this thread");
        }
        FileChannel ch = null;
        try {
            OwnerOnly.directory(root);
            ch = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new StoreLock(ch, ch.lock());
        } catch (IOException | RuntimeException e) {
            if (ch != null) {
                try { ch.close(); } catch (IOException _) {}
            }
            JVM.unlock();
            throw e;
        }
    }

    @Override public void close() throws IOException {
        try {
            lock.release();
            channel.close();
        } finally {
            JVM.unlock();
        }
    }
}
