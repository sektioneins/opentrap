package opentrap.extensions.store;

import java.util.List;

/**
 * One add-on a source offers. {@code location} is the download URL, or the
 * absolute path for a local file; {@code sha256} is 64 lower-case hex.
 * {@code description} and {@code changes} are plain text already ({@code PlainText}),
 * so they are shown and searched as they are.
 */
public record Entry(Kind kind, String sourceId, String id, String name, String description, String author,
                    Version version, Status status, String date, String changes, String infoUrl, long size,
                    String file, String location, String sha256, List<Dependency> dependencies) {}
