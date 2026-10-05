package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** Grouping end to end: /sh, transparent console and chat, function files, and a grouped locate fallback. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftGroupCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 9 12 120 9 minecraft:air");

            server.runCommand("sh (setblock 0 120 9 minecraft:stone || setblock 1 120 9 minecraft:stone) && setblock 2 120 9 minecraft:stone");
            check(stone(server, 0) && !stone(server, 1) && stone(server, 2), "/sh groups: || inside, && after");

            server.runCommand("(/setblock 0 120 9 minecraft:stone || /setblock 3 120 9 minecraft:stone) && /setblock 4 120 9 minecraft:stone");
            check(stone(server, 3) && stone(server, 4), "a line starting with a group chains without /sh");

            server.runCommand("function bashcraft-test:grouped");
            check(stone(server, 5) && stone(server, 6) && stone(server, 7), "grouped chains in function files");

            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard objectives add t2 trigger");
            server.runCommand("scoreboard players enable @a t1");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("(/trigger t1 set 3 ; /trigger t2 set 4)"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t1 matches 3 if score @p t2 matches 4 run setblock 8 120 9 minecraft:stone");
            check(stone(server, 8), "a grouped chain typed by the player");
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("bashcraft")).create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("execute as @p run sh (locate structure #minecraft:nonexistent_tag || locate structure #minecraft:village) | tp @s {x} 200 {z}");
            check(server.computeOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                return Math.abs(player.getY() - 200) < 0.01 && (Math.abs(player.getX()) > 1 || Math.abs(player.getZ()) > 1);
            }), "a group pipes the output of the locate that succeeded");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 9)).is(Blocks.STONE));
    }
}
