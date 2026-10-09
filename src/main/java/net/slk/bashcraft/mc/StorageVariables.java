package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.VariableTable;
import net.slk.bashcraft.exec.Variables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Chain variables in command storage {@code bashcraft:vars} = {@code {global:{…}, players:{"<uuid>":{…}}}} (spec C1 §5).
 * A source whose entity is a player has that player's own set; every other source's own set is the global one.
 */
public final class StorageVariables implements Variables {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("bashcraft", "vars");

    private final CommandStorage storage;
    private final String player;

    private StorageVariables(CommandStorage storage, String player) {
        this.storage = storage;
        this.player = player;
    }

    public static StorageVariables of(CommandSourceStack source) {
        String player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer.getUUID().toString() : null;
        return new StorageVariables(source.getServer().getCommandStorage(), player);
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
    public void unset(Scope scope, String name) {
        Loaded loaded = load();
        loaded.table().unset(scope, name);
        save(loaded);
    }

    private record Loaded(CompoundTag root, Map<String, String> own, Map<String, String> global, VariableTable table) {}

    private Loaded load() {
        CompoundTag root = storage.get(ID).copy();
        Map<String, String> global = strings(root.getCompoundOrEmpty("global"));
        Map<String, String> own = player == null ? global : strings(root.getCompoundOrEmpty("players").getCompoundOrEmpty(player));
        return new Loaded(root, own, global, new VariableTable(own, global));
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
