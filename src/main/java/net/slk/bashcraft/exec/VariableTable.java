package net.slk.bashcraft.exec;

import java.util.Map;
import java.util.Optional;

/**
 * Two variable sets in memory with the scope rules and limits (spec C1 §5). For a source without a player, pass the
 * same map as both sets.
 */
public final class VariableTable implements Variables {
    public static final int MAX_VARIABLES = 256;
    public static final int MAX_LENGTH = 1024;

    private final Map<String, String> own;
    private final Map<String, String> global;

    public VariableTable(Map<String, String> own, Map<String, String> global) {
        this.own = own;
        this.global = global;
    }

    @Override
    public Optional<String> get(Scope scope, String name) {
        return switch (scope) {
            case OWN -> Optional.ofNullable(own.get(name));
            case GLOBAL -> Optional.ofNullable(global.get(name));
            case ANY -> Optional.ofNullable(own.get(name)).or(() -> Optional.ofNullable(global.get(name)));
        };
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        Map<String, String> target = scope == Scope.GLOBAL ? global : own;
        if (value.length() > MAX_LENGTH) {
            throw new ExpansionException("value longer than " + MAX_LENGTH + " characters");
        }
        if (!target.containsKey(name) && target.size() >= MAX_VARIABLES) {
            throw new ExpansionException("too many variables (max " + MAX_VARIABLES + ")");
        }
        target.put(name, value);
    }

    @Override
    public void unset(Scope scope, String name) {
        (scope == Scope.GLOBAL ? global : own).remove(name);
    }
}
