package net.slk.bashcraft.chain;

import java.util.List;

/** Commands joined by {@code |}; each command's output feeds the next. */
public record Pipeline(List<String> commands) {
    public Pipeline {
        commands = List.copyOf(commands);
    }
}
