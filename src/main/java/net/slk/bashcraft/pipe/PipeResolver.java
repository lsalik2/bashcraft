package net.slk.bashcraft.pipe;

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
}
