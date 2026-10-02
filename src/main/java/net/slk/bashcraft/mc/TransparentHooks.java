package net.slk.bashcraft.mc;

import net.minecraft.commands.Commands;
import net.minecraft.server.ServerFunctionLibrary;

import java.util.Arrays;

/** Whether the optional transparent-mode mixins applied; they may fail after a Minecraft update without breaking /sh. */
public final class TransparentHooks {
    private TransparentHooks() {}

    public static boolean applied() {
        return hasHandler(Commands.class) && hasHandler(ServerFunctionLibrary.class);
    }

    /** Mixin merges handlers into the target with their name embedded, e.g. handler$zza000$bashcraft$transparentChain. */
    private static boolean hasHandler(Class<?> target) {
        return Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("bashcraft$transparent"));
    }
}
