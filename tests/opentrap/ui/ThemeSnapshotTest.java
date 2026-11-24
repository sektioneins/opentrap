package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Color;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Pins every themeable value of the light and dark themes to a recorded file.
 *
 * <p>{@code theme-snapshot.txt} holds one line per theme and field:
 * {@code <light|dark> <FIELD> <value>}, sorted by theme, then field. A colour is
 * {@code #RRGGBB}, or {@code #RRGGBBAA} when its alpha is not 255; a number is
 * decimal; an enum is its constant lower-cased. The file is the look the
 * application had when the themes moved out of code, so a change that alters a
 * built-in theme shows up here as a differing line.
 */
public final class ThemeSnapshotTest {

    private static final Path FILE = Path.of("tests", "opentrap", "ui", "theme-snapshot.txt");

    /** Every static field of Theme that a theme may set: a Color, an int or a Theme enum. */
    private static List<Field> covered() {
        List<Field> out = new ArrayList<>();
        for (Field f : Theme.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            boolean enumOfTheme = t.isEnum() && t.getEnclosingClass() == Theme.class;
            if (t == Color.class || t == int.class || enumOfTheme) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static String format(Object v) {
        if (v instanceof Color c) {
            String rgb = String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
            return c.getAlpha() == 255 ? rgb : rgb + String.format("%02X", c.getAlpha());
        }
        if (v instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT);
        return String.valueOf(v);
    }

    /** The snapshot lines for one theme, as the file records them. Configures that theme. */
    public static List<String> current(boolean dark) {
        Theme.configure(dark);
        String theme = dark ? "dark" : "light";
        List<String> out = new ArrayList<>();
        for (Field f : covered()) {
            try {
                out.add(theme + " " + f.getName() + " " + format(f.get(null)));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    private static List<String> recorded() {
        try {
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(FILE)) if (!l.isBlank()) out.add(l);
            return out;
        } catch (IOException e) {
            throw new AssertionError("cannot read " + FILE, e);
        }
    }

    public void run() {
        Harness.suite("theme snapshot");

        Harness.test("light and dark resolve to the recorded values, field for field", () -> {
            try {
                List<String> actual = new ArrayList<>(current(false));
                actual.addAll(current(true));
                java.util.Collections.sort(actual);
                List<String> expected = recorded();
                StringBuilder diff = new StringBuilder();
                for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
                    String e = i < expected.size() ? expected.get(i) : "(none)";
                    String a = i < actual.size() ? actual.get(i) : "(none)";
                    if (!e.equals(a)) diff.append("\n  expected ").append(e).append("\n  actual   ").append(a);
                }
                Expect.isTrue(diff.length() == 0, "the themes differ from the snapshot:" + diff);
            } finally {
                Theme.configure(false);
            }
        });

        Harness.test("every themeable field has a recorded value in both themes", () -> {
            TreeSet<String> names = new TreeSet<>();
            for (Field f : covered()) names.add(f.getName());
            TreeSet<String> light = new TreeSet<>();
            TreeSet<String> dark = new TreeSet<>();
            for (String line : recorded()) {
                String[] p = line.split(" ", 3);
                Expect.isTrue(p.length == 3, "malformed line: " + line);
                Expect.isTrue(p[0].equals("light") || p[0].equals("dark"), "unknown theme in: " + line);
                Expect.isTrue(names.contains(p[1]), "the snapshot names a field Theme does not have: " + line);
                (p[0].equals("light") ? light : dark).add(p[1]);
            }
            Expect.equal(light, names, "fields recorded for light");
            Expect.equal(dark, names, "fields recorded for dark");
        });
    }
}
