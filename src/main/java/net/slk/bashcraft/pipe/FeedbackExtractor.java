package net.slk.bashcraft.pipe;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fallback for messages without an adapter: coordinates, then the last number, then the whole text. */
final class FeedbackExtractor {
    private static final String NUM = "-?\\d+(?:\\.\\d+)?";
    private static final String COORD = "[~^]?(?:-?\\d+(?:\\.\\d+)?)?";
    /** A whole message that is exactly three coordinates, as typed ({@code 100 ~ -20}, {@code ^ ^1.5 ^}). */
    private static final Pattern SPACED = Pattern.compile("\\s*(" + COORD + ") (" + COORD + ") (" + COORD + ")\\s*");
    private static final Pattern TRIPLE = Pattern.compile("(" + NUM + "|~)\\s*,\\s*(" + NUM + "|~)\\s*,\\s*(" + NUM + "|~)");
    /** A standalone number, optionally with an NBT type suffix (20.0f): not glued to letters, ids or other digits. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.:-])(" + NUM + ")[bBsSlLfFdD]?(?![\\w:]|\\.\\d)");

    private FeedbackExtractor() {}

    static PipeValue extract(Feedback feedback) {
        Optional<PipeValue> coordinates = OutputAdapters.coordinates(feedback);
        if (coordinates.isPresent()) {
            return coordinates.get();
        }
        String text = feedback.text();
        Matcher spaced = SPACED.matcher(text);
        if (spaced.matches() && !spaced.group(1).isEmpty() && !spaced.group(2).isEmpty() && !spaced.group(3).isEmpty()) {
            return new PipeValue.Coordinates(spaced.group(1), spaced.group(2), spaced.group(3));
        }
        Matcher triple = TRIPLE.matcher(text);
        if (triple.find()) {
            return new PipeValue.Coordinates(triple.group(1), triple.group(2), triple.group(3));
        }
        Matcher number = NUMBER.matcher(text);
        String last = null;
        while (number.find()) {
            last = number.group(1);
        }
        return last != null ? new PipeValue.Numeric(last) : new PipeValue.Text(text.strip());
    }
}
