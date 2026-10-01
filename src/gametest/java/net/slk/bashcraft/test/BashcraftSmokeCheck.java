package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import static net.slk.bashcraft.test.Checks.check;

/** The mod loads into a singleplayer world. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftSmokeCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            check(singleplayer.getServer().computeOnServer(s -> FabricLoader.getInstance().isModLoaded("bashcraft")),
                    "bashcraft is loaded on the server");
        }
    }
}
