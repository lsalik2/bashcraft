# BashCraft Time Implementation Plan (Sub-project C3: sleep and background `&`)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sleep` pauses a chain without blocking the server and resumes it on a later tick; `X & Y` lets a waiting part run on its own; `jobs` and `kill %N` list and cancel waiting chains.

**Architecture:** Pure parts: a `&` token and `Node.Background` in the parser; `SleepTime` (time syntax); a generic `JobTable<T>` (numbers, owners, limits, tick clock); executor support — a paused run returns a `Continuation` (frames of the lists still to finish, innermost first), `ChainExecutor.resume` runs it on, and a `Jobs` interface in `ShellContext` receives waiting parts. Minecraft parts: `ServerJobs` holds the table, wakes due jobs on `END_SERVER_TICK` (only while the game runs normally) and resumes each as the player who typed it (refreshed) or the source it started with (saved).

**Tech Stack:** as before (Java 25, Minecraft 26.3, Fabric incl. lifecycle events, JUnit 5.14.2, client game tests headless on Xvfb).

**Spec:** `docs/superpowers/specs/2026-10-12-bashcraft-time-design.md`

## Global Constraints

- All earlier constraints (`JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`; pure packages `chain`, `pipe`, `exec`, `config` import no `net.minecraft.*`/`net.fabricmc.*`; `BashCraft: ` prefix; no `Co-Authored-By`; Synergy read-only — never `./gradlew --stop`, never kill by pattern; dev server port 25569; game tests headless on Xvfb automatically).
- Config defaults: `maxSleepTicks` 72000, `maxJobsPerPlayer` 8, `maxJobs` 64; each at least 1.
- Error texts: `sleep needs a time like 20, 20t, 1s or 1d`, `sleep needs at least 1 tick`, `sleep is limited to N ticks`, `sleep can't run inside a pipe`, `sleep can't run inside $(…)`, `sleep can't run inside each`, `too many waiting chains (max N)`, `sleep is not available here`, `no job %N`, `kill needs %N, e.g. kill %1`, `jobs takes no arguments`.
- `jobs` lines: `%N  <ticks left>t  <text>`; operators see every job as `%N  <ticks left>t  <owner>: <text>`.
- Builtin words aliases can't use gain `sleep` and `jobs`.
- **Plan decision:** a sleep's limits (`maxSleepTicks`, job counts) are checked where the waiting part is queued (top of the chain or its `&`), not at the `sleep`; nothing runs in between, so the chain still stops right at the sleep with `[n] <reason>`.
- **Plan decision:** every sleep queues a job, even one with nothing after it (`echo a ; sleep 20`), so limits apply and `jobs` shows it, as in bash.
- **Plan decision:** `\&&` keeps meaning a literal `&&` (documented since M1); a lone `\&` is a literal `&`.
- **Plan decision:** a job keeps its table slot while it runs; one cancelled while running (`kill` on itself, or its player leaving) isn't queued again. A job cancelled before its turn in the same tick doesn't run.

## Review Focus

1. **`&` that used to be text** — the documented `\&&` escape must stay a literal `&&`, and `\&`/quotes keep a lone `&`. → Task 1: existing test `backslashEscapesAnOperator` (`say use \&& here`) plus new `trailingBackgroundAndEscapes`.
2. **Vanilla `kill`** — `kill @e[type=zombie]`, `kill`, `kill 2` must stay vanilla's. → Task 4 tests `killIsBuiltinOnlyWithPercent` and `killCancelsJobsByNumber`.
3. **A saved source whose entity is gone** (an `execute as @e[…] run sh` entity killed while waiting) must end silently, never resume or throw in the tick. → Task 5 game check "a chain whose entity is gone ends".
4. **A job cancelled earlier in the same tick** (job 1 runs `kill %2` while 2 is also due) must not run. → Task 2 test `aJobCancelledBeforeItsTurnDoesNotRun`.
5. **A nested `sh` that sleeps inside a pipe** (`sh sh sleep 10 \; … | grep x`) must return at once and finish as its own job, not stop the outer chain. → Task 5 game check "a nested sh that sleeps returns at once, even in a pipe".

---

### Task 1: The `&` operator

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/chain/Tokenizer.java`, `src/main/java/net/slk/bashcraft/chain/Node.java`, `src/main/java/net/slk/bashcraft/chain/NodeParser.java`, `src/main/java/net/slk/bashcraft/chain/ChainFormatter.java`, `src/main/java/net/slk/bashcraft/chain/ChainLayout.java`, `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`
- Test: `src/test/java/net/slk/bashcraft/chain/NodeParserTest.java`, `ChainFormatterTest.java`, `ChainLayoutTest.java`, `FunctionLinesTest.java`, `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`

**Interfaces:**
- Produces: `Node.Background(Node body)` (a `Node` record; `commandCount`/`containsGroup` delegate to `body`); token kind `Tokenizer.Kind.BACKGROUND`; the parser wraps the `&&`/`||` list before each `&` in a `Background` and joins it to what follows with `Operator.SEQ`; `ChainFormatter.toSh` writes `body &` (and a plain space, not ` ; `, after a `Background` item).

- [ ] **Step 1: Write the failing tests**

Append to `NodeParserTest.java` (before the final `}`):

```java
    @Test
    void backgroundClosesTheAndOrListBeforeIt() throws Exception {
        assertEquals(new Node.Sequence(List.of(
                new Node.Background(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.AND))), cmd("c")),
                List.of(Operator.SEQ)), sh("a && b & c"));
        assertEquals(new Node.Sequence(List.of(cmd("a"), new Node.Background(cmd("b")), cmd("c")),
                List.of(Operator.SEQ, Operator.SEQ)), sh("a ; b & c"));
        assertEquals(new Node.Sequence(List.of(new Node.Background(new Node.Pipeline(List.of(cmd("a"), cmd("b")))), cmd("c")),
                List.of(Operator.SEQ)), sh("a | b & c"));
    }

    @Test
    void trailingBackgroundAndEscapes() throws Exception {
        assertEquals(new Node.Background(cmd("a")), sh("a &"));
        assertEquals(new Node.Sequence(List.of(cmd("a"), new Node.Background(cmd("b"))), List.of(Operator.SEQ)), sh("a ; b &"));
        assertEquals(new Node.Sequence(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("sleep 20"), new Node.Background(cmd("say a"))), List.of(Operator.SEQ))),
                cmd("b")), List.of(Operator.AND)), sh("(sleep 20 ; say a &) && b"));
        assertEquals(cmd("say Tom & Jerry"), sh("say Tom \\& Jerry"));
        assertEquals(cmd("say \"Tom & Jerry\""), sh("say \"Tom & Jerry\""));
        assertEquals(cmd("say use && here"), sh("say use \\&& here"));
    }

    @Test
    void misplacedBackgroundIsAnError() {
        assertEquals("unexpected '&' at col 1", assertThrows(ChainSyntaxException.class, () -> sh("& a")).getMessage());
        assertEquals("unexpected '&' at col 5", assertThrows(ChainSyntaxException.class, () -> sh("a & & b")).getMessage());
        assertEquals("unexpected ';' at col 5", assertThrows(ChainSyntaxException.class, () -> sh("a & ; b")).getMessage());
    }

    @Test
    void backgroundInTransparentModeNeedsASlash() throws Exception {
        assertEquals(new Node.Sequence(List.of(new Node.Background(cmd("a")), cmd("b")), List.of(Operator.SEQ)),
                NodeParser.parse("/a & /b", TRANSPARENT, 32));
        assertEquals(cmd("say Tom & Jerry"), NodeParser.parse("/say Tom & Jerry", TRANSPARENT, 32));
        assertEquals(cmd("say hi &"), NodeParser.parse("/say hi &", TRANSPARENT, 32));
    }
```

Append to `ChainFormatterTest.java` (and add `import java.util.List;`):

```java
    @Test
    void formatsBackgroundAndRoundTrips() throws Exception {
        for (String chain : List.of("a && b & c", "a ; b & c", "(sleep 20 ; say a &) && b", "a &", "a | b & c")) {
            Node node = NodeParser.parse(chain, NodeParser.Mode.SH, 32);
            assertEquals(chain, ChainFormatter.toSh(node));
            assertEquals(node, NodeParser.parse(ChainFormatter.toSh(node), NodeParser.Mode.SH, 32), chain);
        }
        assertEquals("say Tom \\& Jerry", ChainFormatter.toSh(new Node.Command("say Tom & Jerry")));
    }
```

Append to `ChainLayoutTest.java`:

```java
    @Test
    void backgroundIsAnOperator() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 5), new Span(Kind.OPERATOR, 6, 7), new Span(Kind.COMMAND, 8, 9)),
                ChainLayout.forInput("/sh a & b", false).orElseThrow().spans());
    }
