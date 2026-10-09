package net.slk.bashcraft.exec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** One player's recent chains for {@code !!}, {@code !prefix} and {@code history} (spec C4 §4). */
public final class History {
    public static final int MAX = 50;

    private final Deque<String> lines = new ArrayDeque<>();

    public synchronized void record(String line) {
        if (line.isBlank()) {
            return;
        }
        lines.addLast(line.strip());
        while (lines.size() > MAX) {
            lines.removeFirst();
        }
    }

    /** {@code !!}: the last line; {@code !PREFIX}: the most recent line starting with PREFIX. */
    public synchronized Optional<String> resolve(String reference) {
        if (reference.equals("!!")) {
            return Optional.ofNullable(lines.peekLast());
        }
        String prefix = reference.substring(1);
        for (Iterator<String> it = lines.descendingIterator(); it.hasNext(); ) {
            String line = it.next();
            if (line.startsWith(prefix)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}
