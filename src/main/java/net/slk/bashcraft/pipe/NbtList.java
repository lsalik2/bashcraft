package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the elements of a pretty-printed SNBT list ({@code ["a", "b"]}, {@code [1b, 2b]}, {@code [I; 1, 2]}). */
public final class NbtList {
    private NbtList() {}

    /** The elements as item text (strings unquoted, number suffixes dropped, nested values as written), or empty if not a list. */
    public static Optional<List<String>> elements(String text) {
        String trimmed = text.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return Optional.empty();
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).strip();
        if (inner.matches("[BIL];.*")) {
            inner = inner.substring(2).strip();
        }
        List<String> elements = new ArrayList<>();
        if (inner.isEmpty()) {
            return Optional.of(elements);
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                elements.add(element(inner.substring(start, i)));
                start = i + 1;
            }
        }
        elements.add(element(inner.substring(start)));
        return Optional.of(elements);
    }

    private static String element(String raw) {
        String text = raw.strip();
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0)) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < text.length() - 1; i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length() - 1) {
                    out.append(text.charAt(++i));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        return text.matches("-?\\d+(\\.\\d+)?(?:[eE]-?\\d+)?[bBsSlLfFdD]") ? text.substring(0, text.length() - 1) : text;
    }
}
