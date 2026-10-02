package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrites transparent chains in a function file into {@code sh} lines before vanilla compiles it
 * (vanilla would reject them at load). Lines that aren't chains are returned untouched.
 */
public final class FunctionLines {
    private FunctionLines() {}

    public static List<String> rewrite(List<String> lines, int maxSegments) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int last = i;
            // Vanilla joins lines ending in '\' before parsing; join the same way so the chain sees the whole command.
            StringBuilder joined = new StringBuilder(line);
            while (endsWithBackslash(joined) && last + 1 < lines.size()) {
                joined.setLength(joined.length() - 1);
                joined.append(lines.get(++last).strip());
            }
            String rewritten = endsWithBackslash(joined) ? null : rewriteLine(joined.toString(), maxSegments);
            if (rewritten == null) {
                out.addAll(lines.subList(i, last + 1));
            } else {
                out.add(rewritten);
                for (int k = i; k < last; k++) {
                    out.add("");
                }
            }
            i = last;
        }
        return out;
    }

    /**
     * The rewritten line, or null to keep the original. Macro lines ({@code $…}) are never chained: their values are
     * substituted after load and would be re-parsed as part of the chain, so a value could add commands.
     */
    private static String rewriteLine(String line, int maxSegments) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("/") || line.startsWith("$")) {
            return null;
        }
        Optional<Chain> chain = TransparentChains.detect(line, maxSegments);
        return chain.map(c -> "sh " + ChainFormatter.toSh(c)).orElse(null);
    }

    private static boolean endsWithBackslash(CharSequence line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\\';
    }
}
