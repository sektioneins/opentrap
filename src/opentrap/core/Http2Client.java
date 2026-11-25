package opentrap.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpOption;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * The HTTP/2 and HTTP/3 send path. Rather than hand-roll HPACK and framing, this delegates
 * to the JDK's own HTTP client, which negotiates HTTP/2 over ALPN and falls back
 * to HTTP/1.1 under {@link HttpMode#AUTO}. {@link HttpMode#HTTP_3} tries QUIC at
 * the URI's host and port first and falls back to HTTP/2 or HTTP/1.1; the status
 * line names the version that answered. Our raw {@link HttpMessage} is
 * translated into a JDK request and the response translated back, so the rest of
 * the suite sees the same model it always has.
 *
 * <p>Which client sends is decided by the upstream proxy rule for the target
 * ({@link HttpClient#upstreamFor}). With no rule the direct client is used, which
 * names no proxy. An HTTP rule gets a client of its own that sends through that
 * proxy, and the rule's login goes to it preemptively as a Basic
 * {@code Proxy-Authorization} header, as on the HTTP/1 path, so the JDK never
 * runs an authenticator that a server's own 401 or 407 could trigger. A SOCKS rule is refused in every mode here: the JDK connects direct when
 * the selected proxy is not an HTTP one, which would bypass the rule. Only the
 * direct client sends HTTP/3, as QUIC cannot be tunnelled.
 *
 * <p>Known limitation: because HTTP/2 is binary and the JDK manages the header
 * block, this path is not byte-exact the way the HTTP/1.x socket path is, and it
 * does not expose raw HTTP/2 frame editing. Raw HTTP/1.x stays the default.
 */
public final class Http2Client {

    private Http2Client() {}

    static {
        // Let us pass headers the JDK normally reserves; it still manages framing.
        try {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders",
                    "host,content-length,connection,upgrade,transfer-encoding");
        } catch (Exception _) {}
    }

    private record Route(String host, int port, String user, String pass) {}

    private static final java.util.concurrent.ConcurrentHashMap<Route, java.net.http.HttpClient> routed =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.net.http.HttpClient direct;
    private static volatile SSLContext trustAll;

    private static SSLContext trustAll() throws Exception {
        if (trustAll == null) {
            synchronized (Http2Client.class) {
                if (trustAll == null) {
                    SSLContext ssl = SSLContext.getInstance("TLS");
                    ssl.init(null, new TrustManager[]{ new X509ExtendedTrustManager() {
                        // The extended form is what keeps the JDK from checking the host name
                        // too, as the HTTP/1 path does not.
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
                        public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
                        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
                        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }}, new java.security.SecureRandom());
                    trustAll = ssl;
                }
            }
        }
        return trustAll;
    }

    private static java.net.http.HttpClient.Builder builder(HttpClient.Options opt) throws Exception {
        return java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.max(1000, opt.connectTimeoutMs)))
                .sslContext(trustAll());
    }

    /**
     * The JDK client for a send: the direct one when {@code up} is null, else the
     * one for that HTTP upstream proxy, built once per host, port and login. The
     * direct client names no proxy, so the JVM-wide default selector cannot
     * route it. A proxied client leaves the target unresolved. It has no
     * authenticator: {@link #send} puts the rule's login on each request, and the
     * login stays in the key so a changed password gets fresh pooled tunnels.
     */
    static java.net.http.HttpClient clientFor(UpstreamProxy up, HttpClient.Options opt) throws Exception {
        if (up == null) {
            if (direct == null) {
                synchronized (Http2Client.class) {
                    if (direct == null)
                        direct = builder(opt).proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
                }
            }
            return direct;
        }
        Route key = new Route(up.host, up.port, up.user, up.pass);
        java.net.http.HttpClient known = routed.get(key);
        if (known != null) return known;
        java.net.http.HttpClient.Builder b = builder(opt)
                .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(up.host, up.port)));
        java.net.http.HttpClient made = b.build();
        known = routed.putIfAbsent(key, made);
        return known != null ? known : made;
    }

    /** The HTTP version a mode asks the JDK for. */
    static java.net.http.HttpClient.Version requestVersion(HttpMode m) {
        return switch (m) {
            case HTTP_1 -> java.net.http.HttpClient.Version.HTTP_1_1;
            case HTTP_3 -> java.net.http.HttpClient.Version.HTTP_3;
            default -> java.net.http.HttpClient.Version.HTTP_2;
        };
    }

    /** The version as written in the response status line. */
    static String versionLabel(java.net.http.HttpClient.Version v) {
        return switch (v) {
            case HTTP_3 -> "HTTP/3";
            case HTTP_2 -> "HTTP/2";
            default -> "HTTP/1.1";
        };
    }

    public static HttpClient.Result send(HttpService svc, HttpMessage req, HttpClient.Options opt) {
        HttpClient.Result r = new HttpClient.Result();
        long t0 = System.currentTimeMillis();
        try {
            // Going direct would bypass the rule, so refuse what cannot follow it
            // before any socket is opened: QUIC cannot be tunnelled, and the JDK
            // connects direct when the proxy it is given is not an HTTP one.
            UpstreamProxy up = HttpClient.upstreamFor(svc);
            if (up != null && opt.mode == HttpMode.HTTP_3) {
                r.error = "HTTP/3 cannot go through the upstream proxy set for " + svc.host()
                        + "; choose HTTP/1 or HTTP/2, or remove the rule";
                r.millis = System.currentTimeMillis() - t0;
                return r;
            }
            if (up != null && up.type == UpstreamProxy.Type.SOCKS) {
                r.error = opt.mode + " cannot go through the SOCKS upstream proxy set for " + svc.host()
                        + "; choose HTTP/1, or make the rule an HTTP proxy";
                r.millis = System.currentTimeMillis() - t0;
                return r;
            }
            String scheme = svc.secure() ? "https" : "http";
            String authority = svc.defaultPort() ? svc.host() : svc.host() + ":" + svc.port();
            String pq = req.path() + (req.query().isEmpty() ? "" : "?" + req.query());
            if (pq.isEmpty()) pq = "/";
            URI uri = URI.create(scheme + "://" + authority + pq);

            java.net.http.HttpClient.Version ver = requestVersion(opt.mode);

            byte[] body = req.body();
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .version(ver)
                    .timeout(Duration.ofMillis(Math.max(1000, opt.readTimeoutMs)));
            if (opt.mode == HttpMode.HTTP_3)
                b.setOption(HttpOption.H3_DISCOVERY, HttpOption.Http3DiscoveryMode.ANY);
            String method = req.method().isEmpty() ? "GET" : req.method();
            b.method(method, body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());

            for (String line : req.headerLines()) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String n = line.substring(0, c).trim();
                String v = line.substring(c + 1).trim();
                String nl = n.toLowerCase(Locale.ROOT);
                // The JDK owns these; passing them risks a conflict or is disallowed.
                if (nl.isEmpty() || nl.charAt(0) == ':' || nl.equals("content-length")
                        || nl.equals("transfer-encoding") || nl.equals("connection")
                        || nl.equals("upgrade") || nl.equals("keep-alive") || nl.equals("host")) continue;
                try { b.header(n, v); } catch (Exception _) {}
            }

            // Sent preemptively, replacing any copy the request carries: the JDK puts a
            // Proxy-Authorization header on the CONNECT and keeps it from the server.
            if (up != null && up.user != null && !up.user.isEmpty()) {
                String login = up.user + ":" + (up.pass == null ? "" : up.pass);
                b.setHeader("Proxy-Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(login.getBytes(StandardCharsets.ISO_8859_1)));
            }

            HttpResponse<byte[]> resp = clientFor(up, opt).send(b.build(), HttpResponse.BodyHandlers.ofByteArray());

            StringBuilder sb = new StringBuilder();
            sb.append(versionLabel(resp.version())).append(' ').append(resp.statusCode()).append("\r\n");
            resp.headers().map().forEach((k, vs) -> {
                if (k == null || k.isEmpty() || k.charAt(0) == ':') return;
                for (String v : vs) sb.append(k).append(": ").append(v).append("\r\n");
            });
            sb.append("\r\n");
            HttpMessage out = new HttpMessage(Bytes.concat(Bytes.bytes(sb.toString()), resp.body()));
            if (opt.unpackGzip) HttpClient.unpack(out);
            r.response = out;
            // Through a rule the connection is to the proxy, and the target is never resolved here.
            try { r.remoteIp = InetAddress.getByName(up == null ? svc.host() : up.host).getHostAddress(); } catch (Exception _) {}
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }
}
