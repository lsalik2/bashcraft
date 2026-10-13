package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.slk.bashcraft.exec.AliasTable;
import net.slk.bashcraft.exec.Aliases;
import net.slk.bashcraft.exec.ExpansionException;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Aliases in command storage {@code bashcraft:aliases} = {@code {global:{…}, players:{"<uuid>":{…}}}} (spec C4 §2).
 * Personal aliases are visible and definable only in chains their player typed; global ones need operator
 * permission to change. Reads and writes go straight to the live tag, key by key.
 */
public final class StorageAliases implements Aliases {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("bashcraft", "aliases");

    private final MinecraftServer server;
    private final CommandStorage storage;
    private final ServerPlayer typist;
    private final boolean globalWritable;
    private final Predicate<String> isCommand;

    private StorageAliases(MinecraftServer server, ServerPlayer typist, boolean globalWritable) {
        this.server = server;
        this.storage = server.getCommandStorage();
        this.typist = typist;
        this.globalWritable = globalWritable;
        this.isCommand = name -> server.getCommands().getDispatcher().getRoot().getChild(name) != null;
    }

    public static StorageAliases of(CommandSourceStack source) {
        return new StorageAliases(source.getServer(), Sources.typist(source).orElse(null),
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source));
    }

    @Override
    public Optional<String> body(String name) {
        return table(storage.get(ID), false).body(name);
    }

    @Override
    public void define(boolean global, String name, String body) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        try {
            table(root, true).define(global, name, body);
        } finally {
            // Also after a refusal: table() attached the sets to the live tag, and empty ones must not stay.
            save(root);
        }
        AliasSync.changed(server, global ? null : typist);
    }

    @Override
    public void remove(boolean global, String name) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        try {
            table(root, true).remove(global, name);
        } finally {
            save(root);
        }
        AliasSync.changed(server, global ? null : typist);
    }

    @Override
    public List<String> listing() {
        return table(storage.get(ID), false).listing();
    }

    /** Names a player may call: their own aliases and the global ones. */
    public static List<String> names(MinecraftServer server, ServerPlayer player) {
        CompoundTag root = server.getCommandStorage().get(ID);
        TreeSet<String> names = new TreeSet<>(root.getCompoundOrEmpty("global").keySet());
        names.addAll(root.getCompoundOrEmpty("players").getCompoundOrEmpty(player.getUUID().toString()).keySet());
        return AliasTable.callable(names);
    }

    /**
     * The table over the live storage tag. For reads nothing is copied or created; for writes the sets that may change
     * are attached to the tree first, so the per-key edits land in it.
     */
    private AliasTable table(CompoundTag root, boolean forWrite) {
        CompoundTag global = forWrite ? child(root, "global") : root.getCompoundOrEmpty("global");
        TagStrings globalSet = new TagStrings(global);
        if (typist == null) {
            return new AliasTable(globalSet, globalSet, false, globalWritable, isCommand);
        }
        String key = typist.getUUID().toString();
        CompoundTag mine = forWrite ? child(child(root, "players"), key) : root.getCompoundOrEmpty("players").getCompoundOrEmpty(key);
        return new AliasTable(new TagStrings(mine), globalSet, true, globalWritable, isCommand);
    }

    /** Drops empty sets (this player's, the global one), so storage doesn't keep a compound per player forever. */
    private void save(CompoundTag root) {
        root.getCompound("global").filter(CompoundTag::isEmpty).ifPresent(empty -> root.remove("global"));
        if (typist != null) {
            String key = typist.getUUID().toString();
            root.getCompound("players").ifPresent(players -> {
                if (players.getCompoundOrEmpty(key).isEmpty()) {
                    players.remove(key);
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
