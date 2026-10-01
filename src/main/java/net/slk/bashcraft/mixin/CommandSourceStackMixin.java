package net.slk.bashcraft.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.mc.TeeSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** For chain segments (source is a TeeSource): capture success output even when silent, hide it when piped, record and number errors. */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Shadow @Final private CommandSource source;
    @Shadow @Final private boolean silent;

    @Inject(method = "sendSuccess", at = @At("HEAD"), cancellable = true)
    private void bashcraft$captureSuccess(Supplier<Component> message, boolean broadcast, CallbackInfo ci) {
        if (source instanceof TeeSource tee) {
            tee.capture(message.get());
            if (tee.options().swallowSuccess()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "sendFailure", at = @At("HEAD"), cancellable = true)
    private void bashcraft$prefixFailure(Component message, CallbackInfo ci) {
        if (source instanceof TeeSource tee) {
            tee.markFailed();
        }
        if (source instanceof TeeSource tee && tee.options().failurePrefix() != null) {
            ci.cancel();
            if (tee.acceptsFailure() && !silent) {
                tee.sendSystemMessage(Component.empty().append(tee.options().failurePrefix()).append(message).withStyle(ChatFormatting.RED));
            }
        }
    }
}
