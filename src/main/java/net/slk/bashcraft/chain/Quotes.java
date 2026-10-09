package net.slk.bashcraft.chain;

/** Quoting rules shared by the tokenizer, the formatter and expansion, so they always agree. */
public final class Quotes {
    private Quotes() {}

    /** A quote opens a quoted span unless it follows a letter or digit (so "don't" stays plain text). */
    public static boolean opensQuote(String input, int i) {
        return i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1));
    }

    /** Index of the matching quote, skipping backslash-escaped characters; -1 if unclosed. */
    public static int closingQuote(String input, int start) {
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

    /** Index of the ')' closing the {@code $(} at {@code dollar}, honouring quotes, escapes and nesting; -1 if unclosed. */
    public static int substitutionEnd(String text, int dollar) {
        int depth = 0;
        for (int j = dollar + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if ((c == '"' || c == '\'') && opensQuote(text, j)) {
                int close = closingQuote(text, j);
                if (close < 0) {
                    return -1;
                }
                j = close;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return j;
            }
        }
        return -1;
    }
}
