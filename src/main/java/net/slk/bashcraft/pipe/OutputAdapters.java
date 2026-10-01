package net.slk.bashcraft.pipe;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact extraction for known messages, keyed on translation key so it works in any server language. */
final class OutputAdapters {
    private static final Pattern NBT_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?)[bBsSlLfFdD]?");

    private static final Map<String, Function<Feedback, Optional<PipeValue>>> ADAPTERS = Map.ofEntries(
            Map.entry("commands.locate.structure.success", OutputAdapters::coordinates),
            Map.entry("commands.locate.biome.success", OutputAdapters::coordinates),
            Map.entry("commands.locate.poi.success", OutputAdapters::coordinates),
            Map.entry("commands.data.entity.query", f -> data(f, 1)),
            Map.entry("commands.data.block.query", f -> data(f, 3)),
            Map.entry("commands.data.storage.query", f -> data(f, 1)),
            Map.entry("commands.time.query", f -> numeric(f, 0)),
            Map.entry("commands.time.query.gametime", f -> numeric(f, 0)),
            Map.entry("commands.time.query.absolute", f -> numeric(f, 1)),
            Map.entry("commands.time.query.timeline", f -> numeric(f, 1)),
            Map.entry("commands.time.query.timeline.repetitions", f -> numeric(f, 1)),
            Map.entry("commands.scoreboard.players.get.success", f -> numeric(f, 1)));

    private OutputAdapters() {}

    static Optional<PipeValue> adapt(Feedback feedback) {
        Function<Feedback, Optional<PipeValue>> adapter = feedback.key() == null ? null : ADAPTERS.get(feedback.key());
        return adapter == null ? Optional.empty() : adapter.apply(feedback);
    }

    /** The first {@code chat.coordinates} anywhere in the message. */
    static Optional<PipeValue> coordinates(Feedback feedback) {
        return feedback.find("chat.coordinates")
                .filter(c -> c.args().size() == 3)
                .map(c -> new PipeValue.Coordinates(c.argText(0).orElseThrow(), c.argText(1).orElseThrow(), c.argText(2).orElseThrow()));
    }

    private static Optional<PipeValue> numeric(Feedback feedback, int index) {
        return feedback.argText(index).map(PipeValue.Numeric::new);
    }

    /** NBT numbers lose their type suffix; quoted strings lose their quotes; anything else is text. */
    private static Optional<PipeValue> data(Feedback feedback, int index) {
        return feedback.argText(index).map(String::strip).map(text -> {
            Matcher m = NBT_NUMBER.matcher(text);
            if (m.matches()) {
                return new PipeValue.Numeric(m.group(1));
            }
            boolean quoted = text.length() >= 2
                    && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"));
            return new PipeValue.Text(quoted ? text.substring(1, text.length() - 1) : text);
        });
    }
}
