package net.slk.bashcraft.exec;

/**
 * What a chain can reach besides the runner: its variables, aliases, and — only in a chain a player typed — that
 * player's history (null otherwise).
 */
public record ShellContext(Variables variables, Aliases aliases, History history) {
    public static ShellContext of(Variables variables) {
        return new ShellContext(variables, Aliases.NONE, null);
    }
}
