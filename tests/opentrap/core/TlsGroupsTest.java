package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.net.ssl.SSLSocket;

/**
 * Pins the key exchange the request sender offers. No named groups are set
 * anywhere, so the post-quantum hybrid is on only because the JSSE defaults
 * put it first; a change that narrows the groups must fail here.
 */
public final class TlsGroupsTest {

    public void run() {
        Harness.suite("tls key exchange");

        Harness.test("the request sender offers the X25519MLKEM768 hybrid first", () -> {
            try (SSLSocket s = (SSLSocket) HttpClient.sslFactory().createSocket()) { // unconnected
                String[] groups = s.getSSLParameters().getNamedGroups();
                Expect.equal(groups[0], "X25519MLKEM768", "first offered group");
            } catch (Exception e) {
                throw new AssertionError(e.toString(), e);
            }
        });
    }
}
