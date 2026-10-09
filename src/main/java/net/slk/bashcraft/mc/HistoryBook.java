package net.slk.bashcraft.mc;

import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.exec.History;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each online player's history for this session; forgotten on disconnect (spec C4 §4). */
public final class HistoryBook {
    private static final Map<UUID, History> BOOK = new ConcurrentHashMap<>();

    private HistoryBook() {}

    public static History of(ServerPlayer player) {
        return BOOK.computeIfAbsent(player.getUUID(), uuid -> new History());
    }

    public static void forget(UUID player) {
        BOOK.remove(player);
    }
}
