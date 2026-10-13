package net.slk.bashcraft.mc;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.slk.bashcraft.exec.CommandRunner;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.exec.RunOptions;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;
import net.slk.bashcraft.mixin.CommandsAccessor;
import net.slk.bashcraft.pipe.Feedback;

import java.util.List;
import java.util.OptionalInt;

/** Runs chain segments as the invoking source, to completion, capturing result and feedback. */
public final class MinecraftCommandRunner implements CommandRunner {
    private final CommandSourceStack invoker;

    public MinecraftCommandRunner(CommandSourceStack invoker) {
        this.invoker = invoker;
    }

    @Override
    public ExecResult run(String command, RunOptions options) {
        TeeSource tee = new TeeSource(((CommandSourceStackAccessor) invoker).bashcraft$source(), options);
        boolean[] reported = {false};
        boolean[] success = {false};
        int[] result = {0};
        CommandSourceStack stack = invoker.withSource(tee).withCallback((ok, value) -> {
            reported[0] = true;
            success[0] = ok;
            result[0] = value;
        });
        Commands commands = stack.getServer().getCommands();
        ParseResults<CommandSourceStack> parse = commands.getDispatcher().parse(command, stack);
        // /sh's own argument isn't signed, so a player's msg/me/say inside it would bypass chat signing and filtering.
        if (Sources.typist(invoker).isPresent() && SignableCommand.hasSignableArguments(parse)) {
            reportError(prefix(options) + "chat commands like msg, me and say can't run inside /sh when a player types it");
            return ExecResult.failure();
        }
        boolean complete = isComplete(parse, command);
        runNow(commands, parse, command);
        List<Feedback> output = tee.captured().stream().map(ComponentFeedback::toFeedback).toList();
        if (!reported[0]) {
            // A function without `return` never reports a result. It succeeded if it parsed (vanilla showed any parse error);
            // errors from lines inside it don't fail it, as in vanilla.
            return new ExecResult(complete, !complete, 0, output);
        }
        return new ExecResult(success[0] && result[0] > 0, !success[0], result[0], output);
    }

    /** Sends the text through the same tee as a command's success output, so it is captured, hidden or numbered alike. */
    @Override
    public ExecResult echo(String text, RunOptions options) {
        TeeSource tee = new TeeSource(((CommandSourceStackAccessor) invoker).bashcraft$source(), options);
        invoker.withSource(tee).sendSuccess(() -> Component.literal(text), false);
        List<Feedback> output = tee.captured().stream().map(ComponentFeedback::toFeedback).toList();
        return new ExecResult(true, 1, output);
    }

    @Override
    public OptionalInt surfaceY(int x, int z, RunOptions options) {
        String prefix = prefix(options);
        // Loading (possibly generating) a distant chunk is expensive: the same permission /locate needs.
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(invoker)) {
            reportError(prefix + "surface needs operator permission");
            return OptionalInt.empty();
        }
        ServerLevel level = invoker.getLevel();
        if (level.dimensionType().hasCeiling()) {
            reportError(prefix + "surface doesn't work in dimensions with a ceiling");
            return OptionalInt.empty();
        }
        // The world border is how servers limit chunk generation; never generate beyond it.
        if (!level.getWorldBorder().isWithinBounds(x + 0.5, z + 0.5)) {
            reportError(prefix + "surface: outside the world border");
            return OptionalInt.empty();
        }
        level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return OptionalInt.of(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
    }

    @Override
    public void reportError(String message) {
        invoker.sendFailure(Component.literal("BashCraft: " + message));
    }

    private static String prefix(RunOptions options) {
        return options.failurePrefix() == null ? "" : options.failurePrefix();
    }

    /** Whether vanilla will execute this parse (no syntax error, not an incomplete command). */
    private static boolean isComplete(ParseResults<CommandSourceStack> parse, String command) {
        try {
            Commands.validateParseResults(parse);
        } catch (CommandSyntaxException e) {
            return false;
        }
        return ContextChain.tryFlatten(parse.getContext().build(command)).isPresent();
    }

    /**
     * Gives the command its own top-level execution context so it finishes before this returns;
     * otherwise it would be queued behind the running command.
     */
    private static void runNow(Commands commands, ParseResults<CommandSourceStack> parse, String command) {
        ThreadLocal<ExecutionContext<CommandSourceStack>> current = CommandsAccessor.bashcraft$currentExecutionContext();
        ExecutionContext<CommandSourceStack> saved = current.get();
        current.set(null);
        try {
            commands.performCommand(parse, command);
        } finally {
            current.set(saved);
        }
    }
}
