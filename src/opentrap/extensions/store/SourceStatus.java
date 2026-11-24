package opentrap.extensions.store;

import java.time.Instant;
import java.util.List;

/**
 * How one source stands: when its list was last fetched (null: never), why the
 * last try failed (null: it did not), and the entries its feed listed but the
 * checks refused.
 */
public record SourceStatus(Instant fetched, String error, List<String> rejected) {}
