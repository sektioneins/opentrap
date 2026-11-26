package opentrap.extensions.store;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

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
     * True when every segment of the raw path, decoded, is strict UTF-8 that holds no {@code /},
     * {@code \}, {@code ;}, {@code %} or control character and is not made only of dots and
     * spaces. So {@code %20} or {@code %C3%A4} pass, while {@code %2e%2e}, an escaped slash,
     * {@code %252e} (a server that decodes twice) and overlong UTF-8 such as {@code %c0%ae} are
     * refused. Servers that strip path parameters read {@code ..;} as {@code ..}, and Windows
     * trims trailing dots and spaces, so {@code ...} and {@code ..%20} can act as {@code ..}.
     */
    public static boolean plainPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return true;
        for (String segment : rawPath.split("/", -1)) {
            String s = decode(segment);
            if (s == null) return false;
            boolean dots = !s.isEmpty();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '/' || c == '\\' || c == ';' || c == '%' || c < 0x20 || c == 0x7f) return false;
                if (c != '.' && c != ' ') dots = false;
            }
            if (dots) return false;
        }
        return true;
    }

    /** A raw path segment with its escapes decoded as strict UTF-8, or null when it is not. */
    private static String decode(String segment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < segment.length()) {
            int escape = segment.indexOf('%', i);
            if (escape < 0) escape = segment.length();
            bytes.writeBytes(segment.substring(i, escape).getBytes(StandardCharsets.UTF_8));
            if (escape == segment.length()) break;
            if (escape + 2 >= segment.length()) return null;
            int hi = Character.digit(segment.charAt(escape + 1), 16), lo = Character.digit(segment.charAt(escape + 2), 16);
            if (hi < 0 || lo < 0) return null;
            bytes.write(hi * 16 + lo);
            i = escape + 3;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
