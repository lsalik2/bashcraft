package net.slk.bashcraft.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tells clients which alias names they may call (spec C4 §5); wired up in Task 5. */
public final class AliasSync {
    private AliasSync() {}

    /** A player's personal set changed ({@code player}), or the global set ({@code player == null}). */
    public static void changed(MinecraftServer server, ServerPlayer player) {}
}
