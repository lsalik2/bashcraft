package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.CommandBlockEntity;

import java.nio.file.Path;
import java.util.List;

import static net.slk.bashcraft.test.BashcraftGuideCheck.type;
import static net.slk.bashcraft.test.Checks.check;

/** The docs' screenshots (spec D §7); only when ./gradlew updateScreenshots sets bashcraft.screenshots. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftScreenshots implements FabricClientGameTest {
    private interface Scene {
        void stage(ClientGameTestContext context, TestServerContext server);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        String out = System.getProperty("bashcraft.screenshots");
        if (out == null) {
            return;
        }
        ChatLog.start();
        // The GUI lays itself out for the window, so the window itself must have the screenshot's size.
        context.getInput().resizeWindow(1280, 720);
        context.waitTicks(5);
        shoot(context, Path.of(out), "chat-highlighting", false, (c, s) -> {
            c.runOnClient(client -> ChatProbe.open(client, "/sh tp @s ~ ~9 ~ && sleep 2s ; echo hi"));
            c.waitTicks(5);
        });
        shoot(context, Path.of(out), "countdown", false, (c, s) -> {
            type(c, s, "/sh echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1 ; sleep 1s ; title @s title \"Go!\"");
            c.waitTicks(65);
        });
        shoot(context, Path.of(out), "jobs", false, (c, s) -> {
            // Three timers, then only the list: what jobs shows, and the line that cancels one.
            type(c, s, "/sh (sleep 30s ; echo tea is ready) & (sleep 45s ; echo pizza is ready) & (sleep 60s ; echo laundry is done) &");
            c.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
            type(c, s, "/sh jobs");
            c.runOnClient(client -> ChatProbe.open(client, "/sh kill %2"));
            c.waitTicks(5);
        });
        shoot(context, Path.of(out), "pipe-surface", false, (c, s) -> {
            type(c, s, "/sh locate structure #village | surface | tp @s");
            c.runOnClient(client -> look(client, -30, 20));
            c.waitTicks(80);
        });
        shoot(context, Path.of(out), "alias", false, (c, s) -> {
            // The alias itself (alias lists it), a call and its output, and the next call typed (the companion colours it).
            type(c, s, "/sh alias hop = 'tp @s ~ ~$1 ~ && echo up $1 blocks'");
            type(c, s, "/sh alias");
            type(c, s, "/sh hop 5");
            c.runOnClient(client -> ChatProbe.open(client, "/sh hop 10"));
            c.waitTicks(5);
        });
        shoot(context, Path.of(out), "command-block", false, (c, s) -> {
            BlockPos pos = s.computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().blockPosition().offset(2, 0, 0));
            s.runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:command_block");
            c.waitTicks(5);
            c.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
            c.runOnClient(client -> client.gui.setScreen(new CommandBlockEditScreen((CommandBlockEntity) client.level.getBlockEntity(pos))));
            c.waitTicks(5);
            c.getInput().typeChars("sh tp @p ~ ~9 ~ && sleep 2s ; echo hi");
            c.waitTicks(5);
        });
        shoot(context, Path.of(out), "numbered-error", true, (c, s) ->
                type(c, s, "/sh give @s minecraft:apple && give @s minecraft:no_such_item && echo never shown"));
        shoot(context, Path.of(out), "transparent", false, (c, s) -> type(c, s, "/time set noon && /weather clear"));
    }

    private static void look(net.minecraft.client.Minecraft client, float yaw, float pitch) {
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
    }

    private static void shoot(ClientGameTestContext context, Path out, String name, boolean errorsExpected, Scene scene) {
        try (TestSingleplayerContext world = BashcraftGuideCheck.creativeWorld(context)) {
            TestServerContext server = world.getServer();
            server.runOnServer(s -> s.getPlayerList().op(s.getPlayerList().getPlayers().getFirst().nameAndId()));
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            // Above the lakeside village near spawn on this seed, looking across its rooftops (creative flight keeps the
            // player there). Fixed coordinates: the nearest village varies with the spawn point.
            server.runCommand("tp @p -399.5 101 192.5");
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.player.getAbilities().flying = true;
                client.player.onUpdateAbilities();
                look(client, -30, 25);
            });
            context.waitTicks(60);
            context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
            ChatLog.clear();
            scene.stage(context, server);
            List<String> red = ChatLog.red();
            check(errorsExpected != red.isEmpty(), name + (errorsExpected ? " should show an error" : " showed an error: " + red));
            context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
        }
    }
}
