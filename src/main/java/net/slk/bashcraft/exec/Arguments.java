package net.slk.bashcraft.exec;

import java.util.Collections;
import java.util.List;

/**
 * An alias call's words ($1…$9, $@) and, per word, whether it came from a value inserted into the call (so in an
 * elevated chain it must be plain) rather than from the call's own text.
 */
public record Arguments(List<String> words, List<Boolean> untrusted) {
    public Arguments {
        words = List.copyOf(words);
        untrusted = List.copyOf(untrusted);
        if (words.size() != untrusted.size()) {
            throw new IllegalArgumentException("one trust flag per word");
        }
    }

    /** Words of unknown origin: all treated as inserted values. */
    public static Arguments of(List<String> words) {
        return new Arguments(words, Collections.nCopies(words.size(), true));
    }

    public boolean anyUntrusted() {
        return untrusted.contains(true);
    }
}
