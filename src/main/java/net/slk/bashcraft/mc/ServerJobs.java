package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.slk.bashcraft.Bashcraft;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.Continuation;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.JobTable;
import net.slk.bashcraft.exec.Jobs;
import net.slk.bashcraft.exec.ShellContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's waiting chains (spec C3 §5–6). A chain a player typed wakes as that player is now (position, dimension,
 * permission); any other chain as the source it started with, never with more permission than the player whose
 * command started it (if any) has now. Server thread only.
 */
public final class ServerJobs {
    /**
     * A waiting chain and how to find its source again: the player who typed it, else the source it started with and
     * the player whose command started that (null for command blocks, functions run by the server, the console).
     */
    private record Waiting(Continuation continuation, UUID typist, CommandSourceStack saved, NameAndId starter) {}

    private static JobTable<Waiting> table = newTable();
    /**
     * While a job wakes, the player who started it: what it queues (a part after {@code &}, a nested {@code sh}) has
     * a source whose player may have left or respawned since, so they can't be found from it again.
     */
    private static NameAndId wakingStarter;

    private ServerJobs() {}

    /** Where a chain that {@code source} starts puts its waiting parts. */
    public static Jobs forSource(CommandSourceStack source) {
        return new View(source, null);
    }

    /** Counts a tick (only while the game runs normally, so /tick freeze pauses sleeps) and wakes the jobs now due. */
    public static void tick(MinecraftServer server) {
        if (!server.tickRateManager().runsNormally()) {
            return;
        }
        table.tick();
        for (Optional<JobTable.Due<Waiting>> due = table.nextDue(); due.isPresent(); due = table.nextDue()) {
            wake(server, due.get());
        }
    }

    /** A player left: the chains they typed end, and so do chains that run as them (spec C3 §7). */
    public static void forget(UUID player) {
        table.cancelOwner(player.toString());
        table.cancelIf(waiting -> waiting.saved() != null && waiting.saved().getEntity() != null
                && waiting.saved().getEntity().getUUID().equals(player));
    }

    /** Jobs are never kept across a restart. */
    public static void clear() {
        table = newTable();
    }

    private static JobTable<Waiting> newTable() {
        BashcraftConfig config = BashcraftConfig.get();
        return new JobTable<>(config.maxJobs, config.maxJobsPerPlayer, config.maxServerJobs, config.maxSleepTicks);
    }

    private static void wake(MinecraftServer server, JobTable.Due<Waiting> due) {
        Optional<CommandSourceStack> source = sourceOf(server, due.payload());
        View jobs = new View(source.orElse(null), due);
        wakingStarter = due.payload().starter();
        try {
            source.ifPresent(stack -> resume(stack, jobs, due.payload()));
        } catch (RuntimeException e) {
            // An exception out of a tick handler would stop the server; the broken job just ends.
            Bashcraft.LOGGER.error("BashCraft: waiting chain %{} failed", due.number(), e);
        } finally {
            wakingStarter = null;
            if (!jobs.requeued) {
                table.finish(due.number());
            }
        }
    }

    private static void resume(CommandSourceStack source, View jobs, Waiting waiting) {
        BashcraftConfig config = BashcraftConfig.get();
        // A player's waiting chains share one budget per second, so timers can't multiply what they can run.
        String owner = waiting.typist() == null ? null : waiting.typist().toString();
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth, table.budgetSpent(owner));
        if (scope.isEmpty()) {
            return;
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            ShellContext shell = new ShellContext(StorageVariables.of(source), StorageAliases.of(source),
                    Sources.typist(source).map(HistoryBook::of).orElse(null), jobs);
            try {
                new ChainExecutor(new MinecraftCommandRunner(source), shell, config.swallowPipedOutput, config.maxTotalCommands,
                        config.maxDepth).resume(waiting.continuation());
            } finally {
                table.setBudgetSpent(owner, DepthGuard.spent());
            }
        }
    }

    /** The player as they are now, or the saved source; empty when the player left or the source's entity is gone. */
    private static Optional<CommandSourceStack> sourceOf(MinecraftServer server, Waiting waiting) {
        if (waiting.typist() != null) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(waiting.typist())).map(ServerPlayer::createCommandSourceStack);
        }
        CommandSourceStack saved = waiting.saved();
        if (saved.getEntity() != null && saved.getEntity().isRemoved()) {
            return Optional.empty();
        }
        // A de-opped player's chain, even one run as another entity, can't keep their old permission.
        return Optional.of(waiting.starter() == null ? saved : saved.withMaximumPermission(server.getProfilePermissions(waiting.starter())));
    }

    /**
     * The source a chain no player typed wakes as: past any pipe tees (so output reaches the command block or the
     * console) and without a result callback (whoever ran /sh had its result long ago).
     */
    private static CommandSourceStack saved(CommandSourceStack source) {
        return source.withSource(Sources.origin(source)).withCallback(CommandResultCallback.EMPTY);
    }

    /** The player whose command started a chain no player typed, if any (see {@link #wakingStarter}). */
    private static NameAndId starter(CommandSourceStack source) {
        return Sources.starter(source).map(ServerPlayer::nameAndId).orElse(wakingStarter);
    }

    /** One chain's view of the table: who owns the jobs it queues, and which jobs it may see and cancel. */
    private static final class View implements Jobs {
        private final CommandSourceStack source;
        /** The job being resumed, or null for a chain just started. */
        private final JobTable.Due<Waiting> resuming;
        private boolean requeued;

        View(CommandSourceStack source, JobTable.Due<Waiting> resuming) {
            this.source = source;
            this.resuming = resuming;
        }

        @Override
        public void queue(Continuation continuation, int delay, String text) throws ExpansionException {
            Optional<ServerPlayer> typist = Sources.typist(source);
            table.add(owner(), typist.map(player -> player.getName().getString()).orElse(source.getTextName()), text, delay,
                    typist.isPresent() ? new Waiting(continuation, typist.get().getUUID(), null, null)
                            : new Waiting(continuation, null, saved(source), starter(source)));
        }

        @Override
        public void requeue(Continuation continuation, int delay) throws ExpansionException {
            Waiting waiting = resuming.payload();
            table.requeue(resuming.number(), delay, new Waiting(continuation, waiting.typist(), waiting.saved(), waiting.starter()));
            requeued = true;
        }

        @Override
        public List<String> listing() {
            return table.listing(owner(), operator());
        }

        @Override
        public void kill(int number) throws ExpansionException {
            if (!table.kill(number, owner(), operator())) {
                throw new ExpansionException("no job %" + number);
            }
        }

        /** The typist's UUID; null (the server) for chains no player typed. */
        private String owner() {
            return Sources.typist(source).map(player -> player.getUUID().toString()).orElse(null);
        }

        private boolean operator() {
            return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
        }
    }
}
