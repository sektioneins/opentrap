package opentrap.extensions.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a new ZAP minor version can take over from the previous one. Bundled
 * add-ons come with the new engine itself, so only the others are listed.
 */
public final class Carryover {

    private static final Pattern MINOR = Pattern.compile("[0-9]{1,9}\\.[0-9]{1,9}");

    private Carryover() {}

    /** The add-ons not bundled with ZAP in the highest minor folder below {@code minor}; none when there is none. */
    public static List<Installed> fromPrevious(Path root, String minor) throws IOException {
        Version current = Version.parse(minor);
        if (!Files.isDirectory(root)) return List.of();
        Path previous;
        try (Stream<Path> s = Files.list(root)) {
            previous = s.filter(p -> Files.isDirectory(p) && MINOR.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Version.parse(p.getFileName().toString()).compareTo(current) < 0)
                    .max((a, b) -> Version.parse(a.getFileName().toString())
                            .compareTo(Version.parse(b.getFileName().toString())))
                    .orElse(null);
        }
        if (previous == null) return List.of();
        return StoreRecord.load(previous).installed().values().stream()
                .filter(in -> !Source.BUNDLED_ID.equals(in.sourceId())).toList();
    }
}
