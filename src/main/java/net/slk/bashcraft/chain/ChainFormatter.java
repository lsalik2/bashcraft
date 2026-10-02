package net.slk.bashcraft.chain;

import java.util.List;

/** Writes a chain back as {@code /sh} syntax, escaping operator characters that are part of a command's text. */
public final class ChainFormatter {
    private ChainFormatter() {}

    public static String toSh(Chain chain) {
        StringBuilder out = new StringBuilder();
        for (int p = 0; p < chain.pipelines().size(); p++) {
            if (p > 0) {
                out.append(switch (chain.joins().get(p - 1)) {
                    case AND -> " && ";
                    case OR -> " || ";
                    case SEQ -> " ; ";
                });
            }
            List<String> commands = chain.pipelines().get(p).commands();
            for (int i = 0; i < commands.size(); i++) {
                if (i > 0) {
                    out.append(" | ");
                }
                out.append(escape(commands.get(i)));
            }
        }
        return out.toString();
    }

    /** Backslash-escapes {@code & | ; \} outside quoted spans, using the parser's own quote rule. */
    private static String escape(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && ChainParser.opensQuote(command, i)) {
                int close = ChainParser.closingQuote(command, i);
                int end = close < 0 ? command.length() : close + 1;
                out.append(command, i, end);
                i = end;
                continue;
            }
            if ("&|;\\".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
