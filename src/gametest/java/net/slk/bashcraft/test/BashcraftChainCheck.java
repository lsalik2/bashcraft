package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;

import static net.slk.bashcraft.test.Checks.check;

/** /sh end to end: operators, pipes, command blocks, functions and the nesting limit. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftChainCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill -4 120 0 12 120 5 minecraft:air");
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

            // A failed chain must report failure to /execute store and command blocks (no extra error line).
            server.runCommand("execute store success score failed bc run sh setblock 0 120 0 minecraft:stone && setblock 1 120 4 minecraft:stone");
            server.runCommand("execute if score failed bc matches 0 run setblock 0 120 4 minecraft:stone");
            check(stone(server, 0, 4) && !stone(server, 1, 4), "a failed chain reports failure to /execute store success");
            server.runCommand("execute store success score passed bc run sh setblock 2 120 4 minecraft:stone");
            server.runCommand("execute if score passed bc matches 1 run setblock 3 120 4 minecraft:stone");
            check(stone(server, 3, 4), "a successful chain reports success to /execute store success");
            server.runCommand("setblock 5 121 4 minecraft:command_block{Command:\"sh setblock 0 120 0 minecraft:stone && say x\",auto:1b}");
            context.waitTicks(5);
            check(server.computeOnServer(s -> s.overworld().getBlockEntity(new BlockPos(5, 121, 4)) instanceof CommandBlockEntity block
                    && block.getCommandBlock().getSuccessCount() == 0), "a command block running a failed chain has SuccessCount 0");

            // Zero and negative values still pipe.
            server.runCommand("scoreboard players set neg bc -3");
            server.runCommand("sh scoreboard players get neg bc | setblock {value} 120 5 minecraft:stone");
            check(stone(server, -3, 5), "a negative score pipes into a placeholder");

            // A function with one erroring line still counts as success.
            server.runCommand("sh function bashcraft-test:partial && setblock 5 120 5 minecraft:stone");
            check(stone(server, 4, 5) && stone(server, 5, 5), "a function with an erroring line still succeeds for &&");

            // Typed by the (non-op) test player: /trigger is the observable level-0 command.
            server.runCommand("scoreboard objectives add trig trigger");
            server.runCommand("scoreboard players enable @a trig");
            context.runOnClient(client -> client.getConnection().sendCommand("sh trigger trig set 7"));
            context.waitTicks(5);
            server.runCommand("execute if score @p trig matches 7 run setblock 6 120 5 minecraft:stone");
            check(stone(server, 6, 5), "control: the player can run /sh");
            context.runOnClient(client -> client.getConnection().sendCommand("sh setblock 8 120 5 minecraft:stone"));
            context.waitTicks(5);
            check(!stone(server, 8, 5), "/sh grants a player no extra permission");
            server.runCommand("scoreboard players enable @a trig");
            context.runOnClient(client -> client.getConnection().sendCommand("sh msg Player0 hi && trigger trig set 9"));
            context.waitTicks(5);
            server.runCommand("execute if score @p trig matches 9 run setblock 7 120 5 minecraft:stone");
            check(!stone(server, 7, 5), "a player's /sh refuses chat commands that would need signing");
            server.runCommand("scoreboard players enable @a trig");
            context.runOnClient(client -> client.getConnection().sendCommand("sh sh msg Player0 hi && trigger trig set 11"));
            context.waitTicks(5);
            server.runCommand("execute if score @p trig matches 11 run setblock 9 120 5 minecraft:stone");
            check(!stone(server, 9, 5), "a nested sh typed by the player also refuses chat commands");

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
