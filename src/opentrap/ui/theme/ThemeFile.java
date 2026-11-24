package opentrap.ui.theme;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A parsed theme file: its header, its constants and its keys, with the line of
 * each so that later errors can point at it. Values stay as text; {@link ThemeExpr}
 * evaluates them against the type of their key. This class checks only what one
 * file can know on its own. Whether a base is required and what the keys mean is
 * for the resolver.
 */
public final class ThemeFile {

    public static final int MAX_BYTES = 65_536;
    public static final int MAX_LINES = 2_000;
    public static final int MAX_NAME = 64;
    public static final int FORMAT = 1;

    /** A value as written, and the line it was written on. */
    public record Def(String text, int line) { }

    private static final Pattern HEADER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern CONSTANT = Pattern.compile("@[a-z][a-z0-9_]*");
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]*");

    private int format = FORMAT;
    private String name, base, appearance, author, description;
    private final Map<String, Def> constants = new LinkedHashMap<>();
    private final Map<String, Def> keys = new LinkedHashMap<>();
    private final List<String> ignoredHeader = new ArrayList<>();

    private ThemeFile() { }

    public int format() { return format; }
    public String name() { return name; }
    /** {@code light} or {@code dark}, or null for a built-in base. */
    public String base() { return base; }
    public String appearance() { return appearance; }
    public String author() { return author; }
    public String description() { return description; }
    /** Constants by name without the {@code @}, in file order. */
    public Map<String, Def> constants() { return Collections.unmodifiableMap(constants); }
    public Map<String, Def> keys() { return Collections.unmodifiableMap(keys); }
    /** Lower-case header fields this version does not know, in file order. */
    public List<String> ignoredHeader() { return Collections.unmodifiableList(ignoredHeader); }

    public static ThemeFile parse(String text) throws ThemeException {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) throw new ThemeException(0, "theme is " + bytes + " bytes, above " + MAX_BYTES);
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r?\n", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) count--;
        if (count > MAX_LINES) throw new ThemeException(0, "theme has " + count + " lines, above " + MAX_LINES);

        ThemeFile f = new ThemeFile();
        Map<String, Integer> headerLines = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int no = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) throw new ThemeException(no, "expected name = value");
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.length() > MAX_NAME) {
                throw new ThemeException(no, "name is longer than " + MAX_NAME + " characters");
            }
            if (CONSTANT.matcher(key).matches()) {
                if (value.isEmpty()) throw new ThemeException(no, key + " has no value");
                Def prev = f.constants.putIfAbsent(key.substring(1), new Def(value, no));
                if (prev != null) throw new ThemeException(no, key + " is already set on line " + prev.line());
            } else if (KEY.matcher(key).matches()) {
                if (value.isEmpty()) throw new ThemeException(no, key + " has no value");
                Def prev = f.keys.putIfAbsent(key, new Def(value, no));
                if (prev != null) throw new ThemeException(no, key + " is already set on line " + prev.line());
            } else if (HEADER.matcher(key).matches()) {
                Integer prev = headerLines.putIfAbsent(key, no);
                if (prev != null) throw new ThemeException(no, key + " is already set on line " + prev);
                f.header(key, value, no);
            } else {
                throw new ThemeException(no, "bad name " + key);
            }
        }
        if (f.name == null) throw new ThemeException(0, "theme needs a name");
        if (f.appearance == null) throw new ThemeException(0, "theme needs an appearance");
        return f;
    }

    private void header(String key, String value, int no) throws ThemeException {
        switch (key) {
            case "format" -> {
                int n;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new ThemeException(no, "format takes a whole number, got " + value);
                }
                if (n > FORMAT) throw new ThemeException(no, "needs a newer OpenTRAP (format " + n + ")");
                if (n < 1) throw new ThemeException(no, "format takes a whole number from 1, got " + value);
                format = n;
            }
            case "name" -> name = nonEmpty(key, value, no);
            case "author" -> author = nonEmpty(key, value, no);
            case "description" -> description = nonEmpty(key, value, no);
            case "base" -> base = appearanceWord(key, value, no);
            case "appearance" -> appearance = appearanceWord(key, value, no);
            default -> ignoredHeader.add(key);
        }
    }

    private static String nonEmpty(String key, String value, int no) throws ThemeException {
        if (value.isEmpty()) throw new ThemeException(no, key + " has no value");
        return value;
    }

    private static String appearanceWord(String key, String value, int no) throws ThemeException {
        if (!value.equals("light") && !value.equals("dark")) {
            throw new ThemeException(no, key + " takes light or dark, got " + value);
        }
        return value;
    }
}
