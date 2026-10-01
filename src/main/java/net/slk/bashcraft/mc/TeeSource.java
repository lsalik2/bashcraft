package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.exec.RunOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The CommandSource one chain segment runs with: forwards output to the invoker's source and
 * records success messages. {@code CommandSourceStackMixin} feeds {@link #capture} and numbers failures.
 */
public final class TeeSource implements CommandSource {
    private final CommandSource target;
    private final RunOptions options;
    private final List<Component> captured = new ArrayList<>();

    public TeeSource(CommandSource target, RunOptions options) {
        this.target = target;
        this.options = options;
    }

    public CommandSource target() {
        return target;
    }

    public RunOptions options() {
        return options;
    }

    public List<Component> captured() {
        return captured;
    }

    public void capture(Component message) {
        captured.add(message);
    }

    @Override
    public void sendSystemMessage(Component message) {
        target.sendSystemMessage(message);
    }

    /** Piped segments' success output is hidden from the invoker (it still reaches the admin broadcast and log). */
    @Override
    public boolean acceptsSuccess() {
        return !options.swallowSuccess() && target.acceptsSuccess();
    }

    @Override
    public boolean acceptsFailure() {
        return target.acceptsFailure();
    }

    @Override
    public boolean shouldInformAdmins() {
        return target.shouldInformAdmins();
    }

    @Override
    public boolean alwaysAccepts() {
        return target.alwaysAccepts();
    }
}
