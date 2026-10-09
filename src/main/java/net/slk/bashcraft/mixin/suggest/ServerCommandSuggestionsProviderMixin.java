package net.slk.bashcraft.mixin.suggest;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.TransparentHooks;
import net.slk.bashcraft.pipe.Placeholders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/** Server-computed suggestions for a chain: parse only its last command, keeping positions absolute (spec B §6). */
@Mixin(ServerCommandSuggestionsProvider.class)
public abstract class ServerCommandSuggestionsProviderMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "tryProcessRequest", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults bashcraft$suggestForLastCommand(CommandDispatcher dispatcher, StringReader reader, Object source, Operation<ParseResults> original) {
        String request = reader.getString();
        boolean transparent = BashcraftConfig.get().transparentMode && TransparentHooks.applied();
        Optional<ChainLayout> layout = ChainLayout.forInput(request, transparent);
        if (layout.isEmpty()) {
            return original.call(dispatcher, reader, source);
        }
        StringReader last = new StringReader(Placeholders.mask(request));
        last.setCursor(layout.get().window(request.length()).start());
        return original.call(dispatcher, last, source);
    }
}
