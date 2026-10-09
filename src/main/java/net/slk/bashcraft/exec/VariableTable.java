package net.slk.bashcraft.exec;

import java.util.Map;
import java.util.Optional;

/**
 * Two variable sets in memory with the scope rules and limits (spec C1 §5). {@code mine} is the set {@code my.}
 * names use: the source's player's set, or the global map itself for a source without a player.
 */
public final class VariableTable implements Variables {
    public static final int MAX_VARIABLES = 256;
    public static final int MAX_LENGTH = 1024;
    public static final int MAX_NAME = 64;

    private final Map<String, String> mine;
    private final Map<String, String> global;
    private final boolean preferMine;
    private final boolean globalWritable;

    /** A table where unscoped names use {@code mine} first and everything is writable (tests, trusted callers). */
    public VariableTable(Map<String, String> mine, Map<String, String> global) {
        this(mine, global, true, true);
    }

    /**
     * @param preferMine     unscoped names use {@code mine} (then {@code global}); only when the player typed the
     *                       chain themselves, so a player's own variable never shadows a global one in an elevated chain
     * @param globalWritable whether this source may change the global set (operator permission)
     */
    public VariableTable(Map<String, String> mine, Map<String, String> global, boolean preferMine, boolean globalWritable) {
        this.mine = mine;
        this.global = global;
        this.preferMine = preferMine;
        this.globalWritable = globalWritable;
    }

    @Override
    public Optional<String> get(Scope scope, String name) {
        return switch (scope) {
            case OWN -> Optional.ofNullable(mine.get(name));
            case GLOBAL -> Optional.ofNullable(global.get(name));
            case ANY -> preferMine
                    ? Optional.ofNullable(mine.get(name)).or(() -> Optional.ofNullable(global.get(name)))
                    : Optional.ofNullable(global.get(name));
        };
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        Map<String, String> target = writable(scope);
        if (name.length() > MAX_NAME) {
            throw new ExpansionException("variable name longer than " + MAX_NAME + " characters");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ExpansionException("value longer than " + MAX_LENGTH + " characters");
        }
        if (!target.containsKey(name) && target.size() >= MAX_VARIABLES) {
            throw new ExpansionException("too many variables (max " + MAX_VARIABLES + ")");
        }
        target.put(name, value);
    }

    @Override
    public void unset(Scope scope, String name) throws ExpansionException {
        writable(scope).remove(name);
    }

    /** The set a write goes to; the global set only with operator permission, since elevated chains read it. */
    private Map<String, String> writable(Scope scope) throws ExpansionException {
        Map<String, String> target = switch (scope) {
            case OWN -> mine;
            case GLOBAL -> global;
            case ANY -> preferMine ? mine : global;
        };
        if (target == global && !globalWritable) {
            throw new ExpansionException("global variables need operator permission");
        }
        return target;
    }
}
