package opentrap.extensions.store;

/** What a store entry is. Only ZAP add-ons exist so far. */
public enum Kind {
    ZAP_ADDON("ZAP add-on");

    private final String label;

    Kind(String label) { this.label = label; }

    public String label() { return label; }
}
