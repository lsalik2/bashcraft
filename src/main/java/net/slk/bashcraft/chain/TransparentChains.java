package net.slk.bashcraft.chain;

import java.util.Optional;

/** Decides whether a line typed without {@code /sh} is a chain (spec §3, transparent mode). */
public final class TransparentChains {
    private TransparentChains() {}

    /**
     * The chain in {@code line} if it has more than one command or any group. Empty for plain vanilla lines, for
     * {@code sh} lines (the command handles those) and for lines that don't parse — those go to vanilla untouched.
     */
    public static Optional<Node> detect(String line, int maxSegments) {
        String command = line.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.equals("sh") || command.startsWith("sh ")) {
            return Optional.empty();
        }
        try {
            Node node = NodeParser.parse(line, NodeParser.Mode.TRANSPARENT, maxSegments);
            return node.commandCount() > 1 || node.containsGroup() ? Optional.of(node) : Optional.empty();
        } catch (ChainSyntaxException e) {
            return Optional.empty();
        }
    }
}
