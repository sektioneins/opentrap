package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Predicate;

/** Which destinations count as one of the proxy's own listeners. Needs no sockets and no DNS. */
public final class LoopGuardTest {

    /** Only 192.0.2.5 is "this machine", so the result does not depend on the host's interfaces. */
    private static final Predicate<InetAddress> LAN = a -> a.getHostAddress().equals("192.0.2.5");

    private static InetSocketAddress at(String literal, int port) {
        try { return new InetSocketAddress(InetAddress.getByName(literal), port); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static boolean reaches(String dest, int port, InetSocketAddress... listening) {
        return LoopGuard.reaches(at(dest, port), List.of(listening), LAN);
    }

    public void run() {
        Harness.suite("proxy loop guard");

        Harness.test("a listener bound to loopback is reached at its address and port", () ->
                Expect.isTrue(reaches("127.0.0.1", 8080, at("127.0.0.1", 8080)), "same address and port"));

        Harness.test("the same address on another port does not reach the listener", () ->
                Expect.isFalse(reaches("127.0.0.1", 8081, at("127.0.0.1", 8080)), "other port"));

        Harness.test("the any-address on the listener's port reaches it", () -> {
            Expect.isTrue(reaches("0.0.0.0", 8080, at("127.0.0.1", 8080)), "0.0.0.0");
            Expect.isTrue(reaches("::", 8080, at("127.0.0.1", 8080)), "::");
        });

        Harness.test("another loopback address does not reach a listener bound to 127.0.0.1", () ->
                Expect.isFalse(reaches("::1", 8080, at("127.0.0.1", 8080)), "[::1]"));

        Harness.test("any address of this machine reaches a listener bound to all interfaces", () -> {
            Expect.isTrue(reaches("192.0.2.5", 8080, at("0.0.0.0", 8080)), "this machine");
            Expect.isFalse(reaches("198.51.100.1", 8080, at("0.0.0.0", 8080)), "another machine");
        });

        Harness.test("a listener bound to one LAN address is reached only at that address", () -> {
            Expect.isTrue(reaches("192.0.2.5", 8080, at("192.0.2.5", 8080)), "its own address");
            Expect.isFalse(reaches("127.0.0.1", 8080, at("192.0.2.5", 8080)), "loopback");
        });

        Harness.test("a second listener's address and port is refused too", () ->
                Expect.isTrue(reaches("127.0.0.1", 8081, at("127.0.0.1", 8080), at("127.0.0.1", 8081)),
                        "second listener"));

        Harness.test("loopback and the any-address count as this machine, a documentation address does not", () -> {
            for (String a : new String[] {"127.0.0.1", "127.0.0.2", "::1", "0.0.0.0"}) {
                Expect.isTrue(LoopGuard.isThisMachine(at(a, 1).getAddress()), a);
            }
            Expect.isFalse(LoopGuard.isThisMachine(at("203.0.113.7", 1).getAddress()), "203.0.113.7");
        });
    }
}
