package net.slk.bashcraft.exec;

import java.util.Optional;

/** Limits how deeply chains nest on this thread (a function running {@code sh} that calls itself, for example). */
public final class DepthGuard {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private DepthGuard() {}

    /** Enters one more level, or returns empty if {@code maxDepth} levels are already active. */
    public static Optional<Scope> enter(int maxDepth) {
        int[] depth = DEPTH.get();
        if (depth[0] >= maxDepth) {
            return Optional.empty();
        }
        depth[0]++;
        return Optional.of(new Scope());
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                DEPTH.get()[0]--;
            }
        }
    }
}
