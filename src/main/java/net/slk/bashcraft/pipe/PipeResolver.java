package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;

/** Turns a command's captured output into the value piped to the next command. */
public final class PipeResolver {
    private PipeResolver() {}

    /** Uses the last message: its adapter if there is one, otherwise the fallback extractor. */
    public static PipeValue resolve(List<Feedback> output) throws PipeException {
        if (output.isEmpty()) {
            throw new PipeException("produced no output");
        }
        Feedback last = output.getLast();
        PipeValue value = OutputAdapters.adapt(last).orElseGet(() -> FeedbackExtractor.extract(last));
        if (value instanceof PipeValue.Text text && text.text().isBlank()) {
            throw new PipeException("produced no output");
        }
        return value;
    }

    /** One item per output message that yields a value (a list message contributes all its items). */
    public static List<String> items(List<Feedback> output) {
        List<String> items = new ArrayList<>();
        for (Feedback message : output) {
            try {
                items.addAll(resolve(List.of(message)).items());
            } catch (PipeException e) {
                // A message without a value adds no item.
            }
        }
        return items;
    }
}