```

Append to `FunctionLinesTest.java`:

```java
    @Test
    void backgroundLinesBecomeShLinesAndLiteralAmpersandsStayText() {
        assertEquals(List.of("sh say a & say b"), rewrite("say a & /say b"));
        assertEquals(List.of("sh say Tom \\& Jerry && say b"), rewrite("say Tom & Jerry && /say b"));
    }
```

Append to `ChainExecutorTest.java`:

```java
    @Test
    void backgroundWithoutPausesRunsInOrderAndSucceeds() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        ExecResult result = run(r, "a & b && c ; a &");
        assertEquals(List.of("a", "b", "c", "a"), r.ran);
        assertTrue(result.success(), "the status after & is success");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: compilation FAIL — `cannot find symbol: class Background`.

- [ ] **Step 3: Implement**

`Node.java` — add after `Group`:

```java
    /** {@code body &}: if the body pauses, its waiting part goes on by itself and the chain carries on (spec C3 §3). */
    record Background(Node body) implements Node {
        @Override
        public int commandCount() {
            return body.commandCount();
        }

        @Override
        public boolean containsGroup() {
            return body.containsGroup();
        }
    }
```

`Tokenizer.java`:
- `enum Kind { TEXT, AND, OR, PIPE, SEQ, BACKGROUND, OPEN, CLOSE, END }`
- In `run()`, insert before the `ESCAPABLE` check (`if (c == '\\' && i + 1 < input.length() && ESCAPABLE.indexOf(...`):

```java
            if (c == '\\' && input.startsWith("&&", i + 1)) {
                // \&& has always meant a literal && (from before a lone & was an operator).
                appendText("&&", i, i + 3);
                i += 3;
                continue;
            }
```

- In `operatorAt`, after the `';'` line: `if (c == '&') return Kind.BACKGROUND;`

`NodeParser.java` — replace `sequence()` and add `background`:

```java
    private Node sequence() throws ChainSyntaxException {
        List<Node> items = new ArrayList<>(List.of(pipeline()));
        List<Operator> joins = new ArrayList<>();
        // The first item of the current &&/|| list: an '&' backgrounds the items from here on (bash's precedence).
        int listStart = 0;
        while (true) {
            Kind kind = peek().kind();
            if (kind == Kind.BACKGROUND) {
                lastOperator = next();
                background(items, joins, listStart);
                if (peek().kind() == Kind.END || peek().kind() == Kind.CLOSE) {
                    break;
                }
                joins.add(Operator.SEQ);
                items.add(pipeline());
                listStart = items.size() - 1;
                continue;
            }
            Operator op = switch (kind) {
                case AND -> Operator.AND;
                case OR -> Operator.OR;
                case SEQ -> Operator.SEQ;
                default -> null;
            };
            if (op == null) {
                break;
            }
            lastOperator = next();
            items.add(pipeline());
            joins.add(op);
            if (op == Operator.SEQ) {
                listStart = items.size() - 1;
            }
        }
        return items.size() == 1 ? items.getFirst() : new Node.Sequence(items, joins);
    }

    /** Replaces items[from…] (one &&/|| list) and the joins between them with one Background item. */
    private static void background(List<Node> items, List<Operator> joins, int from) {
        List<Node> list = new ArrayList<>(items.subList(from, items.size()));
        List<Operator> listJoins = new ArrayList<>(joins.subList(from, joins.size()));
        items.subList(from, items.size()).clear();
        joins.subList(from, joins.size()).clear();
        items.add(new Node.Background(list.size() == 1 ? list.getFirst() : new Node.Sequence(list, listJoins)));
    }
```

Also update the class Javadoc's first sentence to: `{@code &&}, {@code ||} and {@code ;} are kept in one left-to-right {@link Node.Sequence}; {@code &} wraps the {@code &&}/{@code ||} list before it in a {@link Node.Background}.`

`ChainFormatter.toSh` — replace the `Sequence` case and add `Background`:

```java
            case Node.Sequence sequence -> {
                StringBuilder out = new StringBuilder(toSh(sequence.items().getFirst()));
                for (int i = 1; i < sequence.items().size(); i++) {
                    boolean afterBackground = sequence.items().get(i - 1) instanceof Node.Background;
                    out.append(switch (sequence.joins().get(i - 1)) {
                        case AND -> " && ";
                        case OR -> " || ";
                        case SEQ -> afterBackground ? " " : " ; ";
                    }).append(toSh(sequence.items().get(i)));
                }
                yield out.toString();
            }
            case Node.Group group -> "(" + toSh(group.body()) + ")";
            case Node.Background background -> toSh(background.body()) + " &";
```

`ChainLayout.of`: `case AND, OR, PIPE, SEQ, BACKGROUND -> Kind.OPERATOR;`

`ChainExecutor.java`:
- `number`: add `case Node.Background background -> number(background.body(), numbers);`
- `firstCommand`: add `case Node.Background background -> firstCommand(background.body());`
- `Run.node`: add `case Node.Background background -> background(background, input, feedsPipe);`
- `appendArguments`: add `case Node.Background background -> new Node.Background(appendArguments(background.body()));`
- Add to `Run`:

```java
        /** body &: the status after it is success, as in bash (spec C3 §3). */
        private Outcome background(Node.Background background, Piped input, boolean feedsPipe) {
            Outcome body = node(background.body(), input, feedsPipe);
            lastStatus = 0;
            return new Outcome(new ExecResult(true, 1, List.of()), body.lastCommand());
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (238 + 8 = 246), including the existing `say use \&& here` test.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Parse & as a background operator"
```

---

### Task 2: Sleep times, the job table and config

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/SleepTime.java`, `src/main/java/net/slk/bashcraft/exec/JobTable.java`
- Modify: `src/main/java/net/slk/bashcraft/config/BashcraftConfig.java`
- Test: `src/test/java/net/slk/bashcraft/exec/SleepTimeTest.java`, `src/test/java/net/slk/bashcraft/exec/JobTableTest.java`, `src/test/java/net/slk/bashcraft/config/BashcraftConfigTest.java`

**Interfaces:**
- Produces: `SleepTime.ticks(String) → int` (throws `ExpansionException`; at least 1, saturates at `Integer.MAX_VALUE`); `JobTable<T>(int maxJobs, int maxPerOwner, int maxDelay)` with `int add(String owner, String ownerName, String text, int delay, T payload) throws ExpansionException`, `void tick()`, `Optional<JobTable.Due<T>> nextDue()`, `void requeue(int number, int delay, T payload) throws ExpansionException`, `void finish(int number)`, `List<String> listing(String viewer, boolean all)`, `boolean kill(int number, String viewer, boolean all)`, `void cancelOwner(String owner)`, `int size()`; `record JobTable.Due<T>(int number, T payload)`; config fields `maxSleepTicks`, `maxJobsPerPlayer`, `maxJobs`.

- [ ] **Step 1: Write the failing tests**

`SleepTimeTest.java`:

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SleepTimeTest {
    @Test
    void unitsLikeSchedule() throws Exception {
        assertEquals(20, SleepTime.ticks("20"));
        assertEquals(20, SleepTime.ticks("20t"));
        assertEquals(20, SleepTime.ticks("1s"));
        assertEquals(10, SleepTime.ticks("0.5s"));
        assertEquals(24000, SleepTime.ticks("1d"));
        assertEquals(1, SleepTime.ticks("1.26t"));
        assertEquals(30, SleepTime.ticks(" 1.5s "));
        assertEquals(Integer.MAX_VALUE, SleepTime.ticks("99999999999999999999d"), "the job table reports the limit");
    }

    @Test
    void badTimes() {
        assertEquals("sleep needs at least 1 tick", assertThrows(ExpansionException.class, () -> SleepTime.ticks("0")).getMessage());
        assertEquals("sleep needs at least 1 tick", assertThrows(ExpansionException.class, () -> SleepTime.ticks("0.4t")).getMessage());
        for (String bad : List.of("", "-5", "1m", "s", "20 ticks", "1e3", "NaN")) {
            assertEquals("sleep needs a time like 20, 20t, 1s or 1d",
                    assertThrows(ExpansionException.class, () -> SleepTime.ticks(bad)).getMessage(), bad);
        }
    }
}
```

`JobTableTest.java`:

```java
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
}
```

Append to `BashcraftConfigTest.java`:

