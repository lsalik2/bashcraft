package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobTableTest {
    /** Advances one tick and takes every job now due. */
    private static List<JobTable.Due<String>> wake(JobTable<String> table) {
        table.tick();
        List<JobTable.Due<String>> due = new ArrayList<>();
        for (Optional<JobTable.Due<String>> next = table.nextDue(); next.isPresent(); next = table.nextDue()) {
            due.add(next.get());
        }
        return due;
    }

    @Test
    void smallestFreeNumbersAndWakeOrder() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        assertEquals(1, table.add("p", "Steve", "a", 3, "A"));
        assertEquals(2, table.add("p", "Steve", "b", 2, "B"));
        assertEquals(3, table.add(null, "Server", "c", 2, "C"));
        assertEquals(List.of(), wake(table));
        assertEquals(List.of(new JobTable.Due<>(2, "B"), new JobTable.Due<>(3, "C")), wake(table));
        table.finish(2);
        assertEquals(2, table.add("p", "Steve", "d", 5, "D"), "a finished job's number is free again");
        assertEquals(List.of(new JobTable.Due<>(1, "A")), wake(table));
    }

    @Test
    void aRequeuedJobKeepsItsNumber() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.add("p", "Steve", "a", 1, "A");
        assertEquals(List.of(new JobTable.Due<>(1, "A")), wake(table));
        assertEquals(2, table.add("p", "Steve", "b", 9, "B"), "a running job keeps its number");
        table.requeue(1, 2, "A2");
        assertEquals(List.of(), wake(table));
        assertEquals(List.of(new JobTable.Due<>(1, "A2")), wake(table));
    }

    @Test
    void limits() throws Exception {
        JobTable<String> table = new JobTable<>(3, 2, 100);
        assertEquals("sleep is limited to 100 ticks",
                assertThrows(ExpansionException.class, () -> table.add("p", "Steve", "a", 101, "A")).getMessage());
        table.add("p", "Steve", "a", 5, "A");
        table.add("p", "Steve", "b", 5, "B");
        assertEquals("too many waiting chains (max 2)",
                assertThrows(ExpansionException.class, () -> table.add("p", "Steve", "c", 5, "C")).getMessage());
        table.add("q", "Alex", "c", 5, "C");
        assertEquals("too many waiting chains (max 3)",
                assertThrows(ExpansionException.class, () -> table.add(null, "Server", "d", 5, "D")).getMessage());
        for (int i = 0; i < 5; i++) {
            table.tick();
        }
        int running = table.nextDue().orElseThrow().number();
        assertEquals("sleep is limited to 100 ticks",
                assertThrows(ExpansionException.class, () -> table.requeue(running, 500, "X")).getMessage());
    }

    @Test
    void listingAndKillFollowOwnership() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 2000);
        table.add("p", "Steve", "echo hi ; sleep 40 ; echo bye", 40, "A");
        table.add("q", "Alex", "sleep 1200 ; title @s title go", 1200, "B");
        table.tick();
        assertEquals(List.of("%1  39t  echo hi ; sleep 40 ; echo bye"), table.listing("p", false));
        assertEquals(List.of("%1  39t  Steve: echo hi ; sleep 40 ; echo bye", "%2  1199t  Alex: sleep 1200 ; title @s title go"),
                table.listing(null, true));
        assertFalse(table.kill(2, "p", false), "another player's job looks missing");
        assertFalse(table.kill(7, "p", false));
        assertTrue(table.kill(1, "p", false));
        assertEquals(List.of(), table.listing("p", false));
        assertTrue(table.kill(2, null, true), "operators cancel any job");
        assertEquals(0, table.size());
    }

    @Test
    void cancellingARunningJobDropsItAtItsNextPause() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.add("p", "Steve", "a", 1, "A");
        wake(table);
        assertTrue(table.kill(1, "p", false), "a job may cancel itself");
        table.requeue(1, 5, "A2");
        assertEquals(0, table.size());
    }

    @Test
    void aJobCancelledBeforeItsTurnDoesNotRun() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.add(null, "Server", "a", 1, "A");
        table.add(null, "Server", "b", 1, "B");
        table.tick();
        assertEquals(Optional.of(new JobTable.Due<>(1, "A")), table.nextDue());
        assertTrue(table.kill(2, null, true), "job 1 cancels job 2 while it runs");
        assertEquals(Optional.empty(), table.nextDue());
    }

    @Test
    void cancelOwnerDropsOnlyTheirJobs() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.add("p", "Steve", "a", 5, "A");
        table.add(null, "Server", "b", 5, "B");
        table.cancelOwner("p");
        assertEquals(List.of("%2  5t  Server: b"), table.listing(null, true));
    }

    @Test
    void aPlayersJobsShareABudgetPerSecond() {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.setBudgetSpent("p", 200);
        table.setBudgetSpent(null, 200);
        assertEquals(200, table.budgetSpent("p"));
        assertEquals(0, table.budgetSpent("q"));
        assertEquals(0, table.budgetSpent(null), "the server's jobs get a fresh budget each wake");
        for (int i = 0; i < 19; i++) {
            table.tick();
        }
        assertEquals(200, table.budgetSpent("p"), "the same second");
        table.tick();
        assertEquals(0, table.budgetSpent("p"), "a new second");
    }

    @Test
    void serverJobsHaveTheirOwnCap() throws Exception {
        JobTable<String> table = new JobTable<>(4, 2, 2, 100);
        table.add(null, "Server", "a", 5, "A");
        table.add(null, "Server", "b", 5, "B");
        assertEquals("too many waiting chains (max 2)",
                assertThrows(ExpansionException.class, () -> table.add(null, "Server", "c", 5, "C")).getMessage());
        table.add("p", "Steve", "d", 5, "D");
        table.add("q", "Alex", "e", 5, "E");
        assertEquals(4, table.size(), "players keep the slots the server can't take");
    }

    @Test
    void cancelIfEndsMatchingJobs() throws Exception {
        JobTable<String> table = new JobTable<>(64, 8, 1000);
        table.add(null, "Server", "a", 5, "as steve");
        table.add(null, "Server", "b", 5, "other");
        table.add(null, "Server", "c", 1, "as steve");
        table.tick();
        assertEquals(3, table.nextDue().orElseThrow().number());
        table.cancelIf("as steve"::equals);
        assertEquals(List.of("%2  4t  Server: b"), table.listing(null, true));
        table.requeue(3, 5, "as steve");
        assertEquals(1, table.size(), "a running job ends at its next pause");
    }
}
