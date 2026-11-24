package opentrap.extensions.store;

import java.util.Locale;

/** Maturity of an add-on release, as ZAP's feed names it. */
public enum Status {
    RELEASE(3), BETA(2), ALPHA(1);

    private final int rank;

    Status(int rank) { this.rank = rank; }

    /** Parses release, beta or alpha, ignoring case; anything else is refused. */
    public static Status parse(String s) {
        if (s != null) {
            for (Status st : values()) {
                if (st.label().equals(s.trim().toLowerCase(Locale.ROOT))) return st;
            }
        }
        throw new IllegalArgumentException("Unknown add-on status: " + s);
    }

    /** Higher is more stable. */
    public int rank() { return rank; }

    public String label() { return name().toLowerCase(Locale.ROOT); }
}
