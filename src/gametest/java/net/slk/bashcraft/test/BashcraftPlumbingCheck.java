package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** store, grep and each end to end (spec C2 §6). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftPlumbingCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 15 12 120 15 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("scoreboard players set n bc 5");
            server.runCommand("sh scoreboard players get n bc | store score m bc");
            server.runCommand("execute if score m bc matches 5 run setblock 0 120 15 minecraft:stone");
            check(stone(server, 0), "store score copies a score");

            server.runCommand("sh echo hello | store storage bashcraft:test greeting");
            server.runCommand("execute if data storage bashcraft:test {greeting:\"hello\"} run setblock 1 120 15 minecraft:stone");
            check(stone(server, 1), "store storage writes a string");

            server.runCommand("tag @p add boss");
            server.runCommand("sh data get entity @p Tags | grep boss && setblock 2 120 15 minecraft:stone");
            check(stone(server, 2), "grep finds a tag in an NBT list");
            server.runCommand("sh tag @p list | grep nope || setblock 3 120 15 minecraft:stone");
            check(stone(server, 3), "grep without a match drives ||");

            server.runCommand("sh list | each scoreboard players set {value} bc 9");
            server.runCommand("execute if score Player0 bc matches 9 run setblock 4 120 15 minecraft:stone");
            check(stone(server, 4), "each runs a command per online player");

            // /list and /team list show display names ([Admin] Steve, [red]); items must be the real names.
            server.runCommand("team add red");
            server.runCommand("team modify red prefix \"[Admin] \"");
            server.runCommand("team join red @p");
            server.runCommand("sh list | each scoreboard players set {value} bc 3");
            server.runCommand("execute if score Player0 bc matches 3 run setblock 6 120 15 minecraft:stone");
            check(stone(server, 6), "list items are player names, not team-decorated display names");
            server.runCommand("sh team list | grep -x red && setblock 7 120 15 minecraft:stone");
            check(stone(server, 7), "team list items are team names");
            server.runCommand("team leave @p");
            server.runCommand("scoreboard players set Player0 bc 9");

            server.runCommand("sh list | each scoreboard players get {value} bc | store storage bashcraft:test scores");
            server.runCommand("execute if data storage bashcraft:test {scores:[9]} run setblock 5 120 15 minecraft:stone");
            check(stone(server, 5), "each collects values into a list that store writes");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 15)).is(Blocks.STONE));
    }
}
