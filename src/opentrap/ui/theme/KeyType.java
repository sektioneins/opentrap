package opentrap.ui.theme;

import java.util.List;

/** What a theme key holds: a colour, a whole number in a range, or one word of a fixed set. */
public sealed interface KeyType {

    record Colour() implements KeyType { }

    record Number(int min, int max) implements KeyType { }

    record Choice(List<String> words) implements KeyType {
        public Choice { words = List.copyOf(words); }
    }
}
