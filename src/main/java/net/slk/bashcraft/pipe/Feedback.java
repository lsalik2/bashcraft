package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Minecraft-free view of one chat message.
 *
 * @param key  translation key, or null for plain text
 * @param args translation arguments: nested {@code Feedback} or {@code String}
 * @param text the rendered message
 */
public record Feedback(String key, List<Object> args, String text) {
    public Feedback {
        args = List.copyOf(args);
    }

    public static Feedback plain(String text) {
        return new Feedback(null, List.of(), text);
    }

    public static Feedback translatable(String key, String text, Object... args) {
        List<Object> list = new ArrayList<>();
        for (Object arg : args) {
            list.add(arg instanceof Feedback ? arg : String.valueOf(arg));
        }
        return new Feedback(key, list, text);
    }

    /** Rendered text of argument {@code index}, if present. */
    public Optional<String> argText(int index) {
        if (index < 0 || index >= args.size()) {
            return Optional.empty();
        }
        Object arg = args.get(index);
        return Optional.of(arg instanceof Feedback f ? f.text() : (String) arg);
    }

    /** Depth-first search, this message first, for a message with the given translation key. */
    public Optional<Feedback> find(String wanted) {
        if (wanted.equals(key)) {
            return Optional.of(this);
        }
        for (Object arg : args) {
            if (arg instanceof Feedback f) {
                Optional<Feedback> found = f.find(wanted);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
