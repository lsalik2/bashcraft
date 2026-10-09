package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs a chain's syntax tree with bash semantics (spec §5): && / || / ; by status, | feeds extracted values forward, groups nest. */
public final class ChainExecutor {
    /**
     * Words that make a command run other commands from its text: a nested sh, execute/return … run, function (whose
     * macro lines re-read their arguments). Matched on the expanded text, as Minecraft will run it.
     */
    private static final java.util.regex.Pattern RUNS_COMMANDS = java.util.regex.Pattern.compile("(?<!\\S)(?:sh|function|run)(?!\\S)");

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
    /** A value piped into a node, the producing command's numeric result ({result}), and the items grep/each work on. */
    private record Piped(PipeValue value, int result, List<String> items) {}

    /** What running a node produced; lastCommand is the text of the last command that ran. */
    private record Outcome(ExecResult result, String lastCommand) {}

    private final class Run {
        private final Map<Node.Command, Integer> numbers;
        private final boolean numbered;
        private int lastStatus;
        private boolean budgetExceeded;

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
                Piped piped;
                try {
                    piped = piped(previous.result());
                } catch (PipeException e) {
                    runner.reportError(label(firstCommand(stage)) + "nothing to pipe from /" + previous.lastCommand());
                    return new Outcome(ExecResult.failure(), previous.lastCommand());
                }
                previous = node(stage, piped, i < stages.size() - 1 || feedsPipe);
            }
            return previous;
        }

        private Outcome command(Node.Command command, Piped input, boolean feedsPipe) {
            budgetExceeded = false;
            Builtins.Call call = Builtins.classify(command.text());
            String text = call.argument();
            if (call.kind() != Builtins.Kind.UNSET && call.kind() != Builtins.Kind.EACH) {
                try {
                    boolean takesPipe = call.kind() != Builtins.Kind.GREP && call.kind() != Builtins.Kind.STORE;
                    Expander.Expansion expansion = Expander.expandParts(text, variables, lastStatus, this::substitute,
                            input == null || !takesPipe ? null : new Expander.PipeInput(input.value(), input.result()));
                    text = finalText(call, command.text(), expansion);
                } catch (ExpansionException e) {
                    runner.reportError(label(command) + e.getMessage());
                    return finish(ExecResult.failure(), command);
                }
            }
            if (call.kind() == Builtins.Kind.GREP || call.kind() == Builtins.Kind.STORE || call.kind() == Builtins.Kind.EACH) {
                if (input == null) {
                    runner.reportError(label(command) + call.kind().name().toLowerCase(java.util.Locale.ROOT) + " needs piped input");
                    return finish(ExecResult.failure(), command);
                }
                if (call.kind() == Builtins.Kind.EACH) {
                    return finish(each(command, text, input, feedsPipe), command);
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                budgetExceeded = true;
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
                case GREP -> grep(command, text, input);
                case STORE -> store(command, text, input, options);
                case EACH -> throw new IllegalStateException("each is handled above");
            };
            return finish(result, command);
        }

        /**
         * Values put into a command that can run other commands are escaped (and may not carry quotes), or an inner
         * chain or function macro would read them as syntax; and a value may never itself supply the word that makes a
         * command run commands. Decided on the expanded text, which is what Minecraft runs.
         */
        private String finalText(Builtins.Call call, String raw, Expander.Expansion expansion) throws ExpansionException {
            String plain = expansion.join(false);
            if (call.kind() != Builtins.Kind.NONE) {
                return plain;
            }
            boolean runsCommands = raw.startsWith("$");
            java.util.regex.Matcher keyword = RUNS_COMMANDS.matcher(plain);
            while (keyword.find()) {
                runsCommands = true;
                if (expansion.insertedOverlaps(keyword.start(), keyword.end())) {
                    throw new ExpansionException("a value can't decide which commands run");
                }
            }
            return runsCommands ? expansion.join(true) : plain;
        }

        /** What the next stage receives: a builtin's ready-made value, else the value extracted from the output. */
        private Piped piped(ExecResult result) throws PipeException {
            if (result.value() != null) {
                return new Piped(result.value(), result.result(), result.value().items());
            }
            List<String> items = PipeResolver.items(result.output());
            return new Piped(PipeResolver.resolve(result.output()), result.result(), items);
        }

        /** grep [-v] [-x] TEXT: keep matching items (case-insensitive substring, or whole item with -x). */
        private ExecResult grep(Node.Command command, String arguments, Piped input) {
            boolean invert = false;
            boolean exact = false;
            String rest = arguments.strip();
            while (rest.startsWith("-v ") || rest.startsWith("-x ") || rest.equals("-v") || rest.equals("-x")) {
                invert |= rest.startsWith("-v");
                exact |= rest.startsWith("-x");
                rest = rest.length() > 2 ? rest.substring(3).strip() : "";
            }
            if (rest.isEmpty()) {
                runner.reportError(label(command) + "grep needs a pattern");
                return ExecResult.failure();
            }
            String pattern = rest.toLowerCase(java.util.Locale.ROOT);
            List<String> kept = new java.util.ArrayList<>();
            for (String item : input.items()) {
                String lower = item.toLowerCase(java.util.Locale.ROOT);
                boolean matches = exact ? lower.equals(pattern) : lower.contains(pattern);
                if (matches != invert) {
                    kept.add(item);
                }
            }
            return new ExecResult(!kept.isEmpty(), false, kept.size(), List.of(), new PipeValue.Items(kept));
        }

        /** store score HOLDER OBJECTIVE | store storage ID PATH, by running the vanilla command as the same source. */
        private ExecResult store(Node.Command command, String arguments, Piped input, RunOptions options) {
            String text = arguments.strip();
            String vanilla;
            if (text.startsWith("score ") && text.lastIndexOf(' ') > 6) {
                String holder = text.substring(6, text.lastIndexOf(' ')).strip();
                String objective = text.substring(text.lastIndexOf(' ') + 1);
                java.util.OptionalLong number = scoreNumber(input.value());
                if (number.isEmpty()) {
                    runner.reportError(label(command) + "store score needs a number");
                    return ExecResult.failure();
                }
                vanilla = "scoreboard players set " + holder + " " + objective + " " + number.getAsLong();
            } else if (text.startsWith("storage ") && text.indexOf(' ', 8) > 8) {
                String id = text.substring(8, text.indexOf(' ', 8));
                String path = text.substring(text.indexOf(' ', 8) + 1).strip();
                vanilla = "data modify storage " + id + " " + path + " set value " + Snbt.of(input.value());
            } else {
                runner.reportError(label(command) + "store needs score HOLDER OBJECTIVE or storage ID PATH");
                return ExecResult.failure();
            }
            ExecResult result = runner.run(vanilla, options);
            return new ExecResult(result.success(), result.errored(), result.result(), List.of(), input.value());
        }

        /** A number for store score: a numeric value, or a one-item list whose item is a number; rounded down. */
        private static java.util.OptionalLong scoreNumber(PipeValue value) {
            List<String> items = value.items();
            if (items.size() == 1 && items.getFirst().matches("-?\\d+(\\.\\d+)?")) {
                return java.util.OptionalLong.of((long) Math.floor(Double.parseDouble(items.getFirst())));
            }
            return java.util.OptionalLong.empty();
        }

        /**
         * each COMMAND: run COMMAND once per item with that item as its pipe value, through the normal command path
         * (so expansion, the plain-value rule and escaping apply to every run). Output: the values the runs produced.
         */
        private ExecResult each(Node.Command command, String body, Piped input, boolean feedsPipe) {
            if (body.isBlank()) {
                runner.reportError(label(command) + "each needs a command");
                return ExecResult.failure();
            }
            if (input.items().isEmpty()) {
                runner.reportError(label(command) + "each had no items");
                return ExecResult.failure();
            }
            Node.Command run = new Node.Command(body.strip());
            numbers.put(run, numbers.get(command));
            boolean allSucceeded = true;
            int succeeded = 0;
            List<String> collected = new java.util.ArrayList<>();
            for (String item : input.items()) {
                ExecResult result = command(run, new Piped(PipeValue.of(item), input.result(), List.of(item)), feedsPipe).result();
                if (result.success()) {
                    succeeded++;
                } else {
                    allSucceeded = false;
                }
                if (!result.errored()) {
                    try {
                        collected.add(piped(result).value().asArgument());
                    } catch (PipeException e) {
                        // A run without a value adds nothing.
                    }
                }
                if (budgetExceeded) {
                    // Every further run would be refused the same way.
                    break;
                }
            }
            return new ExecResult(allSucceeded && succeeded > 0, false, succeeded, List.of(), new PipeValue.Items(collected));
        }

        private Outcome finish(ExecResult result, Node.Command command) {
            lastStatus = result.success() ? 0 : 1;
            return new Outcome(result, command.text());
        }

        private ExecResult assign(Node.Command command, String name, String value) {
            Variables.Ref ref = Variables.ref(name, Variables.Scope.ANY);
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
            Variables.Ref ref = Variables.ref(name, Variables.Scope.ANY);
            try {
                variables.unset(ref.scope(), ref.name());
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
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
                    return piped(result).value().asArgument();
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
