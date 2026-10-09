package net.slk.bashcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.slk.bashcraft.client.ClientChains;
import net.slk.bashcraft.network.HelloPayload;

/** Client side: listens for the server's hello (spec B §2). */
public class BashcraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE,
                (payload, context) -> ClientChains.accept(payload.protocol(), payload.transparentMode()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientChains.clear());
    }
}
