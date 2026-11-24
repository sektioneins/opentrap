package opentrap.extensions.store;

/**
 * A change queued for the next engine start. {@code target} is the entry to put
 * in place and {@code stagedFile} the downloaded file for it; both are null
 * where an action does not use them.
 */
public record Pending(Action action, String id, Entry target, String stagedFile) {

    public enum Action { INSTALL, UPDATE, SWITCH, ENABLE, DISABLE, REMOVE }
}
