package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** Variables, $?, $( … ) and echo end to end (spec C1 §7). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftVariableCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 10 12 120 12 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("sh x=1 && setblock $x 120 10 minecraft:stone");
            check(stone(server, 1, 10), "an assignment is visible to the next command");
            server.runCommand("sh setblock $global.x 120 11 minecraft:stone");
            check(stone(server, 1, 11), "variables persist to the next line; the console writes the global set");

            // Storage exists now (a missing id reads as a detached tag): a non-op's refused write leaves no empty player set.
            context.runOnClient(client -> client.getConnection().sendCommand("sh global.g=1"));
            context.waitTicks(5);
            server.runCommand("execute if data storage bashcraft:vars players run setblock 10 120 10 minecraft:stone");
            check(!stone(server, 10, 10), "a variable write that fails leaves nothing in storage");
            server.runCommand("function bashcraft-test:read_global");
            check(stone(server, 6, 10), "datapacks read the global set from command storage");

            server.runCommand("execute as @p run sh my.p=3");
            server.runCommand("sh setblock $p 120 10 minecraft:stone || setblock 4 120 10 minecraft:stone");
            check(!stone(server, 3, 10) && stone(server, 4, 10), "the console can't see a player's own variables");
            server.runCommand("execute as @p run sh setblock $my.p 120 12 minecraft:stone");
            check(stone(server, 3, 12), "an elevated chain reads a player's set when it asks for my.");

            // Typed by the (non-op) test player.
            context.runOnClient(client -> client.getConnection().sendCommand("sh global.g=1"));
            context.runOnClient(client -> client.getConnection().sendCommand("sh x=9"));
            context.waitTicks(5);
            server.runCommand("execute unless data storage bashcraft:vars global.g run setblock 10 120 13 minecraft:stone");
            // SNBT injection: a player's nickname that breaks out of the CustomName string and re-tags the entity.
            context.runOnClient(client -> client.getConnection().sendCommand("sh nick='x\",Tags:[\"bc_pwned\"],CustomName:\"y'"));
            context.waitTicks(5);
            server.runCommand("execute as @p run sh summon minecraft:armor_stand 0 120 14 {Tags:[\"bc_safe\"],CustomName:\"$my.nick\"}");
            server.runCommand("execute if entity @e[tag=bc_pwned] run setblock 12 120 14 minecraft:stone");
            check(!stone(server, 12, 14), "a player's text can't inject SNBT into a command an elevated chain runs");
            check(stone(server, 10, 13), "a non-op player can't write the global set");
            server.runCommand("execute as @p run sh setblock $x 120 13 minecraft:stone");
            check(stone(server, 1, 13) && !stone(server, 9, 13), "a player's own x doesn't shadow the global x in an elevated chain");

            server.runCommand("data modify storage bashcraft:vars global.round set value 3");
            server.runCommand("sh y=1");
            server.runCommand("execute if data storage bashcraft:vars global{round:3} run setblock 11 120 13 minecraft:stone");
            check(stone(server, 11, 13), "a variable write keeps non-string data datapacks stored in global");

            server.runCommand("scoreboard players set n bc 5");
            server.runCommand("sh setblock $(scoreboard players get n bc) 120 10 minecraft:stone");
            check(stone(server, 5, 10), "$( … ) inserts a command's value");

            server.runCommand("sh echo 7 | setblock {value} 120 10 minecraft:stone");
            check(stone(server, 7, 10), "echo output pipes");

            server.runCommand("sh setblock 1 120 10 minecraft:stone ; scoreboard players set s bc $?");
            server.runCommand("execute if score s bc matches 1 run setblock 8 120 10 minecraft:stone");
            check(stone(server, 8, 10), "$? is 1 after a failed command");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
