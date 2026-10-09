package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.Feedback;
import net.slk.bashcraft.pipe.PipeValue;

import java.util.List;

/**
 * Outcome of one command.
 *
 * @param success no error and result &gt; 0 (vanilla's rule; decides && and ||)
 * @param errored the command failed to parse or threw; a result of 0 or less without an error is not an error
 * @param output  success messages, captured whether or not they were shown
 * @param value   a ready-made pipe value (builtins), used instead of extracting one from {@code output}; may be null
 */
public record ExecResult(boolean success, boolean errored, int result, List<Feedback> output, PipeValue value) {
    public ExecResult {
        output = List.copyOf(output);
    }

    public ExecResult(boolean success, boolean errored, int result, List<Feedback> output) {
        this(success, errored, result, output, null);
    }

    /** A result whose only failure mode is an error. */
    public ExecResult(boolean success, int result, List<Feedback> output) {
        this(success, !success, result, output, null);
    }

    public static ExecResult failure() {
        return new ExecResult(false, true, 0, List.of(), null);
    }
}
