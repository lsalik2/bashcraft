package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.VariableTable;
import net.slk.bashcraft.exec.Variables;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;

import java.util.Map;
import java.util.Optional;

/**
 * Chain variables in command storage {@code bashcraft:vars} = {@code {global:{…}, players:{"<uuid>":{…}}}} (spec C1 §5).
 * {@code my.} names use the source entity's player's set (the global set when there is no player). Unscoped names use
 * the player's set first only when that player typed the chain; elevated chains ({@code execute as}, functions,
 * command blocks) read the global set, so a player can't shadow what they rely on. Only sources with operator
 * permission may write the global set, since elevated chains run commands built from it.
 */
public final class StorageVariables implements Variables {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("bashcraft", "vars");

    private final CommandStorage storage;
    private final String player;
    private final boolean preferMine;
    private final boolean globalWritable;

    private StorageVariables(CommandStorage storage, String player, boolean preferMine, boolean globalWritable) {
        this.storage = storage;
        this.player = player;
        this.preferMine = preferMine;
        this.globalWritable = globalWritable;
    }

    public static StorageVariables of(CommandSourceStack source) {
        ServerPlayer entityPlayer = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        return new StorageVariables(source.getServer().getCommandStorage(),
                entityPlayer == null ? null : entityPlayer.getUUID().toString(),
                entityPlayer != null && typedBy(source, entityPlayer),
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source));
    }

    /** The chain came from this player's own command (through any nesting), not a function or another source. */
    private static boolean typedBy(CommandSourceStack source, ServerPlayer player) {
        CommandSource origin = ((CommandSourceStackAccessor) source).bashcraft$source();
        while (origin instanceof TeeSource tee) {
            origin = tee.target();
        }
        return origin == player.commandSource() && !source.isSilent();
    }

    @Override
    public Optional<String> get(Scope scope, String name) {
        return table(storage.get(ID), false).get(scope, name);
    }

    @Override
    public boolean elevated() {
        return !preferMine;
    }

    @Override
    public boolean trusted(Scope scope, String name) {
        return table(storage.get(ID), false).trusted(scope, name);
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        table(root, true).set(scope, name, value);
        save(root);
    }

    @Override
    public void unset(Scope scope, String name) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        table(root, true).unset(scope, name);
        save(root);
    }

    /**
     * The table over the live storage tag. For reads nothing is copied or created; for writes the sets that may change
     * are attached to the tree first, so the per-key edits land in it.
     */
    private VariableTable table(CompoundTag root, boolean forWrite) {
        CompoundTag global = forWrite ? child(root, "global") : root.getCompoundOrEmpty("global");
        Map<String, String> globalSet = new TagStrings(global);
        if (player == null) {
            return new VariableTable(globalSet, globalSet, preferMine, globalWritable);
        }
        CompoundTag mine = forWrite ? child(child(root, "players"), player) : root.getCompoundOrEmpty("players").getCompoundOrEmpty(player);
        return new VariableTable(new TagStrings(mine), globalSet, preferMine, globalWritable);
    }

    /** Drops this player's set once it is empty, so storage doesn't keep a compound per player forever. */
    private void save(CompoundTag root) {
        if (player != null) {
            root.getCompound("players").ifPresent(players -> {
                if (players.getCompoundOrEmpty(player).isEmpty()) {
                    players.remove(player);
                }
                if (players.isEmpty()) {
                    root.remove("players");
                }
            });
        }
        storage.set(ID, root);
    }

    private static CompoundTag child(CompoundTag parent, String key) {
        return parent.getCompound(key).orElseGet(() -> {
            CompoundTag created = new CompoundTag();
            parent.put(key, created);
            return created;
        });
    }
}
