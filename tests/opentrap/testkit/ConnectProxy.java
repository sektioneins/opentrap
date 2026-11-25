package opentrap.testkit;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal HTTP CONNECT proxy on loopback that pipes bytes both ways. With a
 * {@code user:pass} login it answers 407 to a head without that Basic login.
 * It counts tunnels and keeps every Proxy-Authorization and every request
 * target it was sent. A head that is not a CONNECT (an absolute-form request)
 * is recorded and answered with a fixed 200, then the connection closes.
 */
public final class ConnectProxy implements AutoCloseable {
    private final ServerSocket socket;
    private final String required;
    private final InetSocketAddress forwardTo;
    public final AtomicInteger tunnels = new AtomicInteger();
    public final List<String> logins = Collections.synchronizedList(new ArrayList<>());
    /** The request target of every head accepted, as sent: {@code host:port} or an absolute URI. */
    public final List<String> targets = Collections.synchronizedList(new ArrayList<>());

    /** Pipes each tunnel to the CONNECT target. */
    public ConnectProxy(String login) throws IOException {
        this(login, null);
    }

    /** Pipes every tunnel to {@code forwardTo} whatever the CONNECT names. */
    public ConnectProxy(String login, InetSocketAddress forwardTo) throws IOException {
        required = login == null ? null : "Basic " + Base64.getEncoder().encodeToString(login.getBytes());
        this.forwardTo = forwardTo;
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread.ofVirtual().start(() -> tunnel(client));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    public int port() { return socket.getLocalPort(); }

    private void tunnel(Socket client) {
        try (client) {
            var in = client.getInputStream();
            String head;
            while (true) {
                head = head(in);
                if (head == null) return;
                String login = null;
                for (String line : head.split("\r\n")) {
                    if (line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) login = line.substring(20).trim();
                }
                if (login != null) logins.add(login);
                if (required == null || required.equals(login)) break;
                client.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n").getBytes());
                client.getOutputStream().flush();
            }
            String[] line = head.split("\r\n")[0].split(" ");
            targets.add(line[1]);
            if (!line[0].equals("CONNECT")) {
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n"
                        + "Connection: close\r\n\r\nproxy").getBytes());
                client.getOutputStream().flush();
                return;
            }
            String[] target = line[1].split(":");
            try (Socket upstream = forwardTo != null
                    ? new Socket(forwardTo.getAddress(), forwardTo.getPort())
                    : new Socket(target[0], Integer.parseInt(target[1]))) {
                tunnels.incrementAndGet();
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
                client.getOutputStream().flush();
                Thread back = Thread.ofVirtual().start(() -> {
                    try { upstream.getInputStream().transferTo(client.getOutputStream()); }
                    catch (IOException ignored) { }
                });
                try { in.transferTo(upstream.getOutputStream()); } catch (IOException ignored) { }
                upstream.shutdownOutput();
                back.join();
            }
        } catch (IOException | InterruptedException ignored) {
            // the test sees a failed send
        }
    }

    /** One request head up to the blank line, or null at the end of the stream. */
    private static String head(java.io.InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) return null;
            head.append((char) b);
        }
        return head.toString();
    }

    @Override public void close() throws IOException { socket.close(); }
}
