package opentrap.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.function.Predicate;

/**
 * Decides whether a destination is one of the proxy's own listeners. A request
 * that resolves back to a listener would be forwarded to the proxy itself, which
 * forwards it again, until every connection slot is taken.
 */
final class LoopGuard {

    private LoopGuard() {}

    /**
     * True when connecting to {@code dest} reaches one of {@code listening}. Only
     * listeners on the same port count. The any-address is sent to this machine by
     * every OS; a listener bound to all interfaces is reached at any address that
     * {@code thisMachine} accepts; a listener bound to one address only at that one.
     */
    static boolean reaches(InetSocketAddress dest, Collection<InetSocketAddress> listening,
                           Predicate<InetAddress> thisMachine) {
        InetAddress d = dest.getAddress();
        if (d == null) return false;
        for (InetSocketAddress l : listening) {
            if (l.getPort() != dest.getPort()) continue;
            InetAddress bound = l.getAddress();
            if (d.isAnyLocalAddress()) return true;
            if (bound == null) continue;
            if (bound.isAnyLocalAddress() && thisMachine.test(d)) return true;
            if (bound.equals(d)) return true;
        }
        return false;
    }

    /** True for the any-address, loopback and every address of a local network interface. */
    static boolean isThisMachine(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(a) != null;
        } catch (SocketException _) {
            return false;
        }
    }
}
