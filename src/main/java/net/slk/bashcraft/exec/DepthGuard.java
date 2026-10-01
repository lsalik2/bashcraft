package net.slk.bashcraft.exec;

import java.util.Optional;

/**
 * Limits chains nested on this thread (a function running {@code sh} that calls itself, for example):
 * how deep they nest, and how many commands they run in total under the outermost chain.
 */
public final class DepthGuard {
    private static final class State {
        int depth;
        int spent;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private DepthGuard() {}

    /** Enters one more level, or returns empty if {@code maxDepth} levels are already active. */
    public static Optional<Scope> enter(int maxDepth) {
        State state = STATE.get();
        if (state.depth >= maxDepth) {
            return Optional.empty();
        }
        state.depth++;
        return Optional.of(new Scope());
    }

    /**
     * Counts one command toward the budget shared by every chain under the outermost one; false once {@code max}
     * commands have run. Outside any chain there is no budget. The count resets when the outermost chain ends.
     */
    public static boolean spend(int max) {
        State state = STATE.get();
        if (state.depth == 0) {
            return true;
        }
        if (state.spent >= max) {
            return false;
        }
        state.spent++;
        return true;
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                State state = STATE.get();
                if (--state.depth == 0) {
                    state.spent = 0;
                }
            }
        }
    }
}
