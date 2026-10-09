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

import java.util.LinkedHashMap;
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
        return load().table().get(scope, name);
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        Loaded loaded = load();
        loaded.table().set(scope, name, value);
        save(loaded);
    }

    @Override
    public void unset(Scope scope, String name) throws ExpansionException {
        Loaded loaded = load();
        loaded.table().unset(scope, name);
        save(loaded);
    }

    private record Loaded(CompoundTag root, Map<String, String> own, Map<String, String> global, VariableTable table) {}

    private Loaded load() {
        CompoundTag root = storage.get(ID).copy();
        Map<String, String> global = strings(root.getCompoundOrEmpty("global"));
        Map<String, String> own = player == null ? global : strings(root.getCompoundOrEmpty("players").getCompoundOrEmpty(player));
        return new Loaded(root, own, global, new VariableTable(own, global, preferMine, globalWritable));
    }

    private void save(Loaded loaded) {
        loaded.root().put("global", tag(loaded.global()));
        if (player != null) {
            CompoundTag players = loaded.root().getCompoundOrEmpty("players");
            players.put(player, tag(loaded.own()));
            loaded.root().put("players", players);
        }
        storage.set(ID, loaded.root());
    }

    private static Map<String, String> strings(CompoundTag tag) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : tag.keySet()) {
            tag.getString(key).ifPresent(value -> map.put(key, value));
        }
        return map;
    }

    private static CompoundTag tag(Map<String, String> map) {
        CompoundTag tag = new CompoundTag();
        map.forEach(tag::putString);
        return tag;
    }
}
