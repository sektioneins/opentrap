package opentrap.ui;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A project file is untrusted input. Each of the four places that read an object
 * stream out of one must refuse a class the filter does not list before it is
 * instantiated: a canary whose {@code readObject} records that it ran stands in
 * for a gadget.
 */
public final class ProjectFilterTest {

    /** Records whether it was ever instantiated by deserialization. */
    static final class Canary implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        static volatile boolean read;
        private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
            in.defaultReadObject();
            read = true;
        }
    }

    private static final HttpService SVC = new HttpService("example.com", 443, true);

    private static LogEntry entry(String path) {
        LogEntry e = new LogEntry("proxy", SVC,
                new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: example.com\r\n\r\n")));
        e.setResponse(new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nbody")));
        return e;
    }

    private static File tmp() throws Exception {
        File f = Files.createTempFile("opentrap-projfilter", ".otrap").toFile();
        f.delete();
        return f;
    }

    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) { oos.writeObject(o); }
        return baos.toByteArray();
    }

    /** A v7 file holding one good history entry followed by one more record. */
    private static File v7(byte type, byte[] hostile) throws Exception {
        File f = tmp();
        try (ProjectStore s = new ProjectStore(f.toPath(), 1 << 20)) {
            s.append(ProjectStore.ENTRY, entry("/good").indexBytes());
            s.append(type, hostile);
            s.sync();
        }
        return f;
    }

    private static boolean hasInvalidClass(Throwable t) {
        for (; t != null; t = t.getCause()) if (t instanceof InvalidClassException) return true;
        return false;
    }

    private static void closeStores(SuiteContext c) {
        c.store().closeStore();
        c.wsStore().closeStore();
    }

    public void run() {
        Harness.suite("hostile project files");

        Harness.test("a history record holding an unlisted class is skipped without running it, and the rest opens", () -> {
            File f = null;
            SuiteContext back = new SuiteContext();
            try {
                Canary.read = false;
                f = v7(ProjectStore.ENTRY, serialize(new Canary()));
                int n = StateIO.restore(back, f);
                Expect.equal(n, 1, "the good entry opens");
                Expect.isFalse(Canary.read, "the hostile record was never instantiated");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(back); if (f != null) f.delete(); }
        });

        Harness.test("a WebSocket record holding an unlisted class is skipped without running it", () -> {
            File f = null;
            SuiteContext back = new SuiteContext();
            try {
                Canary.read = false;
                f = v7(ProjectStore.WS, serialize(new Canary()));
                StateIO.restore(back, f);
                Expect.equal(back.wsStore().size(), 0, "no frame comes from the hostile record");
                Expect.isFalse(Canary.read, "the hostile record was never instantiated");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(back); if (f != null) f.delete(); }
        });

        Harness.test("a snapshot holding an unlisted class refuses the open without running it", () -> {
            File f = null;
            SuiteContext back = new SuiteContext();
            try {
                Canary.read = false;
                f = v7(ProjectStore.SNAPSHOT, serialize(new Canary()));
                Throwable thrown = null;
                try { StateIO.restore(back, f); } catch (Throwable t) { thrown = t; }
                Expect.notNull(thrown, "the open is refused");
                Expect.isTrue(hasInvalidClass(thrown), "the refusal is an InvalidClassException");
                Expect.isFalse(Canary.read, "the hostile record was never instantiated");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(back); if (f != null) f.delete(); }
        });

        Harness.test("an old-format project holding an unlisted class refuses the open without running it", () -> {
            File f = null;
            SuiteContext back = new SuiteContext();
            try {
                Canary.read = false;
                f = tmp();
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
                    zos.putNextEntry(new ZipEntry("opentrap"));
                    ObjectOutputStream oos = new ObjectOutputStream(zos);
                    oos.writeInt(6);
                    oos.writeObject(new ArrayList<>(List.of(new Canary())));
                    oos.writeObject(new Scope());
                    oos.writeObject(new HashMap<String, String>());
                    oos.writeObject(null); // session
                    oos.writeObject(null); // organizer
                    oos.writeObject(null); // issues
                    oos.writeObject(null); // tool states
                    oos.writeObject(null); // tasks
                    oos.flush();
                    zos.closeEntry();
                }
                Canary.read = false;   // writing the file never reads
                Throwable thrown = null;
                try { StateIO.restore(back, f); } catch (Throwable t) { thrown = t; }
                Expect.notNull(thrown, "the open is refused");
                Expect.isTrue(hasInvalidClass(thrown), "the refusal is an InvalidClassException");
                Expect.isFalse(Canary.read, "the hostile record was never instantiated");
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { closeStores(back); if (f != null) f.delete(); }
        });
    }
}
