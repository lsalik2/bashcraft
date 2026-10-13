package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;

import java.util.Optional;

/** Who typed a chain: the player whose own command this is (through any nesting), not a function or other source. */
public final class Sources {
    private Sources() {}

    /**
     * The player whose own command packet this chain came from: not the console running {@code execute as @p}, and not
     * a function or advancement reward the player runs (those keep the player's source but always suppress output).
     */
    public static Optional<ServerPlayer> typist(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || source.isSilent()) {
            return Optional.empty();
        }
        return origin(source) == player.commandSource() ? Optional.of(player) : Optional.empty();
    }

    /** What started the command: the player, command block or console behind any pipe tees. */
    public static CommandSource origin(CommandSourceStack source) {
        CommandSource origin = ((CommandSourceStackAccessor) source).bashcraft$source();
        while (origin instanceof TeeSource tee) {
            origin = tee.target();
        }
        return origin;
    }

    /**
     * The player whose command this is, when they didn't type it as themselves: run as another entity
     * ({@code execute as … run}) or through a function they ran.
     */
    public static Optional<ServerPlayer> starter(CommandSourceStack source) {
        CommandSource origin = origin(source);
        return source.getServer().getPlayerList().getPlayers().stream().filter(player -> player.commandSource() == origin).findFirst();
    }

    /** A command the player typed themselves, not one run from inside a chain. */
    public static boolean typedDirectly(CommandSourceStack source) {
        return !(((CommandSourceStackAccessor) source).bashcraft$source() instanceof TeeSource) && typist(source).isPresent();
    }
}
