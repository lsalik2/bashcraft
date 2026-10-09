package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** Aliases and history end to end (spec C4 §7). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftAliasCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 16 12 120 16 minecraft:air");

            server.runCommand("sh alias global.mark = setblock $1 120 16 minecraft:stone");
            server.runCommand("sh mark 2");
            check(stone(server, 2), "a global alias with an argument");
            server.runCommand("function bashcraft-test:use_alias");
            check(stone(server, 1), "a global alias from a function");
            server.runCommand("sh alias global.tp = say no");
            server.runCommand("execute unless data storage bashcraft:aliases global.tp run setblock 7 120 16 minecraft:stone");
            check(stone(server, 7), "an alias can't be named after a command");

            // Typed by the (non-op) test player.
            server.runCommand("scoreboard objectives add t trigger");
            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh alias tr = trigger t set 5"));
            context.runOnClient(client -> client.getConnection().sendCommand("sh tr"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t matches 5 run setblock 3 120 16 minecraft:stone");
            check(stone(server, 3), "a player's personal alias from chat");

            server.runCommand("scoreboard players set @p t 0");
            server.runCommand("scoreboard players enable @a t");
            server.runCommand("execute as @p run sh tr");
            server.runCommand("execute if score @p t matches 5 run setblock 4 120 16 minecraft:stone");
            check(!stone(server, 4), "a personal alias never runs in an elevated chain");

            context.runOnClient(client -> client.getConnection().sendCommand("sh alias global.g = trigger t set 6"));
            context.waitTicks(5);
            server.runCommand("execute unless data storage bashcraft:aliases global.g run setblock 5 120 16 minecraft:stone");
            check(stone(server, 5), "a non-op can't define a global alias");

            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh trigger t set 7"));
            context.waitTicks(5);
            server.runCommand("scoreboard players set @p t 0");
            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh !!"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t matches 7 run setblock 6 120 16 minecraft:stone");
            check(stone(server, 6), "/sh !! reruns the player's last line");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 16)).is(Blocks.STONE));
    }
}
