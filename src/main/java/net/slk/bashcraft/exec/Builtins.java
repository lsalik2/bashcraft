package net.slk.bashcraft.exec;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell builtins recognised inside chains (spec C1 §4), on the command's text before expansion. */
public final class Builtins {
    public enum Kind { ASSIGN, UNSET, ECHO, GREP, STORE, EACH, SURFACE, ALIAS, UNALIAS, HISTORY, NONE }

    /** For ASSIGN: name and raw value; UNSET: name; ECHO, GREP, STORE, EACH, SURFACE: the text after the word; NONE: the whole command. */
    public record Call(Kind kind, String name, String argument) {}

    private static final Pattern ASSIGN = Pattern.compile("((?:my\\.|global\\.)?[A-Za-z_][A-Za-z0-9_]*)=(.*)", Pattern.DOTALL);

    private Builtins() {}

    public static Call classify(String command) {
        Matcher m = ASSIGN.matcher(command);
        if (m.matches()) {
            return new Call(Kind.ASSIGN, m.group(1), m.group(2));
        }
        if (command.equals("unset") || command.startsWith("unset ")) {
            return new Call(Kind.UNSET, command.substring(5).strip(), "");
        }
        if (command.equals("echo") || command.startsWith("echo ")) {
            return new Call(Kind.ECHO, "", command.length() > 5 ? command.substring(5) : "");
        }
        for (Kind kind : List.of(Kind.GREP, Kind.STORE, Kind.EACH, Kind.SURFACE, Kind.ALIAS, Kind.UNALIAS, Kind.HISTORY)) {
            String word = kind.name().toLowerCase(java.util.Locale.ROOT);
            if (command.equals(word) || command.startsWith(word + " ")) {
                return new Call(kind, "", command.length() > word.length() + 1 ? command.substring(word.length() + 1) : "");
            }
        }
        return new Call(Kind.NONE, "", command);
    }

    /** Removes one pair of matching surrounding quotes. */
    public static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
