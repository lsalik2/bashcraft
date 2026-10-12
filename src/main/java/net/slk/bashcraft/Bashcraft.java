package net.slk.bashcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.slk.bashcraft.command.ShCommand;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.HistoryBook;
import net.slk.bashcraft.mc.ServerJobs;
import net.slk.bashcraft.mc.TransparentHooks;
import net.slk.bashcraft.network.BashcraftNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class Bashcraft implements ModInitializer {
    public static final String MOD_ID = "bashcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        BashcraftConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bashcraft.json"));
        ShCommand.register();
        BashcraftNetwork.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID player = handler.getPlayer().getUUID();
            HistoryBook.forget(player);
            server.execute(() -> ServerJobs.forget(player));
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerJobs::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerJobs.clear());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ServerJobs.clear();
            if (BashcraftConfig.get().transparentMode && !TransparentHooks.applied()) {
                LOGGER.warn("BashCraft: transparent mode hooks did not apply to this Minecraft version; use /sh instead");
            }
        });
        LOGGER.info("BashCraft loaded");
    }
}
