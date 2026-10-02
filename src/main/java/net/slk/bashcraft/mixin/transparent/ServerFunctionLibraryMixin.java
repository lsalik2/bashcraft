package net.slk.bashcraft.mixin.transparent;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.ServerFunctionLibrary;
import net.slk.bashcraft.chain.FunctionLines;
import net.slk.bashcraft.config.BashcraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Function files are compiled at load, where a chain line would be rejected: rewrite chains into sh lines first. */
@Mixin(ServerFunctionLibrary.class)
public abstract class ServerFunctionLibraryMixin {
    @ModifyReturnValue(method = "readLines", at = @At("RETURN"))
    private static List<String> bashcraft$transparentFunctionLines(List<String> lines) {
        BashcraftConfig config = BashcraftConfig.get();
        return config.transparentMode ? FunctionLines.rewrite(lines, config.maxSegments) : lines;
    }
}
