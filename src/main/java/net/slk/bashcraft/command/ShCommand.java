package net.slk.bashcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.ContextChain;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.CustomCommandExecutor;
import net.minecraft.commands.execution.ExecutionControl;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.exec.ShellContext;
import net.slk.bashcraft.mc.HistoryBook;
import net.slk.bashcraft.mc.MinecraftCommandRunner;
import net.slk.bashcraft.mc.ServerJobs;
import net.slk.bashcraft.mc.Sources;
import net.slk.bashcraft.mc.StorageAliases;
import net.slk.bashcraft.mc.StorageVariables;

import java.util.Optional;

/** {@code /sh <chain>}. Needs no permission itself; each command in the chain checks its own. */
public final class ShCommand {
    private ShCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("sh")
                        .then(Commands.argument("chain", StringArgumentType.greedyString()).executes(new Executor()))));
    }

    /**
     * Reports the chain's own success and result to whoever ran {@code /sh} (command blocks, {@code /execute store}).
     * A plain {@code executes} lambda can only signal failure by throwing, which would print a second error.
     */
    private static final class Executor implements CustomCommandExecutor.CommandAdapter<CommandSourceStack> {
        @Override
        public void run(CommandSourceStack sender, ContextChain<CommandSourceStack> currentStep, ChainModifiers modifiers,
                        ExecutionControl<CommandSourceStack> output) {
            ExecResult result = ShCommand.run(sender, StringArgumentType.getString(currentStep.getTopContext(), "chain"));
            sender.callback().onResult(result.success(), result.success() ? result.result() : 0);
        }
    }

    /** Parses and runs a chain as {@code source}; returns the last segment's outcome (a failure for parse or limit errors). */
    public static ExecResult run(CommandSourceStack source, String input) {
        boolean typed = Sources.typedDirectly(source);
        String line = input.strip();
        if (line.startsWith("!")) {
            if (!typed) {
                source.sendFailure(Component.literal("BashCraft: history is only for chains you type"));
                return ExecResult.failure();
            }
            Optional<String> resolved = HistoryBook.of(Sources.typist(source).orElseThrow()).resolve(line);
            if (resolved.isEmpty()) {
                source.sendFailure(Component.literal(line.equals("!") ? "BashCraft: ! needs a prefix (!! reruns your last line)"
                        : "BashCraft: no history matching " + line.substring(1)));
                return ExecResult.failure();
            }
            line = resolved.get();
            String shown = line;
            source.sendSuccess(() -> Component.literal("sh " + shown).withStyle(ChatFormatting.GRAY), false);
        }
        if (typed) {
            HistoryBook.of(Sources.typist(source).orElseThrow()).record(line);
        }
        Node chain;
        try {
            chain = NodeParser.parse(line, NodeParser.Mode.SH, BashcraftConfig.get().maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            source.sendFailure(Component.literal(e.context(line)).withStyle(ChatFormatting.GRAY));
            return ExecResult.failure();
        }
        return execute(source, chain);
    }

    /** Runs an already-parsed chain as {@code source} (used by /sh and by transparent mode). */
    public static ExecResult execute(CommandSourceStack source, Node chain) {
        BashcraftConfig config = BashcraftConfig.get();
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            source.sendFailure(Component.literal("BashCraft: chains nested deeper than " + config.maxDepth));
            return ExecResult.failure();
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            ShellContext shell = new ShellContext(StorageVariables.of(source), StorageAliases.of(source),
                    Sources.typist(source).map(HistoryBook::of).orElse(null), ServerJobs.forSource(source));
            return new ChainExecutor(new MinecraftCommandRunner(source), shell,
                    config.swallowPipedOutput, config.maxTotalCommands, config.maxDepth, config.maxSegments).execute(chain);
        }
    }
}
