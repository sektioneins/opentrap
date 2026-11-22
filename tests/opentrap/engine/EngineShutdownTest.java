package opentrap.engine;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Shutting the engine down must not wait for a start-up still in progress.
 * The shutdown hook runs while the JVM exits (on Cmd-Q, on the AppKit thread),
 * so waiting there for an engine that takes up to two minutes to answer froze
 * the whole application on quit.
 */
public final class EngineShutdownTest {

    /** Stands in for ZAP: starts, never opens its API port, never exits on its own. */
    public static final class SilentEngine {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    public void run() {
        Harness.suite("Scan engine shutdown");

        Harness.test("shutdown does not wait for an engine still starting up", () -> {
            try {
                ZapEngine engine = ZapEngine.forJar(silentEngineJar().toString());
                Throwable[] startFailure = new Throwable[1];
                Thread starter = new Thread(() -> {
                    try { engine.start(); }
                    catch (Throwable t) { startFailure[0] = t; }
                }, "test-engine-start");
                starter.setDaemon(true);
                starter.start();
                // Wait until start() has launched the process and entered its
                // readiness wait, rather than sleeping for a guessed while.
                long until = System.nanoTime() + 20_000_000_000L;
                while (!engine.awaitingAnswer() && starter.isAlive() && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
                Expect.isTrue(engine.awaitingAnswer(), "start() is waiting for the engine to answer"
                        + (startFailure[0] == null ? "" : " (it failed: " + startFailure[0] + ")"));

                Thread stopper = new Thread(engine::shutdown, "test-engine-shutdown");
                stopper.setDaemon(true);
                long t0 = System.nanoTime();
                stopper.start();
                stopper.join(20_000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Expect.isFalse(stopper.isAlive(), "shutdown() returned (took " + ms + "ms)");

                starter.join(10_000);
                Expect.isFalse(starter.isAlive(), "start() gave up once shutdown began");
                Expect.notNull(startFailure[0], "the abandoned start() reports failure");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** A runnable jar whose main class is {@link SilentEngine}. */
    private static Path silentEngineJar() throws Exception {
        Path dir = Files.createTempDirectory("opentrap-silent-engine-");
        dir.toFile().deleteOnExit();
        Path jar = dir.resolve("zap-0.0.0.jar");
        jar.toFile().deleteOnExit();
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, SilentEngine.class.getName());
        String entry = SilentEngine.class.getName().replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jo = new JarOutputStream(out, mf);
             InputStream cls = EngineShutdownTest.class.getResourceAsStream("/" + entry)) {
            jo.putNextEntry(new JarEntry(entry));
            cls.transferTo(jo);
            jo.closeEntry();
        }
        return jar;
    }
}
