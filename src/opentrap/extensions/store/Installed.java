package opentrap.extensions.store;

/**
 * One add-on the store has put in place. {@code origin} is free text shown to
 * the user (for example "bundled with ZAP 2.17.0" or a source's name);
 * {@code sourceId} is the id of the source it came from.
 */
public record Installed(Kind kind, String id, Version version, String file, String sourceId, String origin,
                        boolean active, String sha256) {}
