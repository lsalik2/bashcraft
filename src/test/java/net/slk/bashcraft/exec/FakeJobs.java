package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.List;

/** Keeps what a chain queues, as the job table would; can refuse like a full table or a delay limit. */
final class FakeJobs implements Jobs {
    record Queued(Continuation continuation, int delay, String text) {}

    final List<Queued> queued = new ArrayList<>();
    final List<Queued> requeued = new ArrayList<>();
    boolean full;
    int maxDelay = Integer.MAX_VALUE;

    @Override
    public void queue(Continuation continuation, int delay, String text) throws ExpansionException {
        check(delay);
        if (full) {
            throw new ExpansionException("too many waiting chains (max 1)");
        }
        queued.add(new Queued(continuation, delay, text));
    }

    @Override
    public void requeue(Continuation continuation, int delay) throws ExpansionException {
        check(delay);
        requeued.add(new Queued(continuation, delay, null));
    }

    private void check(int delay) throws ExpansionException {
        if (delay > maxDelay) {
            throw new ExpansionException("sleep is limited to " + maxDelay + " ticks");
        }
    }
}
