package net.slk.bashcraft.exec;

/** Where a chain's waiting parts go (spec C3 §4–5); the Minecraft side keeps them in a {@link JobTable}. */
public interface Jobs {
    /** A part of the chain paused for {@code delay} ticks: a new job, listed as {@code text}. Throws why it can't wait. */
    void queue(Continuation continuation, int delay, String text) throws ExpansionException;

    /** The job being resumed paused again: it keeps its number. */
    void requeue(Continuation continuation, int delay) throws ExpansionException;

    /** {@code %N  <ticks left>t  <text>} lines for the jobs this chain's source may see. */
    java.util.List<String> listing();

    /** Cancels job {@code number}; throws {@code no job %N} when it doesn't exist or isn't this source's to cancel. */
    void kill(int number) throws ExpansionException;

    /** No job queue (tests, callers without a server): sleep can't pause. */
    Jobs NONE = new Jobs() {
        @Override
        public void queue(Continuation continuation, int delay, String text) throws ExpansionException {
            throw new ExpansionException("sleep is not available here");
        }

        @Override
        public void requeue(Continuation continuation, int delay) throws ExpansionException {
            throw new ExpansionException("sleep is not available here");
        }

        @Override
        public java.util.List<String> listing() {
            return java.util.List.of();
        }

        @Override
        public void kill(int number) throws ExpansionException {
            throw new ExpansionException("no job %" + number);
        }
    };
}
