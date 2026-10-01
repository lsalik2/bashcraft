package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
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
        runNow(stack, command);
        List<Feedback> output = tee.captured().stream().map(ComponentFeedback::toFeedback).toList();
        // A function without `return` never reports a result; it succeeded unless it sent an error.
        boolean ok = reported[0] ? success[0] && result[0] > 0 : !tee.failed();
        return new ExecResult(ok, result[0], output);
    }

    @Override
    public void reportError(String message) {
        invoker.sendFailure(Component.literal("BashCraft: " + message));
    }

    /**
     * Gives the command its own top-level execution context so it finishes before this returns;
     * otherwise it would be queued behind the running command (see docs/superpowers/notes/2026-10-01-m0-spike.md).
     */
    private static void runNow(CommandSourceStack stack, String command) {
        ThreadLocal<ExecutionContext<CommandSourceStack>> current = CommandsAccessor.bashcraft$currentExecutionContext();
        ExecutionContext<CommandSourceStack> saved = current.get();
        current.set(null);
        try {
            stack.getServer().getCommands().performPrefixedCommand(stack, command);
        } finally {
            current.set(saved);
        }
    }
}
