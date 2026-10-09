package net.slk.bashcraft.mc;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.exec.CommandRunner;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.exec.RunOptions;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;
import net.slk.bashcraft.mixin.CommandsAccessor;
import net.slk.bashcraft.pipe.Feedback;

import java.util.List;

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
        if (typedByPlayer(tee) && SignableCommand.hasSignableArguments(parse)) {
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
    public void reportError(String message) {
        invoker.sendFailure(Component.literal("BashCraft: " + message));
    }

    /**
     * The chain came from a player's own command packet: not the console running {@code execute as @p}, and not a
     * function or advancement reward the player runs (those keep the player's source but always suppress output).
     */
    private boolean typedByPlayer(TeeSource tee) {
        ServerPlayer player = invoker.getPlayer();
        CommandSource origin = tee.target();
        while (origin instanceof TeeSource outer) {
            // A nested sh's segments run with the outer segment's tee as their source.
            origin = outer.target();
        }
        return player != null && player.commandSource() == origin && !invoker.isSilent();
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
     * otherwise it would be queued behind the running command (see docs/superpowers/notes/2026-10-01-m0-spike.md).
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
