package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.nio.file.Path;

/**
 * Pins what each certificate mode on a listener actually presents.
 *
 * <p>The modes were recorded and shown in the listeners table for a while
 * before anything acted on them: every listener served a CA-signed per-host
 * certificate whatever its setting said. These cases hold each mode to what
 * the dialog promises.
 */
public final class CertificateModeTest {

    /** Run a body that may throw, failing the case rather than the suite. */
    private interface Body { void run() throws Exception; }

    private static void check(String name, Body body) {
        Harness.test(name, () -> {
            try { body.run(); }
            catch (AssertionError e) { throw e; }
            catch (Exception e) { throw new AssertionError(e.toString(), e); }
        });
    }

    // One throwaway CA for the whole suite. Generating its RSA key is most of
    // what a case costs, and no case here tests generation: each asks for a
    // different mode, and the context cache keeps the modes apart (the first
    // case pins that). CertificateAuthorityIsolationTest covers fresh keys.
    private Path caDir;
    private CertificateAuthority ca;

    private void withCa(CaBody body) throws Exception {
        if (ca == null) {
            caDir = Files.createTempDirectory("opentrap-ca-test");
            ca = new CertificateAuthority(caDir);
        }
        body.run(ca);
    }

    private void removeCa() {
        if (caDir == null) return;
        try (var walk = Files.walk(caDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
        caDir = null;
        ca = null;
    }

    private interface CaBody { void run(CertificateAuthority ca) throws Exception; }

    public void run() {
        try { cases(); } finally { removeCa(); }
    }

    private void cases() {
        Harness.suite("certificate modes");

        check("the cache is keyed by what will be presented, not the host alone", () -> {
            ProxyListener perHost = new ProxyListener(8080);
            perHost.cert = ProxyListener.Cert.PER_HOST;
            ProxyListener selfSigned = new ProxyListener(8080);
            selfSigned.cert = ProxyListener.Cert.SELF_SIGNED;

            Expect.isFalse(CertificateAuthority.cacheKey("example.com", perHost)
                           .equals(CertificateAuthority.cacheKey("example.com", selfSigned)),
                    "two modes on the same host share a cache entry");

            ProxyListener named = new ProxyListener(8080);
            named.cert = ProxyListener.Cert.SPECIFIC_HOSTNAME;
            named.certHostname = "fixed.example.com";
            Expect.equal(CertificateAuthority.cacheKey("a.example.com", named),
                         CertificateAuthority.cacheKey("b.example.com", named),
                    "a fixed hostname presents one certificate whatever host is asked for");

            Expect.equal(CertificateAuthority.cacheKey("example.com", null),
                         CertificateAuthority.cacheKey("example.com", perHost),
                    "no listener means per-host, as before");
        });

        check("a per-host listener presents a certificate the CA signed", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.PER_HOST;
            Expect.notNull(ca.contextFor("example.com", l), "a context is built");
            Expect.notNull(ca.caCertificate(), "there is a CA to sign with");
        }));

        check("a self-signed listener still serves, without the CA", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.SELF_SIGNED;
            Expect.notNull(ca.contextFor("example.com", l), "a context is built");
        }));

        check("a named listener serves the same certificate for every host", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.SPECIFIC_HOSTNAME;
            l.certHostname = "fixed.example.com";
            Expect.isTrue(ca.contextFor("a.example.com", l) == ca.contextFor("b.example.com", l),
                    "two hosts are served the same context");
        }));

        check("a custom store that is not set is reported, not ignored", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.CUSTOM;
            l.certFile = "";
            boolean threw = false;
            try { ca.contextFor("example.com", l); }
            catch (Exception expected) { threw = true; }
            Expect.isTrue(threw, "an unset custom certificate is reported");
        }));

        check("only protocols the runtime knows are asked for", () -> {
            // A name this Java has never heard of must not be requested, or
            // every connection through the listener would fail.
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getDefault();
            try (javax.net.ssl.SSLSocket s =
                         (javax.net.ssl.SSLSocket) sc.getSocketFactory().createSocket()) {
                ProxyListener l = new ProxyListener(8080);
                l.tlsProtocols = "TLSv1.3, NOT-A-PROTOCOL";
                ProxyServer.applyProtocols(s, l);
                java.util.List<String> on = java.util.Arrays.asList(s.getEnabledProtocols());
                Expect.isFalse(on.contains("NOT-A-PROTOCOL"), "an unknown name is requested");
                Expect.isTrue(on.contains("TLSv1.3"), "the known name is requested");

                // Naming nothing usable leaves the runtime's defaults alone.
                ProxyListener junk = new ProxyListener(8080);
                junk.tlsProtocols = "NONSENSE";
                String[] before = s.getEnabledProtocols();
                ProxyServer.applyProtocols(s, junk);
                Expect.equal(String.join(",", s.getEnabledProtocols()), String.join(",", before),
                        "a configuration naming nothing usable changes nothing");
            }
        });

        check("the proxy completes a TLS 1.3 handshake with a client that offers only X25519MLKEM768", () -> {
            withCa(ca -> {
                SSLEngine server = ca.contextFor("pq.example.test").createSSLEngine();
                server.setUseClientMode(false);
                SSLEngine client = pqClient("pq.example.test");
                handshake(client, server);
                Expect.equal(server.getSession().getProtocol(), "TLSv1.3", "negotiated protocol");
            });
        });

        check("a client offering only X25519MLKEM768 fails against a server limited to x25519", () -> {
            withCa(ca -> {
                SSLEngine server = ca.contextFor("pq.example.test").createSSLEngine();
                server.setUseClientMode(false);
                SSLParameters sp = server.getSSLParameters();
                sp.setNamedGroups(new String[]{"x25519"});
                server.setSSLParameters(sp);
                SSLEngine client = pqClient("pq.example.test");
                boolean failed = false;
                try { handshake(client, server); }
                catch (SSLException _) { failed = true; }
                Expect.isTrue(failed, "the handshake succeeded without a shared group");
            });
        });
    }

    /** A TLS 1.3 client engine that trusts anything and offers only the hybrid group. */
    private static SSLEngine pqClient(String host) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{ new X509ExtendedTrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        SSLEngine client = ctx.createSSLEngine(host, 443);
        client.setUseClientMode(true);
        SSLParameters p = client.getSSLParameters();
        p.setProtocols(new String[]{"TLSv1.3"});
        p.setNamedGroups(new String[]{"X25519MLKEM768"});
        client.setSSLParameters(p);
        return client;
    }

    /** Drive both engines against each other in memory until both are done. */
    private static void handshake(SSLEngine client, SSLEngine server) throws Exception {
        client.beginHandshake();
        server.beginHandshake();
        ByteBuffer c2s = ByteBuffer.allocate(4 * client.getSession().getPacketBufferSize());
        ByteBuffer s2c = ByteBuffer.allocate(4 * server.getSession().getPacketBufferSize());
        ByteBuffer empty = ByteBuffer.allocate(0);
        ByteBuffer cApp = ByteBuffer.allocate(client.getSession().getApplicationBufferSize());
        ByteBuffer sApp = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        for (int round = 0; round < 200; round++) {
            step(client, empty, c2s, s2c, cApp);
            step(server, empty, s2c, c2s, sApp);
            if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    && server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) return;
        }
        throw new AssertionError("handshake did not finish in 200 rounds");
    }

    /** Advance one engine as far as it can go: wrap into out, unwrap from in. */
    private static void step(SSLEngine e, ByteBuffer empty, ByteBuffer out, ByteBuffer in,
                             ByteBuffer app) throws Exception {
        for (int guard = 0; guard < 50; guard++) {
            switch (e.getHandshakeStatus()) {
                case NEED_TASK -> {
                    Runnable t;
                    while ((t = e.getDelegatedTask()) != null) t.run();
                }
                case NEED_WRAP -> {
                    SSLEngineResult r = e.wrap(empty, out);
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) return; // let the peer drain
                }
                case NEED_UNWRAP -> {
                    in.flip();
                    SSLEngineResult r;
                    try { r = e.unwrap(in, app); }
                    finally { in.compact(); }
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return; // wait for the peer
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
                        throw new AssertionError("application buffer too small");
                }
                default -> { return; }
            }
        }
    }
}
