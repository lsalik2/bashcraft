package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Runs a chain's syntax tree with bash semantics (spec §5): && / || / ; by status, | feeds extracted values forward, groups nest. */
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

    /** Returns the outcome of the last command that ran (a skipped item leaves the status unchanged). */
    public ExecResult execute(Node root) {
        Map<Node.Command, Integer> numbers = new IdentityHashMap<>();
        number(root, numbers);
        return new Run(numbers, root.commandCount() > 1).node(root, null, false).result();
    }

    /** [n] numbers commands left to right as written, whether or not they run. */
    private static void number(Node node, Map<Node.Command, Integer> numbers) {
        switch (node) {
            case Node.Command command -> numbers.put(command, numbers.size() + 1);
            case Node.Pipeline pipeline -> pipeline.stages().forEach(stage -> number(stage, numbers));
            case Node.Sequence sequence -> sequence.items().forEach(item -> number(item, numbers));
            case Node.Group group -> number(group.body(), numbers);
        }
    }

    private static Node.Command firstCommand(Node node) {
        return switch (node) {
            case Node.Command command -> command;
            case Node.Pipeline pipeline -> firstCommand(pipeline.stages().getFirst());
            case Node.Sequence sequence -> firstCommand(sequence.items().getFirst());
            case Node.Group group -> firstCommand(group.body());
        };
    }

    /** A value piped into a node and the producing command's numeric result ({result}). */
    private record Piped(PipeValue value, int result) {}

    /** What running a node produced; lastCommand is the text of the last command that ran. */
    private record Outcome(ExecResult result, String lastCommand) {}

    private final class Run {
        private final Map<Node.Command, Integer> numbers;
        private final boolean numbered;

        Run(Map<Node.Command, Integer> numbers, boolean numbered) {
            this.numbers = numbers;
            this.numbered = numbered;
        }

        Outcome node(Node node, Piped input, boolean feedsPipe) {
            return switch (node) {
                case Node.Command command -> command(command, input, feedsPipe);
                case Node.Pipeline pipeline -> pipeline(pipeline, input, feedsPipe);
                case Node.Sequence sequence -> sequence(sequence, input, feedsPipe);
                case Node.Group group -> node(group.body(), input, feedsPipe);
            };
        }

        /** A piped value goes to the first item only. Any item may run last, so all hide output when the result feeds a pipe. */
        private Outcome sequence(Node.Sequence sequence, Piped input, boolean feedsPipe) {
            Outcome status = node(sequence.items().getFirst(), input, feedsPipe);
            for (int i = 1; i < sequence.items().size(); i++) {
                boolean run = switch (sequence.joins().get(i - 1)) {
                    case AND -> status.result().success();
                    case OR -> !status.result().success();
                    case SEQ -> true;
                };
                if (run) {
                    status = node(sequence.items().get(i), null, feedsPipe);
                }
            }
            return status;
        }

        private Outcome pipeline(Node.Pipeline pipeline, Piped input, boolean feedsPipe) {
            List<Node> stages = pipeline.stages();
            Outcome previous = node(stages.getFirst(), input, true);
            for (int i = 1; i < stages.size(); i++) {
                // A result of 0 or less is still a value (a score, a coordinate, a distance); only an error stops the pipe.
                if (previous.result().errored()) {
                    return previous;
                }
                Node stage = stages.get(i);
                PipeValue value;
                try {
                    value = PipeResolver.resolve(previous.result().output());
                } catch (PipeException e) {
                    runner.reportError(label(firstCommand(stage)) + "nothing to pipe from /" + previous.lastCommand());
                    return new Outcome(ExecResult.failure(), previous.lastCommand());
                }
                previous = node(stage, new Piped(value, previous.result().result()), i < stages.size() - 1 || feedsPipe);
            }
            return previous;
        }

        private Outcome command(Node.Command command, Piped input, boolean feedsPipe) {
            String text = command.text();
            if (input != null) {
                try {
                    text = Placeholders.inject(text, input.value(), input.result());
                } catch (PipeException e) {
                    runner.reportError(label(command) + e.getMessage());
                    return new Outcome(ExecResult.failure(), command.text());
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return new Outcome(ExecResult.failure(), command.text());
            }
            String prefix = numbered ? label(command) : null;
            return new Outcome(runner.run(text, new RunOptions(feedsPipe && swallowPipedOutput, prefix)), command.text());
        }

        private String label(Node.Command command) {
            return numbered ? "[" + numbers.get(command) + "] " : "";
        }
    }
}
