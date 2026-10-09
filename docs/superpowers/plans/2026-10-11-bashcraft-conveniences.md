# BashCraft Conveniences Implementation Plan (Sub-project C4: aliases and history)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Personal and global aliases (named chains with positional arguments) and per-player history (`!!`, `!prefix`, `history`), with alias calls recognised by the client companion.

**Architecture:** Pure parts: positional references in `Expander` (only active inside an alias body), `History`, `Aliases`/`AliasTable` (scope rules, names, limits), `Words` (argument splitting), and executor support (alias builtins, alias calls as nested chains with positionals, `history` builtin) through a `ShellContext` (variables, aliases, history). Minecraft parts: `Sources.typist` (who typed a chain), `StorageAliases` (command storage `bashcraft:aliases`), `HistoryBook` (in-memory per player), `/sh` `!` resolution and recording, recording in transparent mode, and an `AliasNamesPayload` so the client can colour alias calls.

**Tech Stack:** as before (Java 25, Minecraft 26.3, Fabric incl. Networking API, JUnit 5.14.2, client game tests headless on Xvfb).

**Spec:** `docs/superpowers/specs/2026-10-11-bashcraft-conveniences-design.md`

## Global Constraints

- All earlier constraints (`JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`; pure packages import no `net.minecraft.*`/`net.fabricmc.*`; `BashCraft: ` prefix; no `Co-Authored-By`; Synergy read-only; server port 25569; game tests headless automatically).
- Alias names `[a-z_][a-z0-9_-]{0,31}`; builtin words that can't be aliases: `alias unalias echo unset grep store each surface history sh`; 64 aliases per set; body ≤ 1024 chars; history 50 lines.
- Error texts: `alias needs NAME = BODY`, `alias name NAME is already a command`, `alias body: <parse error>`, `alias body longer than 1024 characters`, `too many aliases (max 64)`, `global aliases need operator permission`, `personal aliases can only be defined in chains you type`, `aliases are not available here`, `aliases nested deeper than N`, `$N is not set`, `history is only for chains you type`, `no history matching X`.
- **Plan decision:** positional references (`$1`…`$9`, `$@`, `$#`, `${N:-default}`) are recognised only while an alias body runs; elsewhere `$1` stays literal text (C1's rule that `$5` is literal still holds outside aliases).
- **Plan decision:** the append rule rewrites the body's rightmost command to end with ` $@`, so appended words are inserted as values (one expansion, C1 rules), never spliced as text.
- **Plan decision:** a value piped into an alias call goes to the body's first command (as with groups).

## Review Focus

1. **`$1` in ordinary chains** (`say costs $1`) must stay literal outside aliases. → Task 1 test `positionalsAreLiteralOutsideAliases`.
2. **Quoted alias arguments** (`c a 'b c'`) must stay one word. → Task 2 test `wordsKeepQuotedSpans`.
3. **A recursive alias** must stop at `maxDepth`. → Task 3 test `recursiveAliasesStop`.
4. **A player's personal alias in an elevated chain** must never run. → Task 3 test `elevatedChainsSeeOnlyGlobalAliases`.
5. **Alias names that are commands or builtins** must be refused. → Task 2 test `aliasNamesCantShadowCommandsOrBuiltins`.

---

### Task 1: Positional references and history

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/exec/Expander.java`
- Create: `src/main/java/net/slk/bashcraft/exec/History.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ExpanderTest.java`, `src/test/java/net/slk/bashcraft/exec/HistoryTest.java`

**Interfaces:**
- Produces: `Expander.Kind.POSITIONAL`; `Expander.expandParts(String text, Variables variables, int lastStatus, Substituter substituter, PipeInput input, List<String> positionals)` (positionals null = not in an alias); the 5-argument `expandParts` delegates with `null`; `Expander.hasPositionals(String text)`; `History` with `record(String)`, `Optional<String> resolve(String reference)`, `List<String> lines()`, `MAX = 50`.

- [ ] **Step 1: Write the failing tests**

Append to `ExpanderTest.java` (before the final `}`):

```java
    private static String positional(String text, List<String> words) throws ExpansionException {
        return Expander.expandParts(text, vars(), 0, chain -> "", null, words).join(false);
    }

    @Test
    void positionalReferences() throws Exception {
        assertEquals("a b c a b c 2 d a", positional("$1 $2 $@ $# ${3:-d} ${1:-x}", List.of("a", "b c")));
        assertEquals("0", positional("$#", List.of()));
        assertEquals("$3 is not set", assertThrows(ExpansionException.class, () -> positional("say $3", List.of("a"))).getMessage());
    }

    @Test
    void positionalsAreLiteralOutsideAliases() throws Exception {
        assertEquals("say costs $1, $@ and $#", expand("say costs $1, $@ and $#", vars()));
        assertTrue(Expander.hasPositionals("say $1"));
        assertTrue(Expander.hasPositionals("say ${2:-x} && b"));
        assertFalse(Expander.hasPositionals("say $x '$1'"));
    }
```
Add `import java.util.List;` if missing.

`HistoryTest.java`:

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HistoryTest {
    @Test
    void resolvesLastAndPrefix() {
        History history = new History();
        history.record("tp @s 0 80 0");
        history.record("locate structure #minecraft:village");
        history.record("say hi");
        assertEquals(Optional.of("say hi"), history.resolve("!!"));
        assertEquals(Optional.of("tp @s 0 80 0"), history.resolve("!tp"));
        assertEquals(Optional.of("locate structure #minecraft:village"), history.resolve("!locate"));
        assertEquals(Optional.empty(), history.resolve("!nope"));
        assertEquals(Optional.empty(), new History().resolve("!!"));
    }

    @Test
    void keepsTheLast50AndIgnoresBlanks() {
        History history = new History();
        for (int i = 0; i < 60; i++) {
            history.record("say " + i);
        }
        history.record("   ");
        assertEquals(History.MAX, history.lines().size());
        assertEquals("say 10", history.lines().getFirst());
        assertEquals("say 59", history.lines().getLast());
        assertEquals(List.of(), new History().lines());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class History` / `expandParts(...6 args)`.

