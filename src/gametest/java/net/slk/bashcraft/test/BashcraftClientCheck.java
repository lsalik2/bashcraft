package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.slk.bashcraft.client.ClientChains;

import static net.slk.bashcraft.test.Checks.check;

/** The server's hello turns the client companion on; leaving the world turns it off. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftClientCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitFor(client -> ClientChains.active(), 100);
            check(context.computeOnClient(client -> ClientChains.layout("/tp @s ~ ~ ~ && /summon zombie").isPresent()),
                    "the hello reports transparent mode, so prefix-free chains are recognised");
            check(context.computeOnClient(client -> ClientChains.layout("/tp @s ~ ~ ~").isEmpty()), "plain commands are not chains");
        }
        check(!context.computeOnClient(client -> ClientChains.active()), "leaving the world clears the companion");
    }
}
