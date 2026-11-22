package opentrap.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * The object streams that read project files (VULN-001). A project file can come
 * from someone else, and plain Java deserialization builds whatever class the
 * stream names, so every reader goes through here: only the classes a project's
 * saved state carries are allowed. Nesting depth is bounded, and so is the size
 * of an array: by the record length in a v7 record, and in a v6 stream by the
 * record cap for a {@code byte[]} and by {@link #LEGACY_MAX_REFERENCES} elements
 * for every other array. Within those bounds a stream can still use a lot of
 * memory; the limits only stop a few crafted bytes from asking for gigabytes.
 *
 * <p>Rule for maintainers: a class that a project's saved state carries must be
 * added to {@link #ALLOWED} and to the first case of {@code ProjectSerialTest}.
 */
public final class ProjectSerial {

    static final int MAX_DEPTH = 20;

    /**
     * The most elements any array other than a {@code byte[]} may hold in an
     * old-format stream. Bodies can be up to the record cap, but no history holds
     * 16 million entries, and a reference or wide primitive array that big is
     * hundreds of megabytes to gigabytes of heap.
     */
    static final int LEGACY_MAX_REFERENCES = 1 << 24;

    /** The class allowlist, in filter-pattern syntax, ending with a reject-everything-else rule. */
    static final String ALLOWED = String.join(";",
            // The project's own saved state.
            "opentrap.core.LogEntry",
            "opentrap.core.HttpMessage",
            "opentrap.core.HttpService",
            "opentrap.core.WebSocketMessage",
            "opentrap.core.WebSocketMessage$Direction",
            "opentrap.core.WebSocketMessage$Op",
            "opentrap.core.Scope",
            "opentrap.core.Scope$Rule",
            "opentrap.core.Issue",
            "opentrap.core.Issue$Severity",
            "opentrap.core.Issue$Confidence",
            "opentrap.core.Issue$Dedupe",
            "opentrap.core.ScanTask$Snapshot",
            "opentrap.core.ScanTask$Type",
            "opentrap.organizer.OrganizerItem",
            "opentrap.session.SessionManager$State",
            "opentrap.session.Cookie",
            "opentrap.session.SessionRule",
            "opentrap.session.SessionRule$Trigger",
            "opentrap.session.Macro",
            "opentrap.session.Macro$Item",
            "opentrap.session.Extraction",
            "opentrap.session.Extraction$From",
            "opentrap.proxy.HistoryFilter",
            "opentrap.repeater.RepeaterTool$TabState",
            // Component type of Object[], which ArrayList and CollSer check.
            "java.lang.Object",
            // Plain values a saved state or an extension's saveState can hold.
            "java.lang.String",
            // Superclass descriptors of the enums and the boxed numbers.
            "java.lang.Enum",
            "java.lang.Number",
            // More plain values.
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.util.Date",
            // Collections.
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            // The array type that HashMap and HashSet check.
            "java.util.Map$Entry",
            // How List.of travels (Issue.tags).
            "java.util.CollSer",
            "java.util.ImmutableCollections$*",
            // ScanTask.Snapshot.log.
            "java.util.concurrent.CopyOnWriteArrayList",
            // Everything else is refused.
            "!*");

    private ProjectSerial() {}

    /**
     * A stream over one record. An array cannot have more elements than its
     * record has bytes, so the record length bounds the array size.
     */
    public static ObjectInputStream recordReader(byte[] record) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(record));
        ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                "maxdepth=" + MAX_DEPTH + ";maxarray=" + record.length + ";" + ALLOWED));
        return ois;
    }

    /** A stream over the v6 zip entry: a {@code byte[]} is bounded by the record cap, any other array by {@link #LEGACY_MAX_REFERENCES}. */
    public static ObjectInputStream legacyReader(InputStream in) throws IOException {
        ObjectInputFilter pattern = ObjectInputFilter.Config.createFilter(
                "maxdepth=" + MAX_DEPTH + ";maxarray=" + ProjectStore.MAX_RECORD + ";" + ALLOWED);
        ObjectInputStream ois = new ObjectInputStream(in);
        ois.setObjectInputFilter(info -> {
            Class<?> c = info.serialClass();
            if (c != null && c.isArray() && c.getComponentType() != byte.class
                    && info.arrayLength() > LEGACY_MAX_REFERENCES) {
                return ObjectInputFilter.Status.REJECTED;
            }
            return pattern.checkInput(info);
        });
        return ois;
    }
}
