package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records what ran; commands succeed with result 1 and no output unless told otherwise. */
final class FakeRunner implements CommandRunner {
    final List<String> ran = new ArrayList<>();
    final List<RunOptions> options = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    private final Map<String, ExecResult> results = new HashMap<>();

    FakeRunner on(String command, ExecResult result) {
        results.put(command, result);
        return this;
    }

    @Override
    public ExecResult run(String command, RunOptions options) {
        ran.add(command);
        this.options.add(options);
        return results.getOrDefault(command, new ExecResult(true, 1, List.of()));
    }

    @Override
    public void reportError(String message) {
        errors.add(message);
    }
}