```java
    @Test
    void timeLimitsHaveDefaultsAndAreAtLeastOne() throws Exception {
        BashcraftConfig defaults = BashcraftConfig.load(dir.resolve("a.json"));
        assertEquals(72000, defaults.maxSleepTicks);
        assertEquals(8, defaults.maxJobsPerPlayer);
        assertEquals(64, defaults.maxJobs);
        Path file = dir.resolve("b.json");
        Files.writeString(file, "{\"maxSleepTicks\": 0, \"maxJobsPerPlayer\": -1, \"maxJobs\": 0}");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(1, config.maxSleepTicks);
        assertEquals(1, config.maxJobsPerPlayer);
        assertEquals(1, config.maxJobs);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: compilation FAIL — `cannot find symbol: class SleepTime` / `class JobTable` / `maxSleepTicks`.

- [ ] **Step 3: Implement**

`SleepTime.java`:

```java
package net.slk.bashcraft.exec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code sleep}'s time (spec C3 §2), read like {@code /schedule}'s: ticks, or a {@code t}, {@code s} (20) or {@code d} (24000) unit. */
public final class SleepTime {
    private static final Pattern TIME = Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)([tsd]?)");

    private SleepTime() {}

    /** Whole ticks, rounded to the nearest; at least 1. A time beyond any limit comes back as Integer.MAX_VALUE. */
    public static int ticks(String text) throws ExpansionException {
        Matcher m = TIME.matcher(text.strip());
        if (!m.matches()) {
            throw new ExpansionException("sleep needs a time like 20, 20t, 1s or 1d");
        }
        int unit = switch (m.group(2)) {
            case "s" -> 20;
            case "d" -> 24000;
            default -> 1;
        };
        double exact = Double.parseDouble(m.group(1)) * unit;
        if (exact >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long ticks = Math.round(exact);
        if (ticks < 1) {
            throw new ExpansionException("sleep needs at least 1 tick");
        }
        return (int) ticks;
    }
}
```

`JobTable.java`:

```java
package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** Advances the clock by one tick. */
    public void tick() {
        now++;
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
```

`BashcraftConfig.java` — add after `swallowPipedOutput`:

```java
    /** Longest single sleep, in ticks (an hour). */
    public int maxSleepTicks = 72000;
    /** Waiting chains one player may have. */
    public int maxJobsPerPlayer = 8;
    /** Waiting chains on the whole server. */
    public int maxJobs = 64;
```

and in `load`, after the `maxTotalCommands` clamp:

```java
        config.maxSleepTicks = Math.max(1, config.maxSleepTicks);
        config.maxJobsPerPlayer = Math.max(1, config.maxJobsPerPlayer);
        config.maxJobs = Math.max(1, config.maxJobs);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (246 + 10 = 256).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add sleep times, the job table and time limits"
```

---

### Task 3: Pausing and resuming chains

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/Continuation.java`, `src/main/java/net/slk/bashcraft/exec/Jobs.java`, `src/test/java/net/slk/bashcraft/exec/FakeJobs.java`
- Modify: `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`, `src/main/java/net/slk/bashcraft/exec/Builtins.java`, `src/main/java/net/slk/bashcraft/exec/ShellContext.java`, `src/main/java/net/slk/bashcraft/exec/AliasTable.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`, `BuiltinsTest.java`, `AliasTableTest.java`

**Interfaces:**
- Consumes: `SleepTime.ticks(String)` (Task 2); `Node.Background` (Task 1); `ChainFormatter.toSh(Node)`.
- Produces: `interface Jobs { void queue(Continuation, int delay, String text) throws ExpansionException; void requeue(Continuation, int delay) throws ExpansionException; Jobs NONE; }`; `final class Continuation` (opaque; `int depth()` = frames waiting); `ShellContext(Variables, Aliases, History, Jobs)` plus the old 3-argument constructor (→ `Jobs.NONE`); `ChainExecutor.resume(Continuation)`; `Builtins.Kind.SLEEP`.

- [ ] **Step 1: Write the failing tests**

`FakeJobs.java`:

```java
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
```

Append to `ChainExecutorTest.java` (add imports `java.util.LinkedHashMap`):

```java
    private static ChainExecutor timed(FakeRunner runner, FakeJobs jobs, int maxDepth) {
        return new ChainExecutor(runner, new ShellContext(table(), aliases(), null, jobs), true, Integer.MAX_VALUE, maxDepth);
    }

    private static ExecResult start(FakeRunner runner, FakeJobs jobs, String chain) throws Exception {
        return timed(runner, jobs, 8).execute(NodeParser.parse(chain, NodeParser.Mode.SH, 32));
    }

    @Test
    void pauseAndResumeThroughListsAndGroups() throws Exception {
        FakeRunner r = new FakeRunner().on("b", FAIL);
        FakeJobs jobs = new FakeJobs();
        ExecResult result = start(r, jobs, "a ; (sleep 20 ; b) || c ; d");
        assertEquals(List.of("a"), r.ran);
        assertTrue(result.success());
        assertEquals(20, result.result(), "/sh reports the sleep");
        assertEquals(List.of(new FakeJobs.Queued(jobs.queued.getFirst().continuation(), 20, "a ; (sleep 20 ; b) || c ; d")), jobs.queued);
        timed(r, jobs, 8).resume(jobs.queued.getFirst().continuation());
        assertEquals(List.of("a", "b", "c", "d"), r.ran);
        assertTrue(jobs.requeued.isEmpty());

        FakeJobs timer = new FakeJobs();
        start(new FakeRunner(), timer, "d=7 ; sleep $d ; a");
        assertEquals(7, timer.queued.getFirst().delay());
    }

    @Test
    void statusAndArgumentsSurviveAPause() throws Exception {
        FakeRunner r = new FakeRunner().on("fail", FAIL);
        FakeJobs jobs = new FakeJobs();
        start(r, jobs, "alias t = 'sleep 5 ; say $1 ; fail' ; t x || say $? && say after");
        assertTrue(r.ran.isEmpty());
        timed(r, jobs, 8).resume(jobs.queued.getFirst().continuation());
        assertEquals(List.of("say x", "fail", "say 1", "say after"), r.ran);
    }

    @Test
    void aliasTimerKeepsOneFrame() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        start(r, jobs, "alias tick = 'say hi ; sleep 20 ; tick' ; tick");
        Continuation waiting = jobs.queued.getFirst().continuation();
        for (int round = 0; round < 20; round++) {
            jobs.requeued.clear();
            timed(r, jobs, 8).resume(waiting);
            waiting = jobs.requeued.getFirst().continuation();
            assertEquals(1, waiting.depth(), "round " + round);
        }
        assertEquals(21, r.ran.size());
        assertTrue(r.errors.isEmpty(), r.errors.toString());
    }

    @Test
    void depthCountsAcrossPauses() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        start(r, jobs, "alias t = 'say hi ; sleep 20 ; t ; say after' ; t");
        Continuation waiting = jobs.queued.getFirst().continuation();
        int rounds = 0;
        while (r.errors.isEmpty() && rounds < 50) {
            jobs.requeued.clear();
            timed(r, jobs, 4).resume(waiting);
            if (jobs.requeued.isEmpty()) {
                break;
            }
            waiting = jobs.requeued.getFirst().continuation();
            rounds++;
        }
        assertEquals("aliases nested deeper than 4", r.errors.getFirst());
        assertTrue(rounds < 10, "stopped after a few rounds: " + rounds);
    }

    @Test
    void everyWakeGetsAFreshBudget() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        ChainExecutor small = new ChainExecutor(r, new ShellContext(table(), aliases(), null, jobs), true, 3, 8);
        try (DepthGuard.Scope ignored = DepthGuard.enter(8).orElseThrow()) {
            small.execute(NodeParser.parse("a ; b ; sleep 1 ; c ; d ; sleep 1 ; e", NodeParser.Mode.SH, 32));
        }
        assertEquals(List.of("a", "b"), r.ran);
        try (DepthGuard.Scope ignored = DepthGuard.enter(8).orElseThrow()) {
            small.resume(jobs.queued.getFirst().continuation());
        }
        assertEquals(List.of("a", "b", "c", "d"), r.ran);
        try (DepthGuard.Scope ignored = DepthGuard.enter(8).orElseThrow()) {
            small.resume(jobs.requeued.getFirst().continuation());
        }
        assertEquals(List.of("a", "b", "c", "d", "e"), r.ran);
        assertTrue(r.errors.isEmpty(), r.errors.toString());
    }

    @Test
    void refusedSleepsStopTheChain() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("echo 5 | sleep 20 ; a || b", "[2] sleep can't run inside a pipe");
        cases.put("say $(sleep 20) ; a || b", "sleep can't run inside $(…)");
        cases.put("echo x | each sleep 20 ; a || b", "[2] sleep can't run inside each");
        cases.put("sleep 0 ; a || b", "[1] sleep needs at least 1 tick");
        cases.put("(sleep soon || a) ; a || b", "[1] sleep needs a time like 20, 20t, 1s or 1d");
        cases.put("(sleep 0 ; a) & a || b", "[1] sleep needs at least 1 tick");
        cases.put("alias z = 'sleep 2 ; a' ; z | grep x ; a || b", "sleep can't run inside a pipe");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            FakeRunner r = new FakeRunner();
            FakeJobs jobs = new FakeJobs();
            ExecResult result = start(r, jobs, c.getKey());
            assertEquals(List.of(c.getValue()), r.errors, c.getKey());
            assertEquals(List.of(), r.ran, c.getKey());
            assertFalse(result.success(), c.getKey());
            assertTrue(jobs.queued.isEmpty(), c.getKey());
        }
    }

    @Test
    void aSleepTheJobsRefuseStopsTheChain() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs full = new FakeJobs();
        full.full = true;
        assertFalse(start(r, full, "a ; sleep 20 ; b").success());
        start(r, full, "(sleep 5 ; c) & d");
        assertEquals(List.of("[2] too many waiting chains (max 1)", "[1] too many waiting chains (max 1)"), r.errors);
        assertEquals(List.of("a"), r.ran);

        FakeRunner limited = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        jobs.maxDelay = 100;
        start(limited, jobs, "sleep 101 ; b");
        assertEquals(List.of("[1] sleep is limited to 100 ticks"), limited.errors);
        assertTrue(limited.ran.isEmpty());
    }

    @Test
    void backgroundDetachesItsWaitingPart() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        ExecResult result = start(r, jobs, "(sleep 10 ; a) & (sleep 20 ; b) & c ; sleep 5 ; d");
        assertEquals(List.of("c"), r.ran);
        assertEquals(List.of("(sleep 10 ; a)", "(sleep 20 ; b)", "(sleep 10 ; a) & (sleep 20 ; b) & c ; sleep 5 ; d"),
                jobs.queued.stream().map(FakeJobs.Queued::text).toList());
        assertEquals(List.of(10, 20, 5), jobs.queued.stream().map(FakeJobs.Queued::delay).toList());
        assertEquals(5, result.result());
        for (FakeJobs.Queued queued : List.copyOf(jobs.queued)) {
            timed(r, jobs, 8).resume(queued.continuation());
        }
        assertEquals(List.of("c", "a", "b", "d"), r.ran);

        FakeRunner list = new FakeRunner();
        FakeJobs listJobs = new FakeJobs();
        start(list, listJobs, "a && sleep 5 && b & c");
        assertEquals(List.of("a", "c"), list.ran);
        assertEquals("a && sleep 5 && b", listJobs.queued.getFirst().text());
    }

    @Test
    void aDetachedPartKeepsItsOwnStatus() throws Exception {
        FakeRunner r = new FakeRunner().on("fail", FAIL);
        FakeJobs jobs = new FakeJobs();
        start(r, jobs, "(sleep 5 ; say $?) & fail");
        assertEquals(List.of("fail"), r.ran);
        timed(r, jobs, 8).resume(jobs.queued.getFirst().continuation());
        assertEquals(List.of("fail", "say 0"), r.ran, "the chain going on doesn't change the waiting part's $?");
    }
```

Append to `BuiltinsTest.java`:

```java
    @Test
    void sleepIsABuiltin() {
        assertEquals(new Builtins.Call(Builtins.Kind.SLEEP, "", "20"), Builtins.classify("sleep 20"));
        assertEquals(Builtins.Kind.NONE, Builtins.classify("sleepy 20").kind());
    }
```

In `AliasTableTest.aliasNamesCantShadowCommandsOrBuiltins`, add:

```java
        assertEquals("alias name sleep is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "sleep", "say")).getMessage());
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: compilation FAIL — `cannot find symbol: class Jobs` / `class Continuation` / `Kind.SLEEP`.

- [ ] **Step 3: Add `Jobs`, `Continuation`, the context field, the builtin word**

`Jobs.java`:

```java
package net.slk.bashcraft.exec;

/** Where a chain's waiting parts go (spec C3 §4–5); the Minecraft side keeps them in a {@link JobTable}. */
public interface Jobs {
    /** A part of the chain paused for {@code delay} ticks: a new job, listed as {@code text}. Throws why it can't wait. */
    void queue(Continuation continuation, int delay, String text) throws ExpansionException;

    /** The job being resumed paused again: it keeps its number. */
    void requeue(Continuation continuation, int delay) throws ExpansionException;

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
    };
}
```

`Continuation.java`:

```java
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
```

`ShellContext.java`:

```java
/**
 * What a chain can reach besides the runner: its variables, aliases, — only in a chain a player typed — that player's
 * history (null otherwise), and where its waiting parts go.
 */
