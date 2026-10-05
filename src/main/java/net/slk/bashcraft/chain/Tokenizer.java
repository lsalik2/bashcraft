package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into text runs and operators (spec §3). Knows quoting and escapes, where {@code (} opens a
 * group (at a command position, optionally after {@code /}) and when {@code )} closes one (no unmatched {@code (} in
 * the current command's text, and a group is open).
 */
final class Tokenizer {
    enum Kind { TEXT, AND, OR, PIPE, SEQ, OPEN, CLOSE, END }

    /** @param column 1-based column of the token (for text: of its first non-space character) */
    record Token(Kind kind, String text, int column) {}

    private static final String ESCAPABLE = "&|;\\()";

    private final String input;
    private final NodeParser.Mode mode;
    private final List<Token> tokens = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private int textColumn = -1;
    private int textParens;
    private int groups;
    private boolean usedGroups;
    private boolean commandStart = true;

    private Tokenizer(String input, NodeParser.Mode mode) {
        this.input = input;
        this.mode = mode;
    }

    static List<Token> tokenize(String input, NodeParser.Mode mode) throws ChainSyntaxException {
        return new Tokenizer(input, mode).run();
    }

    private List<Token> run() throws ChainSyntaxException {
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (commandStart) {
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int open = groupOpenAt(i);
                if (open >= 0) {
                    emit(Kind.OPEN, "(", open);
                    i = open + 1;
                    continue;
                }
                commandStart = false;
            }
            if ((c == '"' || c == '\'') && opensQuote(input, i)) {
                int close = closingQuote(input, i);
                if (close < 0) {
                    throw new ChainSyntaxException("unclosed " + c, i + 1);
                }
                appendText(input.substring(i, close + 1), i);
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < input.length() && ESCAPABLE.indexOf(input.charAt(i + 1)) >= 0) {
                appendText(String.valueOf(input.charAt(i + 1)), i);
                i += 2;
                continue;
            }
            if (c == '(') {
                textParens++;
                appendText("(", i);
                i++;
                continue;
            }
            if (c == ')') {
                if (textParens > 0) {
                    textParens--;
                    appendText(")", i);
                } else if (groups > 0) {
                    emit(Kind.CLOSE, ")", i);
                } else if (usedGroups) {
                    // Once the line uses groups, a stray ')' most likely closed a group early (a smiley inside it).
                    throw new ChainSyntaxException("unmatched ')', write \\) for a literal one", i + 1);
                } else {
                    appendText(")", i);
                }
                i++;
                continue;
            }
            Kind op = operatorAt(i);
            if (op != null) {
                int length = op == Kind.AND || op == Kind.OR ? 2 : 1;
                if (mode == NodeParser.Mode.SH || nextIsCommandStart(i + length)) {
                    emit(op, input.substring(i, i + length), i);
                    i += length;
                    continue;
                }
            }
            appendText(String.valueOf(c), i);
            i++;
        }
        flushText();
        tokens.add(new Token(Kind.END, "", input.length() + 1));
        return tokens;
    }

    /** Index of the '(' that opens a group at this command position ({@code (} or {@code /(}), else -1. */
    private int groupOpenAt(int i) {
        if (input.charAt(i) == '(') {
            return i;
        }
        if (input.charAt(i) == '/' && i + 1 < input.length() && input.charAt(i + 1) == '(') {
            return i + 1;
        }
        return -1;
    }

    private Kind operatorAt(int i) {
        if (input.startsWith("&&", i)) return Kind.AND;
        if (input.startsWith("||", i)) return Kind.OR;
        char c = input.charAt(i);
        if (c == '|') return Kind.PIPE;
        if (c == ';') return Kind.SEQ;
        return null;
    }

    /**
     * Transparent mode: an operator counts only when a slash command follows, directly or as the first thing inside
     * one or more groups ({@code && /b}, {@code && (/b …)}, {@code && /(…)}). So {@code say hi && (hug)} stays text.
     */
    private boolean nextIsCommandStart(int from) {
        int j = from;
        while (j < input.length() && (Character.isWhitespace(input.charAt(j)) || input.charAt(j) == '(')) {
            j++;
        }
        return j < input.length() && input.charAt(j) == '/';
    }

    private void appendText(String s, int index) {
        if (textColumn < 0 && !s.isBlank()) {
            textColumn = index + 1;
        }
        text.append(s);
    }

    private void emit(Kind kind, String tokenText, int index) {
        flushText();
        tokens.add(new Token(kind, tokenText, index + 1));
        textParens = 0;
        if (kind == Kind.OPEN) {
            groups++;
            usedGroups = true;
        } else if (kind == Kind.CLOSE) {
            groups--;
        }
        commandStart = kind != Kind.CLOSE;
    }

    private void flushText() {
        if (!text.toString().isBlank()) {
            tokens.add(new Token(Kind.TEXT, text.toString(), textColumn));
        }
        text.setLength(0);
        textColumn = -1;
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
}
