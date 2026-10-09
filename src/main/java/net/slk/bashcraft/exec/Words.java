package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;

import java.util.ArrayList;
import java.util.List;

/** Splits alias call arguments into words: whitespace separates, quoted spans stay whole (one quote pair removed). */
public final class Words {
    private Words() {}

    public static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(Builtins.unquote(word.toString()));
                    word.setLength(0);
                    inWord = false;
                }
                i++;
                continue;
            }
            inWord = true;
            if ((c == '"' || c == '\'') && Quotes.opensQuote(text, i)) {
                int close = Quotes.closingQuote(text, i);
                int end = close < 0 ? text.length() : close + 1;
                word.append(text, i, end);
                i = end;
                continue;
            }
            word.append(c);
            i++;
        }
        if (inWord) {
            words.add(Builtins.unquote(word.toString()));
        }
        return words;
    }
}
