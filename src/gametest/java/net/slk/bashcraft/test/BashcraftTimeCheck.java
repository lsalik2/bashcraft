package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** sleep, &, jobs and kill end to end (spec C3 §9). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftTimeCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 20 20 120 20 minecraft:air");

            server.runCommand("sh setblock 0 120 20 minecraft:stone ; sleep 20 ; setblock 1 120 20 minecraft:stone");
            check(stone(server, 0) && !stone(server, 1), "a chain pauses at sleep");
            context.waitTicks(25);
            check(stone(server, 1), "the rest of the chain runs after the sleep");

            server.runCommand("sh (sleep 10 ; setblock 3 120 20 minecraft:stone) & setblock 2 120 20 minecraft:stone");
            check(stone(server, 2) && !stone(server, 3), "& runs what follows at once");
            context.waitTicks(15);
            check(stone(server, 3), "the backgrounded part runs after its sleep");

            server.runCommand("function bashcraft-test:sleepy");
            check(stone(server, 4) && !stone(server, 5), "a function goes on past a line that paused");
            context.waitTicks(15);
            check(stone(server, 5), "the paused line finishes later");

            server.runCommand("sh sh sleep 10 \\; setblock 12 120 20 minecraft:stone | grep x ; setblock 13 120 20 minecraft:stone");
            check(stone(server, 13) && !stone(server, 12), "a nested sh that sleeps returns at once, even in a pipe");
            context.waitTicks(15);
            check(stone(server, 12), "the nested chain finishes as its own job");

            server.runCommand("summon minecraft:armor_stand 0 121 22 {Tags:[\"sleeper\"],NoGravity:1b}");
            server.runCommand("execute as @e[tag=sleeper] run sh sleep 10 ; setblock 14 120 20 minecraft:stone");
            server.runCommand("kill @e[tag=sleeper]");
            context.waitTicks(15);
            check(!stone(server, 14), "a chain whose entity is gone ends");

            server.runCommand("tick freeze");
            server.runCommand("sh sleep 5 ; setblock 11 120 20 minecraft:stone");
            context.waitTicks(10);
            check(!stone(server, 11), "a frozen game pauses sleeps");
            server.runCommand("tick unfreeze");
            context.waitTicks(10);
            check(stone(server, 11), "sleeps go on when the game runs again");

            server.runCommand("sh sleep 30 ; setblock 6 120 20 minecraft:stone");
            server.runCommand("sh kill %1");
            context.waitTicks(35);
            check(!stone(server, 6), "kill %1 cancels a waiting chain");

            // Typed by the (non-op) test player: their job (%1) resumes as them; they can't cancel the server's (%2).
            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard players enable @a t1");
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 20 ; trigger t1 set 3"));
            context.waitTicks(3);
            server.runCommand("sh sleep 40 ; setblock 7 120 20 minecraft:stone");
            context.runOnClient(client -> client.getConnection().sendCommand("sh kill %2"));
            context.waitTicks(50);
            server.runCommand("execute if score @p t1 matches 3 run setblock 8 120 20 minecraft:stone");
            check(stone(server, 8), "a player's chain resumes as that player");
            check(stone(server, 7), "a player can't cancel another's job");

            // A player's waiting chains share one command budget per second: a timer that runs too much ends.
            context.runOnClient(client -> client.getConnection().sendCommand(
                    "sh alias e10 = 'echo x ; echo x ; echo x ; echo x ; echo x ; echo x ; echo x ; echo x ; echo x ; echo x'"));
            context.runOnClient(client -> client.getConnection().sendCommand("sh alias spin = 'sleep 1 ; e10 ; e10 ; e10 ; e10 ; e10 ; spin'"));
            context.runOnClient(client -> client.getConnection().sendCommand("sh spin"));
            context.waitTicks(3);
            server.runCommand("sh jobs | grep spin && setblock 17 120 20 minecraft:stone");
            check(stone(server, 17), "a player's timer waits as a job");
            context.waitTicks(40);
            server.runCommand("sh jobs | grep spin && setblock 18 120 20 minecraft:stone");
            check(!stone(server, 18), "a player's waiting chains share a command budget per second");

            // An operator's waiting chain has their permission only while they keep it.
            // So does one they start as another entity (execute as … run sh), which wakes as that entity.
            server.runCommand("summon minecraft:armor_stand 0 121 22 {Tags:[\"keeper\"],NoGravity:1b}");
            server.runOnServer(s -> s.getPlayerList().op(player(s).nameAndId()));
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 10 ; setblock 9 120 20 minecraft:stone"));
            context.runOnClient(client -> client.getConnection().sendCommand(
                    "execute as @e[tag=keeper] run sh sleep 10 ; setblock 16 120 20 minecraft:stone"));
            context.waitTicks(15);
            check(stone(server, 9), "an operator's waiting chain keeps their powers");
            check(stone(server, 16), "an operator's chain started as another entity keeps their powers");
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 20 ; setblock 10 120 20 minecraft:stone"));
            context.runOnClient(client -> client.getConnection().sendCommand(
                    "execute as @e[tag=keeper] run sh sleep 20 ; setblock 15 120 20 minecraft:stone"));
            context.waitTicks(3);
            server.runOnServer(s -> s.getPlayerList().deop(player(s).nameAndId()));
            context.waitTicks(25);
            check(!stone(server, 10), "a waiting chain loses its powers after a de-op");
            check(!stone(server, 15), "a chain started as another entity loses its powers after a de-op");
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 20)).is(Blocks.STONE));
    }
}