- [ ] **Step 3: Write `History.java`**

```java
package net.slk.bashcraft.exec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** One player's recent chains for {@code !!}, {@code !prefix} and {@code history} (spec C4 §4). */
public final class History {
    public static final int MAX = 50;

    private final Deque<String> lines = new ArrayDeque<>();

    public synchronized void record(String line) {
        if (line.isBlank()) {
            return;
        }
        lines.addLast(line.strip());
        while (lines.size() > MAX) {
            lines.removeFirst();
        }
    }

    /** {@code !!}: the last line; {@code !PREFIX}: the most recent line starting with PREFIX. */
    public synchronized Optional<String> resolve(String reference) {
        if (reference.equals("!!")) {
            return Optional.ofNullable(lines.peekLast());
        }
        String prefix = reference.substring(1);
        for (Iterator<String> it = lines.descendingIterator(); it.hasNext(); ) {
            String line = it.next();
            if (line.startsWith(prefix)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}
```

- [ ] **Step 4: Add positionals to `Expander.java`**
- Change the `Kind` enum to `public enum Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, PLACEHOLDER, ESCAPED_PLACEHOLDER, POSITIONAL, BAD }`.
- Add the pattern next to `NAME`: `private static final Pattern POSITIONAL_BRACE = Pattern.compile("([1-9])(?::-(.*))?", Pattern.DOTALL);`
- Replace `public static List<Reference> scan(String text, boolean placeholders) {` with:

```java
    public static List<Reference> scan(String text, boolean placeholders) {
        return scan(text, placeholders, false);
    }

    /** With {@code positionals} (inside an alias body), {@code $1}…{@code $9}, {@code $@}, {@code $#}, {@code ${N:-d}} are references. */
    public static List<Reference> scan(String text, boolean placeholders, boolean positionals) {
```
- In the same method, change `Reference reference = reference(text, i);` to `Reference reference = reference(text, i, positionals);`.
- Change `private static Reference reference(String text, int i) {` to `private static Reference reference(String text, int i, boolean positionals) {`, and in its body:
  - right after the `if (next == '?') { … }` block, insert:

```java
        if (positionals && (next == '@' || next == '#' || (next >= '1' && next <= '9'))) {
            return new Reference(i, i + 2, Kind.POSITIONAL, String.valueOf(next));
        }
```
  - inside the `if (next == '{')` block, before `return new Reference(i, close < 0 ? text.length() : close + 1, Kind.BAD, "bad ${…}");`, insert:

```java
            if (positionals && close >= 0 && POSITIONAL_BRACE.matcher(text.substring(i + 2, close)).matches()) {
                return new Reference(i, close + 1, Kind.POSITIONAL, text.substring(i + 2, close));
            }
```
- Add after `scan`:

```java
    /** Whether an alias body uses positional references (if not, call arguments are appended to it). */
    public static boolean hasPositionals(String text) {
        return scan(text, false, true).stream().anyMatch(reference -> reference.kind() == Kind.POSITIONAL);
    }
```
- Replace the signature `public static Expansion expandParts(String text, Variables variables, int lastStatus, Substituter substituter,
                                        PipeInput input) throws ExpansionException {
        List<Reference> references = scan(text, input != null);` with:

```java
    public static Expansion expandParts(String text, Variables variables, int lastStatus, Substituter substituter,
                                        PipeInput input) throws ExpansionException {
        return expandParts(text, variables, lastStatus, substituter, input, null);
    }

    /** @param positionals the alias call's words while an alias body runs, else null */
    public static Expansion expandParts(String text, Variables variables, int lastStatus, Substituter substituter,
                                        PipeInput input, List<String> positionals) throws ExpansionException {
        List<Reference> references = scan(text, input != null, positionals != null);
```
- In the `switch (reference.kind())` of `expandParts`, before `default -> throw …`, add:

```java
                case POSITIONAL -> new Part(checked(positional(reference.body(), positionals), variables.elevated()), true);
```
- Add the helper:

```java
    private static String positional(String body, List<String> words) throws ExpansionException {
        if (body.equals("@")) {
            return String.join(" ", words);
        }
        if (body.equals("#")) {
            return Integer.toString(words.size());
        }
        Matcher m = POSITIONAL_BRACE.matcher(body);
        m.matches();
        int index = m.group(1).charAt(0) - '1';
        if (index < words.size()) {
            return words.get(index);
        }
        if (m.group(2) != null) {
            return m.group(2);
        }
        throw new ExpansionException("$" + m.group(1) + " is not set");
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (213 + 4 = 217).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add positional references for alias bodies and per-player history"
```

---

