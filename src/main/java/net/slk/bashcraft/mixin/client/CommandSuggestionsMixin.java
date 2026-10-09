package net.slk.bashcraft.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.client.ChainStyles;
import net.slk.bashcraft.client.ClientChains;
import net.slk.bashcraft.client.ClientHooks;
import net.slk.bashcraft.pipe.Placeholders;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Chains in chat and the command-block screen: highlight, complete and report errors per command (spec B §5). */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow @Final private boolean commandsOnly;
    @Shadow @Final private List<FormattedCharSequence> commandUsage;
    @Shadow private int commandUsagePosition;
    @Shadow private ParseResults<ClientSuggestionProvider> currentParse;

    @Shadow
    private void recomputeUsageBoxWidth() {}

    @Unique private ChainLayout bashcraft$layout;
    @Unique private ChainStyles bashcraft$styles;

    @Inject(method = "updateCommandInfo", at = @At("HEAD"))
    private void bashcraft$detectChain(CallbackInfo ci) {
        bashcraft$layout = null;
        bashcraft$styles = null;
        String value = input.getValue();
        if (!commandsOnly && !value.startsWith("/")) {
            return;
        }
        try {
            ChainLayout layout = ClientChains.layout(value).orElse(null);
            Minecraft minecraft = Minecraft.getInstance();
            if (layout != null && minecraft.player != null) {
                bashcraft$styles = ChainStyles.compute(value, layout, minecraft.player.connection.getCommands(),
                        minecraft.player.connection.getSuggestionsProvider());
                bashcraft$layout = layout;
                // The parse depends on where the cursor is, not just on the text.
                currentParse = null;
            }
        } catch (RuntimeException e) {
            ClientHooks.failed(e);
            bashcraft$layout = null;
            bashcraft$styles = null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults bashcraft$parseCommandUnderCursor(CommandDispatcher dispatcher, StringReader reader, Object source, Operation<ParseResults> original) {
        ChainLayout layout = bashcraft$layout;
        if (layout == null) {
            return original.call(dispatcher, reader, source);
        }
        try {
            ChainLayout.Window window = layout.window(input.getCursorPosition());
            StringReader command = new StringReader(Placeholders.mask(input.getValue().substring(0, window.end())));
            command.setCursor(window.start());
            return original.call(dispatcher, command, source);
        } catch (RuntimeException e) {
            ClientHooks.failed(e);
            return original.call(dispatcher, reader, source);
        }
    }

    @Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
    private void bashcraft$formatChain(String text, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
        ChainStyles styles = bashcraft$styles;
        if (styles != null && styles.matches(input.getValue())) {
            try {
                cir.setReturnValue(styles.format(text, offset));
            } catch (RuntimeException e) {
                ClientHooks.failed(e);
            }
        }
    }

    @Inject(method = "updateUsageInfo", at = @At("HEAD"), cancellable = true)
    private void bashcraft$chainError(ParseResults<ClientSuggestionProvider> parse, Suggestions suggestions, CallbackInfo ci) {
        ChainLayout layout = bashcraft$layout;
        if (layout == null || layout.error().isEmpty()) {
            return;
        }
        commandUsage.clear();
        commandUsage.add(Component.literal("BashCraft: " + layout.error().get().getMessage()).withStyle(ChatFormatting.RED).getVisualOrderText());
        commandUsagePosition = 0;
        recomputeUsageBoxWidth();
        ci.cancel();
    }
}
