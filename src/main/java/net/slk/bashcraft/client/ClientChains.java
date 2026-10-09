package net.slk.bashcraft.client;

import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.network.HelloPayload;

import java.util.Optional;

/** Whether the current server runs BashCraft (it sent a hello), and how it wants input lines read. */
public final class ClientChains {
    private static volatile boolean active;
    private static volatile boolean transparentMode;

    private ClientChains() {}

    public static void accept(int protocol, boolean transparent) {
        active = protocol == HelloPayload.PROTOCOL;
        transparentMode = transparent;
    }

    /** Runs on disconnect: the next server must say hello again. */
    public static void clear() {
        active = false;
        transparentMode = false;
    }

    public static boolean active() {
        return active;
    }

    /** The chain in an input-box line, when the companion is active and the line is one. */
    public static Optional<ChainLayout> layout(String value) {
        return active ? ChainLayout.forInput(value, transparentMode) : Optional.empty();
    }
}
