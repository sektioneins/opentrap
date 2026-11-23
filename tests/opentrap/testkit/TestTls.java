package opentrap.testkit;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * A TLS server context for a local test server, for cases that need TLS but
 * do not test the certificate authority.
 *
 * <p>A {@code CertificateAuthority} generates two RSA-2048 key pairs, which
 * takes 100 to 400 ms and varies from run to run. This makes one self-signed
 * EC P-256 certificate instead, in a few milliseconds. The suite's clients
 * trust any certificate, so self-signed is enough.
 */
public final class TestTls {

    private TestTls() {}

    private static final char[] PASSWORD = "test".toCharArray();

    /** A server context presenting a self-signed certificate for this IP address. */
    public static SSLContext serverContext(String ip) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            KeyPair kp = g.generateKeyPair();
            X500Name name = new X500Name("CN=" + ip);
            long now = System.currentTimeMillis();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
                    new Date(now - 3_600_000L), new Date(now + 86_400_000L), name, kp.getPublic());
            b.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)));
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate())));

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, PASSWORD);
            ks.setKeyEntry("server", kp.getPrivate(), PASSWORD, new Certificate[]{ cert });
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASSWORD);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("could not build a test TLS context", e);
        }
    }
}
