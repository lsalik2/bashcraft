package net.slk.bashcraft.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the ThreadLocal that makes nested command calls join the running execution context instead of running now. */
@Mixin(Commands.class)
public interface CommandsAccessor {
    @Accessor("CURRENT_EXECUTION_CONTEXT")
    static ThreadLocal<ExecutionContext<CommandSourceStack>> bashcraft$currentExecutionContext() {
        throw new AssertionError();
    }
}
