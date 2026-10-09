package net.slk.bashcraft.exec;

/** Runs single commands for {@link ChainExecutor}; the Minecraft implementation lives in {@code mc}. */
public interface CommandRunner {
    /** Runs the command to completion and returns its outcome. Errors are shown to the user by the runner. */
    ExecResult run(String command, RunOptions options);

    /** Shows a BashCraft error (one not produced by a command) to the user. */
    void reportError(String message);

    /** Sends {@code text} to the user as ordinary success output (so it can be piped), honouring {@code options}. */
    ExecResult echo(String text, RunOptions options);
}
