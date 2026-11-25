package opentrap.ui.theme;

import opentrap.ui.Theme;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The keys a theme can set and the theme files that ship in the jar. The keys
 * are the static, non-final {@link Color}, {@code int} and enum fields of
 * {@link Theme}, so a new field there is a new key with no second list to keep.
 */
public final class ThemeKeys {
    private ThemeKeys() { }

    /** The allowed range of each number key. A number field without an entry here is a bug. */
    private static final Map<String, KeyType.Number> RANGES = Map.of(
        "RADIUS", new KeyType.Number(0, 12),
        "CARD_RADIUS", new KeyType.Number(0, 16),
        "TAB_HOVER_RADIUS", new KeyType.Number(0, 16),
        "LINE_WIDTH", new KeyType.Number(1, 2),
        "ICON_STROKE", new KeyType.Number(75, 150),
        "TAB_UNDERLINE", new KeyType.Number(1, 4));

    /** The key spec of {@link Theme}, in field order. */
    public static Map<String, KeyType> spec() {
        return spec(Theme.class);
    }

    static Map<String, KeyType> spec(Class<?> holder) {
        Map<String, KeyType> spec = new LinkedHashMap<>();
        for (Field f : holder.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
            Class<?> t = f.getType();
            if (t == Color.class) {
                spec.put(f.getName(), new KeyType.Colour());
            } else if (t == int.class) {
                KeyType.Number range = RANGES.get(f.getName());
                if (range == null) throw new IllegalStateException("no range for the number key " + f.getName());
                spec.put(f.getName(), range);
            } else if (t.isEnum() && t.getDeclaringClass() == holder) {
                List<String> words = new ArrayList<>();
                for (Object c : t.getEnumConstants()) words.add(((Enum<?>) c).name().toLowerCase(Locale.ROOT));
                spec.put(f.getName(), new KeyType.Choice(words));
            }
        }
        return spec;
    }

    /** Reads and parses a theme that ships in the jar, such as {@code light}. */
    public static ThemeFile builtIn(String id) throws ThemeException {
        String path = "/opentrap/ui/themes/" + id + ".theme";
        try (InputStream in = ThemeKeys.class.getResourceAsStream(path)) {
            if (in == null) throw new ThemeException(0, "no built-in theme " + id);
            return ThemeFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ThemeException(0, "cannot read built-in theme " + id + ": " + e.getMessage());
        }
    }
}