public record ShellContext(Variables variables, Aliases aliases, History history, Jobs jobs) {
    public ShellContext(Variables variables, Aliases aliases, History history) {
        this(variables, aliases, history, Jobs.NONE);
    }

    public static ShellContext of(Variables variables) {
        return new ShellContext(variables, Aliases.NONE, null);
    }
}
```

`Builtins.java`: enum `{ ASSIGN, UNSET, ECHO, GREP, STORE, EACH, SURFACE, ALIAS, UNALIAS, HISTORY, SLEEP, NONE }`; add `Kind.SLEEP` to the word loop's `List.of(...)`; extend the `Call` Javadoc's list with `SLEEP`.

`AliasTable.BUILTIN_WORDS`: add `"sleep"`.

- [ ] **Step 4: Make the executor pause and resume**

In `ChainExecutor.java` add imports `net.slk.bashcraft.chain.ChainFormatter`, `java.util.ArrayDeque`, `java.util.ArrayList`, `java.util.Deque`.

Replace `execute` and add `resume`, `detached` and the new nested types (put them after `firstCommand`):

```java
    /**
     * Returns the outcome of the last command that ran (a skipped item leaves the status unchanged). A chain that
     * paused goes to {@link Jobs#queue} and reports the {@code sleep}.
     */
    public ExecResult execute(Node root) {
        Map<Node.Command, Integer> numbers = new IdentityHashMap<>();
        number(root, numbers);
        Outcome outcome = new Run(new RunState(numbers, root.commandCount() > 1, null, 0), null).node(root, null, false);
        Halt halt = outcome.halt();
        if (halt != null && !halt.stopped()) {
            try {
                context.jobs().queue(new Continuation(halt.frames()), halt.delay(), ChainFormatter.toSh(root));
            } catch (ExpansionException e) {
                runner.reportError(halt.label() + e.getMessage());
                return ExecResult.failure();
            }
        }
        return outcome.result();
    }

    /**
     * Runs a paused chain on (spec C3 §5): its frames innermost first, each one's outcome deciding the {@code &&} /
     * {@code ||} of the frame outside it. A new pause goes back to the same job through {@link Jobs#requeue}.
     */
    public void resume(Continuation continuation) {
        List<Frame> frames = continuation.frames;
        // Only alias levels with frames left count, renumbered from 0: a timer that calls itself last stays one level deep.
        List<Integer> levels = frames.stream().map(frame -> frame.state().level).distinct().sorted().toList();
        Map<RunState, RunState> renumbered = new IdentityHashMap<>();
        for (Frame frame : frames) {
            renumbered.computeIfAbsent(frame.state(), state -> state.atLevel(levels.indexOf(state.level)));
        }
        Map<RunState, Run> runs = new IdentityHashMap<>();
        Deque<DepthGuard.Scope> scopes = new ArrayDeque<>();
        try {
            Outcome outcome = null;
            RunState previous = null;
            for (int f = 0; f < frames.size(); f++) {
                Frame frame = frames.get(f);
                RunState state = renumbered.get(frame.state());
                while (scopes.size() > state.level) {
                    scopes.pop().close();
                }
                while (scopes.size() < state.level) {
                    Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
                    if (scope.isEmpty()) {
                        runner.reportError("aliases nested deeper than " + maxDepth);
                        return;
                    }
                    scopes.push(scope.get());
                }
                if (outcome == null) {
                    outcome = new Outcome(frame.status(), frame.lastCommand());
                } else if (state != previous) {
                    // The alias call this frame waited on has finished: like any command, it sets $?.
                    state.lastStatus = outcome.result().success() ? 0 : 1;
                }
                previous = state;
                outcome = runs.computeIfAbsent(state, s -> new Run(s, null)).continueSequence(frame.sequence(), frame.next(), outcome, false);
                Halt halt = outcome.halt();
                if (halt != null) {
                    if (!halt.stopped()) {
                        List<Frame> rest = new ArrayList<>(halt.frames());
                        frames.subList(f + 1, frames.size()).forEach(outer -> rest.add(outer.withState(renumbered.get(outer.state()))));
                        try {
                            context.jobs().requeue(new Continuation(rest), halt.delay());
                        } catch (ExpansionException e) {
                            runner.reportError(halt.label() + e.getMessage());
                        }
                    }
                    return;
                }
            }
        } finally {
            while (!scopes.isEmpty()) {
                scopes.pop().close();
            }
        }
    }

    /** The frames with their run states copied, so the chain going on can't change a waiting part's $?. */
    private static List<Frame> detached(List<Frame> frames) {
        Map<RunState, RunState> copies = new IdentityHashMap<>();
        return frames.stream().map(frame -> frame.withState(copies.computeIfAbsent(frame.state(), state -> state.atLevel(state.level)))).toList();
    }

    /** A list a paused chain was inside: run its items from {@code next} on, after {@code status}. */
    record Frame(Node.Sequence sequence, int next, ExecResult status, String lastCommand, RunState state) {
        Frame withState(RunState other) {
            return new Frame(sequence, next, status, lastCommand, other);
        }
    }

    /**
     * One run's settings and {@code $?}, shared by the frames it leaves: command numbers, whether errors are numbered,
     * the alias arguments in force ({@code null} outside an alias) and how many alias levels deep it is.
     */
    static final class RunState {
        final Map<Node.Command, Integer> numbers;
        final boolean numbered;
        final Arguments positionals;
        final int level;
        int lastStatus;

        RunState(Map<Node.Command, Integer> numbers, boolean numbered, Arguments positionals, int level) {
            this.numbers = numbers;
            this.numbered = numbered;
            this.positionals = positionals;
            this.level = level;
        }

        RunState atLevel(int other) {
            RunState copy = new RunState(numbers, numbered, positionals, other);
            copy.lastStatus = lastStatus;
            return copy;
        }
    }

    /**
     * Why a run ended early: paused by {@code sleep} for {@code delay} ticks — the lists still to finish are collected
     * in {@code frames} on the way up, innermost first — or stopped for good by a sleep that couldn't pause (already
     * reported). {@code label} is the sleep's {@code [n] }, for an error from the job queue.
     */
    private record Halt(boolean stopped, int delay, String label, List<Frame> frames) {
        static Halt pause(int delay, String label) {
            return new Halt(false, delay, label, new ArrayList<>());
        }

        static Halt stop() {
            return new Halt(true, 0, "", List.of());
        }
    }

    /** Thrown out of a {@code $( … )} whose chain stopped, so the command around it stops the chain too, silently. */
    private static final class ChainStopped extends ExpansionException {
        ChainStopped() {
            super("the chain stopped");
        }
    }
