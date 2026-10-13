package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.slk.bashcraft.mc.ServerJobs;
import net.slk.bashcraft.docs.GuideSnippets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.slk.bashcraft.test.Checks.check;

/** Every "Give it a try!" snippet in the docs, pasted into chat in a fresh creative world (spec D §6). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftGuideCheck implements FabricClientGameTest {
    /** A fresh creative world with cheats, on the seed the surface checks use (it has villages near spawn). */
    static TestSingleplayerContext creativeWorld(ClientGameTestContext context) {
        return context.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> {
            settings.setSeed("bashcraft");
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        }).create();
    }

    /** Sends a chat command as the player and waits until the server has handled it and its replies arrived. */
    static void type(ClientGameTestContext context, TestServerContext server, String command) {
        context.runOnClient(client -> client.getConnection().sendCommand(command.substring(1)));
        context.waitTicks(5);
        server.runOnServer(s -> {});
        context.waitTicks(5);
    }

    /**
     * A reader pastes the next line only after this one has finished: wait until no chain is waiting (at most 110 ticks,
     * a little over the 100-tick sleep a snippet line may have; a timer that repeats never finishes).
     */
    static void settle(ClientGameTestContext context, TestServerContext server) {
        for (int t = 0; t < 110 && server.computeOnServer(s -> ServerJobs.waiting()) > 0; t += 5) {
            context.waitTicks(5);
        }
        server.runOnServer(s -> {});
        context.waitTicks(5);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ChatLog.start();
        Path root = Path.of(System.getProperty("bashcraft.projectDir"));
        List<Path> files;
        try {
            files = GuideSnippets.docFiles(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean canaryDone = false;
        for (Path file : files) {
            String name = root.relativize(file).toString();
            List<GuideSnippets.Snippet> snippets;
            try {
                snippets = GuideSnippets.parse(name, Files.readAllLines(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (snippets.isEmpty() && canaryDone) {
                continue;
            }
            try (TestSingleplayerContext world = creativeWorld(context)) {
                TestServerContext server = world.getServer();
                server.runOnServer(s -> s.getPlayerList().op(s.getPlayerList().getPlayers().getFirst().nameAndId()));
                context.waitTicks(5);
                if (!canaryDone) {
                    // The detector itself: an unknown command must be seen as an error.
                    ChatLog.clear();
                    type(context, server, "/sh bashcraft_no_such_command");
                    check(!ChatLog.red().isEmpty(), "the snippet check sees error messages");
                    canaryDone = true;
                }
                for (GuideSnippets.Snippet snippet : snippets) {
                    ChatLog.clear();
                    for (String command : snippet.commands()) {
                        type(context, server, command);
                        settle(context, server);
                    }
                    List<String> red = ChatLog.red();
                    String where = snippet.file() + ":" + snippet.line();
                    if (snippet.errorsExpected()) {
                        check(!red.isEmpty(), where + " is marked errors-expected but showed no error");
                    } else {
                        check(red.isEmpty(), where + " showed an error: " + red);
                    }
                }
            }
        }
    }
}
