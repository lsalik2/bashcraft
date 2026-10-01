package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** /sh end to end: operators, pipes, command blocks, functions and the nesting limit. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftChainCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 0 12 120 3 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("sh setblock 0 120 0 minecraft:stone && setblock 1 120 0 minecraft:stone");
            check(stone(server, 0, 0) && stone(server, 1, 0), "&& runs the second command after a success");
            server.runCommand("sh setblock 0 120 0 minecraft:stone && setblock 2 120 0 minecraft:stone");
            check(!stone(server, 2, 0), "&& skips the second command after a failure");
            server.runCommand("sh setblock 0 120 0 minecraft:stone || setblock 3 120 0 minecraft:stone");
            check(stone(server, 3, 0), "|| runs the second command after a failure");
            server.runCommand("sh setblock 0 120 0 minecraft:stone ; setblock 4 120 0 minecraft:stone");
            check(stone(server, 4, 0), "; always runs the second command");

            server.runCommand("scoreboard players set n bc 11");
            server.runCommand("sh scoreboard players get n bc | setblock {value} 120 1 minecraft:stone");
            check(stone(server, 11, 1), "a score pipes into a placeholder");

            server.runCommand("setblock 5 121 0 minecraft:command_block{Command:\"sh setblock 5 120 0 minecraft:stone && setblock 6 120 0 minecraft:stone\",auto:1b}");
            context.waitTicks(5);
            check(stone(server, 5, 0) && stone(server, 6, 0), "chains run from a command block");

            server.runCommand("function bashcraft-test:chain");
            check(stone(server, 7, 0) && stone(server, 8, 0), "chains run from a function");

            server.runCommand("function bashcraft-test:pipe");
            check(stone(server, 11, 3), "pipes capture output inside a function, where output is suppressed");

            server.runCommand("sh function bashcraft-test:chain && setblock 10 120 0 minecraft:stone");
            check(stone(server, 10, 0), "a function without return counts as success for &&");

            server.runCommand("function bashcraft-test:recurse");
            server.runCommand("sh scoreboard players get depth bc | setblock {value} 120 2 minecraft:stone");
            check(stone(server, 9, 2), "recursion through functions stops after maxDepth (8) nested chains: 9 runs");
        }

        // The default test world is flat with no structures; /locate needs a normal world. Fixed seed keeps it deterministic.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("bashcraft")).create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("execute as @p run sh locate structure #minecraft:village | tp @s {x} 200 {z}");
            check(server.computeOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                return Math.abs(player.getY() - 200) < 0.01 && (Math.abs(player.getX()) > 1 || Math.abs(player.getZ()) > 1);
            }), "locate pipes coordinates into tp");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
