package opentrap.session;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The suite-wide cookie jar. Every tool that sends through the session hook
 * feeds its responses here, so cookies observed in the proxy are available to
 * Repeater and Intruder without the tester copying them by hand.
 */
public final class CookieJar implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    public synchronized void set(Cookie c) {
        if (c == null || c.name == null || c.name.isEmpty()) return;
        cookies.put(c.key(), c);
    }

    public synchronized void remove(Cookie c) { if (c != null) cookies.remove(c.key()); }
    public synchronized void clear() { cookies.clear(); }
    public synchronized List<Cookie> all() { return new ArrayList<>(cookies.values()); }
    public synchronized int size() { return cookies.size(); }

    /** Store every Set-Cookie from a response against the host that sent it. */
    public synchronized void updateFrom(HttpService svc, HttpMessage resp) {
        if (svc == null || resp == null) return;
        for (String line : resp.headerLines()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")) {
                Cookie c = parseSetCookie(line.substring("set-cookie:".length()).trim(), svc.host());
                if (c != null) set(c);
            }
        }
    }

    /** The cookie a Set-Cookie value makes for {@code host}, or null when RFC 6265 5.3 says to ignore it. */
    private static Cookie parseSetCookie(String v, String host) {
        String reqHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (reqHost.isEmpty()) return null;
        String[] parts = v.split(";");
        if (parts.length == 0) return null;
        int eq = parts[0].indexOf('=');
        if (eq < 0) return null;
        Cookie c = new Cookie();
        c.name = parts[0].substring(0, eq).trim();
        c.value = parts[0].substring(eq + 1).trim();
        // Without a Domain attribute the cookie belongs to the sending host alone
        // (RFC 6265 5.3 step 6). A literally empty Domain (or "Domain=.") still counts
        // as the last Domain attribute and leaves the cookie host-only, as Chromium
        // does; RFC 6265 5.2.3 says to ignore the attribute, which would let an earlier
        // Domain stand.
        c.domain = reqHost;
        c.hostOnly = true;
        c.path = "/";
        String domainAttr = "";   // empty: host-only
        for (int i = 1; i < parts.length; i++) {
            String a = parts[i].trim();
            String al = a.toLowerCase(Locale.ROOT);
            if (al.startsWith("domain=")) {
                String d = a.substring(7).trim();
                if (d.startsWith(".")) d = d.substring(1);
                domainAttr = d.toLowerCase(Locale.ROOT);   // the last one wins, even when empty (see above)
            } else if (al.startsWith("path=")) c.path = a.substring(5).trim();
            else if (al.equals("secure")) c.secure = true;
            else if (al.startsWith("max-age=")) {
                try { c.expires = System.currentTimeMillis() + Long.parseLong(a.substring(8).trim()) * 1000L; }
                catch (Exception _) {}
            } else if (al.startsWith("expires=")) {
                try {
                    c.expires = java.time.ZonedDateTime.parse(a.substring(8).trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                } catch (Exception _) {}
            }
        }
        if (!domainAttr.isEmpty()) {
            // A Domain the host may not set (5.3 steps 5 and 6) is ignored: it must
            // domain-match the host, checked first so a junk TLD never reaches the
            // suffix list. A public suffix is kept host-only when it is the host itself.
            if (!Cookie.domainMatch(reqHost, domainAttr)) return null;
            // A trailing dot is ignored for the public-suffix check only.
            if (!PublicSuffixes.jdk().isPublicSuffix(stripTrailingDot(domainAttr))) {
                c.domain = domainAttr;
                c.hostOnly = false;
            } else if (!domainAttr.equals(reqHost)) return null;
        }
        return c.name.isEmpty() ? null : c;
    }

    private static String stripTrailingDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The Cookie header value for a request, with one value per cookie name. A
     * sibling under the same parent domain may set a cookie of the same name (see
     * {@link #parseSetCookie}), and ZAP's jar sends one per name as well, so of the
     * matching jar cookies of a name the most specific wins: a hand-added one, then
     * a host-only one, then the longer domain, the longer path, and the later one
     * in the jar. A learned cookie with the same {@link Cookie#key()} as a hand-added
     * one still replaces it in {@link #set}.
     * The request's own cookies keep their order; the first of a name the jar has a
     * winner for takes the winner's value, later ones of that name are dropped, so
     * session handling can refresh a stale session in Repeater and Intruder. Winners
     * for names the request lacks follow, longest path first and earlier in the jar
     * first within a path (RFC 6265 5.4). This holds with or without a Cookie header.
     * Returns null when nothing from the jar applies and the request had none.
     */
    public synchronized String headerFor(HttpService svc, HttpMessage req, String existingCookie) {
        String path = req == null ? "/" : req.path();
        Map<String, Cookie> winners = new LinkedHashMap<>();   // name -> winner, in the slot of the first match
        Map<Cookie, Integer> position = new java.util.IdentityHashMap<>();
        int n = 0;
        for (Cookie c : cookies.values()) {
            position.put(c, n++);
            if (!c.matches(svc, path)) continue;
            Cookie best = winners.get(c.name);
            if (best == null || moreSpecific(c, best)) winners.put(c.name, c);
        }
        if (winners.isEmpty() && existingCookie == null) return null;
        List<String> pieces = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (existingCookie != null) {
            for (String p : existingCookie.split(";")) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                int eq = t.indexOf('=');
                String name = eq < 0 ? t : t.substring(0, eq).trim();
                Cookie w = winners.get(name);
                if (w == null) pieces.add(eq < 0 ? name + "=" : name + "=" + t.substring(eq + 1).trim());
                else if (used.add(name)) pieces.add(name + "=" + w.value);
            }
        }
        List<Cookie> rest = new ArrayList<>();
        for (Cookie w : winners.values()) if (!used.contains(w.name)) rest.add(w);
        rest.sort((a, b) -> {
            int byPath = Integer.compare(b.path.length(), a.path.length());
            return byPath != 0 ? byPath : Integer.compare(position.get(a), position.get(b));
        });
        for (Cookie w : rest) pieces.add(w.name + "=" + w.value);
        return String.join("; ", pieces);
    }

    /** True when {@code c} beats {@code best}; {@code c} is later in the jar, so a tie goes to it. */
    private static boolean moreSpecific(Cookie c, Cookie best) {
        if (c.manual != best.manual) return c.manual;
        if (c.hostOnly != best.hostOnly) return c.hostOnly;
        int dc = domainLength(c), db = domainLength(best);
        if (dc != db) return dc > db;
        if (c.path.length() != best.path.length()) return c.path.length() > best.path.length();
        return true;
    }

    private static int domainLength(Cookie c) {
        return c.domain.startsWith(".") ? c.domain.length() - 1 : c.domain.length();
    }
}
