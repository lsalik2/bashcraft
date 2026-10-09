package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs a chain's syntax tree with bash semantics (spec §5): && / || / ; by status, | feeds extracted values forward, groups nest. */
public final class ChainExecutor {
    private final CommandRunner runner;
    private final Variables variables;
    private final boolean swallowPipedOutput;
    private final int maxTotalCommands;
    private final int maxDepth;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this(runner, Variables.NONE, swallowPipedOutput, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput, int maxTotalCommands) {
        this(runner, Variables.NONE, swallowPipedOutput, maxTotalCommands, Integer.MAX_VALUE);
    }

    /**
     * @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend})
     * @param maxDepth         nesting limit for {@code $( … )}, shared with nested {@code sh}
     */
    public ChainExecutor(CommandRunner runner, Variables variables, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this.runner = runner;
        this.variables = variables;
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
        this.maxDepth = maxDepth;
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
        private int lastStatus;

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
            Builtins.Call call = Builtins.classify(command.text());
            String text = call.argument();
            if (call.kind() != Builtins.Kind.UNSET) {
                try {
                    text = Expander.expand(text, variables, lastStatus, this::substitute);
                    if (input != null) {
                        text = Placeholders.inject(text, input.value(), input.result());
                    }
                } catch (ExpansionException | PipeException e) {
                    runner.reportError(label(command) + e.getMessage());
                    return finish(ExecResult.failure(), command);
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return finish(ExecResult.failure(), command);
            }
            String prefix = numbered ? label(command) : null;
            RunOptions options = new RunOptions(feedsPipe && swallowPipedOutput, prefix);
            ExecResult result = switch (call.kind()) {
                case NONE -> runner.run(text, options);
                case ECHO -> runner.echo(text, options);
                case ASSIGN -> assign(command, call.name(), Builtins.unquote(text.strip()));
                case UNSET -> unset(command, call.name());
            };
            return finish(result, command);
        }

        private Outcome finish(ExecResult result, Node.Command command) {
            lastStatus = result.success() ? 0 : 1;
            return new Outcome(result, command.text());
        }

        private ExecResult assign(Node.Command command, String name, String value) {
            Variables.Ref ref = Variables.ref(name, Variables.Scope.OWN);
            try {
                variables.set(ref.scope(), ref.name(), value);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult unset(Node.Command command, String name) {
            if (!name.matches("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*")) {
                runner.reportError(label(command) + "unset: bad name " + name);
                return ExecResult.failure();
            }
            Variables.Ref ref = Variables.ref(name, Variables.Scope.OWN);
            variables.unset(ref.scope(), ref.name());
            return new ExecResult(true, 1, List.of());
        }

        /** Runs the chain inside $( … ) with its output hidden and returns the value extracted from it. */
        private String substitute(String chain) throws ExpansionException {
            Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
            if (scope.isEmpty()) {
                throw new ExpansionException("$(…) nested deeper than " + maxDepth);
            }
            try (DepthGuard.Scope ignored = scope.get()) {
                Node node;
                try {
                    node = NodeParser.parse(chain, NodeParser.Mode.SH, Integer.MAX_VALUE);
                } catch (ChainSyntaxException e) {
                    throw new ExpansionException("$(…): " + e.getMessage());
                }
                Map<Node.Command, Integer> innerNumbers = new IdentityHashMap<>();
                number(node, innerNumbers);
                ExecResult result = new Run(innerNumbers, false).node(node, null, true).result();
                if (result.errored()) {
                    throw new ExpansionException("$(…) produced no value");
                }
                try {
                    return PipeResolver.resolve(result.output()).asArgument();
                } catch (PipeException e) {
                    throw new ExpansionException("$(…) produced no value");
                }
            }
        }

        private String label(Node.Command command) {
            return numbered ? "[" + numbers.get(command) + "] " : "";
        }
    }
}
