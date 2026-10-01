package net.slk.bashcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bashcraft implements ModInitializer {
    public static final String MOD_ID = "bashcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("BashCraft loaded");
    }
}
