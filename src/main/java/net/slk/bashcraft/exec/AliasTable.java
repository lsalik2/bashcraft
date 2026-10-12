package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Two alias sets with the scope rules, name rules and limits (spec C4 §2). */
public final class AliasTable implements Aliases {
    public static final int MAX_ALIASES = 64;
    public static final int MAX_BODY = 1024;
    public static final Set<String> BUILTIN_WORDS = Set.of("alias", "unalias", "echo", "unset", "grep", "store", "each", "surface", "history", "sh", "sleep");
    public static final int MAX_CALLABLE = 128;
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

    private final Map<String, String> mine;
    private final Map<String, String> global;
    private final boolean minePreferred;
    private final boolean globalWritable;
    private final Predicate<String> isCommand;

    /**
     * @param minePreferred  the chain was typed by the player whose set {@code mine} is
     * @param globalWritable the source may change global aliases (operator permission)
     * @param isCommand      whether a name is already a registered command
     */
    public AliasTable(Map<String, String> mine, Map<String, String> global, boolean minePreferred, boolean globalWritable,
                      Predicate<String> isCommand) {
        this.mine = mine;
        this.global = global;
        this.minePreferred = minePreferred;
        this.globalWritable = globalWritable;
        this.isCommand = isCommand;
    }

    /** Names a client is told about: valid alias names only (storage can be edited by hand), sorted, at most {@link #MAX_CALLABLE}. */
    public static List<String> callable(java.util.Collection<String> names) {
        return names.stream().filter(name -> NAME.matcher(name).matches()).distinct().sorted().limit(MAX_CALLABLE).toList();
    }

    @Override
    public Optional<String> body(String name) {
        // A command registered after the alias was defined (or a name written to storage by hand) wins over the alias.
        if (BUILTIN_WORDS.contains(name) || isCommand.test(name)) {
            return Optional.empty();
        }
        if (minePreferred && mine.containsKey(name)) {
            return Optional.of(mine.get(name));
        }
        return Optional.ofNullable(global.get(name));
    }

    @Override
    public void define(boolean global, String name, String body) throws ExpansionException {
        if (!NAME.matcher(name).matches()) {
            throw new ExpansionException("alias needs NAME = BODY");
        }
        if (BUILTIN_WORDS.contains(name) || isCommand.test(name)) {
            throw new ExpansionException("alias name " + name + " is already a command");
        }
        if (body.length() > MAX_BODY) {
            throw new ExpansionException("alias body longer than " + MAX_BODY + " characters");
        }
        Map<String, String> target = target(global);
        if (!target.containsKey(name) && target.size() >= MAX_ALIASES) {
            throw new ExpansionException("too many aliases (max " + MAX_ALIASES + ")");
        }
        target.put(name, body);
    }

    @Override
    public void remove(boolean global, String name) throws ExpansionException {
        target(global).remove(name);
    }

    @Override
    public List<String> listing() {
        List<String> lines = new ArrayList<>();
        if (minePreferred) {
            new TreeMap<>(mine).forEach((name, body) -> lines.add(name + " = " + body));
        }
        new TreeMap<>(global).forEach((name, body) -> lines.add("global." + name + " = " + body));
        return lines;
    }

    private Map<String, String> target(boolean globalSet) throws ExpansionException {
        if (globalSet) {
            if (!globalWritable) {
                throw new ExpansionException("global aliases need operator permission");
            }
            return global;
        }
        if (!minePreferred) {
            throw new ExpansionException("personal aliases can only be defined in chains you type");
        }
        return mine;
    }
}
