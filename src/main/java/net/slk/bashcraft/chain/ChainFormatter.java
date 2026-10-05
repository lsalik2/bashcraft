package net.slk.bashcraft.chain;

/** Writes a chain back as {@code /sh} syntax: {@code NodeParser.parse(toSh(n), SH)} equals {@code n}. */
public final class ChainFormatter {
    private ChainFormatter() {}

    public static String toSh(Node node) {
        return switch (node) {
            case Node.Command command -> command(command.text());
            case Node.Pipeline pipeline -> String.join(" | ", pipeline.stages().stream().map(ChainFormatter::toSh).toList());
            case Node.Sequence sequence -> {
                StringBuilder out = new StringBuilder(toSh(sequence.items().getFirst()));
                for (int i = 1; i < sequence.items().size(); i++) {
                    out.append(switch (sequence.joins().get(i - 1)) {
                        case AND -> " && ";
                        case OR -> " || ";
                        case SEQ -> " ; ";
                    }).append(toSh(sequence.items().get(i)));
                }
                yield out.toString();
            }
            case Node.Group group -> "(" + toSh(group.body()) + ")";
        };
    }

    /** Escapes text; text that itself starts with '/' gets one more, since the parser strips one. */
    private static String command(String text) {
        String escaped = escape(text);
        return text.startsWith("/") ? "/" + escaped : escaped;
    }

    /** Backslash-escapes {@code & | ; \ ( )} outside quoted spans, using the tokenizer's own quote rule. */
    private static String escape(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && Tokenizer.opensQuote(command, i)) {
                int close = Tokenizer.closingQuote(command, i);
                int end = close < 0 ? command.length() : close + 1;
                out.append(command, i, end);
                i = end;
                continue;
            }
            if ("&|;\\()".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
