package opentrap.extensions.store;

/**
 * A place add-ons are listed from. {@code prefix} is what every download URL of
 * a feed source must start with; it is empty for sources that download nothing.
 */
public record Source(String id, String name, SourceType type, String location, String prefix, boolean enabled) {

    public static final String ZAP_ID = "zap";
    public static final String BUNDLED_ID = "bundled";

    private static final String ZAP_FEED_BASE = "https://raw.githubusercontent.com/zaproxy/zap-admin/master/ZapVersions-";
    private static final String ZAP_DOWNLOADS = "https://github.com/zaproxy/";

    /** The ZAP project's own feed for one ZAP minor version such as {@code 2.17}. */
    public static Source zapFeed(String minor, boolean enabled) {
        return new Source(ZAP_ID, "ZAP add-ons", SourceType.ZAP_FEED, ZAP_FEED_BASE + minor + ".xml",
                ZAP_DOWNLOADS, enabled);
    }

    /** Sources the store provides itself, which the user cannot remove. */
    public boolean builtIn() { return type == SourceType.ZAP_FEED || type == SourceType.BUNDLED; }
}
