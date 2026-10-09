package net.slk.bashcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.slk.bashcraft.client.ClientChains;
import net.slk.bashcraft.network.AliasNamesPayload;
import net.slk.bashcraft.network.HelloPayload;

/** Client side: listens for the server's hello (spec B §2). */
public class BashcraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(AliasNamesPayload.TYPE, (payload, context) -> ClientChains.acceptAliases(payload.names()));
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE,
                (payload, context) -> ClientChains.accept(payload.protocol(), payload.transparentMode()));
        // A new play connection (including a proxy's switch to another backend, which never fires DISCONNECT) starts
        // without BashCraft until that server says hello.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> ClientChains.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientChains.clear());
    }
}
