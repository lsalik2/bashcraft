package net.slk.bashcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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

/**
 * For chain segments (source is a TeeSource): capture success output even when silent, number errors, and keep
 * the admin broadcast and log behaving as if the invoker had run the command directly.
 */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Shadow @Final private CommandSource source;
    @Shadow @Final private boolean silent;

    /** Capture only; whether the invoker sees it is TeeSource.acceptsSuccess, so the admin broadcast still happens. */
    @Inject(method = "sendSuccess", at = @At("HEAD"))
    private void bashcraft$captureSuccess(Supplier<Component> message, boolean broadcast, CallbackInfo ci) {
        if (source instanceof TeeSource tee) {
            tee.capture(message.get());
        }
    }

    @Inject(method = "sendFailure", at = @At("HEAD"), cancellable = true)
    private void bashcraft$prefixFailure(Component message, CallbackInfo ci) {
        if (source instanceof TeeSource tee && tee.options().failurePrefix() != null) {
            ci.cancel();
            if (tee.acceptsFailure() && !silent) {
                tee.sendSystemMessage(Component.empty().append(tee.options().failurePrefix()).append(message).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** broadcastToAdmins skips the invoker and the console by identity; compare against the real source, not the tee. */
    @ModifyExpressionValue(method = "broadcastToAdmins",
            at = @At(value = "FIELD", target = "Lnet/minecraft/commands/CommandSourceStack;source:Lnet/minecraft/commands/CommandSource;"))
    private CommandSource bashcraft$unwrapTee(CommandSource original) {
        CommandSource real = original;
        while (real instanceof TeeSource tee) {
            real = tee.target();
        }
        return real;
    }
}
