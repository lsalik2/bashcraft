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

            context.runOnClient(client -> ChatProbe.open(client, "/(/tp @s ~ ~ ~ && /summon "));
            context.waitFor(client -> ChatProbe.usage(client).stream().anyMatch(line -> line.contains("<entity>")), 100);

            context.runOnClient(client -> {
                ChatProbe.open(client, "/(/say a) && /say b");
                ChatProbe.moveCursor(client, 9);
            });
            context.waitTicks(2);
            check(context.computeOnClient(client -> ChatProbe.completions(client).isEmpty()), "nothing is suggested right after ')'");

            context.runOnClient(client -> ChatProbe.open(client, "/sh say hi && /tp @s ~ ~ ~"));
            context.waitTicks(2);
            check(context.computeOnClient(ChatProbe::isMessage), "a chat command anywhere in the chain marks the line as a message");

            context.runOnClient(client -> ChatProbe.open(client, "/sh x=5 && setblock $x 64 $(scoreboard players get n bc) minecraft:stone"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "variables, substitutions and assignments are not shown as errors");
            context.takeScreenshot("bashcraft-variables");

            context.runOnClient(client -> ChatProbe.open(client, "/sh list | each gamemode "));
            context.waitFor(client -> ChatProbe.completions(client).contains("creative"), 100);
            context.runOnClient(client -> ChatProbe.open(client, "/sh list | grep x | each tp @s ~ ~1 ~ | store storage bashcraft:t v"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "pipe builtins are not shown as errors");

            context.runOnClient(client -> client.getConnection().sendCommand("sh alias hb = say hi"));
            context.waitTicks(5);
            context.runOnClient(client -> ChatProbe.open(client, "/sh hb && hb"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "alias calls are not shown as errors");
            context.runOnClient(client -> ChatProbe.open(client, "/sh echo a ; sleep 1s & jobs ; kill %1"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "sleep, &, jobs and kill %N are not shown as errors");
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
