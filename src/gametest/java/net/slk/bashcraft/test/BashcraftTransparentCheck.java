package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.TransparentHooks;

import static net.slk.bashcraft.test.Checks.check;

/** Transparent mode: chains without /sh from the console, command blocks, functions and player chat. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftTransparentCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            check(server.computeOnServer(s -> TransparentHooks.applied()), "transparent hooks applied");
            server.runCommand("fill 0 120 6 12 120 8 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("setblock 0 120 6 minecraft:stone && /setblock 1 120 6 minecraft:stone");
            check(stone(server, 0, 6) && stone(server, 1, 6), "the console chains without /sh");

            server.runCommand("setblock 2 121 6 minecraft:command_block{Command:\"setblock 2 120 6 minecraft:stone && /setblock 3 120 6 minecraft:stone\",auto:1b}");
            server.runCommand("setblock 3 121 7 minecraft:command_block{Command:\"setblock 0 120 6 minecraft:stone && /say x\",auto:1b}");
            context.waitTicks(5);
            check(stone(server, 2, 6) && stone(server, 3, 6), "command blocks chain without /sh");
            check(server.computeOnServer(s -> s.overworld().getBlockEntity(new BlockPos(3, 121, 7)) instanceof CommandBlockEntity block
                    && block.getCommandBlock().getSuccessCount() == 0), "a command block's failed transparent chain has SuccessCount 0");

            server.runCommand("function bashcraft-test:transparent");
            check(stone(server, 4, 6) && stone(server, 5, 6), "function files chain without sh");
            server.runCommand("function bashcraft-test:transparent_macro {msg:\"x ; setblock 6 120 6 minecraft:stone\"}");
            check(!stone(server, 6, 6) && !stone(server, 7, 6), "macro lines aren't chained, so a macro value can't add commands");

            server.runCommand("scoreboard players set n bc 9");
            server.runCommand("scoreboard players get n bc | /setblock {value} 120 7 minecraft:stone");
            check(stone(server, 9, 7), "transparent pipes");

            server.runCommand("setblock 10 120 6 minecraft:stone && setblock 11 120 6 minecraft:stone");
            check(!stone(server, 10, 6) && !stone(server, 11, 6), "no slash, no chain: the line goes to vanilla as one command");

            server.runCommand("sh setblock 11 120 7 minecraft:stone \\&& /setblock 12 120 7 minecraft:stone");
            check(!stone(server, 12, 7), "escaped operators stay literal (a running chain's segments are not re-split)");

            // Typed by the (non-op) test player.
            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard objectives add t2 trigger");
            server.runCommand("scoreboard players enable @a t1");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("trigger t1 set 5 && /trigger t2 set 6"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t1 matches 5 if score @p t2 matches 6 run setblock 8 120 6 minecraft:stone");
            check(stone(server, 8, 6), "player chat chains without /sh");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("msg Player0 hi && /trigger t2 set 7"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t2 matches 7 run setblock 0 120 7 minecraft:stone");
            check(!stone(server, 0, 7), "transparent chat refuses chat commands that need signing");

            // A function the player runs has the player's source but suppressed output; its chat commands aren't the player's chat.
            // /op is dedicated-server only; grant op through the player list.
            server.runOnServer(s -> s.getPlayerList().op(s.getPlayerList().getPlayers().getFirst().nameAndId()));
            context.runOnClient(client -> client.getConnection().sendCommand("function bashcraft-test:say_chain"));
            context.waitTicks(5);
            server.runOnServer(s -> s.getPlayerList().deop(s.getPlayerList().getPlayers().getFirst().nameAndId()));
            check(stone(server, 9, 8), "a function run by a player can chain say");

            server.runOnServer(s -> BashcraftConfig.get().transparentMode = false);
            server.runCommand("setblock 0 120 8 minecraft:stone && /setblock 1 120 8 minecraft:stone");
            server.runOnServer(s -> BashcraftConfig.get().transparentMode = true);
            check(!stone(server, 0, 8) && !stone(server, 1, 8), "transparentMode false turns the hook off");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
