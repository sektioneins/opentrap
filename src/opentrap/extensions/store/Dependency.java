package opentrap.extensions.store;

/** A dependency on another add-on: its id and the versions of it that will do. */
public record Dependency(String id, VersionRange range) {}
