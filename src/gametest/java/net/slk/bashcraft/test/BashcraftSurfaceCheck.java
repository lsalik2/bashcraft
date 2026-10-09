package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import static net.slk.bashcraft.test.Checks.check;

/** surface: a locate pipe lands on the ground; non-ops can't force chunk loading with it. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftSurfaceCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("bashcraft")).create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("execute as @p run sh locate structure #minecraft:village | surface | tp @s");
            check(server.computeOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                int ground = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(player.getX()), Mth.floor(player.getZ()));
                return Math.abs(player.getY() - ground) < 0.01 && (Math.abs(player.getX()) > 1 || Math.abs(player.getZ()) > 1);
            }), "locate | surface | tp lands on the ground");

            server.runCommand("scoreboard objectives add t trigger");
            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh echo 5000 ~ 5000 | surface && trigger t set 4"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t matches 4 run setblock 0 300 0 minecraft:stone");
            check(!server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(0, 300, 0)).is(Blocks.STONE)),
                    "a non-op player's surface is refused");
        }
    }
}
