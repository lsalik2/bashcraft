package net.slk.bashcraft.mixin.transparent;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.slk.bashcraft.chain.TransparentChains;
import net.slk.bashcraft.command.ShCommand;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.mc.TeeSource;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat (signed and unsigned), console and command blocks all end here: run transparent chains instead of failing them. */
@Mixin(Commands.class)
public abstract class CommandsTransparentMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void bashcraft$transparentChain(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        BashcraftConfig config = BashcraftConfig.get();
        CommandSourceStack source = parse.getContext().getSource();
        // A chain's own segments are never re-split, so an escaped \&& stays literal.
        if (!config.transparentMode || ((CommandSourceStackAccessor) source).bashcraft$source() instanceof TeeSource) {
            return;
        }
        TransparentChains.detect(command, config.maxSegments).ifPresent(chain -> {
            ci.cancel();
            ExecResult result = ShCommand.execute(source, chain);
            source.callback().onResult(result.success(), result.success() ? result.result() : 0);
        });
    }
}