```

Replace the `Outcome` record:

```java
    /** What running a node produced; lastCommand is the text of the last command that ran; halt is set when it paused or stopped. */
    private record Outcome(ExecResult result, String lastCommand, Halt halt) {
        Outcome(ExecResult result, String lastCommand) {
            this(result, lastCommand, null);
        }

        Outcome halted(Halt other) {
            return new Outcome(result, lastCommand, other);
        }
    }
```

In `Run`: replace the fields and constructors (`numbers`, `numbered`, `lastStatus`, `positionals`, both constructors) with:

```java
        private final RunState state;
        /** Why a sleep can't pause here ("inside a pipe", …), or null where it can. */
        private String noPause;
        private boolean budgetExceeded;
        private boolean expansionFailed;

        Run(RunState state, String noPause) {
            this.state = state;
            this.noPause = noPause;
        }
```

Then everywhere in `Run`: `numbers` → `state.numbers`, `numbered` → `state.numbered`, `positionals` → `state.positionals`, `lastStatus` → `state.lastStatus` (the compiler lists every place). `label` becomes:

```java
        private String label(Node.Command command) {
            return state.numbered ? "[" + state.numbers.get(command) + "] " : "";
        }
```

Replace `node`, `sequence` and add `continueSequence`:

```java
        Outcome node(Node node, Piped input, boolean feedsPipe) {
            return switch (node) {
                case Node.Command command -> command(command, input, feedsPipe);
                case Node.Pipeline pipeline -> pipeline(pipeline, input, feedsPipe);
                case Node.Sequence sequence -> sequence(sequence, input, feedsPipe);
                case Node.Group group -> node(group.body(), input, feedsPipe);
                case Node.Background background -> background(background, input, feedsPipe);
            };
        }

        /** A piped value goes to the first item only. Any item may run last, so all hide output when the result feeds a pipe. */
        private Outcome sequence(Node.Sequence sequence, Piped input, boolean feedsPipe) {
            return continueSequence(sequence, 1, node(sequence.items().getFirst(), input, feedsPipe), feedsPipe);
        }

        /**
         * Runs the items from {@code from} on, after {@code status} (the outcome of the item before). When an item
         * pauses, a frame for the items still to run is added on the way up; when one stops, nothing more runs.
         */
        Outcome continueSequence(Node.Sequence sequence, int from, Outcome status, boolean feedsPipe) {
            for (int i = from; ; i++) {
                if (status.halt() != null) {
                    if (!status.halt().stopped() && i < sequence.items().size()) {
                        status.halt().frames().add(new Frame(sequence, i, status.result(), status.lastCommand(), state));
                    }
                    return status;
                }
                if (i == sequence.items().size()) {
                    return status;
                }
                boolean run = switch (sequence.joins().get(i - 1)) {
                    case AND -> status.result().success();
                    case OR -> !status.result().success();
                    case SEQ -> true;
                };
                if (run) {
                    status = node(sequence.items().get(i), null, feedsPipe);
                }
            }
        }
```

`pipeline`: wrap the body so its stages can't pause, and stop at a halt:

```java
        private Outcome pipeline(Node.Pipeline pipeline, Piped input, boolean feedsPipe) {
            String outer = noPause;
            noPause = "inside a pipe";
            try {
                List<Node> stages = pipeline.stages();
                Outcome previous = node(stages.getFirst(), input, true);
                for (int i = 1; i < stages.size(); i++) {
                    // A result of 0 or less is still a value (a score, a coordinate, a distance); only an error stops the pipe.
                    if (previous.halt() != null || previous.result().errored()) {
                        return previous;
                    }
                    Node stage = stages.get(i);
                    Piped piped;
                    try {
                        piped = piped(previous.result());
                    } catch (PipeException e) {
                        runner.reportError(label(firstCommand(stage)) + "nothing to pipe from /" + previous.lastCommand());
                        return new Outcome(ExecResult.failure(), previous.lastCommand());
                    }
                    previous = node(stage, piped, i < stages.size() - 1 || feedsPipe);
                }
                return previous;
            } finally {
                noPause = outer;
            }
        }
```

`command`: three changes.
1. The alias branch:

```java
                if (alias.isPresent()) {
                    Outcome called = callAlias(command, word, alias.get(), input, feedsPipe);
                    return finish(called.result(), command).halted(called.halt());
                }
```

2. The expansion `catch`:

```java
                } catch (ExpansionException e) {
                    expansionFailed = true;
                    if (e instanceof ChainStopped) {
                        // A sleep refused inside $( … ) has said why; the chain stops here.
                        return finish(ExecResult.failure(), command).halted(Halt.stop());
                    }
                    runner.reportError(label(command) + e.getMessage());
                    return finish(ExecResult.failure(), command);
                }
```

3. `return finish(each(command, text, input, feedsPipe), command);` becomes `return each(command, text, input, feedsPipe);`; after the budget check insert

```java
            if (call.kind() == Builtins.Kind.SLEEP) {
                return sleep(command, text);
            }
```

and the switch's last case becomes `case EACH, SLEEP -> throw new IllegalStateException(call.kind() + " is handled above");`.

Add `sleep` and replace `background` (from Task 1):

```java
        /** sleep TIME: pauses the chain (spec C3 §2); a sleep that can't pause stops it. */
        private Outcome sleep(Node.Command command, String text) {
            int ticks;
            try {
                if (noPause != null) {
                    throw new ExpansionException("sleep can't run " + noPause);
                }
                ticks = SleepTime.ticks(text);
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return finish(ExecResult.failure(), command).halted(Halt.stop());
            }
            return finish(new ExecResult(true, false, ticks, List.of()), command).halted(Halt.pause(ticks, label(command)));
        }

        /** body &: if the body pauses, its waiting part becomes a job of its own and the chain carries on (spec C3 §3). */
        private Outcome background(Node.Background background, Piped input, boolean feedsPipe) {
            Outcome body = node(background.body(), input, feedsPipe);
            Halt halt = body.halt();
            if (halt != null && halt.stopped()) {
                return body;
            }
            if (halt != null) {
                try {
                    context.jobs().queue(new Continuation(detached(halt.frames())), halt.delay(), ChainFormatter.toSh(background.body()));
                } catch (ExpansionException e) {
                    runner.reportError(halt.label() + e.getMessage());
                    return new Outcome(ExecResult.failure(), body.lastCommand(), Halt.stop());
                }
            }
            state.lastStatus = 0;
            return new Outcome(new ExecResult(true, 1, List.of()), body.lastCommand());
        }
