package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.exec.RunOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The CommandSource one chain segment runs with: forwards output to the invoker's source and
 * records success messages. {@code CommandSourceStackMixin} feeds {@link #capture} and applies {@link RunOptions}.
 */
public final class TeeSource implements CommandSource {
    private final CommandSource target;
    private final RunOptions options;
    private final List<Component> captured = new ArrayList<>();
    private boolean failed;

    public TeeSource(CommandSource target, RunOptions options) {
        this.target = target;
        this.options = options;
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

    /** Whether the segment sent a failure message (shown or not). */
    public boolean failed() {
        return failed;
    }

    public void markFailed() {
        failed = true;
    }

    @Override
    public void sendSystemMessage(Component message) {
        target.sendSystemMessage(message);
    }

    @Override
    public boolean acceptsSuccess() {
        return target.acceptsSuccess();
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
