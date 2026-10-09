package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.PipeValue;

import java.util.List;

/** Writes a pipe value as SNBT for {@code store storage}; text is always quoted by us, so it can't break out. */
public final class Snbt {
    private static final String INTEGER = "-?\\d+";
    private static final String NUMBER = "-?\\d+(\\.\\d+)?";

    private Snbt() {}

    public static String of(PipeValue value) {
        return switch (value) {
            case PipeValue.Numeric numeric -> number(numeric.text(), false);
            case PipeValue.Items items -> list(items.items());
            default -> string(value.asArgument());
        };
    }

    private static String list(List<String> items) {
        boolean numbers = !items.isEmpty() && items.stream().allMatch(item -> item.matches(NUMBER));
        boolean doubles = numbers && items.stream().anyMatch(item -> !fitsInt(item));
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(numbers ? number(items.get(i), doubles) : string(items.get(i)));
        }
        return out.append(']').toString();
    }

    private static String number(String text, boolean forceDouble) {
        if (!forceDouble && fitsInt(text)) {
            return text;
        }
        return Double.toString(Double.parseDouble(text)) + "d";
    }

    private static boolean fitsInt(String text) {
        if (!text.matches(INTEGER)) {
            return false;
        }
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String string(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
