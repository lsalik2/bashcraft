package net.slk.bashcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.chain.Chain;
import net.slk.bashcraft.chain.ChainParser;
import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.mc.MinecraftCommandRunner;

import java.util.Optional;

/** {@code /sh <chain>}. Needs no permission itself; each command in the chain checks its own. */
public final class ShCommand {
    private ShCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("sh")
                        .then(Commands.argument("chain", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "chain"))))));
    }

    /** Parses and runs a chain as {@code source}. Returns the last segment's result, or 0 if it failed. */
    public static int run(CommandSourceStack source, String input) {
        BashcraftConfig config = BashcraftConfig.get();
        Chain chain;
        try {
            chain = ChainParser.parse(input, ChainParser.Mode.SH, config.maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            return 0;
        }
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            source.sendFailure(Component.literal("BashCraft: chains nested deeper than " + config.maxDepth));
            return 0;
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            ExecResult result = new ChainExecutor(new MinecraftCommandRunner(source), config.swallowPipedOutput).execute(chain);
            return result.success() ? result.result() : 0;
        }
    }
}