```

`each` now returns an `Outcome` and finishes itself:

```java
        private Outcome each(Node.Command command, String body, Piped input, boolean feedsPipe) {
            if (body.isBlank()) {
                runner.reportError(label(command) + "each needs a command");
                return finish(ExecResult.failure(), command);
            }
            if (input.items().isEmpty()) {
                // Nothing to do (e.g. a grep that matched nothing): a quiet failure, like bash's exit status.
                return finish(new ExecResult(false, false, 0, List.of(), new PipeValue.Items(List.of())), command);
            }
            Node.Command run = new Node.Command(body.strip());
            state.numbers.put(run, state.numbers.get(command));
            boolean allSucceeded = true;
            int succeeded = 0;
            List<String> collected = new java.util.ArrayList<>();
            String outer = noPause;
            noPause = "inside each";
            try {
                for (String item : input.items()) {
                    Outcome outcome = command(run, new Piped(PipeValue.of(item), input.result(), List.of(item)), feedsPipe);
                    if (outcome.halt() != null) {
                        return finish(ExecResult.failure(), command).halted(outcome.halt());
                    }
                    ExecResult result = outcome.result();
                    if (result.success()) {
                        succeeded++;
                    } else {
                        allSucceeded = false;
                    }
                    if (!result.errored()) {
                        try {
                            collected.add(piped(result).value().asArgument());
                        } catch (PipeException e) {
                            // A run without a value adds nothing.
                        }
                    }
                    if (budgetExceeded || expansionFailed) {
                        // Stop rather than report the same refusal once per item.
                        break;
                    }
                }
            } finally {
                noPause = outer;
            }
            return finish(new ExecResult(allSucceeded && succeeded > 0, false, succeeded, List.of(), new PipeValue.Items(collected)), command);
        }
```

`substitute`: replace the line that runs the inner chain:

```java
                Outcome outcome = new Run(new RunState(innerNumbers, false, state.positionals, state.level), "inside $(…)")
                        .node(node, null, true);
                if (outcome.halt() != null) {
                    throw new ChainStopped();
                }
                ExecResult result = outcome.result();
```

`callAlias` returns an `Outcome`:

```java
        private Outcome callAlias(Node.Command command, String name, String body, Piped input, boolean feedsPipe) {
            String rest = command.text().substring(name.length());
            // A piped value fills placeholders in the call's arguments if there are any; otherwise it goes to the body.
            boolean placesInput = input != null && Expander.scan(rest, true).stream()
                    .anyMatch(reference -> reference.kind() == Expander.Kind.PLACEHOLDER);
            Arguments words;
            try {
                Expander.Expansion expansion = Expander.expandParts(rest, variables, state.lastStatus, this::substitute,
                        placesInput ? new Expander.PipeInput(input.value(), input.result()) : null, state.positionals);
                List<Words.Word> spans = Words.spans(expansion.join(false));
                words = new Arguments(spans.stream().map(Words.Word::text).toList(),
                        spans.stream().map(word -> expansion.untrustedOverlaps(word.start(), word.end())).toList());
            } catch (ExpansionException e) {
                expansionFailed = true;
                if (e instanceof ChainStopped) {
                    return new Outcome(ExecResult.failure(), command.text(), Halt.stop());
                }
                runner.reportError(label(command) + e.getMessage());
                return new Outcome(ExecResult.failure(), command.text());
            }
            Node node;
            try {
                node = NodeParser.parse(body, NodeParser.Mode.SH, Integer.MAX_VALUE);
            } catch (ChainSyntaxException e) {
                runner.reportError(label(command) + "alias " + name + ": " + e.getMessage());
                return new Outcome(ExecResult.failure(), command.text());
            }
            if (!words.words().isEmpty() && !Expander.hasPositionals(body)) {
                node = appendArguments(node);
            }
            Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
            if (scope.isEmpty()) {
                runner.reportError(label(command) + "aliases nested deeper than " + maxDepth);
                return new Outcome(ExecResult.failure(), command.text());
            }
            try (DepthGuard.Scope ignored = scope.get()) {
                if (!DepthGuard.spend(maxTotalCommands)) {
                    budgetExceeded = true;
                    runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                    return new Outcome(ExecResult.failure(), command.text());
                }
                Map<Node.Command, Integer> innerNumbers = new IdentityHashMap<>();
                number(node, innerNumbers);
                // Inside a pipe, $( … ) or each, the body can't pause either.
                return new Run(new RunState(innerNumbers, false, words, state.level + 1), noPause)
                        .node(node, placesInput ? null : input, feedsPipe);
            }
        }
