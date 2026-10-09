package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.slk.bashcraft.client.ClientChains;

import static net.slk.bashcraft.test.Checks.check;

/** The chat box highlights and completes chains like ordinary commands (spec B §1). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftChatCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitFor(client -> ClientChains.active(), 100);
            // The client only knows the commands its player may use; the test player isn't an op by default.
            singleplayer.getServer().runOnServer(s -> s.getPlayerList().op(s.getPlayerList().getPlayers().getFirst().nameAndId()));
            context.waitFor(client -> client.player.connection.getCommands().getRoot().getChild("summon") != null, 100);

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~ && /summon "));
            context.waitFor(client -> !ChatProbe.completions(client).isEmpty(), 100);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "a valid chain has no red");
            check(context.computeOnClient(client -> ChatProbe.completions(client).stream().anyMatch(s -> s.endsWith("zombie"))),
                    "the second command gets summon's suggestions");

            context.runOnClient(client -> ChatProbe.open(client, "/locate structure #minecraft:village | /tp @s {x} 150 {z}"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "placeholders don't turn a pipe stage red");
            context.takeScreenshot("bashcraft-chain-highlight");

            context.runOnClient(client -> ChatProbe.open(client, "/sh say a && function bashcraft-test:"));
            context.waitFor(client -> ChatProbe.completions(client).contains("bashcraft-test:chain"), 100);

            context.runOnClient(client -> ChatProbe.open(client, "/(/say a || /say b"));
            context.waitFor(client -> !ChatProbe.usage(client).isEmpty(), 100);
            check(context.computeOnClient(client -> ChatProbe.usage(client).getFirst().startsWith("BashCraft: unclosed '('")),
                    "an unclosed group shows the BashCraft hint");

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "a vanilla line is unchanged");

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~ && /summon "));
            context.runOnClient(client -> {
                ClientChains.clear();
                ChatProbe.suggestions(client).updateCommandInfo();
            });
            check(context.computeOnClient(client -> ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "without a hello (no BashCraft server) the chain is vanilla red again");
            context.runOnClient(client -> client.gui.setScreen(null));
        }
    }
}
