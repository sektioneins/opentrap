package opentrap.proxy;

import opentrap.core.Debug;
import opentrap.core.SuiteContext;
import opentrap.core.WebSocketMessage;
import opentrap.core.WebSocketMessage.Direction;
import opentrap.core.WebSocketMessage.Op;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One upgraded WebSocket connection. It pumps frames both ways between the
 * browser and the upstream server, recording each into the suite's WebSocket
 * store, optionally pausing text/binary frames for interception, and letting the
 * tester inject new frames (the WebSocket equivalent of Repeater).
 */
public final class WebSocketConnection {

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final List<WebSocketConnection> LIVE = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LIVE_LISTENERS = new CopyOnWriteArrayList<>();

    /** Global toggle, driven by the WebSocket tool's intercept checkbox. */
    public static volatile boolean intercepting = false;
    private static volatile WsInterceptor interceptor;

    public static List<WebSocketConnection> live()            { return LIVE; }
    public static void setInterceptor(WsInterceptor i)        { interceptor = i; }
    public static void addLiveListener(Runnable r)            { LIVE_LISTENERS.add(r); }
    private static void liveChanged() { for (Runnable r : LIVE_LISTENERS) r.run(); }

    public final int id = IDS.incrementAndGet();
    public final opentrap.core.HttpService service;
    private final String path; // the handshake request's path
    private final SuiteContext ctx;
    private final OutputStream clientOut; // to the browser (frames unmasked)
    private final OutputStream serverOut; // to the upstream server (frames masked)
    private final List<MatchReplaceRule> rules; // WebSocket match and replace, shared with the proxy
    private final int frameCap; // largest payload held whole; larger frames are streamed
    private volatile boolean open = true;

    /** {@code frameCap} is the largest payload in bytes the relay holds whole, at least 1. */
    public WebSocketConnection(SuiteContext ctx, opentrap.core.HttpService service, String path,
                               OutputStream clientOut, OutputStream serverOut,
                               List<MatchReplaceRule> rules, int frameCap) {
        if (frameCap < 1) throw new IllegalArgumentException("frameCap must be at least 1");
        this.ctx = ctx;
        this.service = service;
        this.path = path;
        this.clientOut = clientOut;
        this.serverOut = serverOut;
        this.rules = rules;
        this.frameCap = frameCap;
    }

    @Override public String toString() { return "#" + id + " " + (service == null ? "" : service.host()); }

    /** Relay until either side closes. Blocks the calling thread on the C2S pump. */
    public void run(InputStream clientIn, InputStream serverIn) {
        LIVE.add(this);
        liveChanged();
        Thread s2c = Thread.ofVirtual().name("ws-s2c-" + id).start(() -> {
            try { pump(serverIn, Direction.S2C); }
            catch (RuntimeException e) {
                // Normally the IOException path in pump() ends a relay; a
                // runtime failure would otherwise vanish and look like a
                // silently dead direction.
                Debug.error("websocket s2c relay failed for " + service, e);
            }
        });
        try {
            pump(clientIn, Direction.C2S); // this thread handles client-to-server
        } finally {
            // Every exit path lands here: a runtime failure in the pump must
            // not leave a stale "live" entry in the WebSockets panel.
            open = false;
            try { s2c.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            LIVE.remove(this);
            liveChanged();
        }
    }

    /**
     * Relay frames from {@code in} until it ends or a close frame passes. The cap
     * decides between holding a frame and streaming it: the header comes first, and
     * only a frame whose declared length fits the cap is read whole, because a
     * header alone must not size a buffer. A larger frame is passed on as it
     * arrives, with its first part recorded, and skips match and replace and
     * interception, which both need the whole payload.
     */
    private void pump(InputStream in, Direction dir) {
        try {
            while (open) {
                WebSocketFrame f = WebSocketFrame.readHeader(in);
                if (f == null) break;
                Op op = WebSocketMessage.opFrom(f.opcode);
                if (f.length > frameCap) {
                    stream(f, in, dir, op);
                    if (op == Op.CLOSE) break;
                    continue;
                }
                f.readPayload(in);
                byte[] payload = f.payload;
                boolean edited = false;

                // Match and replace runs on whole text messages only (a single
                // unfragmented frame), before interception sees them.
                if (f.fin && op == Op.TEXT && rules != null && !rules.isEmpty()) {
                    String text = new String(payload, StandardCharsets.UTF_8);
                    String replaced = MatchReplaceRule.applyAllWs(rules, text, dir == Direction.C2S);
                    if (!replaced.equals(text)) {
                        payload = replaced.getBytes(StandardCharsets.UTF_8);
                        edited = true;
                    }
                }

                if (intercepting && interceptor != null && (op == Op.TEXT || op == Op.BINARY)) {
                    WebSocketMessage probe = new WebSocketMessage(id, service, path, dir, op, payload, false);
                    WsInterceptor.Decision d = interceptor.intercept(probe);
                    if (d != null && d.drop) continue; // drop: do not forward or log
                    if (d != null && d.payload != null && !Arrays.equals(d.payload, payload)) {
                        payload = d.payload;
                        edited = true;
                    }
                }

                ctx.wsStore().add(new WebSocketMessage(id, service, path, dir, op, payload, edited));
                OutputStream target = dir == Direction.C2S ? serverOut : clientOut;
                byte[] out = WebSocketFrame.encode(f.fin, f.opcode, payload, dir == Direction.C2S);
                synchronized (target) { target.write(out); target.flush(); }
                if (op == Op.CLOSE) break;
            }
        } catch (IOException _) {
        } finally {
            open = false;
        }
    }

    /**
     * Pass one frame above the cap through as it arrives. The target's lock is held
     * for the whole frame, header and payload, so an injected frame cannot land
     * inside it. The frame is recorded once it is through, with its first
     * {@code frameCap} bytes and its declared length.
     */
    private void stream(WebSocketFrame f, InputStream in, Direction dir, Op op) throws IOException {
        OutputStream target = dir == Direction.C2S ? serverOut : clientOut;
        byte[] key = dir == Direction.C2S ? WebSocketFrame.newMaskKey() : null;
        byte[] kept;
        synchronized (target) {
            target.write(WebSocketFrame.encodeHeader(f.fin, f.opcode, f.length, key));
            kept = f.streamPayload(in, target, key, frameCap);
            target.flush();
        }
        ctx.wsStore().add(new WebSocketMessage(id, service, path, dir, op, kept, false, f.length));
    }

    /** Inject a new text frame towards the server, as if the client sent it. */
    public void sendTextToServer(String text) throws IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = WebSocketFrame.encode(true, 1, p, true);
        synchronized (serverOut) { serverOut.write(frame); serverOut.flush(); }
        ctx.wsStore().add(new WebSocketMessage(id, service, path, Direction.C2S, Op.TEXT, p, true));
    }
}