### Task 2: Alias definitions, words and the shell context

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/Aliases.java`, `AliasTable.java`, `Words.java`, `ShellContext.java`
- Modify: `src/main/java/net/slk/bashcraft/exec/Builtins.java`
- Test: `src/test/java/net/slk/bashcraft/exec/AliasTableTest.java`, `src/test/java/net/slk/bashcraft/exec/WordsTest.java`

**Interfaces:**
- Produces:
  - `interface Aliases { Optional<String> body(String name); void define(boolean global, String name, String body) throws ExpansionException; void remove(boolean global, String name) throws ExpansionException; List<String> listing(); Aliases NONE; }`
  - `AliasTable(Map<String,String> mine, Map<String,String> global, boolean minePreferred, boolean globalWritable, Predicate<String> isCommand)` implements `Aliases`; `MAX_ALIASES = 64`, `MAX_BODY = 1024`, `BUILTIN_WORDS`.
  - `Words.split(String text)` → `List<String>`.
  - `record ShellContext(Variables variables, Aliases aliases, History history)` (history null outside chains a player typed).
  - `Builtins.Kind` gains `ALIAS, UNALIAS, HISTORY`.

- [ ] **Step 1: Write the failing tests**

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AliasTableTest {
    private static AliasTable typed(Map<String, String> mine, Map<String, String> global) {
        return new AliasTable(new HashMap<>(mine), new HashMap<>(global), true, true, name -> name.equals("tp"));
    }

    @Test
    void personalFirstThenGlobal() throws Exception {
        AliasTable table = typed(Map.of("home", "say mine"), Map.of("home", "say global", "spawn", "tp @s 0 100 0"));
        assertEquals(Optional.of("say mine"), table.body("home"));
        assertEquals(Optional.of("tp @s 0 100 0"), table.body("spawn"));
        assertEquals(Optional.empty(), table.body("nope"));
        assertEquals(List.of("home = say mine", "global.home = say global", "global.spawn = tp @s 0 100 0"), table.listing());
    }

    @Test
    void elevatedSourcesSeeOnlyGlobal() throws Exception {
        AliasTable table = new AliasTable(new HashMap<>(Map.of("home", "say mine")), new HashMap<>(Map.of("home", "say global")), false, true, name -> false);
        assertEquals(Optional.of("say global"), table.body("home"));
        assertEquals("personal aliases can only be defined in chains you type",
                assertThrows(ExpansionException.class, () -> table.define(false, "x", "say")).getMessage());
        assertEquals(List.of("global.home = say global"), table.listing());
    }

    @Test
    void aliasNamesCantShadowCommandsOrBuiltins() {
        AliasTable table = typed(Map.of(), Map.of());
        assertEquals("alias name tp is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "tp", "say")).getMessage());
        assertEquals("alias name echo is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "echo", "say")).getMessage());
        assertEquals("alias name sh is already a command", assertThrows(ExpansionException.class, () -> table.define(true, "sh", "say")).getMessage());
    }

    @Test
    void permissionsAndLimits() throws Exception {
        AliasTable noOp = new AliasTable(new HashMap<>(), new HashMap<>(), true, false, name -> false);
        assertEquals("global aliases need operator permission", assertThrows(ExpansionException.class, () -> noOp.define(true, "g", "say")).getMessage());
        assertThrows(ExpansionException.class, () -> noOp.remove(true, "g"));
        AliasTable table = typed(Map.of(), Map.of());
        assertEquals("alias body longer than 1024 characters",
                assertThrows(ExpansionException.class, () -> table.define(false, "long", "a".repeat(1025))).getMessage());
        for (int i = 0; i < 64; i++) {
            table.define(false, "a" + i, "say");
        }
        assertEquals("too many aliases (max 64)", assertThrows(ExpansionException.class, () -> table.define(false, "extra", "say")).getMessage());
        table.define(false, "a0", "say again");
        table.remove(false, "a1");
        assertEquals(Optional.empty(), table.body("a1"));
    }
}
```

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordsTest {
    @Test
    void wordsKeepQuotedSpans() {
        assertEquals(List.of("a", "b c", "d"), Words.split("a 'b c'  \"d\""));
        assertEquals(List.of("{\"text\":\"x y\"}"), Words.split("{\"text\":\"x y\"}"));
        assertEquals(List.of(), Words.split("   "));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class AliasTable`.

- [ ] **Step 3: Write `Aliases.java`, `AliasTable.java`, `Words.java`, `ShellContext.java`**

```java
package net.slk.bashcraft.exec;

import java.util.List;
import java.util.Optional;

/** Alias definitions as a chain sees them (spec C4 §2–3). */
public interface Aliases {
    /** The body for {@code name}: the player's own aliases (only in a chain they typed), then global ones. */
    Optional<String> body(String name);

    void define(boolean global, String name, String body) throws ExpansionException;

    void remove(boolean global, String name) throws ExpansionException;

    /** {@code NAME = BODY} lines: own aliases (when visible), then {@code global.NAME = BODY}. */
    List<String> listing();

    Aliases NONE = new Aliases() {
        @Override
        public Optional<String> body(String name) {
            return Optional.empty();
        }

        @Override
        public void define(boolean global, String name, String body) throws ExpansionException {
            throw new ExpansionException("aliases are not available here");
        }

        @Override
        public void remove(boolean global, String name) throws ExpansionException {
            throw new ExpansionException("aliases are not available here");
        }

        @Override
        public List<String> listing() {
            return List.of();
        }
    };
}
```

```java
package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Two alias sets with the scope rules, name rules and limits (spec C4 §2). */
public final class AliasTable implements Aliases {
    public static final int MAX_ALIASES = 64;
    public static final int MAX_BODY = 1024;
    public static final Set<String> BUILTIN_WORDS = Set.of("alias", "unalias", "echo", "unset", "grep", "store", "each", "surface", "history", "sh");
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

    private final Map<String, String> mine;
    private final Map<String, String> global;
    private final boolean minePreferred;
    private final boolean globalWritable;
    private final Predicate<String> isCommand;

    /**
     * @param minePreferred  the chain was typed by the player whose set {@code mine} is
     * @param globalWritable the source may change global aliases (operator permission)
     * @param isCommand      whether a name is already a registered command
     */
    public AliasTable(Map<String, String> mine, Map<String, String> global, boolean minePreferred, boolean globalWritable,
                      Predicate<String> isCommand) {
        this.mine = mine;
        this.global = global;
        this.minePreferred = minePreferred;
        this.globalWritable = globalWritable;
        this.isCommand = isCommand;
    }

    @Override
    public Optional<String> body(String name) {
        if (minePreferred && mine.containsKey(name)) {
            return Optional.of(mine.get(name));
        }
        return Optional.ofNullable(global.get(name));
    }

    @Override
    public void define(boolean global, String name, String body) throws ExpansionException {
        if (!NAME.matcher(name).matches()) {
            throw new ExpansionException("alias needs NAME = BODY");
        }
        if (BUILTIN_WORDS.contains(name) || isCommand.test(name)) {
            throw new ExpansionException("alias name " + name + " is already a command");
        }
        if (body.length() > MAX_BODY) {
            throw new ExpansionException("alias body longer than " + MAX_BODY + " characters");
        }
        Map<String, String> target = target(global);
        if (!target.containsKey(name) && target.size() >= MAX_ALIASES) {
            throw new ExpansionException("too many aliases (max " + MAX_ALIASES + ")");
        }
        target.put(name, body);
    }

    @Override
    public void remove(boolean global, String name) throws ExpansionException {
        target(global).remove(name);
    }

    @Override
    public List<String> listing() {
        List<String> lines = new ArrayList<>();
        if (minePreferred) {
            new TreeMap<>(mine).forEach((name, body) -> lines.add(name + " = " + body));
        }
        new TreeMap<>(global).forEach((name, body) -> lines.add("global." + name + " = " + body));
        return lines;
    }

    private Map<String, String> target(boolean globalSet) throws ExpansionException {
        if (globalSet) {
            if (!globalWritable) {
                throw new ExpansionException("global aliases need operator permission");
            }
            return global;
        }
        if (!minePreferred) {
            throw new ExpansionException("personal aliases can only be defined in chains you type");
        }
        return mine;
    }
}
```

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;

import java.util.ArrayList;
import java.util.List;

/** Splits alias call arguments into words: whitespace separates, quoted spans stay whole (one quote pair removed). */
public final class Words {
    private Words() {}

    public static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(Builtins.unquote(word.toString()));
                    word.setLength(0);
                    inWord = false;
                }
                i++;
                continue;
            }
            inWord = true;
            if ((c == '"' || c == '\'') && Quotes.opensQuote(text, i)) {
                int close = Quotes.closingQuote(text, i);
                int end = close < 0 ? text.length() : close + 1;
                word.append(text, i, end);
                i = end;
                continue;
            }
            word.append(c);
            i++;
        }
        if (inWord) {
            words.add(Builtins.unquote(word.toString()));
        }
        return words;
    }
}
```

```java
package net.slk.bashcraft.exec;

/**
 * What a chain can reach besides the runner: its variables, aliases, and — only in a chain a player typed — that
 * player's history (null otherwise).
 */
public record ShellContext(Variables variables, Aliases aliases, History history) {
    public static ShellContext of(Variables variables) {
        return new ShellContext(variables, Aliases.NONE, null);
    }
}
```

- [ ] **Step 4: Extend `Builtins.java`**
- `public enum Kind { ASSIGN, UNSET, ECHO, GREP, STORE, EACH, SURFACE, NONE }` → `public enum Kind { ASSIGN, UNSET, ECHO, GREP, STORE, EACH, SURFACE, ALIAS, UNALIAS, HISTORY, NONE }`.
- `for (Kind kind : List.of(Kind.GREP, Kind.STORE, Kind.EACH, Kind.SURFACE)) {` → `for (Kind kind : List.of(Kind.GREP, Kind.STORE, Kind.EACH, Kind.SURFACE, Kind.ALIAS, Kind.UNALIAS, Kind.HISTORY)) {`.
- In `BuiltinsTest.pipeBuiltins`, the existing assertions stay valid.

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (217 + 5 = 222).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add alias tables, argument words and the shell context"
```

---

### Task 3: Aliases and history in the executor

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `new ChainExecutor(CommandRunner runner, ShellContext context, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth)`; the `Variables` constructor delegates with `ShellContext.of(variables)`.

- [ ] **Step 1: Add the failing tests** (append to `ChainExecutorTest.java`; add `import java.util.function.Predicate;` if needed)

```java
    private static ExecResult run(FakeRunner runner, ShellContext context, String chain, int maxDepth) throws Exception {
        return new ChainExecutor(runner, context, true, Integer.MAX_VALUE, maxDepth).execute(NodeParser.parse(chain, NodeParser.Mode.SH, 32));
    }

    private static ShellContext typedShell(AliasTable aliases, History history) {
        return new ShellContext(table(), aliases, history);
    }

    private static AliasTable aliases() {
        return new AliasTable(new HashMap<>(), new HashMap<>(), true, true, name -> name.equals("tp"));
    }

    @Test
    void defineAndCallAnAlias() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias home = tp @s 0 80 0 ; home", 8);
        assertEquals(List.of("tp @s 0 80 0"), r.ran);
    }

    @Test
    void aliasArgumentsAndDefaults() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null),
                "alias heal = 'effect give $1 minecraft:regeneration 5 ${2:-1} && say healed $1' ; heal @s 3 ; heal @p", 8);
        assertEquals(List.of("effect give @s minecraft:regeneration 5 3", "say healed @s",
                "effect give @p minecraft:regeneration 5 1", "say healed @p"), r.ran);
    }

    @Test
    void appendRuleAndCounts() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias g = gamemode ; g creative @s ; alias c = 'say $# $@' ; c a 'b c'", 8);
        assertEquals(List.of("gamemode creative @s", "say 2 a b c"), r.ran);
    }

    @Test
    void missingArgumentIsAnError() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias m = 'say $2' ; m a", 8);
        assertEquals(List.of("$2 is not set"), r.errors);
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void aliasDefinitionErrors() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias tp = say hi ; alias echo = say ; alias Bad = x ; alias b = 'say \"oops'", 8);
        assertEquals(List.of("[1] alias name tp is already a command", "[2] alias name echo is already a command",
                "[3] alias needs NAME = BODY", "[4] alias body: unclosed \" at col 5"), r.errors);
    }

    @Test
    void elevatedChainsSeeOnlyGlobalAliases() throws Exception {
        FakeRunner r = new FakeRunner();
        AliasTable shared = new AliasTable(new HashMap<>(Map.of("home", "say mine")), new HashMap<>(Map.of("home", "say global")), false, true, name -> false);
        run(r, new ShellContext(elevated(Map.of(), Map.of()), shared, null), "home", 8);
        assertEquals(List.of("say global"), r.ran);
    }

    @Test
    void recursiveAliasesStop() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias loop = loop ; loop", 3);
        assertEquals("aliases nested deeper than 3", r.errors.getFirst());
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void listUnaliasAndPipeIntoAnAlias() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), null), "alias a = say 1 ; alias global.b = say 2 ; alias ; unalias a ; a ; alias s = say ; echo 7 | s", 8);
        assertEquals(List.of("a = say 1", "global.b = say 2", "7"), r.echoed);
        assertEquals(List.of("a", "say 7"), r.ran);
    }

    @Test
    void historyBuiltin() throws Exception {
        History history = new History();
        history.record("say hi");
        FakeRunner r = new FakeRunner();
        run(r, typedShell(aliases(), history), "history", 8);
        assertEquals(List.of("say hi"), r.echoed);
        FakeRunner none = new FakeRunner();
        run(none, typedShell(aliases(), null), "history", 8);
        assertEquals(List.of("history is only for chains you type"), none.errors);
    }

    @Test
    void aliasArgumentsInElevatedChainsMustBePlain() throws Exception {
        FakeRunner r = new FakeRunner();
        AliasTable shared = new AliasTable(new HashMap<>(), new HashMap<>(), false, true, name -> false);
        run(r, new ShellContext(elevated(Map.of(), Map.of()), shared, null), "alias global.s = 'say $1' ; s 'a\"b'", 8);
        assertTrue(r.errors.getFirst().contains("a value from a player can only be one plain word or a list of numbers here"));
        assertTrue(r.ran.isEmpty());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.ChainExecutorTest'`
Expected: compilation FAIL — no `ChainExecutor(FakeRunner, ShellContext, …)` constructor.

- [ ] **Step 3: Update `ChainExecutor`**

Constructors and fields — replace the field `private final Variables variables;` with:

```java
    private final ShellContext context;
    private final Variables variables;
```
and replace the 5-argument `Variables` constructor with:

```java
    public ChainExecutor(CommandRunner runner, Variables variables, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this(runner, ShellContext.of(variables), swallowPipedOutput, maxTotalCommands, maxDepth);
    }

    /**
     * @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend})
     * @param maxDepth         nesting limit for {@code $( … )}, aliases and nested {@code sh}
     */
    public ChainExecutor(CommandRunner runner, ShellContext context, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this.runner = runner;
        this.context = context;
        this.variables = context.variables();
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
        this.maxDepth = maxDepth;
    }
```

Add a field `private static final java.util.regex.Pattern ALIAS_DEF = java.util.regex.Pattern.compile("(global\\.)?([a-z_][a-z0-9_-]{0,31})\\s*=\\s*(.*)", java.util.regex.Pattern.DOTALL);`.

`Run` gets positionals — replace

```java
        Run(Map<Node.Command, Integer> numbers, boolean numbered) {
            this.numbers = numbers;
            this.numbered = numbered;
        }
```
with

```java
        private final List<String> positionals;

        Run(Map<Node.Command, Integer> numbers, boolean numbered) {
            this(numbers, numbered, null);
        }

        /** @param positionals the alias call's words while running an alias body, else null */
        Run(Map<Node.Command, Integer> numbers, boolean numbered, List<String> positionals) {
            this.numbers = numbers;
            this.numbered = numbered;
            this.positionals = positionals;
        }
```

In `substitute`, change `new Run(innerNumbers, false)` to `new Run(innerNumbers, false, positionals)`.

In `Run.command`:
- At the very top (after the flag resets), insert the alias-call check:

```java
            Builtins.Call call = Builtins.classify(command.text());
            if (call.kind() == Builtins.Kind.NONE) {
                String word = command.text().split("\\s", 2)[0];
                Optional<String> alias = context.aliases().body(word);
                if (alias.isPresent()) {
                    return finish(callAlias(command, word, alias.get(), input, feedsPipe), command);
                }
            }
```
  and delete the original `Builtins.Call call = Builtins.classify(command.text());` line that follows.
- Change the expansion condition `if (call.kind() != Builtins.Kind.UNSET && call.kind() != Builtins.Kind.EACH) {` to
  `if (call.kind() != Builtins.Kind.UNSET && call.kind() != Builtins.Kind.EACH && call.kind() != Builtins.Kind.ALIAS && call.kind() != Builtins.Kind.UNALIAS) {`
- In the same block, pass positionals: change `input == null || !takesPipe ? null : new Expander.PipeInput(input.value(), input.result()));` to `input == null || !takesPipe ? null : new Expander.PipeInput(input.value(), input.result()), positionals);`
- Extend the `switch (call.kind())`:

```java
                case ALIAS -> alias(command, text, options);
                case UNALIAS -> unalias(command, text);
                case HISTORY -> history(command, options);
```

Add to `Run`:

```java
        /** Runs an alias body as a nested chain with the call's words as $1…$9/$@/$#; output shown as usual. */
        private ExecResult callAlias(Node.Command command, String name, String body, Piped input, boolean feedsPipe) {
            String rest = command.text().substring(name.length());
            List<String> words;
            try {
                words = Words.split(Expander.expandParts(rest, variables, lastStatus, this::substitute, null, positionals).join(false));
            } catch (ExpansionException e) {
                expansionFailed = true;
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
            Node node;
            try {
                node = NodeParser.parse(body, NodeParser.Mode.SH, Integer.MAX_VALUE);
            } catch (ChainSyntaxException e) {
                runner.reportError(label(command) + "alias " + name + ": " + e.getMessage());
                return ExecResult.failure();
            }
            if (!words.isEmpty() && !Expander.hasPositionals(body)) {
                node = appendArguments(node);
            }
            Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
            if (scope.isEmpty()) {
                runner.reportError(label(command) + "aliases nested deeper than " + maxDepth);
                return ExecResult.failure();
            }
            try (DepthGuard.Scope ignored = scope.get()) {
                Map<Node.Command, Integer> innerNumbers = new IdentityHashMap<>();
                number(node, innerNumbers);
                return new Run(innerNumbers, false, words).node(node, input, feedsPipe).result();
            }
        }

        /** The append rule: the body's rightmost command gets " $@", so the words go in as values. */
        private static Node appendArguments(Node node) {
            return switch (node) {
                case Node.Command command -> new Node.Command(command.text() + " $@");
                case Node.Pipeline pipeline -> new Node.Pipeline(replaceLast(pipeline.stages()));
                case Node.Sequence sequence -> new Node.Sequence(replaceLast(sequence.items()), sequence.joins());
                case Node.Group group -> new Node.Group(appendArguments(group.body()));
            };
        }

        private static List<Node> replaceLast(List<Node> nodes) {
            List<Node> copy = new java.util.ArrayList<>(nodes);
            copy.set(copy.size() - 1, appendArguments(copy.getLast()));
            return copy;
        }

        /** alias (list) | alias NAME = BODY | alias global.NAME = BODY */
        private ExecResult alias(Node.Command command, String arguments, RunOptions options) {
            String text = arguments.strip();
            if (text.isEmpty()) {
                List<String> lines = context.aliases().listing();
                lines.forEach(line -> runner.echo(line, options));
                return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
            }
            java.util.regex.Matcher m = ALIAS_DEF.matcher(text);
            if (!m.matches()) {
                runner.reportError(label(command) + "alias needs NAME = BODY");
                return ExecResult.failure();
            }
            String body = Builtins.unquote(m.group(3).strip());
            try {
                NodeParser.parse(body, NodeParser.Mode.SH, Integer.MAX_VALUE);
            } catch (ChainSyntaxException e) {
                runner.reportError(label(command) + "alias body: " + e.getMessage());
                return ExecResult.failure();
            }
            try {
                context.aliases().define(m.group(1) != null, m.group(2), body);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult unalias(Node.Command command, String arguments) {
            String name = arguments.strip();
            boolean global = name.startsWith("global.");
            try {
                context.aliases().remove(global, global ? name.substring(7) : name);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult history(Node.Command command, RunOptions options) {
            if (context.history() == null) {
                runner.reportError(label(command) + "history is only for chains you type");
                return ExecResult.failure();
            }
            List<String> lines = context.history().lines();
            lines.forEach(line -> runner.echo(line, options));
            return new ExecResult(true, false, lines.size(), List.of(), new PipeValue.Items(lines));
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (222 + 10 = 232).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Run aliases as nested chains with positional arguments; history builtin"
```

---

### Task 4: Storage, history recording and `!` lines in Minecraft

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mc/Sources.java`, `src/main/java/net/slk/bashcraft/mc/StorageAliases.java`, `src/main/java/net/slk/bashcraft/mc/HistoryBook.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftAliasCheck.java`, `src/gametest/resources/data/bashcraft-test/function/use_alias.mcfunction`
- Modify: `src/main/java/net/slk/bashcraft/command/ShCommand.java`, `src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java`, `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/gametest/resources/fabric.mod.json`, `README.md`

**Interfaces:**
- Produces: `Sources.typist(CommandSourceStack)` → `Optional<ServerPlayer>`; `StorageAliases.of(CommandSourceStack)` → `Aliases`; `StorageAliases.names(MinecraftServer, ServerPlayer)` → `List<String>`; `HistoryBook.of(ServerPlayer)` → `History`, `HistoryBook.forget(UUID)`; `ShCommand` builds a `ShellContext`.

- [ ] **Step 1: Write the game test and function**

`src/gametest/resources/data/bashcraft-test/function/use_alias.mcfunction`:
```
sh mark 1
```

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** Aliases and history end to end (spec C4 §7). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftAliasCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 16 12 120 16 minecraft:air");

            server.runCommand("sh alias global.mark = setblock $1 120 16 minecraft:stone");
            server.runCommand("sh mark 2");
            check(stone(server, 2), "a global alias with an argument");
            server.runCommand("function bashcraft-test:use_alias");
            check(stone(server, 1), "a global alias from a function");

            server.runCommand("scoreboard objectives add t trigger");
            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh alias tr = trigger t set 5"));
            context.runOnClient(client -> client.getConnection().sendCommand("sh tr"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t matches 5 run setblock 3 120 16 minecraft:stone");
            check(stone(server, 3), "a player's personal alias from chat");

            server.runCommand("scoreboard players set @p t 0");
            server.runCommand("scoreboard players enable @a t");
            server.runCommand("execute as @p run sh tr");
            server.runCommand("execute if score @p t matches 5 run setblock 4 120 16 minecraft:stone");
            check(!stone(server, 4), "a personal alias never runs in an elevated chain");

            context.runOnClient(client -> client.getConnection().sendCommand("sh alias global.g = trigger t set 6"));
            context.waitTicks(5);
            server.runCommand("execute unless data storage bashcraft:aliases global.g run setblock 5 120 16 minecraft:stone");
            check(stone(server, 5), "a non-op can't define a global alias");

            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh trigger t set 7"));
            context.waitTicks(5);
            server.runCommand("scoreboard players set @p t 0");
            server.runCommand("scoreboard players enable @a t");
            context.runOnClient(client -> client.getConnection().sendCommand("sh !!"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t matches 7 run setblock 6 120 16 minecraft:stone");
            check(stone(server, 6), "/sh !! reruns the player's last line");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 16)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftAliasCheck"` to the `fabric-client-gametest` list.

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|BUILD"`
Expected: `Check failed: a global alias with an argument` (`/sh` has no alias store yet).

- [ ] **Step 3: Write `Sources.java` and `HistoryBook.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;

import java.util.Optional;

/** Who typed a chain: the player whose own command this is (through any nesting), not a function or other source. */
public final class Sources {
    private Sources() {}

    public static Optional<ServerPlayer> typist(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || source.isSilent()) {
            return Optional.empty();
        }
        CommandSource origin = ((CommandSourceStackAccessor) source).bashcraft$source();
        while (origin instanceof TeeSource tee) {
            origin = tee.target();
        }
        return origin == player.commandSource() ? Optional.of(player) : Optional.empty();
    }
}
```

```java
package net.slk.bashcraft.mc;

import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.exec.History;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each online player's history for this session; forgotten on disconnect (spec C4 §4). */
public final class HistoryBook {
    private static final Map<UUID, History> BOOK = new ConcurrentHashMap<>();

    private HistoryBook() {}

    public static History of(ServerPlayer player) {
        return BOOK.computeIfAbsent(player.getUUID(), uuid -> new History());
    }

    public static void forget(UUID player) {
        BOOK.remove(player);
    }
}
```

- [ ] **Step 4: Write `StorageAliases.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.slk.bashcraft.exec.AliasTable;
import net.slk.bashcraft.exec.Aliases;
import net.slk.bashcraft.exec.ExpansionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Aliases in command storage {@code bashcraft:aliases} = {@code {global:{…}, players:{"<uuid>":{…}}}} (spec C4 §2).
 * Personal aliases are visible and definable only in chains their player typed; global ones need operator
 * permission to change. Reads and writes go straight to the live tag, key by key.
 */
public final class StorageAliases implements Aliases {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("bashcraft", "aliases");

    private final MinecraftServer server;
    private final CommandStorage storage;
    private final ServerPlayer typist;
    private final boolean globalWritable;
    private final Predicate<String> isCommand;

    private StorageAliases(MinecraftServer server, ServerPlayer typist, boolean globalWritable) {
        this.server = server;
        this.storage = server.getCommandStorage();
        this.typist = typist;
        this.globalWritable = globalWritable;
        this.isCommand = name -> server.getCommands().getDispatcher().getRoot().getChild(name) != null;
    }

    public static StorageAliases of(CommandSourceStack source) {
        return new StorageAliases(source.getServer(), Sources.typist(source).orElse(null),
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source));
    }

    @Override
    public Optional<String> body(String name) {
        return table(storage.get(ID), false).body(name);
    }

    @Override
    public void define(boolean global, String name, String body) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        table(root, true).define(global, name, body);
        save(root);
        AliasSync.changed(server, global ? null : typist);
    }

    @Override
    public void remove(boolean global, String name) throws ExpansionException {
        CompoundTag root = storage.get(ID);
        table(root, true).remove(global, name);
        save(root);
        AliasSync.changed(server, global ? null : typist);
    }

    @Override
    public List<String> listing() {
        return table(storage.get(ID), false).listing();
    }

    /** Names a player may call: their own aliases and the global ones. */
    public static List<String> names(MinecraftServer server, ServerPlayer player) {
        CompoundTag root = server.getCommandStorage().get(ID);
        TreeSet<String> names = new TreeSet<>(root.getCompoundOrEmpty("global").keySet());
        names.addAll(root.getCompoundOrEmpty("players").getCompoundOrEmpty(player.getUUID().toString()).keySet());
        return new ArrayList<>(names);
    }

    private AliasTable table(CompoundTag root, boolean forWrite) {
        CompoundTag global = forWrite ? child(root, "global") : root.getCompoundOrEmpty("global");
        TagStrings globalSet = new TagStrings(global);
        if (typist == null) {
            return new AliasTable(globalSet, globalSet, false, globalWritable, isCommand);
        }
        String key = typist.getUUID().toString();
        CompoundTag mine = forWrite ? child(child(root, "players"), key) : root.getCompoundOrEmpty("players").getCompoundOrEmpty(key);
        return new AliasTable(new TagStrings(mine), globalSet, true, globalWritable, isCommand);
    }

    private void save(CompoundTag root) {
        if (typist != null) {
            String key = typist.getUUID().toString();
            root.getCompound("players").ifPresent(players -> {
                if (players.getCompoundOrEmpty(key).isEmpty()) {
                    players.remove(key);
                }
                if (players.isEmpty()) {
                    root.remove("players");
                }
            });
        }
        storage.set(ID, root);
    }

    private static CompoundTag child(CompoundTag parent, String key) {
        return parent.getCompound(key).orElseGet(() -> {
            CompoundTag created = new CompoundTag();
            parent.put(key, created);
            return created;
        });
    }
}
```

Create `src/main/java/net/slk/bashcraft/mc/AliasSync.java` with a no-op body for now (filled in Task 5):

```java
package net.slk.bashcraft.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tells clients which alias names they may call (spec C4 §5); wired up in Task 5. */
public final class AliasSync {
    private AliasSync() {}

    /** A player's personal set changed ({@code player}), or the global set ({@code player == null}). */
    public static void changed(MinecraftServer server, ServerPlayer player) {}
}
```

- [ ] **Step 5: Wire `/sh`, transparent mode and disconnects**

In `ShCommand.run`, replace the start of the method (before the parse) with history handling:

```java
    public static ExecResult run(CommandSourceStack source, String input) {
        Optional<ServerPlayer> typist = Sources.typist(source);
        String line = input.strip();
        if (line.startsWith("!")) {
            if (typist.isEmpty()) {
                source.sendFailure(Component.literal("BashCraft: history is only for chains you type"));
                return ExecResult.failure();
            }
            Optional<String> resolved = HistoryBook.of(typist.get()).resolve(line);
            if (resolved.isEmpty()) {
                source.sendFailure(Component.literal("BashCraft: no history matching " + line.substring(1)));
                return ExecResult.failure();
            }
            line = resolved.get();
            String shown = line;
            source.sendSuccess(() -> Component.literal("sh " + shown).withStyle(ChatFormatting.GRAY), false);
        }
        String recorded = line;
        typist.ifPresent(player -> HistoryBook.of(player).record(recorded));
        Node chain;
        try {
            chain = NodeParser.parse(line, NodeParser.Mode.SH, BashcraftConfig.get().maxSegments);
```
(the rest of the method stays, with `input` in the context-line call replaced by `line`).

In `ShCommand.execute`, replace the executor construction with:

```java
            ShellContext shell = new ShellContext(StorageVariables.of(source), StorageAliases.of(source),
                    Sources.typist(source).map(HistoryBook::of).orElse(null));
            return new ChainExecutor(new MinecraftCommandRunner(source), shell,
                    config.swallowPipedOutput, config.maxTotalCommands, config.maxDepth).execute(chain);
```
Imports: `net.minecraft.server.level.ServerPlayer`, `net.slk.bashcraft.exec.ShellContext`, `net.slk.bashcraft.mc.HistoryBook`, `net.slk.bashcraft.mc.Sources`, `net.slk.bashcraft.mc.StorageAliases`.

In `CommandsTransparentMixin`, inside the `ifPresent(chain -> {` lambda, before `ExecResult result = …`, add:

```java
            Sources.typist(source).ifPresent(player -> HistoryBook.of(player).record(command));
```
(imports `net.slk.bashcraft.mc.HistoryBook`, `net.slk.bashcraft.mc.Sources`).

In `Bashcraft.onInitialize()`, after `BashcraftNetwork.register();`:

```java
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> HistoryBook.forget(handler.getPlayer().getUUID()));
```
(imports `net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents`, `net.slk.bashcraft.mc.HistoryBook`).

- [ ] **Step 6: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 7: Update `README.md`** — add before `### Without /sh`:

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
````

- [ ] **Step 8: Commit**

```bash
git add src README.md
git commit -m "Store aliases, record history and resolve ! lines in Minecraft"
```

---

### Task 5: Client support and dedicated server check

**Files:**
- Create: `src/main/java/net/slk/bashcraft/network/AliasNamesPayload.java`
- Modify: `src/main/java/net/slk/bashcraft/network/BashcraftNetwork.java`, `src/main/java/net/slk/bashcraft/mc/AliasSync.java`, `src/main/java/net/slk/bashcraft/client/ClientChains.java`, `src/main/java/net/slk/bashcraft/BashcraftClient.java`, `src/main/java/net/slk/bashcraft/client/ChainStyles.java`, `src/main/java/net/slk/bashcraft/mixin/client/CommandSuggestionsMixin.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`

- [ ] **Step 1: Add the failing game check** — in `BashcraftChatCheck.java`, insert before `context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));`:

```java
            context.runOnClient(client -> client.getConnection().sendCommand("sh alias hb = say hi"));
            context.waitTicks(5);
            context.runOnClient(client -> ChatProbe.open(client, "/sh hb && hb"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "alias calls are not shown as errors");
```

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|BUILD"`
Expected: `Check failed: alias calls are not shown as errors`.

- [ ] **Step 3: Write `AliasNamesPayload.java` and register it**

```java
package net.slk.bashcraft.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.slk.bashcraft.Bashcraft;

import java.util.List;

/** Server → client: the alias names this player may call, so the chat box can colour alias calls (spec C4 §5). */
public record AliasNamesPayload(List<String> names) implements CustomPacketPayload {
    public static final Type<AliasNamesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Bashcraft.MOD_ID, "aliases"));
    public static final StreamCodec<ByteBuf, AliasNamesPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), AliasNamesPayload::names,
            AliasNamesPayload::new);

    @Override
    public Type<AliasNamesPayload> type() {
        return TYPE;
    }
}
```

In `BashcraftNetwork.register()`, add `PayloadTypeRegistry.clientboundPlay().register(AliasNamesPayload.TYPE, AliasNamesPayload.CODEC);` and, inside the JOIN handler after sending the hello, `AliasSync.send(server, handler.getPlayer());` (import `net.slk.bashcraft.mc.AliasSync`).

- [ ] **Step 4: Fill in `AliasSync`**

```java
package net.slk.bashcraft.mc;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.slk.bashcraft.network.AliasNamesPayload;

/** Tells clients which alias names they may call (spec C4 §5). */
public final class AliasSync {
    private AliasSync() {}

    /** A player's personal set changed ({@code player}), or the global set ({@code player == null}: everyone). */
    public static void changed(MinecraftServer server, ServerPlayer player) {
        if (player != null) {
            send(server, player);
        } else {
            server.getPlayerList().getPlayers().forEach(online -> send(server, online));
        }
    }

    public static void send(MinecraftServer server, ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, AliasNamesPayload.TYPE)) {
            ServerPlayNetworking.send(player, new AliasNamesPayload(StorageAliases.names(server, player)));
        }
    }
}
```

- [ ] **Step 5: Client state and colouring**
- `ClientChains`: add `private static volatile java.util.Set<String> aliasNames = java.util.Set.of();`, `public static void acceptAliases(java.util.List<String> names) { aliasNames = java.util.Set.copyOf(names); }`, `public static boolean isAlias(String word) { return active && aliasNames.contains(word); }`, and clear `aliasNames = java.util.Set.of();` in `clear()`.
- `BashcraftClient.onInitializeClient()`: add `ClientPlayNetworking.registerGlobalReceiver(AliasNamesPayload.TYPE, (payload, context) -> ClientChains.acceptAliases(payload.names()));` (import `net.slk.bashcraft.network.AliasNamesPayload`).
- `ChainStyles.compute`: right after `Builtins.Kind kind = Builtins.classify(value.substring(span.start(), span.end())).kind();`, add

```java
            if (kind == Builtins.Kind.NONE && ClientChains.isAlias(value.substring(span.start(), span.end()).split("\\s", 2)[0])) {
                continue;
            }
```
- `CommandSuggestionsMixin.bashcraft$builtinAt`: before the final `return`, add

```java
        String word = value.substring(window.start(), Math.min(window.end(), value.length())).strip().split("\\s", 2)[0];
        if (ClientChains.isAlias(word)) {
            return true;
        }
```
(declare `String value = input.getValue();` at the top of that method if it isn't there.)

- [ ] **Step 6: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 7: Dedicated server check**

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "sh alias global.hi = 'say hello $1'"; sleep 2; echo "sh hi world ; alias"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-c4.log 2>&1
grep -E "\[Server\]|global.hi|Done \(" build/server-c4.log | head
grep -E "WARN|ERROR" build/server-c4.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: `[Server] hello world`, a `global.hi = say hello $1` line, `Done (`, `no bashcraft warnings`.

- [ ] **Step 8: Commit**

```bash
git add src
git commit -m "Sync alias names to the client and colour alias calls"
```
