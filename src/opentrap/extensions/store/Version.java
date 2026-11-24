package opentrap.extensions.store;

/**
 * A version of one to three dot-separated non-negative integers. Missing parts
 * are zero, so {@code 50} is 50.0.0 and {@code 0.13} is 0.13.0. ZAP numbers
 * add-ons with a plain integer, and its dependency ranges use the dotted form.
 */
public final class Version implements Comparable<Version> {

    private final String text;
    private final int[] parts;

    private Version(String text, int[] parts) {
        this.text = text;
        this.parts = parts;
    }

    public static Version parse(String s) {
        if (s == null) throw new IllegalArgumentException("Version is missing");
        String[] pieces = s.trim().split("\\.", -1);
        if (pieces.length > 3) throw new IllegalArgumentException("Not a version: " + s);
        int[] parts = new int[3];
        for (int i = 0; i < pieces.length; i++) parts[i] = number(pieces[i], s);
        return new Version(s.trim(), parts);
    }

    /** One part: ASCII digits only, so signs, spaces and non-Latin digits are refused. */
    private static int number(String piece, String whole) {
        if (piece.isEmpty() || piece.length() > 9) throw new IllegalArgumentException("Not a version: " + whole);
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Not a version: " + whole);
        }
        return Integer.parseInt(piece);
    }

    int part(int i) { return parts[i]; }

    @Override public int compareTo(Version o) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(parts[i], o.parts[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override public int hashCode() {
        return (parts[0] * 31 + parts[1]) * 31 + parts[2];
    }

    @Override public String toString() { return text; }
}
