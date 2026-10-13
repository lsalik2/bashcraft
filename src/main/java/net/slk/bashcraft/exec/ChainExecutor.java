package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.ChainFormatter;
import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    /** Block coordinates beyond this are outside any Minecraft world. */
    private static final double WORLD_LIMIT = 29_999_984;

    private static final java.util.regex.Pattern ALIAS_DEF =
            java.util.regex.Pattern.compile("(global\\.)?([a-z_][a-z0-9_-]{0,31})\\s*=\\s*(.*)", java.util.regex.Pattern.DOTALL);

    private final CommandRunner runner;
    private final ShellContext context;
    private final Variables variables;
    private final boolean swallowPipedOutput;
    private final int maxTotalCommands;
    private final int maxDepth;
    private final int maxSegments;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this(runner, Variables.NONE, swallowPipedOutput, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput, int maxTotalCommands) {
        this(runner, Variables.NONE, swallowPipedOutput, maxTotalCommands, Integer.MAX_VALUE);
    }

    public ChainExecutor(CommandRunner runner, Variables variables, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this(runner, ShellContext.of(variables), swallowPipedOutput, maxTotalCommands, maxDepth);
    }

    public ChainExecutor(CommandRunner runner, ShellContext context, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this(runner, context, swallowPipedOutput, maxTotalCommands, maxDepth, Integer.MAX_VALUE);
    }

    /**
     * @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend})
     * @param maxDepth         nesting limit for {@code $( … )}, aliases and nested {@code sh}
     * @param maxSegments      most commands in an alias body, as in any chain
     */
    public ChainExecutor(CommandRunner runner, ShellContext context, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth,
                         int maxSegments) {
        this.runner = runner;
        this.context = context;
        this.variables = context.variables();
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
        this.maxDepth = maxDepth;
        this.maxSegments = maxSegments;
    }

    /** [n] numbers commands left to right as written, whether or not they run. */
    private static void number(Node node, Map<Node.Command, Integer> numbers) {
        switch (node) {
            case Node.Command command -> numbers.put(command, numbers.size() + 1);
            case Node.Pipeline pipeline -> pipeline.stages().forEach(stage -> number(stage, numbers));
            case Node.Sequence sequence -> sequence.items().forEach(item -> number(item, numbers));
            case Node.Group group -> number(group.body(), numbers);
            case Node.Background background -> number(background.body(), numbers);
        }
    }

    private static Node.Command firstCommand(Node node) {
        return switch (node) {
            case Node.Command command -> command;
            case Node.Pipeline pipeline -> firstCommand(pipeline.stages().getFirst());
            case Node.Sequence sequence -> firstCommand(sequence.items().getFirst());
            case Node.Group group -> firstCommand(group.body());
            case Node.Background background -> firstCommand(background.body());
        };
    }

    /**
     * Returns the outcome of the last command that ran (a skipped item leaves the status unchanged). A chain that
     * paused goes to {@link Jobs#queue} and reports the {@code sleep}.
     */
    public ExecResult execute(Node root) {
        Map<Node.Command, Integer> numbers = new IdentityHashMap<>();
        number(root, numbers);
        Outcome outcome = new Run(new RunState(numbers, root.commandCount() > 1, null, 0), null).node(root, null, false);
        Halt halt = outcome.halt();
        if (halt != null && !halt.stopped()) {
            try {
                context.jobs().queue(new Continuation(halt.frames()), halt.delay(), ChainFormatter.toSh(root));
            } catch (ExpansionException e) {
                runner.reportError(halt.label() + e.getMessage());
                return ExecResult.failure();
            }
        }
        return outcome.result();
    }

    /**
     * Runs a paused chain on (spec C3 §5): its frames innermost first, each one's outcome deciding the {@code &&} /
     * {@code ||} of the frame outside it. A new pause goes back to the same job through {@link Jobs#requeue}.
     */
    public void resume(Continuation continuation) {
        List<Frame> frames = continuation.frames;
        // Only alias levels with frames left count, renumbered from 0: a timer that calls itself last stays one level deep.
        List<Integer> levels = frames.stream().map(frame -> frame.state().level).distinct().sorted().toList();
        Map<RunState, RunState> renumbered = new IdentityHashMap<>();
        for (Frame frame : frames) {
            renumbered.computeIfAbsent(frame.state(), state -> state.atLevel(levels.indexOf(state.level)));
        }
        Map<RunState, Run> runs = new IdentityHashMap<>();
        Deque<DepthGuard.Scope> scopes = new ArrayDeque<>();
        try {
            Outcome outcome = null;
            RunState previous = null;
            for (int f = 0; f < frames.size(); f++) {
                Frame frame = frames.get(f);
                RunState state = renumbered.get(frame.state());
                while (scopes.size() > state.level) {
                    scopes.pop().close();
                }
                while (scopes.size() < state.level) {
                    Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
                    if (scope.isEmpty()) {
                        runner.reportError("aliases nested deeper than " + maxDepth);
                        return;
                    }
                    scopes.push(scope.get());
                }
                if (outcome == null) {
                    outcome = new Outcome(frame.status(), frame.lastCommand());
                } else if (state != previous) {
                    // The alias call this frame waited on has finished: like any command, it sets $?.
                    state.lastStatus = outcome.result().success() ? 0 : 1;
                }
                previous = state;
                outcome = runs.computeIfAbsent(state, s -> new Run(s, null)).continueSequence(frame.sequence(), frame.next(), outcome, false);
                Halt halt = outcome.halt();
                if (halt != null) {
                    if (!halt.stopped()) {
                        List<Frame> rest = new ArrayList<>(halt.frames());
                        frames.subList(f + 1, frames.size()).forEach(outer -> rest.add(outer.withState(renumbered.get(outer.state()))));
                        try {
                            context.jobs().requeue(new Continuation(rest), halt.delay());
                        } catch (ExpansionException e) {
                            runner.reportError(halt.label() + e.getMessage());
                        }
                    }
                    return;
                }
            }
        } finally {
            while (!scopes.isEmpty()) {
                scopes.pop().close();
            }
        }
    }

    /** The frames with their run states copied, so the chain going on can't change a waiting part's $?. */
    private static List<Frame> detached(List<Frame> frames) {
        Map<RunState, RunState> copies = new IdentityHashMap<>();
        return frames.stream().map(frame -> frame.withState(copies.computeIfAbsent(frame.state(), state -> state.atLevel(state.level)))).toList();
    }

    /** A list a paused chain was inside: run its items from {@code next} on, after {@code status}. */
    record Frame(Node.Sequence sequence, int next, ExecResult status, String lastCommand, RunState state) {
        Frame withState(RunState other) {
            return new Frame(sequence, next, status, lastCommand, other);
        }
    }

    /**
     * One run's settings and {@code $?}, shared by the frames it leaves: command numbers, whether errors are numbered,
     * the alias arguments in force ({@code null} outside an alias) and how many alias levels deep it is.
     */
    static final class RunState {
        final Map<Node.Command, Integer> numbers;
        final boolean numbered;
        final Arguments positionals;
        final int level;
        int lastStatus;

        RunState(Map<Node.Command, Integer> numbers, boolean numbered, Arguments positionals, int level) {
            this.numbers = numbers;
            this.numbered = numbered;
            this.positionals = positionals;
            this.level = level;
        }

        RunState atLevel(int other) {
            RunState copy = new RunState(numbers, numbered, positionals, other);
            copy.lastStatus = lastStatus;
            return copy;
        }
    }

    /**
     * Why a run ended early: paused by {@code sleep} for {@code delay} ticks — the lists still to finish are collected
     * in {@code frames} on the way up, innermost first — or stopped for good by a sleep that couldn't pause (already
     * reported). {@code label} is the sleep's {@code [n] }, for an error from the job queue.
     */
    private record Halt(boolean stopped, int delay, String label, List<Frame> frames) {
        static Halt pause(int delay, String label) {
            return new Halt(false, delay, label, new ArrayList<>());
        }

        static Halt stop() {
            return new Halt(true, 0, "", List.of());
        }
    }

    /** Thrown out of a {@code $( … )} whose chain stopped, so the command around it stops the chain too, silently. */
    private static final class ChainStopped extends ExpansionException {
        ChainStopped() {
            super("the chain stopped");
        }
    }

    /** A value piped into a node and the producing command's numeric result ({result}). */
    /** A value piped into a node, the producing command's numeric result ({result}), and the items grep/each work on. */
    private record Piped(PipeValue value, int result, List<String> items) {}

    /** What running a node produced; lastCommand is the text of the last command that ran; halt is set when it paused or stopped. */
    private record Outcome(ExecResult result, String lastCommand, Halt halt) {
        Outcome(ExecResult result, String lastCommand) {
            this(result, lastCommand, null);
        }

        Outcome halted(Halt other) {
            return new Outcome(result, lastCommand, other);
        }
    }

    private final class Run {
        private final RunState state;
        /** Why a sleep can't pause here ("inside a pipe", …), or null where it can. */
        private String noPause;
        private boolean budgetExceeded;
        private boolean expansionFailed;

        Run(RunState state, String noPause) {
            this.state = state;
            this.noPause = noPause;
        }

        Outcome node(Node node, Piped input, boolean feedsPipe) {
            return switch (node) {
                case Node.Command command -> command(command, input, feedsPipe);
                case Node.Pipeline pipeline -> pipeline(pipeline, input, feedsPipe);
                case Node.Sequence sequence -> sequence(sequence, input, feedsPipe);
                case Node.Group group -> node(group.body(), input, feedsPipe);
                case Node.Background background -> background(background, input, feedsPipe);
            };
        }

        /** A piped value goes to the first item only. Any item may run last, so all hide output when the result feeds a pipe. */
        private Outcome sequence(Node.Sequence sequence, Piped input, boolean feedsPipe) {
            return continueSequence(sequence, 1, node(sequence.items().getFirst(), input, feedsPipe), feedsPipe);
        }

        /**
         * Runs the items from {@code from} on, after {@code status} (the outcome of the item before). When an item
         * pauses, a frame for the items still to run is added on the way up; when one stops, nothing more runs.
         */
        Outcome continueSequence(Node.Sequence sequence, int from, Outcome status, boolean feedsPipe) {
            for (int i = from; ; i++) {
                if (status.halt() != null) {
                    if (!status.halt().stopped() && i < sequence.items().size()) {
                        status.halt().frames().add(new Frame(sequence, i, status.result(), status.lastCommand(), state));
                    }
                    return status;
                }
                if (i == sequence.items().size()) {
                    return status;
                }
                boolean run = switch (sequence.joins().get(i - 1)) {
                    case AND -> status.result().success();
                    case OR -> !status.result().success();
                    case SEQ -> true;
                };
                if (run) {
                    status = node(sequence.items().get(i), null, feedsPipe);
                }
            }
        }

        /** sleep TIME: pauses the chain (spec C3 §2); a sleep that can't pause stops it. */
        private Outcome sleep(Node.Command command, String text) {
            int ticks;
            try {
                if (noPause != null) {
                    throw new ExpansionException("sleep can't run " + noPause);
                }
                ticks = SleepTime.ticks(text);
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return finish(ExecResult.failure(), command).halted(Halt.stop());
            }
            return finish(new ExecResult(true, false, ticks, List.of()), command).halted(Halt.pause(ticks, label(command)));
        }

        /** body &: if the body pauses, its waiting part becomes a job of its own and the chain carries on (spec C3 §3). */
        private Outcome background(Node.Background background, Piped input, boolean feedsPipe) {
            Outcome body = node(background.body(), input, feedsPipe);
            Halt halt = body.halt();
            if (halt != null && halt.stopped()) {
                return body;
            }
            if (halt != null) {
                try {
                    context.jobs().queue(new Continuation(detached(halt.frames())), halt.delay(), ChainFormatter.toSh(background.body()));
                } catch (ExpansionException e) {
                    runner.reportError(halt.label() + e.getMessage());
                    return new Outcome(ExecResult.failure(), body.lastCommand(), Halt.stop());
                }
            }
            state.lastStatus = 0;
            return new Outcome(new ExecResult(true, 1, List.of()), body.lastCommand());
        }

        private Outcome pipeline(Node.Pipeline pipeline, Piped input, boolean feedsPipe) {
            String outer = noPause;
            noPause = "inside a pipe";
            try {
                List<Node> stages = pipeline.stages();
                Outcome previous = node(stages.getFirst(), input, true);
                for (int i = 1; i < stages.size(); i++) {
                    // A result of 0 or less is still a value (a score, a coordinate, a distance); only an error stops the pipe.
                    if (previous.halt() != null || previous.result().errored()) {
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
            } finally {
                noPause = outer;
            }
        }

        private Outcome command(Node.Command command, Piped input, boolean feedsPipe) {
            budgetExceeded = false;
            expansionFailed = false;
            Builtins.Call call = Builtins.classify(command.text());
            if (call.kind() == Builtins.Kind.NONE) {
                String word = command.text().split("\\s", 2)[0];
                Optional<String> alias = context.aliases().body(word);
                if (alias.isPresent()) {
                    Outcome called = callAlias(command, word, alias.get(), input, feedsPipe);
                    return finish(called.result(), command).halted(called.halt());
                }
            }
            String text = call.argument();
            if (call.kind() != Builtins.Kind.UNSET && call.kind() != Builtins.Kind.EACH && call.kind() != Builtins.Kind.ALIAS
                    && call.kind() != Builtins.Kind.UNALIAS) {
                try {
                    boolean takesPipe = call.kind() != Builtins.Kind.GREP && call.kind() != Builtins.Kind.STORE
                            && call.kind() != Builtins.Kind.SURFACE;
                    Expander.Expansion expansion = Expander.expandParts(text, variables, state.lastStatus, this::substitute,
                            input == null || !takesPipe ? null : new Expander.PipeInput(input.value(), input.result()), state.positionals);
                    text = finalText(call, command.text(), expansion);
                } catch (ExpansionException e) {
                    expansionFailed = true;
                    if (e instanceof ChainStopped) {
                        // A sleep refused inside $( … ) has said why; the chain stops here.
                        return finish(ExecResult.failure(), command).halted(Halt.stop());
                    }
                    runner.reportError(label(command) + e.getMessage());
                    return finish(ExecResult.failure(), command);
                }
            }
            if (call.kind() == Builtins.Kind.GREP || call.kind() == Builtins.Kind.STORE || call.kind() == Builtins.Kind.EACH
                    || call.kind() == Builtins.Kind.SURFACE) {
                if (input == null) {
                    runner.reportError(label(command) + call.kind().name().toLowerCase(java.util.Locale.ROOT) + " needs piped input");
                    return finish(ExecResult.failure(), command);
                }
                if (call.kind() == Builtins.Kind.EACH) {
                    return each(command, text, input, feedsPipe);
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                budgetExceeded = true;
                runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return finish(ExecResult.failure(), command);
            }
            if (call.kind() == Builtins.Kind.SLEEP) {
                return sleep(command, text);
            }
            String prefix = state.numbered ? label(command) : null;
            RunOptions options = new RunOptions(feedsPipe && swallowPipedOutput, prefix);
            ExecResult result = switch (call.kind()) {
                case NONE -> runner.run(text, options);
                case ECHO -> runner.echo(text, options);
                case ASSIGN -> assign(command, call.name(), Builtins.unquote(text.strip()));
                case UNSET -> unset(command, call.name());
                case GREP -> grep(command, text, input);
                case STORE -> store(command, text, input, options);
                case SURFACE -> surface(command, text, input, options);
                case ALIAS -> alias(command, text, options);
                case UNALIAS -> unalias(command, text);
                case HISTORY -> history(command, options);
                case JOBS -> jobs(command, text, options);
                case KILL -> kill(command, text);
                case EACH, SLEEP -> throw new IllegalStateException(call.kind() + " is handled above");
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

        /** surface: the piped coordinates with Y replaced by the standing height at that x and z. */
        private ExecResult surface(Node.Command command, String arguments, Piped input, RunOptions options) {
            if (!arguments.isBlank()) {
                runner.reportError(label(command) + "surface takes no arguments");
                return ExecResult.failure();
            }
            List<String> xyz = switch (input.value()) {
                case PipeValue.Coordinates c -> List.of(c.x(), c.y(), c.z());
                case PipeValue.Items items when items.items().size() == 3 -> items.items();
                default -> null;
            };
            if (xyz == null) {
                runner.reportError(label(command) + "surface needs coordinates");
                return ExecResult.failure();
            }
            String number = "-?\\d+(\\.\\d+)?";
            if (!xyz.get(0).matches(number) || !xyz.get(2).matches(number)) {
                runner.reportError(label(command) + "surface needs absolute x and z");
                return ExecResult.failure();
            }
            double exactX = Double.parseDouble(xyz.get(0));
            double exactZ = Double.parseDouble(xyz.get(2));
            // Range-check before converting: a huge value would saturate to Integer.MIN_VALUE, whose abs() is negative.
            if (!(Math.abs(exactX) <= WORLD_LIMIT) || !(Math.abs(exactZ) <= WORLD_LIMIT)) {
                runner.reportError(label(command) + "surface: outside the world");
                return ExecResult.failure();
            }
            int x = (int) Math.floor(exactX);
            int z = (int) Math.floor(exactZ);
            java.util.OptionalInt y = runner.surfaceY(x, z, options);
            if (y.isEmpty()) {
                return ExecResult.failure();
            }
            return new ExecResult(true, false, y.getAsInt(), List.of(), new PipeValue.Coordinates(xyz.get(0), Integer.toString(y.getAsInt()), xyz.get(2)));
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
        private Outcome each(Node.Command command, String body, Piped input, boolean feedsPipe) {
            if (body.isBlank()) {
                runner.reportError(label(command) + "each needs a command");
                return finish(ExecResult.failure(), command);
            }
            if (input.items().isEmpty()) {
                // Nothing to do (e.g. a grep that matched nothing): a quiet failure, like bash's exit status.
                return finish(new ExecResult(false, false, 0, List.of(), new PipeValue.Items(List.of())), command);
            }
            Node.Command run = new Node.Command(body.strip());
            state.numbers.put(run, state.numbers.get(command));
            boolean allSucceeded = true;
            int succeeded = 0;
            List<String> collected = new java.util.ArrayList<>();
            String outer = noPause;
            noPause = "inside each";
            try {
                for (String item : input.items()) {
                    Outcome outcome = command(run, new Piped(PipeValue.of(item), input.result(), List.of(item)), feedsPipe);
                    if (outcome.halt() != null) {
                        return finish(ExecResult.failure(), command).halted(outcome.halt());
                    }
                    ExecResult result = outcome.result();
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
                    if (budgetExceeded || expansionFailed) {
                        // Stop rather than report the same refusal once per item.
                        break;
                    }
                }
            } finally {
                noPause = outer;
            }
            return finish(new ExecResult(allSucceeded && succeeded > 0, false, succeeded, List.of(), new PipeValue.Items(collected)), command);
        }

        private Outcome finish(ExecResult result, Node.Command command) {
            state.lastStatus = result.success() ? 0 : 1;
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
                Outcome outcome = new Run(new RunState(innerNumbers, false, state.positionals, state.level), "inside $(…)")
                        .node(node, null, true);
                if (outcome.halt() != null) {
                    throw new ChainStopped();
                }
                ExecResult result = outcome.result();
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

        /** Runs an alias body as a nested chain with the call's words as $1…$9/$@/$#; output shown as usual. */
        private Outcome callAlias(Node.Command command, String name, String body, Piped input, boolean feedsPipe) {
            String rest = command.text().substring(name.length());
            // A piped value fills placeholders in the call's arguments if there are any; otherwise it goes to the body.
            boolean placesInput = input != null && Expander.scan(rest, true).stream()
                    .anyMatch(reference -> reference.kind() == Expander.Kind.PLACEHOLDER);
            Arguments words;
            try {
                Expander.Expansion expansion = Expander.expandParts(rest, variables, state.lastStatus, this::substitute,
                        placesInput ? new Expander.PipeInput(input.value(), input.result()) : null, state.positionals);
                List<Words.Word> spans = Words.spans(expansion.join(false));
                words = new Arguments(spans.stream().map(Words.Word::text).toList(),
                        spans.stream().map(word -> expansion.untrustedOverlaps(word.start(), word.end())).toList());
            } catch (ExpansionException e) {
                expansionFailed = true;
                if (e instanceof ChainStopped) {
                    return new Outcome(ExecResult.failure(), command.text(), Halt.stop());
                }
                runner.reportError(label(command) + e.getMessage());
                return new Outcome(ExecResult.failure(), command.text());
            }
            Node node;
            try {
                node = NodeParser.parse(body, NodeParser.Mode.SH, maxSegments);
            } catch (ChainSyntaxException e) {
                runner.reportError(label(command) + "alias " + name + ": " + e.getMessage());
                return new Outcome(ExecResult.failure(), command.text());
            }
            if (!words.words().isEmpty() && !Expander.hasPositionals(body)) {
                node = appendArguments(node);
            }
            Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
            if (scope.isEmpty()) {
                runner.reportError(label(command) + "aliases nested deeper than " + maxDepth);
                return new Outcome(ExecResult.failure(), command.text());
            }
            try (DepthGuard.Scope ignored = scope.get()) {
                if (!DepthGuard.spend(maxTotalCommands)) {
                    budgetExceeded = true;
                    runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                    return new Outcome(ExecResult.failure(), command.text());
                }
                Map<Node.Command, Integer> innerNumbers = new IdentityHashMap<>();
                number(node, innerNumbers);
                // Inside a pipe, $( … ) or each, the body can't pause either.
                return new Run(new RunState(innerNumbers, false, words, state.level + 1), noPause)
                        .node(node, placesInput ? null : input, feedsPipe);
            }
        }

        /** The append rule: the body's rightmost command gets " $@", so the words go in as values. */
        private static Node appendArguments(Node node) {
            return switch (node) {
                case Node.Command command -> new Node.Command(command.text() + " $@");
                case Node.Pipeline pipeline -> new Node.Pipeline(replaceLast(pipeline.stages()));
                case Node.Sequence sequence -> new Node.Sequence(replaceLast(sequence.items()), sequence.joins());
                case Node.Group group -> new Node.Group(appendArguments(group.body()));
                case Node.Background background -> new Node.Background(appendArguments(background.body()));
            };
        }

        private static List<Node> replaceLast(List<Node> nodes) {
            List<Node> copy = new java.util.ArrayList<>(nodes);
            copy.set(copy.size() - 1, appendArguments(copy.getLast()));
            return copy;
        }

        /** alias (list) | alias NAME = BODY | alias global.NAME = BODY */
        private ExecResult alias(Node.Command command, String arguments, RunOptions options) {
            String text = arguments.strip();
            if (text.isEmpty()) {
                List<String> lines = context.aliases().listing();
                lines.forEach(line -> runner.echo(line, options));
                return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
            }
            java.util.regex.Matcher m = ALIAS_DEF.matcher(text);
            if (!m.matches()) {
                runner.reportError(label(command) + "alias needs NAME = BODY");
                return ExecResult.failure();
            }
            String body = Builtins.unquote(m.group(3).strip());
            try {
                NodeParser.parse(body, NodeParser.Mode.SH, maxSegments);
            } catch (ChainSyntaxException e) {
                runner.reportError(label(command) + "alias body: " + e.getMessage());
                return ExecResult.failure();
            }
            try {
                context.aliases().define(m.group(1) != null, m.group(2), body);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult unalias(Node.Command command, String arguments) {
            String name = arguments.strip();
            boolean global = name.startsWith("global.");
            if (global) {
                name = name.substring(7);
            }
            if (name.isEmpty()) {
                runner.reportError(label(command) + "unalias needs NAME");
                return ExecResult.failure();
            }
            try {
                context.aliases().remove(global, name);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult history(Node.Command command, RunOptions options) {
            if (context.history() == null) {
                runner.reportError(label(command) + "history is only for chains you type");
                return ExecResult.failure();
            }
            List<String> lines = context.history().lines();
            lines.forEach(line -> runner.echo(line, options));
            return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
        }

        /** jobs: one line per waiting chain this source may see, pipeable (spec C3 §4). */
        private ExecResult jobs(Node.Command command, String arguments, RunOptions options) {
            if (!arguments.isBlank()) {
                runner.reportError(label(command) + "jobs takes no arguments");
                return ExecResult.failure();
            }
            List<String> lines = context.jobs().listing();
            lines.forEach(line -> runner.echo(line, options));
            return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
        }

        /** kill %N…: cancels waiting chains; succeeds when every named one was cancelled, result = how many were. */
        private ExecResult kill(Node.Command command, String arguments) {
            int killed = 0;
            int failed = 0;
            for (String word : arguments.strip().split("\\s+")) {
                if (!word.matches("%\\d{1,9}")) {
                    runner.reportError(label(command) + "kill needs %N, e.g. kill %1");
                    failed++;
                    continue;
                }
                try {
                    context.jobs().kill(Integer.parseInt(word.substring(1)));
                    killed++;
                } catch (ExpansionException e) {
                    runner.reportError(label(command) + e.getMessage());
                    failed++;
                }
            }
            return new ExecResult(failed == 0, failed > 0, killed, List.of());
        }

        private String label(Node.Command command) {
            return state.numbered ? "[" + state.numbers.get(command) + "] " : "";
        }
    }
}
