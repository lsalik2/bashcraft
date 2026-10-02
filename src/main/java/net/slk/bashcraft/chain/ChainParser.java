package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into pipelines and operators, bash-style.
 * Quotes ({@code "…"}, or {@code '…'} not preceded by a letter or digit) are never split and are kept verbatim.
 * Outside quotes, {@code \} before {@code & | ; \} yields that character; any other backslash is kept.
 */
public final class ChainParser {
    public enum Mode {
        /** {@code /sh}: every unquoted operator counts. */
        SH,
        /** Typed directly: an operator only counts when the next non-space character is {@code /}. */
        TRANSPARENT
    }

    private static final String ESCAPABLE = "&|;\\";

    private ChainParser() {}

    public static Chain parse(String input, Mode mode, int maxSegments) throws ChainSyntaxException {
        List<Pipeline> pipelines = new ArrayList<>();
        List<Operator> joins = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lastOp = null;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if ((c == '"' || c == '\'') && opensQuote(input, i)) {
                int close = closingQuote(input, i);
                if (close < 0) {
                    throw new ChainSyntaxException("unclosed " + c, i + 1);
                }
                current.append(input, i, close + 1);
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < input.length() && ESCAPABLE.indexOf(input.charAt(i + 1)) >= 0) {
                current.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            String op = operatorAt(input, i);
            if (op == null || (mode == Mode.TRANSPARENT && !nextNonSpaceIsSlash(input, i + op.length()))) {
                current.append(c);
                i++;
                continue;
            }
            String segment = segment(current);
            if (segment.isEmpty()) {
                throw new ChainSyntaxException("unexpected '" + op + "'", i + 1);
            }
            commands.add(segment);
            checkLimit(pipelines, commands, maxSegments, i + 1);
            if (!op.equals("|")) {
                pipelines.add(new Pipeline(commands));
                commands = new ArrayList<>();
                joins.add(switch (op) {
                    case "&&" -> Operator.AND;
                    case "||" -> Operator.OR;
                    default -> Operator.SEQ;
                });
            }
            current.setLength(0);
            lastOp = op;
            i += op.length();
        }
        String segment = segment(current);
        if (segment.isEmpty()) {
            if (lastOp == null) {
                throw new ChainSyntaxException("empty command", 1);
            }
            throw new ChainSyntaxException("missing command after '" + lastOp + "'", input.length() + 1);
        }
        commands.add(segment);
        checkLimit(pipelines, commands, maxSegments, input.length() + 1);
        pipelines.add(new Pipeline(commands));
        return new Chain(pipelines, joins);
    }

    private static String segment(StringBuilder raw) {
        String text = raw.toString().strip();
        return text.startsWith("/") ? text.substring(1).strip() : text;
    }

    private static void checkLimit(List<Pipeline> pipelines, List<String> commands, int max, int column) throws ChainSyntaxException {
        int count = commands.size() + pipelines.stream().mapToInt(p -> p.commands().size()).sum();
        if (count > max) {
            throw new ChainSyntaxException("too many commands (max " + max + ")", column);
        }
    }

    /** A quote opens a quoted span unless it follows a letter or digit (so "don't" stays plain text). */
    static boolean opensQuote(String input, int i) {
        return i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1));
    }

    /** Index of the matching quote, skipping backslash-escaped characters; -1 if unclosed. */
    static int closingQuote(String input, int start) {
        char quote = input.charAt(start);
        for (int j = start + 1; j < input.length(); j++) {
            char c = input.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j;
            }
        }
        return -1;
    }

    private static String operatorAt(String input, int i) {
        if (input.startsWith("&&", i)) return "&&";
        if (input.startsWith("||", i)) return "||";
        char c = input.charAt(i);
        if (c == '|') return "|";
        if (c == ';') return ";";
        return null;
    }

    private static boolean nextNonSpaceIsSlash(String input, int from) {
        int j = from;
        while (j < input.length() && Character.isWhitespace(input.charAt(j))) {
            j++;
        }
        return j < input.length() && input.charAt(j) == '/';
    }
}
