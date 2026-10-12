package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Waiting chains (spec C3 §4–6): numbered from the smallest free number, owned by a player (a key such as a UUID) or
 * the server (null), bounded in delay and number, woken by a tick clock. A job stays in the table while it runs, so it
 * keeps its number and its slot. Server thread only.
 */
public final class JobTable<T> {
    /** A job that is due: resume it, then {@link #requeue} or {@link #finish} it. */
    public record Due<T>(int number, T payload) {}

    private static final class Job<T> {
        final int number;
        final String owner;
        final String ownerName;
        final String text;
        long wakeTick;
        T payload;
        boolean running;
        boolean cancelled;

        Job(int number, String owner, String ownerName, String text, long wakeTick, T payload) {
            this.number = number;
            this.owner = owner;
            this.ownerName = ownerName;
            this.text = text;
            this.wakeTick = wakeTick;
            this.payload = payload;
        }
    }

    private final TreeMap<Integer, Job<T>> jobs = new TreeMap<>();
    /** Commands each player's jobs have run in the current second of the clock. */
    private final Map<String, Integer> budgets = new HashMap<>();
    private final int maxJobs;
    private final int maxPerOwner;
    private final int maxDelay;
    private long now;

    public JobTable(int maxJobs, int maxPerOwner, int maxDelay) {
        this.maxJobs = maxJobs;
        this.maxPerOwner = maxPerOwner;
        this.maxDelay = maxDelay;
    }

    /** Queues a job that wakes after {@code delay} ticks; the exception says why it can't wait. */
    public int add(String owner, String ownerName, String text, int delay, T payload) throws ExpansionException {
        checkDelay(delay);
        if (jobs.size() >= maxJobs) {
            throw new ExpansionException("too many waiting chains (max " + maxJobs + ")");
        }
        if (owner != null && jobs.values().stream().filter(job -> owner.equals(job.owner)).count() >= maxPerOwner) {
            throw new ExpansionException("too many waiting chains (max " + maxPerOwner + ")");
        }
        int number = 1;
        while (jobs.containsKey(number)) {
            number++;
        }
        jobs.put(number, new Job<>(number, owner, ownerName, text, now + delay, payload));
        return number;
    }

    /** Advances the clock by one tick; every 20 ticks starts a new second for the players' budgets. */
    public void tick() {
        now++;
        if (now % 20 == 0) {
            budgets.clear();
        }
    }

    /**
     * Commands {@code owner}'s jobs have run so far this second: a player's waiting chains share one budget per second
     * (spec C3 §6). Always 0 for the server (null), whose jobs get a fresh budget each wake.
     */
    public int budgetSpent(String owner) {
        return owner == null ? 0 : budgets.getOrDefault(owner, 0);
    }

    /** Records how many commands {@code owner}'s jobs have run so far this second. */
    public void setBudgetSpent(String owner, int commands) {
        if (owner != null) {
            budgets.put(owner, commands);
        }
    }

    /** The next job due now (by wake tick, then number), marked running; empty when none is left this tick. */
    public Optional<Due<T>> nextDue() {
        return jobs.values().stream()
                .filter(job -> !job.running && job.wakeTick <= now)
                .min(Comparator.comparingLong((Job<T> job) -> job.wakeTick).thenComparingInt(job -> job.number))
                .map(job -> {
                    job.running = true;
                    return new Due<>(job.number, job.payload);
                });
    }

    /** A running job paused again: it keeps its number. One cancelled while it ran is dropped instead. */
    public void requeue(int number, int delay, T payload) throws ExpansionException {
        Job<T> job = jobs.get(number);
        if (job == null || job.cancelled) {
            jobs.remove(number);
            return;
        }
        checkDelay(delay);
        job.wakeTick = now + delay;
        job.payload = payload;
        job.running = false;
    }

    /** A running job ended. */
    public void finish(int number) {
        jobs.remove(number);
    }

    /** {@code %N  <ticks left>t  <text>} for the jobs {@code viewer} owns; with {@code all}, every job, with its owner. */
    public List<String> listing(String viewer, boolean all) {
        List<String> lines = new ArrayList<>();
        for (Job<T> job : jobs.values()) {
            if (job.cancelled || !(all || Objects.equals(job.owner, viewer))) {
                continue;
            }
            long left = job.running ? 0 : job.wakeTick - now;
            lines.add("%" + job.number + "  " + left + "t  " + (all ? job.ownerName + ": " : "") + job.text);
        }
        return lines;
    }

    /** Cancels job {@code number} if {@code viewer} owns it (or {@code all}); a running one ends at its next pause. */
    public boolean kill(int number, String viewer, boolean all) {
        Job<T> job = jobs.get(number);
        if (job == null || job.cancelled || !(all || Objects.equals(job.owner, viewer))) {
            return false;
        }
        cancel(job);
        return true;
    }

    /** Ends every job {@code owner} owns (they left). */
    public void cancelOwner(String owner) {
        for (Job<T> job : List.copyOf(jobs.values())) {
            if (owner.equals(job.owner)) {
                cancel(job);
            }
        }
    }

    public int size() {
        return jobs.size();
    }

    private void cancel(Job<T> job) {
        if (job.running) {
            job.cancelled = true;
        } else {
            jobs.remove(job.number);
        }
    }

    private void checkDelay(int delay) throws ExpansionException {
        if (delay > maxDelay) {
            throw new ExpansionException("sleep is limited to " + maxDelay + " ticks");
        }
    }
}
