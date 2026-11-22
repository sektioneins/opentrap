package opentrap.core;

/**
 * How a request is put on the wire. HTTP/1.x is the byte-exact raw path and the
 * default; the HTTP/2 and HTTP/3 modes delegate to the JDK's HTTP client, which
 * negotiates ALPN and manages HPACK, or QUIC and QPACK. HTTP/3 falls back to
 * HTTP/2 or HTTP/1.1 when the server has no QUIC; {@link #fallbackNote} says so.
 */
public enum HttpMode {
    /** Byte-exact HTTP/1.x over a raw socket (default). */
    HTTP_1,
    /** HTTP/2, negotiated over TLS ALPN. */
    HTTP_2,
    /** HTTP/2 without requiring ALPN (prior knowledge; best effort). */
    HTTP_2_IGNORE_ALPN,
    /** HTTP/3 over QUIC (UDP), tried first and falling back to TCP. Never chosen by AUTO. */
    HTTP_3,
    /** Negotiate the best protocol, falling back to HTTP/1.1. */
    AUTO;

    @Override public String toString() {
        return switch (this) {
            case HTTP_1 -> "HTTP/1";
            case HTTP_2 -> "HTTP/2";
            case HTTP_2_IGNORE_ALPN -> "HTTP/2 (no ALPN)";
            case HTTP_3 -> "HTTP/3";
            case AUTO -> "auto";
        };
    }

    /** The protocol that answered, as the Repeater chip shows it: HTTP/3, HTTP/2 or HTTP/1. */
    public static String answeredOver(String responseStartLine) {
        int major = majorOf(responseStartLine);
        return major == 3 ? "HTTP/3" : major == 2 ? "HTTP/2" : "HTTP/1";
    }

    /**
     * A short note for the UI when a request for HTTP/2 or HTTP/3 was answered over
     * an older version, or "" when nothing fell back. HTTP/1 and auto never fall back.
     */
    public static String fallbackNote(HttpMode requested, String responseStartLine) {
        if (responseStartLine == null || responseStartLine.isEmpty()) return "";
        int want = requested == HTTP_3 ? 3
                : requested == HTTP_2 || requested == HTTP_2_IGNORE_ALPN ? 2 : 0;
        if (want == 0 || majorOf(responseStartLine) >= want) return "";
        int sp = responseStartLine.indexOf(' ');
        String token = sp < 0 ? responseStartLine : responseStartLine.substring(0, sp);
        return "HTTP/" + want + " not available, answered over " + token;
    }

    private static int majorOf(String startLine) {
        if (startLine == null) return 1;
        if (startLine.startsWith("HTTP/3")) return 3;
        if (startLine.startsWith("HTTP/2")) return 2;
        return 1;
    }
}
