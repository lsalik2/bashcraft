package net.slk.bashcraft.client;

import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.exec.Builtins;
import net.slk.bashcraft.network.HelloPayload;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Whether the current server runs BashCraft (it sent a hello), and how it wants input lines read. */
public final class ClientChains {
    private static volatile boolean active;
    private static volatile boolean transparentMode;
    private static volatile Set<String> aliasNames = Set.of();

    private ClientChains() {}

    public static void accept(int protocol, boolean transparent) {
        active = protocol == HelloPayload.PROTOCOL;
        transparentMode = transparent;
    }

    /** Runs on disconnect: the next server must say hello again. */
    public static void clear() {
        active = false;
        transparentMode = false;
        aliasNames = Set.of();
    }

    public static void acceptAliases(List<String> names) {
        aliasNames = Set.copyOf(names);
    }

    /** Whether {@code word} calls one of the player's aliases (so it is coloured as a builtin, not parsed by vanilla). */
    public static boolean isAlias(String word) {
        return active && aliasNames.contains(word);
    }

    /** Whether BashCraft runs {@code command}, not vanilla: a builtin or a call of one of the player's aliases. */
    public static boolean isBashcraft(String command) {
        String text = command.strip();
        return Builtins.classify(text).kind() != Builtins.Kind.NONE || isAlias(text.split("\\s", 2)[0]);
    }

    public static boolean active() {
        return active;
    }

    /** The chain in an input-box line, when the companion is active and the line is one. */
    public static Optional<ChainLayout> layout(String value) {
        return active ? ChainLayout.forInput(value, transparentMode) : Optional.empty();
    }
}
