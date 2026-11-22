package opentrap.core;

import opentrap.proxy.CertificateAuthority;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Tests the HTTP mode plumbing without a network: the default stays the raw
 * HTTP/1.x path, and selecting an HTTP/2 mode routes through the JDK transport
 * and fails gracefully (no exception) when the target cannot be reached.
 */
public final class HttpModeTest {

    public void run() {
        Harness.suite("http mode");

        Harness.test("the default send mode is raw HTTP/1", () -> {
            Expect.equal(new HttpClient.Options().mode, HttpMode.HTTP_1, "default mode");
        });

        Harness.test("an HTTP/2 send to a closed port returns an error, not an exception", () -> {
            HttpService svc = new HttpService("127.0.0.1", 1, false); // nothing listening
            HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
            HttpClient.Options opt = new HttpClient.Options();
            opt.mode = HttpMode.HTTP_2;
            HttpClient.Result r = new HttpClient().send(svc, req, opt);
            Expect.isFalse(r.ok(), "send to a closed port is not ok");
            Expect.isTrue(r.error != null && !r.error.isEmpty(), "an error message is set");
        });

        Harness.test("mode labels read cleanly for the UI", () -> {
            Expect.equal(HttpMode.HTTP_2.toString(), "HTTP/2", "http2 label");
            Expect.equal(HttpMode.AUTO.toString(), "auto", "auto label");
        });

        Harness.test("the HTTP/3 mode is labelled HTTP/3", () -> {
            Expect.equal(HttpMode.HTTP_3.toString(), "HTTP/3", "http3 label");
        });

        Harness.test("each mode asks the JDK for its own HTTP version", () -> {
            Expect.equal(Http2Client.requestVersion(HttpMode.HTTP_1), Version.HTTP_1_1, "http1");
            Expect.equal(Http2Client.requestVersion(HttpMode.HTTP_2), Version.HTTP_2, "http2");
            Expect.equal(Http2Client.requestVersion(HttpMode.HTTP_2_IGNORE_ALPN), Version.HTTP_2, "http2 no alpn");
            Expect.equal(Http2Client.requestVersion(HttpMode.AUTO), Version.HTTP_2, "auto");
            Expect.equal(Http2Client.requestVersion(HttpMode.HTTP_3), Version.HTTP_3, "http3");
        });

        Harness.test("an answer over HTTP/3 is written with an HTTP/3 status line", () -> {
            Expect.equal(Http2Client.versionLabel(Version.HTTP_3), "HTTP/3", "http3");
            Expect.equal(Http2Client.versionLabel(Version.HTTP_2), "HTTP/2", "http2");
            Expect.equal(Http2Client.versionLabel(Version.HTTP_1_1), "HTTP/1.1", "http1.1");
        });

        Harness.test("a fallback from HTTP/3 names the version that answered", () -> {
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_3, "HTTP/2 200"),
                    "HTTP/3 not available, answered over HTTP/2", "to http2");
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_3, "HTTP/1.1 200 OK"),
                    "HTTP/3 not available, answered over HTTP/1.1", "to http1.1");
        });

        Harness.test("a fallback from HTTP/2 to HTTP/1.1 is named too", () -> {
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_2_IGNORE_ALPN, "HTTP/1.1 200 OK"),
                    "HTTP/2 not available, answered over HTTP/1.1", "note");
        });

        Harness.test("an answer over the requested version carries no note", () -> {
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_3, "HTTP/3 200"), "", "http3");
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_2, "HTTP/2 204"), "", "http2");
            Expect.equal(HttpMode.fallbackNote(HttpMode.AUTO, "HTTP/1.1 200 OK"), "", "auto");
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_1, "HTTP/1.0 200 OK"), "", "http1");
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_3, ""), "", "empty start line");
            Expect.equal(HttpMode.fallbackNote(HttpMode.HTTP_3, null), "", "null start line");
        });

        Harness.test("the Repeater chip shows the version that answered", () -> {
            Expect.equal(HttpMode.answeredOver("HTTP/3 200"), "HTTP/3", "http3");
            Expect.equal(HttpMode.answeredOver("HTTP/2 200"), "HTTP/2", "http2");
            Expect.equal(HttpMode.answeredOver("HTTP/1.1 200 OK"), "HTTP/1", "http1");
        });

        Harness.test("an HTTP/3 send is refused when an upstream proxy rule covers the target", () -> {
            HttpClient.setProxyProvider(svc -> new UpstreamProxy());
            try {
                HttpService svc = new HttpService("127.0.0.1", 1, true);
                HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
                HttpClient.Options opt = new HttpClient.Options();
                opt.mode = HttpMode.HTTP_3;
                HttpClient.Result r = new HttpClient().send(svc, req, opt);
                Expect.isFalse(r.ok(), "refused");
                Expect.isTrue(r.error.contains("upstream proxy"), "error names the upstream proxy: " + r.error);
            } finally {
                HttpClient.setProxyProvider(null);
            }
        });

        Harness.test("an HTTP/3 send to an HTTPS server without QUIC is answered over HTTP/1.1", () -> {
            Path dir = null;
            HttpsServer server = null;
            try {
                dir = Files.createTempDirectory("http3-fallback");
                server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setHttpsConfigurator(new HttpsConfigurator(new CertificateAuthority(dir).contextFor("127.0.0.1")));
                server.createContext("/", ex -> {
                    byte[] b = Bytes.bytes("hello");
                    ex.sendResponseHeaders(200, b.length);
                    try (var o = ex.getResponseBody()) { o.write(b); }
                });
                server.start();
                HttpService svc = new HttpService("127.0.0.1", server.getAddress().getPort(), true);
                HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
                HttpClient.Options opt = new HttpClient.Options();
                opt.mode = HttpMode.HTTP_3;
                opt.connectTimeoutMs = 2000;
                opt.readTimeoutMs = 2000;
                HttpClient.Result r = new HttpClient().send(svc, req, opt);
                System.out.println("  HTTP/3 fallback to HTTP/1.1 took " + r.millis + " ms");
                Expect.isTrue(r.ok(), "send succeeded: " + r.error);
                Expect.isTrue(r.response.startLine().startsWith("HTTP/1.1 200"), "answered over HTTP/1.1: " + r.response.startLine());
                Expect.isFalse(HttpMode.fallbackNote(HttpMode.HTTP_3, r.response.startLine()).isEmpty(), "fallback is noted");
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (server != null) server.stop(0);
                if (dir != null) {
                    try (Stream<Path> s = Files.walk(dir)) {
                        s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    } catch (java.io.IOException _) {}
                }
            }
        });
    }
}
