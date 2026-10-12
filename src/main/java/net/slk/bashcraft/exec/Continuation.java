package net.slk.bashcraft.exec;

import java.util.List;

/**
 * What is left of a paused chain (spec C3 §5): the lists it was inside, innermost first. Only {@link ChainExecutor}
 * looks inside; a job table just keeps it.
 */
public final class Continuation {
    final List<ChainExecutor.Frame> frames;

    Continuation(List<ChainExecutor.Frame> frames) {
        this.frames = List.copyOf(frames);
    }

    /** How many lists are waiting to go on (a timer that calls itself last keeps one). */
    public int depth() {
        return frames.size();
    }
}
