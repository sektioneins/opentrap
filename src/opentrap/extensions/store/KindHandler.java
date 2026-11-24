package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The on-disk layout of one kind of extension: where downloads wait for the
 * next engine start, how a queued change is carried out, and how the record is
 * made to agree with what is on disk. Callers hold the {@link StoreLock}.
 */
public interface KindHandler {

    Kind kind();

    /** The folder downloaded and verified files wait in. */
    Path stagingDir();

    /**
     * Carries out one queued change, removes it from {@code rec.pending()} and saves the record. Every step is
     * safe to repeat after a crash. An IOException leaves the pending item in place.
     */
    void apply(StoreRecord rec, Pending p) throws IOException;

    /**
     * Makes {@code rec.installed()} follow the folders (they win over the record) and returns it. The record is
     * changed in memory only; the caller saves.
     */
    List<Installed> reconcile(StoreRecord rec) throws IOException;
}
