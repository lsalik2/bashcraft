package net.slk.bashcraft.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.AliasSync;
import net.slk.bashcraft.mc.TransparentHooks;

/** The hello and alias-name payloads: registered on both sides, sent on join to clients that have BashCraft. */
public final class BashcraftNetwork {
    private BashcraftNetwork() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AliasNamesPayload.TYPE, AliasNamesPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler, HelloPayload.TYPE)) {
                boolean transparent = BashcraftConfig.get().transparentMode && TransparentHooks.applied();
                sender.sendPacket(new HelloPayload(HelloPayload.PROTOCOL, transparent));
            }
            AliasSync.send(server, handler.getPlayer());
        });
    }
}
