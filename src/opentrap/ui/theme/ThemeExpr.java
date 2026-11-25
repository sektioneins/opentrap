package opentrap.ui.theme;

import java.awt.Color;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates one theme value: a colour (hex, a constant or a function of
 * colours), a whole number (numbers and constants joined by + and -) or one word
 * of a choice. Errors name the key and carry the line of the value.
 */
public final class ThemeExpr {

    private static final int MAX_DEPTH = 8;
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})(\\.\\d)?%");
    private static final Pattern HEX = Pattern.compile("#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})");
    private static final Pattern NAME = Pattern.compile("@[a-z][a-z0-9_]*");
    private static final Pattern IDENT = Pattern.compile("[a-z][a-zA-Z0-9_]*");
    private static final Pattern WHOLE = Pattern.compile("\\d{1,9}");

    private final String name;
    private final String text;
    private final Function<String, Object> constant;
    private final int line;
    private int pos;

    private ThemeExpr(String name, String text, Function<String, Object> constant, int line) {
        this.name = name;
        this.text = text;
        this.constant = constant;
        this.line = line;
    }

    /**
     * Evaluates {@code text} as the value of a key of the given type, naming it
     * {@code value} in errors. Use the overload with a name where there is one.
     *
     * @return a {@link Color}, an {@link Integer} or the choice word
     */
    public static Object eval(String text, KeyType type, Function<String, Object> constant, int line)
            throws ThemeException {
        return eval("value", text, type, constant, line);
    }

    /**
     * Evaluates {@code text} as the value of the key {@code name}.
     *
     * @param constant returns the value of a constant, or null when it is unknown
     * @return a {@link Color}, an {@link Integer} or the choice word
     */
    public static Object eval(String name, String text, KeyType type, Function<String, Object> constant,
                              int line) throws ThemeException {
        ThemeExpr p = new ThemeExpr(name, text.strip(), constant, line);
        return switch (type) {
            case KeyType.Colour c -> p.colourValue();
            case KeyType.Number n -> p.numberValue(n);
            case KeyType.Choice c -> p.choiceValue(c);
        };
    }

    /** Evaluates the value of a constant, whose type comes from the value itself. */
    public static Object evalConstant(String text, Function<String, Object> constant, int line)
            throws ThemeException {
        String t = text.strip();
        ThemeExpr p = new ThemeExpr("constant", t, constant, line);
        if (NAME.matcher(t).matches()) return p.lookup(t);
        if (t.startsWith("#") || IDENT.matcher(t).lookingAt()) return p.colourValue();
        if (t.startsWith("@") || WHOLE.matcher(t).lookingAt()) return p.numberExpr();
        throw p.error("needs a colour or a number, got " + shown(t));
    }

    // --- values by key type ---

    private Color colourValue() throws ThemeException {
        Color c = colour(1);
        skip();
        if (pos < text.length()) throw error("needs a colour, got " + shown(text));
        return c;
    }

    private Integer numberValue(KeyType.Number range) throws ThemeException {
        int n = numberExpr();
        if (n > range.max()) throw error("is " + n + ", above " + range.max());
        if (n < range.min()) throw error("is " + n + ", below " + range.min());
        return n;
    }

    private String choiceValue(KeyType.Choice choice) throws ThemeException {
        if (choice.words().contains(text)) return text;
        throw error("takes " + join(choice.words()) + ", got " + shown(text));
    }

    private static String join(List<String> words) {
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " or " + words.get(words.size() - 1);
    }

    // --- colours ---

    private Color colour(int depth) throws ThemeException {
        skip();
        if (peek() == '#') {
            Matcher m = HEX.matcher(text);
            m.region(pos, text.length());
            if (!m.lookingAt()) throw error("needs a colour, got " + shown(text));
            pos = m.end();
            String h = m.group(1);
            int rgb = Integer.parseInt(h.substring(0, 6), 16);
            int a = h.length() == 8 ? Integer.parseInt(h.substring(6), 16) : 255;
            return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a);
        }
        if (peek() == '@') {
            String ref = word();
            if (lookup(ref) instanceof Color c) return c;
            throw error("needs a colour, " + shown(ref) + " is a number");
        }
        Matcher id = IDENT.matcher(text);
        id.region(pos, text.length());
        if (id.lookingAt() && id.end() < text.length() && text.charAt(id.end()) == '(') {
            pos = id.end() + 1;
            return function(id.group(), depth);
        }
        throw error("needs a colour, got " + shown(text));
    }

    private Color function(String fn, int depth) throws ThemeException {
        if (!fn.equals("darker") && !fn.equals("lighter") && !fn.equals("mix") && !fn.equals("alpha")) {
            throw new ThemeException(line, "unknown function " + shown(fn));
        }
        if (depth > MAX_DEPTH) throw error("nests functions deeper than " + MAX_DEPTH);
        Color a = colour(depth + 1);
        Color b = null;
        if (fn.equals("mix")) {
            comma();
            b = colour(depth + 1);
        }
        comma();
        double p = percent();
        skip();
        if (peek() != ')') throw error("is missing a closing bracket in " + shown(text));
        pos++;
        return switch (fn) {
            case "darker" -> new Color(ch(a.getRed() * (1 - p)), ch(a.getGreen() * (1 - p)),
                ch(a.getBlue() * (1 - p)), a.getAlpha());
            case "lighter" -> new Color(ch(a.getRed() + (255 - a.getRed()) * p),
                ch(a.getGreen() + (255 - a.getGreen()) * p), ch(a.getBlue() + (255 - a.getBlue()) * p),
                a.getAlpha());
            case "mix" -> new Color(ch(a.getRed() * p + b.getRed() * (1 - p)),
                ch(a.getGreen() * p + b.getGreen() * (1 - p)), ch(a.getBlue() * p + b.getBlue() * (1 - p)),
                ch(a.getAlpha() * p + b.getAlpha() * (1 - p)));
            default -> new Color(a.getRed(), a.getGreen(), a.getBlue(), ch(255 * p));
        };
    }

    private static int ch(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private double percent() throws ThemeException {
        skip();
        int end = pos;
        while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != ')') end++;
        String tok = text.substring(pos, end).strip();
        if (!PERCENT.matcher(tok).matches()
                || Double.parseDouble(tok.substring(0, tok.length() - 1)) > 100) {
            throw error("needs a percent from 0% to 100% with at most one decimal, got " + shown(tok));
        }
        pos = end;
        return Double.parseDouble(tok.substring(0, tok.length() - 1)) / 100;
    }

    private void comma() throws ThemeException {
        skip();
        if (peek() != ',') throw error("needs a comma in " + shown(text));
        pos++;
    }

    // --- numbers ---

    private int numberExpr() throws ThemeException {
        long sum = term();
        for (;;) {
            skip();
            char c = peek();
            if (c != '+' && c != '-') break;
            pos++;
            long t = term();
            sum = c == '+' ? sum + t : sum - t;
        }
        if (pos < text.length()) throw error("needs a number, got " + shown(text));
        if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) throw error("is out of range");
        return (int) sum;
    }

    private long term() throws ThemeException {
        skip();
        if (peek() == '@') {
            String ref = word();
            if (lookup(ref) instanceof Integer i) return i;
            throw error("needs a number, " + shown(ref) + " is a colour");
        }
        Matcher m = WHOLE.matcher(text);
        m.region(pos, text.length());
        if (!m.lookingAt()) throw error("needs a number, got " + shown(text));
        pos = m.end();
        return Long.parseLong(m.group());
    }

    // --- shared ---

    private Object lookup(String ref) throws ThemeException {
        Object v = constant.apply(ref.substring(1));
        if (v == null) throw new ThemeException(line, "unknown constant " + shown(ref));
        return v;
    }

    /** Reads a {@code @name} at the cursor. */
    private String word() throws ThemeException {
        Matcher m = NAME.matcher(text);
        m.region(pos, text.length());
        if (!m.lookingAt()) throw error("needs a constant name after @, got " + shown(text));
        pos = m.end();
        return m.group();
    }

    private void skip() {
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private static String shown(String value) {
        return ThemeException.excerpt(value);
    }

    private ThemeException error(String message) {
        return new ThemeException(line, name + " " + message);
    }
}
