package opentrap.extensions.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import opentrap.core.HttpService;
import opentrap.core.OwnerOnly;
import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;

/**
 * The store's only network code: feed fetches and add-on downloads from
 * untrusted servers. HTTPS only, at every redirect too; certificates checked
 * against the trust store it is given (the JDK's, never the suite's trust-all
 * client); the suite's HTTP upstream proxy rules, looked up per host; a size cap
 * on every body. A download is refused unless its sha256 is the feed's and the
 * file is the add-on the entry names.
 */
public final class Fetcher {

    /** How far a download is; {@code total} is -1 when not known. */
    public interface Progress { void bytes(long done, long total); }

    public static final long FEED_CAP = 10L << 20;
    private static final long DOWNLOAD_CAP = 200L << 20;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FEED_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]+");

    static final String SOCKS_REFUSED = "Add-on sources cannot be fetched through a SOCKS upstream proxy";

    private final SSLContext tls;
    private final Function<String, UpstreamProxy> upstream;

    /** {@code upstream} gives the upstream proxy rule for a host, or null to connect directly. */
    public Fetcher(SSLContext tls, Function<String, UpstreamProxy> upstream) {
        this.tls = tls;
        this.upstream = upstream;
    }

    /** The JDK's trust store and the suite's upstream proxy rules. */
    public static Fetcher forSuite(SuiteContext ctx) {
        try {
            // the rules match on the host only, so the port given here does not change the rule found
            return new Fetcher(SSLContext.getDefault(),
                    host -> ctx.upstreamProxies().proxyFor(new HttpService(host, 443, true)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The body at {@code url}, refused once it is longer than {@code maxBytes}. */
    public byte[] fetch(String url, long maxBytes) throws IOException {
        return get(url, FEED_TIMEOUT, (in, length) -> {
            if (length > maxBytes) throw tooLarge(url, maxBytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, url, maxBytes, n -> {}, () -> false);
            return out.toByteArray();
        });
    }

    /**
     * Downloads the entry's file into {@code dir} as {@code .<id>.part}, checks
     * its size, sha256 and contents, and renames it to {@code e.file()}. The part
     * file is gone after any failure, cancel included.
     */
    public Path download(Entry e, Path dir, Progress progress, BooleanSupplier cancelled) throws IOException {
        if (!ID.matcher(e.id()).matches()) throw new IOException("Refused an add-on id that is not a plain name");
        long cap = e.size() > 0 ? e.size() : DOWNLOAD_CAP;
        Path part = dir.resolve("." + e.id() + ".part");
        Files.deleteIfExists(part);  // a part file left by a crash may be wider than owner-only
        boolean done = false;
        try {
            MessageDigest sha = sha256();
            get(e.location(), null, (in, length) -> {
                if (length > cap) throw tooLarge(e.location(), cap);
                long total = e.size() > 0 ? e.size() : length;
                try (OutputStream out = Channels.newOutputStream(Files.newByteChannel(part,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly(dir)))) {
                    copy(in, new DigestOutputStream(out, sha), e.location(), cap,
                            n -> progress.bytes(n, total), cancelled);
                }
                return null;
            });
            if (!HexFormat.of().formatHex(sha.digest()).equals(e.sha256())) {
                throw new IOException("Checksum mismatch for " + e.file());
            }
            ZapAddOnFile.check(part, e.file(), e);
            // check() accepted e.file() as <id>-<status>-<version>.zap, so it names a file directly in dir
            Path target = dir.resolve(e.file());
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            done = true;
            return target;
        } finally {
            if (!done) Files.deleteIfExists(part);
        }
    }

    private interface Body<T> { T read(InputStream in, long length) throws IOException; }

    /**
     * GETs {@code url}, following up to {@link #MAX_REDIRECTS} redirects to HTTPS
     * addresses only, and hands a 200 body and its declared length (-1 when not
     * given) to {@code body}. A null timeout leaves the request without one.
     */
    private <T> T get(String url, Duration timeout, Body<T> body) throws IOException {
        URI uri = https(url, "Refused a non-HTTPS address: " + url);
        for (int redirects = 0; ; redirects++) {
            HttpClient client = client(uri.getHost());
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(uri).GET();
                if (timeout != null) req.timeout(timeout);
                HttpResponse<InputStream> r = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body()) {
                    int status = r.statusCode();
                    if (REDIRECTS.contains(status)) {
                        if (redirects == MAX_REDIRECTS) throw new IOException(url + ": more than " + MAX_REDIRECTS + " redirects");
                        String location = r.headers().firstValue("Location")
                                .orElseThrow(() -> new IOException(r.uri() + " redirects without a Location"));
                        uri = https(resolve(uri, location), "Refused a redirect to a non-HTTPS address: " + location);
                        continue;
                    }
                    if (status != 200) throw new IOException(uri + " answered HTTP " + status);
                    return body.read(in, r.headers().firstValueAsLong("Content-Length").orElse(-1));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException(url + ": interrupted");
            } finally {
                // the body has been read or abandoned; nothing of this exchange is wanted any more
                client.shutdownNow();
            }
        }
    }

    /** A client for one host: its upstream rule, the checked trust store, no redirects of its own. */
    private HttpClient client(String host) throws IOException {
        HttpClient.Builder b = HttpClient.newBuilder()
                .sslContext(tls)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        UpstreamProxy p = upstream.apply(host);
        if (p == null) return b.proxy(HttpClient.Builder.NO_PROXY).build();
        if (p.type == UpstreamProxy.Type.SOCKS) throw new IOException(SOCKS_REFUSED);
        b.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(p.host, p.port)));
        if (p.user != null && !p.user.isEmpty()) {
            String user = p.user;
            char[] pass = p.pass == null ? new char[0] : p.pass.toCharArray();
            b.authenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    // the proxy's credentials, never handed to a server that asks for a login
                    return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(user, pass) : null;
                }
            });
        }
        return b.build();
    }

    private static URI https(String url, String refusal) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(refusal);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IOException(refusal);
        return uri;
    }

    private static String resolve(URI base, String location) throws IOException {
        try {
            return base.resolve(location).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException(base + " redirects to an address that cannot be read: " + location);
        }
    }

    /** Copies at most {@code cap} bytes and fails on the byte after; checks {@code cancelled} before each block. */
    private static void copy(InputStream in, OutputStream out, String url, long cap, LongConsumer progress,
                             BooleanSupplier cancelled) throws IOException {
        byte[] buf = new byte[8192];
        long done = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download of " + url + " cancelled");
            done += n;
            if (done > cap) throw tooLarge(url, cap);
            out.write(buf, 0, n);
            progress.accept(done);
        }
    }

    private static IOException tooLarge(String url, long cap) {
        return new IOException(url + " is larger than " + cap + " bytes");
    }

    private static FileAttribute<?>[] ownerOnly(Path dir) {
        return dir.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[]{ PosixFilePermissions.asFileAttribute(OwnerOnly.FILE) }
                : new FileAttribute<?>[0];
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
