package opentrap.extensions.store;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A place add-ons are listed from. {@code prefix} is a scheme, host, port and
 * folder: every download URL of a feed source must be in that folder or below
 * it. It is empty for sources that download nothing.
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

    /**
     * Whether {@code url} is a download this source may name. A string prefix was
     * not enough: a server removes dot segments before it serves the path, so
     * {@code /zaproxy/../other/} leaves the folder, and {@code /zaproxy-other/}
     * starts with {@code /zaproxy}. Never throws.
     */
    public boolean admits(String url) {
        if (url == null || prefix.isEmpty()) return false;
        try {
            URI u = new URI(url);
            URI p = new URI(prefix);
            if (!"https".equals(u.getScheme()) || !"https".equals(p.getScheme())) return false;
            if (u.getHost() == null || p.getHost() == null || u.getRawUserInfo() != null) return false;
            if (!u.getHost().equalsIgnoreCase(p.getHost())) return false;
            if (port(u) != port(p)) return false;
            String folder = p.getRawPath();
            if (folder == null || folder.isEmpty()) folder = "/";
            else if (!folder.endsWith("/")) folder += "/";
            String path = u.getRawPath();
            return path != null && plainPath(folder) && plainPath(path) && path.startsWith(folder);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static int port(URI u) { return u.getPort() == -1 ? 443 : u.getPort(); }

    /**
     * True when the raw path holds no {@code %}, no {@code ;} and no segment made only of dots. A
     * server's percent decoding cannot be known ({@code %2e}, {@code %252e} and overlong UTF-8
     * such as {@code %c0%ae} all become a dot somewhere), and ZAP's and GitHub's release URLs
     * have no escapes at all. Servers that strip path parameters read {@code ..;} as {@code ..}.
     * Windows trims trailing dots, so {@code ...} can act as {@code ..}.
     */
    public static boolean plainPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return true;
        if (rawPath.indexOf('%') >= 0 || rawPath.indexOf(';') >= 0) return false;
        for (String segment : rawPath.split("/", -1)) {
            if (!segment.isEmpty() && segment.chars().allMatch(c -> c == '.')) return false;
        }
        return true;
    }
}
