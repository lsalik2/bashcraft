package net.slk.bashcraft.exec;

/**
 * What a chain can reach besides the runner: its variables and aliases, the history of the player who typed it (null
 * in any other chain), and where its waiting parts go.
 */
public record ShellContext(Variables variables, Aliases aliases, History history, Jobs jobs) {
    public ShellContext(Variables variables, Aliases aliases, History history) {
        this(variables, aliases, history, Jobs.NONE);
    }

    public static ShellContext of(Variables variables) {
        return new ShellContext(variables, Aliases.NONE, null);
    }
}
