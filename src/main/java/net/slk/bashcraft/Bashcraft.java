package net.slk.bashcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.slk.bashcraft.command.ShCommand;
import net.slk.bashcraft.config.BashcraftConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bashcraft implements ModInitializer {
    public static final String MOD_ID = "bashcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        BashcraftConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bashcraft.json"));
        ShCommand.register();
        LOGGER.info("BashCraft loaded");
    }
}
