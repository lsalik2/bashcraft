package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds a piped value into the next command. Only {x} {y} {z} {value} {result} are placeholders;
 * other braces (NBT, JSON) are untouched. With no placeholders the value is appended.
 */
public final class Placeholders {
    private static final Pattern TOKEN = Pattern.compile("\\\\?\\{(x|y|z|value|result)}");

    private Placeholders() {}

    public static String inject(String command, PipeValue value, int previousResult) throws PipeException {
        Matcher m = TOKEN.matcher(command);
        StringBuilder out = new StringBuilder();
        boolean substituted = false;
        while (m.find()) {
            String replacement;
            if (m.group().startsWith("\\")) {
                replacement = m.group().substring(1);
            } else {
                substituted = true;
                String name = m.group(1);
                replacement = name.equals("result")
                        ? Integer.toString(previousResult)
                        : value.placeholder(name).orElseThrow(() -> new PipeException("{" + name + "} is not available from " + value.describe()));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String result = out.toString();
        return substituted ? result : result.stripTrailing() + " " + value.asArgument();
    }

    /** {@code command} with each placeholder replaced by zeros of the same length, so a client-side parse accepts it. */
    public static String mask(String command) {
        StringBuilder out = new StringBuilder(command);
        for (int[] range : ranges(command)) {
            for (int i = range[0]; i < range[1]; i++) {
                out.setCharAt(i, '0');
            }
        }
        return out.toString();
    }

    /** {@code {start, end}} of each placeholder (escaped ones excluded), in order. */
    public static List<int[]> ranges(String command) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = TOKEN.matcher(command);
        while (m.find()) {
            if (!m.group().startsWith("\\")) {
                ranges.add(new int[] {m.start(), m.end()});
            }
        }
        return ranges;
    }
}
