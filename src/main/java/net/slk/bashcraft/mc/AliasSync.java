package net.slk.bashcraft.mc;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.network.AliasNamesPayload;

/** Tells clients which alias names they may call (spec C4 §5). */
public final class AliasSync {
    private AliasSync() {}

    /** A player's personal set changed ({@code player}), or the global set ({@code player == null}: everyone). */
    public static void changed(MinecraftServer server, ServerPlayer player) {
        if (player != null) {
            send(server, player);
        } else {
            server.getPlayerList().getPlayers().forEach(online -> send(server, online));
        }
    }

    public static void send(MinecraftServer server, ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, AliasNamesPayload.TYPE)) {
            ServerPlayNetworking.send(player, new AliasNamesPayload(StorageAliases.names(server, player)));
        }
    }
}
