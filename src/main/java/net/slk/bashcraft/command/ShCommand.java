package net.slk.bashcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.ContextChain;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.CustomCommandExecutor;
import net.minecraft.commands.execution.ExecutionControl;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.chain.Chain;
import net.slk.bashcraft.chain.ChainParser;
import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.mc.MinecraftCommandRunner;

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
        BashcraftConfig config = BashcraftConfig.get();
        Chain chain;
        try {
            chain = ChainParser.parse(input, ChainParser.Mode.SH, config.maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            return ExecResult.failure();
        }
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            source.sendFailure(Component.literal("BashCraft: chains nested deeper than " + config.maxDepth));
            return ExecResult.failure();
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            return new ChainExecutor(new MinecraftCommandRunner(source), config.swallowPipedOutput, config.maxTotalCommands).execute(chain);
        }
    }
}