```

`finish`:

```java
        private Outcome finish(ExecResult result, Node.Command command) {
            state.lastStatus = result.success() ? 0 : 1;
            return new Outcome(result, command.text());
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (256 + 10 = 266).

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Pause chains at sleep and resume them; & detaches waiting parts"
```

---

### Task 4: `jobs` and `kill %N`

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/exec/Jobs.java`, `src/main/java/net/slk/bashcraft/exec/Builtins.java`, `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`, `src/main/java/net/slk/bashcraft/exec/AliasTable.java`, `src/test/java/net/slk/bashcraft/exec/FakeJobs.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`, `BuiltinsTest.java`, `AliasTableTest.java`

**Interfaces:**
- Consumes: `Jobs`, `FakeJobs`, `start(...)` test helper (Task 3).
- Produces: `Jobs.listing() → List<String>`, `Jobs.kill(int number) throws ExpansionException` (message `no job %N`); `Builtins.Kind.JOBS`, `Builtins.Kind.KILL` (only for `kill %…`).

- [ ] **Step 1: Write the failing tests**

In `FakeJobs.java` add:

```java
    final List<String> lines = new ArrayList<>();
    final java.util.Set<Integer> live = new java.util.TreeSet<>();

    @Override
    public List<String> listing() {
        return lines;
    }

    @Override
    public void kill(int number) throws ExpansionException {
        if (!live.remove(number)) {
            throw new ExpansionException("no job %" + number);
        }
    }
```

Append to `ChainExecutorTest.java`:

```java
    @Test
    void jobsListsWaitingChains() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        jobs.lines.addAll(List.of("%1  40t  echo hi ; sleep 40 ; echo bye", "%2  5t  sleep 5"));
        ExecResult listed = start(r, jobs, "jobs");
        assertEquals(jobs.lines, r.echoed);
        assertEquals(2, listed.result());
        assertTrue(start(new FakeRunner(), jobs, "jobs | grep sleep 5").success(), "pipeable");
        FakeRunner bad = new FakeRunner();
        start(bad, jobs, "jobs now");
        assertEquals(List.of("jobs takes no arguments"), bad.errors);
    }

    @Test
    void killCancelsJobsByNumber() throws Exception {
        FakeRunner r = new FakeRunner();
        FakeJobs jobs = new FakeJobs();
        jobs.live.addAll(List.of(1, 2));
        start(r, jobs, "kill %1 %7 && say no ; kill %2 ; kill 2 ; kill %x");
        assertEquals(List.of("[1] no job %7", "[5] kill needs %N, e.g. kill %1"), r.errors);
        assertEquals(List.of("kill 2"), r.ran, "kill without % is vanilla's");
        assertTrue(jobs.live.isEmpty());
    }
```

Append to `BuiltinsTest.java`:

```java
    @Test
    void killIsBuiltinOnlyWithPercent() {
        assertEquals(new Builtins.Call(Builtins.Kind.KILL, "", "%1 %2"), Builtins.classify("kill %1 %2"));
        assertEquals(new Builtins.Call(Builtins.Kind.JOBS, "", ""), Builtins.classify("jobs"));
        assertEquals(Builtins.Kind.NONE, Builtins.classify("kill @e[type=zombie]").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("kill").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("kill 2").kind());
    }
```

In `AliasTableTest.aliasNamesCantShadowCommandsOrBuiltins`, add:

```java
        assertEquals("alias name jobs is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "jobs", "say")).getMessage());
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: compilation FAIL — `method does not override or implement a method from a supertype` (FakeJobs) / `Kind.KILL`.

- [ ] **Step 3: Implement**

`Jobs.java` — add to the interface:

```java
    /** {@code %N  <ticks left>t  <text>} lines for the jobs this chain's source may see. */
    java.util.List<String> listing();

    /** Cancels job {@code number}; throws {@code no job %N} when it doesn't exist or isn't this source's to cancel. */
    void kill(int number) throws ExpansionException;
```

and to `NONE`:

```java
        @Override
        public java.util.List<String> listing() {
            return java.util.List.of();
        }

        @Override
        public void kill(int number) throws ExpansionException {
            throw new ExpansionException("no job %" + number);
        }
```

`Builtins.java`: enum gains `JOBS, KILL` (before `NONE`); `Kind.JOBS` joins the word loop's list; add

```java
    private static final Pattern KILL = Pattern.compile("kill\\s+(%.*)", Pattern.DOTALL);
```

and, before `return new Call(Kind.NONE, "", command);`:

```java
        // Only kill %N is BashCraft's: any other kill is vanilla's.
        Matcher kill = KILL.matcher(command);
        if (kill.matches()) {
            return new Call(Kind.KILL, "", kill.group(1));
        }
```

`AliasTable.BUILTIN_WORDS`: add `"jobs"`.

`ChainExecutor.Run.command` switch: add

```java
                case JOBS -> jobs(command, text, options);
                case KILL -> kill(command, text);
```

and to `Run`:

```java
        /** jobs: one line per waiting chain this source may see, pipeable (spec C3 §4). */
        private ExecResult jobs(Node.Command command, String arguments, RunOptions options) {
            if (!arguments.isBlank()) {
                runner.reportError(label(command) + "jobs takes no arguments");
                return ExecResult.failure();
            }
            List<String> lines = context.jobs().listing();
            lines.forEach(line -> runner.echo(line, options));
            return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
        }

        /** kill %N…: cancels waiting chains; succeeds when every named one was cancelled, result = how many were. */
        private ExecResult kill(Node.Command command, String arguments) {
            int killed = 0;
            int failed = 0;
            for (String word : arguments.strip().split("\\s+")) {
                if (!word.matches("%\\d{1,9}")) {
                    runner.reportError(label(command) + "kill needs %N, e.g. kill %1");
                    failed++;
                    continue;
                }
                try {
                    context.jobs().kill(Integer.parseInt(word.substring(1)));
                    killed++;
                } catch (ExpansionException e) {
                    runner.reportError(label(command) + e.getMessage());
                    failed++;
                }
            }
            return new ExecResult(failed == 0, failed > 0, killed, List.of());
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (266 + 3 = 269).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add the jobs and kill %N builtins"
```

---

### Task 5: Waiting chains in Minecraft

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mc/ServerJobs.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftTimeCheck.java`, `src/gametest/resources/data/bashcraft-test/function/sleepy.mcfunction`
- Modify: `src/main/java/net/slk/bashcraft/command/ShCommand.java`, `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/gametest/resources/fabric.mod.json`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`, `README.md`

**Interfaces:**
- Consumes: `JobTable` (Task 2), `Jobs`/`Continuation`/`ChainExecutor.resume`/`ShellContext(…, Jobs)` (Tasks 3–4), `Sources.typist`, `HistoryBook`, `StorageVariables`, `StorageAliases`, `MinecraftCommandRunner`, `TeeSource`.
- Produces: `ServerJobs.forSource(CommandSourceStack) → Jobs`, `ServerJobs.tick(MinecraftServer)`, `ServerJobs.forget(UUID)`, `ServerJobs.clear()`.

- [ ] **Step 1: Add the failing game check**

`sleepy.mcfunction`:

```
sh sleep 10 ; setblock 5 120 20 minecraft:stone
setblock 4 120 20 minecraft:stone
```

`BashcraftTimeCheck.java`:

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** sleep, &, jobs and kill end to end (spec C3 §9). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftTimeCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 20 15 120 20 minecraft:air");

            server.runCommand("sh setblock 0 120 20 minecraft:stone ; sleep 20 ; setblock 1 120 20 minecraft:stone");
            check(stone(server, 0) && !stone(server, 1), "a chain pauses at sleep");
            context.waitTicks(25);
            check(stone(server, 1), "the rest of the chain runs after the sleep");

            server.runCommand("sh (sleep 10 ; setblock 3 120 20 minecraft:stone) & setblock 2 120 20 minecraft:stone");
            check(stone(server, 2) && !stone(server, 3), "& runs what follows at once");
            context.waitTicks(15);
            check(stone(server, 3), "the backgrounded part runs after its sleep");

            server.runCommand("function bashcraft-test:sleepy");
            check(stone(server, 4) && !stone(server, 5), "a function goes on past a line that paused");
            context.waitTicks(15);
            check(stone(server, 5), "the paused line finishes later");

            server.runCommand("sh sh sleep 10 \\; setblock 12 120 20 minecraft:stone | grep x ; setblock 13 120 20 minecraft:stone");
            check(stone(server, 13) && !stone(server, 12), "a nested sh that sleeps returns at once, even in a pipe");
            context.waitTicks(15);
            check(stone(server, 12), "the nested chain finishes as its own job");

            server.runCommand("summon minecraft:armor_stand 0 121 22 {Tags:[\"sleeper\"],NoGravity:1b}");
            server.runCommand("execute as @e[tag=sleeper] run sh sleep 10 ; setblock 14 120 20 minecraft:stone");
            server.runCommand("kill @e[tag=sleeper]");
            context.waitTicks(15);
            check(!stone(server, 14), "a chain whose entity is gone ends");

            server.runCommand("tick freeze");
            server.runCommand("sh sleep 5 ; setblock 11 120 20 minecraft:stone");
            context.waitTicks(10);
            check(!stone(server, 11), "a frozen game pauses sleeps");
            server.runCommand("tick unfreeze");
            context.waitTicks(10);
            check(stone(server, 11), "sleeps go on when the game runs again");

            server.runCommand("sh sleep 30 ; setblock 6 120 20 minecraft:stone");
            server.runCommand("sh kill %1");
            context.waitTicks(35);
            check(!stone(server, 6), "kill %1 cancels a waiting chain");

            // Typed by the (non-op) test player: their job (%1) resumes as them; they can't cancel the server's (%2).
            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard players enable @a t1");
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 20 ; trigger t1 set 3"));
            context.waitTicks(3);
            server.runCommand("sh sleep 40 ; setblock 7 120 20 minecraft:stone");
            context.runOnClient(client -> client.getConnection().sendCommand("sh kill %2"));
            context.waitTicks(50);
            server.runCommand("execute if score @p t1 matches 3 run setblock 8 120 20 minecraft:stone");
            check(stone(server, 8), "a player's chain resumes as that player");
            check(stone(server, 7), "a player can't cancel another's job");

            // An operator's waiting chain has their permission only while they keep it.
            server.runOnServer(s -> s.getPlayerList().op(player(s).nameAndId()));
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 10 ; setblock 9 120 20 minecraft:stone"));
            context.waitTicks(15);
            check(stone(server, 9), "an operator's waiting chain keeps their powers");
            context.runOnClient(client -> client.getConnection().sendCommand("sh sleep 20 ; setblock 10 120 20 minecraft:stone"));
            context.waitTicks(3);
            server.runOnServer(s -> s.getPlayerList().deop(player(s).nameAndId()));
            context.waitTicks(25);
            check(!stone(server, 10), "a waiting chain loses its powers after a de-op");
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 20)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftTimeCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json` (after `BashcraftAliasCheck`).

In `BashcraftChatCheck.java`, insert before `context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));`:

```java
            context.runOnClient(client -> ChatProbe.open(client, "/sh echo a ; sleep 1s & jobs ; kill %1"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "sleep, &, jobs and kill %N are not shown as errors");
```

(This check already passes after Task 4, since the client colours through `Builtins.classify`; it guards the client against regressions.)

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|BUILD"`
Expected: `Check failed: the rest of the chain runs after the sleep` (`/sh` has no job queue yet: `sleep is not available here`).

- [ ] **Step 3: Write `ServerJobs.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.Bashcraft;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.Continuation;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.JobTable;
import net.slk.bashcraft.exec.Jobs;
import net.slk.bashcraft.exec.ShellContext;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's waiting chains (spec C3 §5–6). A chain a player typed wakes as that player is now (position, dimension,
 * permission); any other chain as the source it started with. Server thread only.
 */
public final class ServerJobs {
    /** A waiting chain and how to find its source again: the player who typed it, else the source it started with. */
    private record Waiting(Continuation continuation, UUID typist, CommandSourceStack saved) {}

    private static JobTable<Waiting> table = newTable();

    private ServerJobs() {}

    /** Where a chain that {@code source} starts puts its waiting parts. */
    public static Jobs forSource(CommandSourceStack source) {
        return new View(source, null);
    }

    /** Counts a tick (only while the game runs normally, so /tick freeze pauses sleeps) and wakes the jobs now due. */
    public static void tick(MinecraftServer server) {
        if (!server.tickRateManager().runsNormally()) {
            return;
        }
        table.tick();
        for (Optional<JobTable.Due<Waiting>> due = table.nextDue(); due.isPresent(); due = table.nextDue()) {
            wake(server, due.get());
        }
    }

    /** A player left: the chains they typed end. Chains saved with them as the source end when they would wake. */
    public static void forget(UUID player) {
        table.cancelOwner(player.toString());
    }

    /** Jobs are never kept across a restart. */
    public static void clear() {
        table = newTable();
    }

    private static JobTable<Waiting> newTable() {
        BashcraftConfig config = BashcraftConfig.get();
        return new JobTable<>(config.maxJobs, config.maxJobsPerPlayer, config.maxSleepTicks);
    }

    private static void wake(MinecraftServer server, JobTable.Due<Waiting> due) {
        Optional<CommandSourceStack> source = sourceOf(server, due.payload());
        View jobs = new View(source.orElse(null), due);
        try {
            source.ifPresent(stack -> resume(stack, jobs, due.payload().continuation()));
        } catch (RuntimeException e) {
            // An exception out of a tick handler would stop the server; the broken job just ends.
            Bashcraft.LOGGER.error("BashCraft: waiting chain %{} failed", due.number(), e);
        } finally {
            if (!jobs.requeued) {
                table.finish(due.number());
            }
        }
    }

    private static void resume(CommandSourceStack source, View jobs, Continuation continuation) {
        BashcraftConfig config = BashcraftConfig.get();
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            return;
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            ShellContext shell = new ShellContext(StorageVariables.of(source), StorageAliases.of(source),
                    Sources.typist(source).map(HistoryBook::of).orElse(null), jobs);
            new ChainExecutor(new MinecraftCommandRunner(source), shell, config.swallowPipedOutput, config.maxTotalCommands,
                    config.maxDepth).resume(continuation);
        }
    }

    /** The player as they are now, or the saved source; empty when the player left or the source's entity is gone. */
    private static Optional<CommandSourceStack> sourceOf(MinecraftServer server, Waiting waiting) {
        if (waiting.typist() != null) {
            return Optional.ofNullable(server.getPlayerList().getPlayer(waiting.typist())).map(ServerPlayer::createCommandSourceStack);
        }
        CommandSourceStack saved = waiting.saved();
        return saved.getEntity() != null && saved.getEntity().isRemoved() ? Optional.empty() : Optional.of(saved);
    }

    /**
     * The source a chain no player typed wakes as: past any pipe tees (so output reaches the command block or the
     * console) and without a result callback (whoever ran /sh had its result long ago).
     */
    private static CommandSourceStack saved(CommandSourceStack source) {
        CommandSource origin = ((CommandSourceStackAccessor) source).bashcraft$source();
        while (origin instanceof TeeSource tee) {
            origin = tee.target();
        }
        return source.withSource(origin).withCallback(CommandResultCallback.EMPTY);
    }

    /** One chain's view of the table: who owns the jobs it queues, and which jobs it may see and cancel. */
    private static final class View implements Jobs {
        private final CommandSourceStack source;
        /** The job being resumed, or null for a chain just started. */
        private final JobTable.Due<Waiting> resuming;
        private boolean requeued;

        View(CommandSourceStack source, JobTable.Due<Waiting> resuming) {
            this.source = source;
            this.resuming = resuming;
        }

        @Override
        public void queue(Continuation continuation, int delay, String text) throws ExpansionException {
            Optional<ServerPlayer> typist = Sources.typist(source);
            table.add(owner(), typist.map(player -> player.getName().getString()).orElse(source.getTextName()), text, delay,
                    new Waiting(continuation, typist.map(ServerPlayer::getUUID).orElse(null), typist.isPresent() ? null : saved(source)));
        }

        @Override
        public void requeue(Continuation continuation, int delay) throws ExpansionException {
            Waiting waiting = resuming.payload();
            table.requeue(resuming.number(), delay, new Waiting(continuation, waiting.typist(), waiting.saved()));
            requeued = true;
        }

        @Override
        public List<String> listing() {
            return table.listing(owner(), operator());
        }

        @Override
        public void kill(int number) throws ExpansionException {
            if (!table.kill(number, owner(), operator())) {
                throw new ExpansionException("no job %" + number);
            }
        }

        /** The typist's UUID; null (the server) for chains no player typed. */
        private String owner() {
            return Sources.typist(source).map(player -> player.getUUID().toString()).orElse(null);
        }

        private boolean operator() {
            return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
        }
    }
}
```

- [ ] **Step 4: Wire it in**

`ShCommand.execute` — the shell context becomes:

```java
            ShellContext shell = new ShellContext(StorageVariables.of(source), StorageAliases.of(source),
                    Sources.typist(source).map(HistoryBook::of).orElse(null), ServerJobs.forSource(source));
```

(import `net.slk.bashcraft.mc.ServerJobs`).

`Bashcraft.onInitialize` — replace the `DISCONNECT` line and extend `SERVER_STARTING`; add the tick and stop hooks (import `net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents`, `net.slk.bashcraft.mc.ServerJobs`, `java.util.UUID`):

```java
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID player = handler.getPlayer().getUUID();
            HistoryBook.forget(player);
            server.execute(() -> ServerJobs.forget(player));
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerJobs::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerJobs.clear());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ServerJobs.clear();
            if (BashcraftConfig.get().transparentMode && !TransparentHooks.applied()) {
                LOGGER.warn("BashCraft: transparent mode hooks did not apply to this Minecraft version; use /sh instead");
            }
        });
```

- [ ] **Step 5: Run everything**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 6: Update `README.md`**

The C4 aliases paragraph planned for the README never landed (commit `ea0cc8d` didn't include it). Add both sections before `### Without /sh`:

````markdown
**Aliases and history:**

```
/sh alias home = tp @s 0 80 0
/sh alias heal = 'effect give $1 minecraft:regeneration 5 ${2:-1} && say healed $1'
/sh home && heal @s 3
/sh !!          /sh !tp          /sh history | grep locate
```

An alias is a named chain (quote it to include `&&`, `;`, `|`). `$1`…`$9`, `$@` (all), `$#` (count) and
`${N:-default}` are its arguments; with none of those, arguments are appended to its last command. `alias` lists
aliases, `unalias NAME` removes one. Your aliases only run in chains you type; operators can define
`alias global.NAME = …`, which works everywhere (functions, command blocks). Alias names can't be existing commands.
`/sh !!` reruns your last line and `/sh !PREFIX` the last one starting with PREFIX; `history` lists this session's
lines (kept until you leave).

**Time:** `sleep` pauses a chain, and `&` lets part of one wait on its own.

```
/sh echo 3 ; sleep 20 ; echo 2 ; sleep 20 ; echo 1 ; sleep 20 ; title @s title "go"
/sh (sleep 1200 ; title @s title "time's up") & echo timer started
/sh alias tick = 'effect give @s minecraft:speed 2 ; sleep 2s ; tick'
/sh jobs          /sh kill %1
```

`sleep TIME` takes `/schedule`'s units: ticks by default, or `20t`, `1s`, `0.5s`, `1d` (at least 1 tick, at most an
hour by default). The server never waits: `/sh` returns at once (reporting the `sleep`), a function goes on to its
next line, and the rest of the chain runs on a later tick. `X & Y` runs `X`, and if `X` sleeps, the rest of `X` waits
on its own while `Y` runs at once; as in bash, `&` takes the whole `&&`/`||` list before it. In `/sh` a lone `&` is
an operator, so write `\&` for a literal one (`\&&` is still a literal `&&`); without `/sh`, `&` needs a `/` after it
like the other operators. `sleep` can't be used inside a pipe, `$( … )` or `each`, and a `sleep` that can't wait
stops the chain there.

`jobs` lists your waiting chains (operators see everyone's) and `kill %N` cancels one (any other `kill` is vanilla's).
A chain you typed carries on as you are when it wakes — where you stand and the permissions you have then — and ends
if you leave. Other chains (command blocks, functions, the console) carry on as they started. Waiting chains follow
`/tick freeze` and `/tick rate` like `/schedule`, and are not kept across restarts.
````

Update the **Quoting** line's escapes to: `` `\&`, `\&&`, `\|`, `\;`, `\(`, `\)` are literal ``.

In **Config**, add `maxSleepTicks` (72000), `maxJobsPerPlayer` (8), `maxJobs` (64) to the list.

- [ ] **Step 7: Dedicated server check**

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "sh say one ; sleep 40 ; say two"; sleep 1; echo "sh jobs"; sleep 3; echo "sh (sleep 20 ; say three) & say four"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-c3.log 2>&1
grep -E "\[Server\]|%1|Done \(" build/server-c3.log | head
grep -E "WARN|ERROR" build/server-c3.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```

Expected: `[Server] one`, a `%1  …t  Server: say one ; sleep 40 ; say two` line, then `[Server] two`, `[Server] four` before `[Server] three`, `Done (`, `no bashcraft warnings`.

- [ ] **Step 8: Commit**

```bash
git add src README.md
git commit -m "Run waiting chains on the server: wake, resume, cancel; document time and aliases"
```
