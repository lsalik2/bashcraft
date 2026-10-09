package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;

import java.util.Optional;

/** Who typed a chain: the player whose own command this is (through any nesting), not a function or other source. */
public final class Sources {
    private Sources() {}

    public static Optional<ServerPlayer> typist(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || source.isSilent()) {
            return Optional.empty();
        }
        CommandSource origin = ((CommandSourceStackAccessor) source).bashcraft$source();
        while (origin instanceof TeeSource tee) {
            origin = tee.target();
        }
        return origin == player.commandSource() ? Optional.of(player) : Optional.empty();
    }

    /** A command the player typed themselves, not one run from inside a chain. */
    public static boolean typedDirectly(CommandSourceStack source) {
        return !(((CommandSourceStackAccessor) source).bashcraft$source() instanceof TeeSource) && typist(source).isPresent();
    }
}
