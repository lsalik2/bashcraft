package net.slk.bashcraft.exec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code sleep}'s time (spec C3 §2), read like {@code /schedule}'s: ticks, or a {@code t}, {@code s} (20) or {@code d} (24000) unit. */
public final class SleepTime {
    private static final Pattern TIME = Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)([tsd]?)");

    private SleepTime() {}

    /** Whole ticks, rounded to the nearest; at least 1. A time beyond any limit comes back as Integer.MAX_VALUE. */
    public static int ticks(String text) throws ExpansionException {
        Matcher m = TIME.matcher(text.strip());
        if (!m.matches()) {
            throw new ExpansionException("sleep needs a time like 20, 20t, 1s or 1d");
        }
        int unit = switch (m.group(2)) {
            case "s" -> 20;
            case "d" -> 24000;
            default -> 1;
        };
        double exact = Double.parseDouble(m.group(1)) * unit;
        if (exact >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long ticks = Math.round(exact);
        if (ticks < 1) {
            throw new ExpansionException("sleep needs at least 1 tick");
        }
        return (int) ticks;
    }
}
