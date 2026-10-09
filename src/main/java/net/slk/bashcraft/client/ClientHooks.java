package net.slk.bashcraft.client;

import net.slk.bashcraft.Bashcraft;

import java.util.concurrent.atomic.AtomicBoolean;

/** Client hooks fall back to vanilla on any error; the first one is logged so it can be reported. */
public final class ClientHooks {
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ClientHooks() {}

    public static void failed(RuntimeException e) {
        if (LOGGED.compareAndSet(false, true)) {
            Bashcraft.LOGGER.error("BashCraft: chat highlighting failed, falling back to vanilla for this line", e);
        }
    }
}
