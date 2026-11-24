package opentrap.extensions.store;

import java.util.List;

/** What a source offers: the entries that passed every check, and one "id: reason" line per entry left out. */
public record Listing(List<Entry> entries, List<String> rejected) {}
