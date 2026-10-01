package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Chain;
import net.slk.bashcraft.chain.Pipeline;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.List;

/** Runs a chain with bash semantics: && / || / ; decided by the last status, | feeds extracted values forward. */
public final class ChainExecutor {
    private final CommandRunner runner;
    private final boolean swallowPipedOutput;
    private final int maxTotalCommands;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this(runner, swallowPipedOutput, Integer.MAX_VALUE);
    }

    /** @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend}) */
    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput, int maxTotalCommands) {
        this.runner = runner;
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
    }

    /** Returns the outcome of the last segment that ran (a skipped pipeline leaves the status unchanged). */
    public ExecResult execute(Chain chain) {
        boolean numbered = chain.segmentCount() > 1;
        int firstIndex = 1;
        ExecResult status = null;
        for (int p = 0; p < chain.pipelines().size(); p++) {
            Pipeline pipeline = chain.pipelines().get(p);
            boolean run = p == 0 || switch (chain.joins().get(p - 1)) {
                case AND -> status.success();
                case OR -> !status.success();
                case SEQ -> true;
            };
            if (run) {
                status = runPipeline(pipeline, firstIndex, numbered);
            }
            firstIndex += pipeline.commands().size();
        }
        return status;
    }

    private ExecResult runPipeline(Pipeline pipeline, int firstIndex, boolean numbered) {
        List<String> commands = pipeline.commands();
        ExecResult previous = null;
        for (int i = 0; i < commands.size(); i++) {
            String prefix = numbered ? "[" + (firstIndex + i) + "] " : null;
            String label = prefix == null ? "" : prefix;
            String command = commands.get(i);
            if (previous != null) {
                // A result of 0 or less is still a value (a score, a coordinate, a distance); only an error stops the pipe.
                if (previous.errored()) {
                    return previous;
                }
                PipeValue value;
                try {
                    value = PipeResolver.resolve(previous.output());
                } catch (PipeException e) {
                    runner.reportError(label + "nothing to pipe from /" + commands.get(i - 1));
                    return ExecResult.failure();
                }
                try {
                    command = Placeholders.inject(command, value, previous.result());
                } catch (PipeException e) {
                    runner.reportError(label + e.getMessage());
                    return ExecResult.failure();
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                runner.reportError(label + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return ExecResult.failure();
            }
            boolean feedsPipe = i < commands.size() - 1;
            previous = runner.run(command, new RunOptions(feedsPipe && swallowPipedOutput, prefix));
        }
        return previous;
    }
}
