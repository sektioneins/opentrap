package opentrap.core;

/**
 * An optional hook the {@link HttpClient} consults around each send, so session
 * handling can run for any tool without the client knowing what a session is.
 *
 * <p>Defined in core and implemented in the session package, mirroring how the
 * context already reaches the out-of-band collaborator: core owns the contract,
 * the feature owns the logic.
 */
public interface SessionHook {

    /** Mutate the outgoing request before it goes on the wire (e.g. add cookies). */
    void beforeSend(String tool, HttpService svc, HttpMessage req);

    /**
     * Learn from the response (e.g. store Set-Cookie), and decide whether the
     * request should be sent again once. Returning true asks the client to
     * re-run {@link #beforeSend} and resend, which is how a rule re-authenticates
     * after detecting an expired session. It gets the last response of a
     * redirect chain only; the earlier ones go to {@link #onRedirect}.
     */
    boolean afterResponse(String tool, HttpService svc, HttpMessage req, HttpMessage resp);

    /**
     * Called with each redirect response the client is about to follow, {@code svc}
     * and {@code req} being the hop that got it. Learn from it (store its
     * Set-Cookie), nothing more: only the last response, given to
     * {@link #afterResponse}, can ask for a resend.
     */
    default void onRedirect(String tool, HttpService svc, HttpMessage req, HttpMessage resp) {}
}
