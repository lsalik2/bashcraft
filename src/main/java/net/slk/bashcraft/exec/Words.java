package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;

import java.util.ArrayList;
import java.util.List;

/** Splits alias call arguments into words: whitespace separates, quoted spans stay whole (one quote pair removed). */
public final class Words {
    private Words() {}

    /** A word and where it sits in the text it was split from, {@code [start, end)}. */
    public record Word(String text, int start, int end) {}

    public static List<String> split(String text) {
        return spans(text).stream().map(Word::text).toList();
    }

    public static List<Word> spans(String text) {
        List<Word> words = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (start >= 0) {
                    words.add(new Word(Builtins.unquote(text.substring(start, i)), start, i));
                    start = -1;
                }
                i++;
                continue;
            }
            if (start < 0) {
                start = i;
            }
            if ((c == '"' || c == '\'') && Quotes.opensQuote(text, i)) {
                int close = Quotes.closingQuote(text, i);
                i = close < 0 ? text.length() : close + 1;
                continue;
            }
            i++;
        }
        if (start >= 0) {
            words.add(new Word(Builtins.unquote(text.substring(start)), start, text.length()));
        }
        return words;
    }
}
