package net.slk.bashcraft.chain;

import java.util.Optional;

/** Decides whether a line typed without {@code /sh} is a chain (spec §3, transparent mode). */
public final class TransparentChains {
    private TransparentChains() {}

    /**
     * The chain in {@code line} if it has more than one command. Empty for plain vanilla lines, for {@code sh} lines
     * (the command handles those) and for lines that don't parse as a chain — those go to vanilla untouched.
     */
    public static Optional<Chain> detect(String line, int maxSegments) {
        String command = line.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.equals("sh") || command.startsWith("sh ")) {
            return Optional.empty();
        }
        try {
            Chain chain = ChainParser.parse(line, ChainParser.Mode.TRANSPARENT, maxSegments);
            return chain.segmentCount() > 1 ? Optional.of(chain) : Optional.empty();
        } catch (ChainSyntaxException e) {
            return Optional.empty();
        }
    }
}
