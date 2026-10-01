package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.Feedback;

import java.util.List;

/**
 * Outcome of one command.
 *
 * @param success no error and result &gt; 0 (vanilla's rule)
 * @param output  success messages, captured whether or not they were shown
 */
public record ExecResult(boolean success, int result, List<Feedback> output) {
    public ExecResult {
        output = List.copyOf(output);
    }

    public static ExecResult failure() {
        return new ExecResult(false, 0, List.of());
    }
}
