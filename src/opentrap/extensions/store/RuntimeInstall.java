package opentrap.extensions.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ZAP always loads the {@code plugin/} of the install it runs from. So on macOS
 * and Linux the engine runs from {@code runtime/<key>/}: a link to every entry
 * of the engine install except {@code plugin/}, which is an empty folder, and
 * the store's {@code enabled/} folder is given to ZAP as its add-on folder.
 * Nothing is ever written into the engine install itself.
 */
public final class RuntimeInstall {

    private static final String PLUGIN = "plugin";

    private RuntimeInstall() {}

    /** False where the runtime folder cannot be built from links (Windows). */
    public static boolean linksSupported() {
        return !System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Brings {@code runtimeRoot/<key>} in line with the install and returns it.
     * A link whose target changed is deleted and made again, and a link to an
     * entry the install no longer has is removed.
     */
    public static Path prepare(Path engineInstall, Path runtimeRoot) throws IOException {
        Path install = engineInstall.toRealPath();
        Path dir = runtimeRoot.resolve(key(engineInstall));
        Files.createDirectories(dir.resolve(PLUGIN));
        List<Path> entries;
        try (Stream<Path> s = Files.list(install)) {
            entries = s.filter(p -> !p.getFileName().toString().equals(PLUGIN)).sorted().toList();
        }
        Set<String> names = entries.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        List<Path> present;
        try (Stream<Path> s = Files.list(dir)) { present = s.toList(); }
        for (Path old : present) {
            String name = old.getFileName().toString();
            if (!name.equals(PLUGIN) && !names.contains(name) && Files.isSymbolicLink(old)) Files.delete(old);
        }
        for (Path target : entries) {
            Path link = dir.resolve(target.getFileName().toString());
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isSymbolicLink(link)) {
                    throw new IOException(link + " is in the way of a link to the engine install");
                }
                if (Files.readSymbolicLink(link).equals(target)) continue;
                Files.delete(link);
            }
            Files.createSymbolicLink(link, target);
        }
        return dir;
    }

    /** 16 hex characters naming the install: its real path and its {@code zap-*.jar}. */
    static String key(Path engineInstall) throws IOException {
        String jar;
        try (Stream<Path> s = Files.list(engineInstall)) {
            jar = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("zap-") && n.endsWith(".jar")).sorted().findFirst().orElse("");
        }
        return hash16(engineInstall.toRealPath() + "\n" + jar);
    }

    /** The first 16 hex characters of the SHA-256 of the text. */
    static String hash16(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
