package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the optional connect guard: it is asked with the resolved address the
 * socket would connect to, and a refusal ends the send before any connection.
 * Only TEST-NET literals and a reserved name are used, so nothing needs a
 * reachable network.
 */
public final class HttpClientGuardTest {

    private static HttpMessage get() {
        return new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: h\r\n\r\n"));
    }

    public void run() {
        Harness.suite("HttpClient connect guard");

        Harness.test("a refusing guard fails the send with its reason before any connection", () -> {
            HttpClient.Options o = new HttpClient.Options();
            o.connectGuard = d -> "no";
            HttpClient.Result r = new HttpClient().send(new HttpService("203.0.113.7", 9, false), get(), o);
            Expect.isFalse(r.ok(), "send fails");
            Expect.equal(r.error, "no", "error is the guard's reason");
            Expect.isTrue(r.response == null, "no response");
        });

        Harness.test("the guard is asked with the resolved target address and port", () -> {
            AtomicReference<InetSocketAddress> seen = new AtomicReference<>();
            HttpClient.Options o = new HttpClient.Options();
            o.connectGuard = d -> { seen.set(d); return "no"; };
            new HttpClient().send(new HttpService("203.0.113.7", 9, false), get(), o);
            InetSocketAddress d = seen.get();
            Expect.isTrue(d != null, "guard was asked");
            Expect.isFalse(d.isUnresolved(), "address is resolved");
            Expect.equal(d.getAddress().getHostAddress(), "203.0.113.7", "target address");
            Expect.equal(d.getPort(), 9, "target port");
        });

        Harness.test("with an upstream rule the guard is asked about the upstream proxy, not the target", () -> {
            UpstreamProxy upstream = new UpstreamProxy();
            upstream.type = UpstreamProxy.Type.HTTP;
            upstream.host = "198.51.100.9";
            upstream.port = 3128;
            AtomicReference<InetSocketAddress> seen = new AtomicReference<>();
            HttpClient.Options o = new HttpClient.Options();
            o.connectGuard = d -> { seen.set(d); return "no"; };
            HttpClient.setProxyProvider(svc -> upstream);
            try {
                new HttpClient().send(new HttpService("203.0.113.7", 9, false), get(), o);
            } finally {
                HttpClient.setProxyProvider(null);
            }
            InetSocketAddress d = seen.get();
            Expect.isTrue(d != null, "guard was asked");
            Expect.equal(d.getAddress().getHostAddress(), "198.51.100.9", "upstream address");
            Expect.equal(d.getPort(), 3128, "upstream port");
        });

        Harness.test("a name that does not resolve is reported as unknown and the guard is not asked", () -> {
            AtomicInteger calls = new AtomicInteger();
            HttpClient.Options o = new HttpClient.Options();
            o.connectGuard = d -> { calls.incrementAndGet(); return "no"; };
            HttpClient.Result r = new HttpClient().send(new HttpService("no-such-host.invalid", 80, false), get(), o);
            Expect.equal(r.error, "unknown host: no-such-host.invalid", "unknown host text");
            Expect.equal(calls.get(), 0, "guard not called");
        });

        Harness.test("openSocket refuses with the guard's reason", () -> {
            try {
                HttpClient.openSocket(new HttpService("203.0.113.7", 9, false), 15000, 0, d -> "no");
                throw new AssertionError("openSocket should have refused");
            } catch (Exception e) {
                Expect.isTrue(e instanceof IOException, "an IOException");
                Expect.equal(e.getMessage(), "no", "message is the guard's reason");
            }
        });
    }
}
