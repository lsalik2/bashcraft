# BashCraft Output Plumbing Implementation Plan (Sub-project C2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Builtin pipe stages `store score|storage …`, `grep [-v] [-x] TEXT` and `each COMMAND`, with real item lists from list-producing vanilla commands.

**Architecture:** `PipeValue` gains an `Items` variant; list adapters and an SNBT list reader produce it. `ExecResult` gains an optional ready-made pipe value so builtins can pass lists on. `Builtins` recognises `grep`/`store`/`each`; the executor implements `grep` and `store` (composing vanilla `scoreboard players set` / `data modify storage` commands, run as the same source) and `each` (re-entering the normal command path once per item, so C1's expansion, plain-value rule and escaping apply to every run). The client colours the new builtins and treats `each`'s body as a command.

**Tech Stack:** as before (Java 25, Minecraft 26.3, Fabric, JUnit 5.14.2, client game tests headless on Xvfb).

**Spec:** `docs/superpowers/specs/2026-10-10-bashcraft-output-plumbing-design.md`

## Global Constraints

- All earlier constraints (`JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`; pure packages import no `net.minecraft.*`/`net.fabricmc.*`; `BashCraft: ` prefix; no `Co-Authored-By`; Synergy read-only; server port 25569; game tests run headless automatically).
- List adapters (26.3, verified): `commands.list.players` arg 2; `commands.tag.list.single.success` arg 2; `commands.tag.list.multiple.success` arg 2; `commands.team.list.members.success` arg 2; `commands.team.list.teams.success` arg 1; `commands.scoreboard.players.list.success` arg 1 — each a `", "`-joined list (`ComponentUtils.formatList`, empty text for no items). `commands.data.{entity,block,storage}.query` value args that are SNBT lists → their elements.
- Error texts (after `[n] `): `grep needs piped input` (likewise `store`, `each`), `grep needs a pattern`, `store score needs a number`, `store needs score HOLDER OBJECTIVE or storage ID PATH`, `each needs a command`, `each had no items`.
- **Plan decision:** `ExecResult` gains `PipeValue value` (nullable); when present it is the value a pipe stage receives instead of extracting one from the output messages.
- **Plan decision:** a pipe stage now receives `Piped(value, result, items)`; `items` = the value's items when the previous result carried a value, else one item per output message (list values contribute all their items).
- **Plan decision:** `each`'s body is expanded per run (not once at the `each` level), as a fresh command node sharing the `each` command's `[n]` label.

## Review Focus

1. **Commas inside SNBT strings** (`["a, b", "c"]`) must not split items. → Task 1 test `nbtListKeepsCommasInsideStrings`.
2. **Text with quotes and backslashes stored to storage** must round-trip, never break out of the SNBT string. → Task 2 test `snbtQuotesText`.
3. **Mixed integer/decimal lists** must become a valid homogeneous NBT list. → Task 2 test `snbtMixedNumbersBecomeDoubles`.
4. **`each` over player-controlled items in an elevated chain** must apply the plain-value rule per item. → Task 3 test `eachInAnElevatedChainChecksItems`.
5. **`grep`/`store` arguments** must never get the piped value appended. → Task 2 test `storeArgumentsDontGetThePipedValue`.

---

### Task 1: Item lists

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/pipe/PipeValue.java`, `src/main/java/net/slk/bashcraft/pipe/OutputAdapters.java`, `src/main/java/net/slk/bashcraft/pipe/PipeResolver.java`
- Create: `src/main/java/net/slk/bashcraft/pipe/NbtList.java`
- Test: `src/test/java/net/slk/bashcraft/pipe/ItemsTest.java`

**Interfaces:**
- Produces: `PipeValue.Items(List<String> items)`; `List<String> PipeValue.items()` (default: one item `asArgument()`); `PipeValue.of(String item)` → `Numeric` for `-?\d+(\.\d+)?`, else `Text`; `NbtList.elements(String text)` → `Optional<List<String>>`; `PipeResolver.items(List<Feedback> output)` → `List<String>`.

- [ ] **Step 1: Write the failing tests `ItemsTest.java`**

```java
package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ItemsTest {
    private static PipeValue resolve(Feedback... output) throws PipeException {
        return PipeResolver.resolve(List.of(output));
    }

    @Test
    void listPlayers() throws Exception {
        assertEquals(new PipeValue.Items(List.of("Alex", "Steve")),
                resolve(Feedback.translatable("commands.list.players", "There are 2 of a max of 20 players online: Alex, Steve", 2, 20, Feedback.plain("Alex, Steve"))));
        assertEquals(new PipeValue.Items(List.of()),
                resolve(Feedback.translatable("commands.list.players", "There are 0 of a max of 20 players online: ", 0, 20, Feedback.plain(""))));
    }

    @Test
    void tagsTeamsAndScoreHolders() throws Exception {
        assertEquals(List.of("a", "boss"), resolve(Feedback.translatable("commands.tag.list.single.success", "x", "Steve", 2, Feedback.plain("a, boss"))).items());
        assertEquals(List.of("a"), resolve(Feedback.translatable("commands.tag.list.multiple.success", "x", 3, 1, Feedback.plain("a"))).items());
        assertEquals(List.of("Alex"), resolve(Feedback.translatable("commands.team.list.members.success", "x", "red", 1, Feedback.plain("Alex"))).items());
        assertEquals(List.of("blue", "red"), resolve(Feedback.translatable("commands.team.list.teams.success", "x", 2, Feedback.plain("blue, red"))).items());
        assertEquals(List.of("#fake", "Steve"), resolve(Feedback.translatable("commands.scoreboard.players.list.success", "x", 2, Feedback.plain("#fake, Steve"))).items());
    }

    @Test
    void dataGetLists() throws Exception {
        assertEquals(List.of("boss", "a"), resolve(Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("[\"boss\", \"a\"]"))).items());
        assertEquals(List.of("1", "2"), resolve(Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("[1b, 2b]"))).items());
    }

    @Test
    void nbtListKeepsCommasInsideStrings() {
        assertEquals(Optional.of(List.of("a, b", "c")), NbtList.elements("[\"a, b\", 'c']"));
        assertEquals(Optional.of(List.of("say \"hi\"")), NbtList.elements("[\"say \\\"hi\\\"\"]"));
    }

    @Test
    void nbtTypedAndNestedLists() {
        assertEquals(Optional.of(List.of("1", "2")), NbtList.elements("[I; 1, 2]"));
        assertEquals(Optional.of(List.of("[1, 2]", "{a: 1b, b: [3]}")), NbtList.elements("[[1, 2], {a: 1b, b: [3]}]"));
        assertEquals(Optional.of(List.of()), NbtList.elements("[]"));
        assertEquals(Optional.empty(), NbtList.elements("\"not a list\""));
    }

    @Test
    void itemsOfOtherValuesAndMessages() throws Exception {
        assertEquals(List.of("5"), new PipeValue.Numeric("5").items());
        assertEquals("Alex Steve", new PipeValue.Items(List.of("Alex", "Steve")).asArgument());
        assertEquals(Optional.of("Alex Steve"), new PipeValue.Items(List.of("Alex", "Steve")).placeholder("value"));
        assertEquals(Optional.empty(), new PipeValue.Items(List.of("a")).placeholder("x"));
        assertEquals(List.of("1", "9"), PipeResolver.items(List.of(Feedback.plain("first 1"), Feedback.plain("second 9"))));
        assertEquals(List.of("Alex", "Steve", "7"), PipeResolver.items(List.of(
                Feedback.translatable("commands.list.players", "x", 2, 20, Feedback.plain("Alex, Steve")), Feedback.plain("lucky 7"))));
    }

    @Test
    void itemsAreTyped() {
        assertEquals(new PipeValue.Numeric("12"), PipeValue.of("12"));
        assertEquals(new PipeValue.Numeric("-1.5"), PipeValue.of("-1.5"));
        assertEquals(new PipeValue.Text("Steve"), PipeValue.of("Steve"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.ItemsTest'`
Expected: compilation FAIL — `cannot find symbol: class Items`.

- [ ] **Step 3: Extend `PipeValue.java`** — add to the interface (after `describe()`):

```java
    /** The items {@code grep} filters and {@code each} runs once per: a list's items, otherwise this one value. */
    default List<String> items() {
        return List.of(asArgument());
    }

    /** An item as a value: a number when it looks like one, else text. */
    static PipeValue of(String item) {
        return item.matches("-?\\d+(\\.\\d+)?") ? new Numeric(item) : new Text(item);
    }

    /** An ordered list of items (player names, tags, NBT list elements…); inserted as the items joined by spaces. */
    record Items(List<String> items) implements PipeValue {
        public Items {
            items = List.copyOf(items);
        }

        @Override
        public String asArgument() {
            return String.join(" ", items);
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(asArgument()) : Optional.empty();
        }

        @Override
        public String describe() {
            return "a list";
        }

        @Override
        public List<String> items() {
            return items;
        }
    }
```
Add `import java.util.List;`.

- [ ] **Step 4: Write `NbtList.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the elements of a pretty-printed SNBT list ({@code ["a", "b"]}, {@code [1b, 2b]}, {@code [I; 1, 2]}). */
public final class NbtList {
    private NbtList() {}

    /** The elements as item text (strings unquoted, number suffixes dropped, nested values as written), or empty if not a list. */
    public static Optional<List<String>> elements(String text) {
        String trimmed = text.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return Optional.empty();
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).strip();
        if (inner.matches("[BIL];.*")) {
            inner = inner.substring(2).strip();
        }
        List<String> elements = new ArrayList<>();
        if (inner.isEmpty()) {
            return Optional.of(elements);
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                elements.add(element(inner.substring(start, i)));
                start = i + 1;
            }
        }
        elements.add(element(inner.substring(start)));
        return Optional.of(elements);
    }

    private static String element(String raw) {
        String text = raw.strip();
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0)) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < text.length() - 1; i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length() - 1) {
                    out.append(text.charAt(++i));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        return text.matches("-?\\d+(\\.\\d+)?(?:[eE]-?\\d+)?[bBsSlLfFdD]") ? text.substring(0, text.length() - 1) : text;
    }
}
```

- [ ] **Step 5: Add the list adapters to `OutputAdapters.java`** — add these entries to the `Map.ofEntries(...)`:

```java
            Map.entry("commands.list.players", f -> list(f, 2)),
            Map.entry("commands.tag.list.single.success", f -> list(f, 2)),
            Map.entry("commands.tag.list.multiple.success", f -> list(f, 2)),
            Map.entry("commands.team.list.members.success", f -> list(f, 2)),
            Map.entry("commands.team.list.teams.success", f -> list(f, 1)),
            Map.entry("commands.scoreboard.players.list.success", f -> list(f, 1)),
```

add the helper:

```java
    /** A ", "-joined list argument (ComponentUtils.formatList); empty text means no items. */
    private static Optional<PipeValue> list(Feedback feedback, int index) {
        return feedback.argText(index).map(text -> text.isEmpty()
                ? new PipeValue.Items(List.of())
                : new PipeValue.Items(List.of(text.split(", "))));
    }
```

and at the start of the lambda in `data(...)`, before the number check, insert:

```java
            Optional<List<String>> elements = NbtList.elements(text);
            if (elements.isPresent()) {
                return new PipeValue.Items(elements.get());
            }
```
Add `import java.util.List;`.

- [ ] **Step 6: Add `items` to `PipeResolver.java`**

```java
    /** One item per output message that yields a value (a list message contributes all its items). */
    public static List<String> items(List<Feedback> output) {
        List<String> items = new ArrayList<>();
        for (Feedback message : output) {
            try {
                items.addAll(resolve(List.of(message)).items());
            } catch (PipeException e) {
                // A message without a value adds no item.
            }
        }
        return items;
    }
```
Add `import java.util.ArrayList;`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (181 + 7 = 188).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/slk/bashcraft/pipe src/test/java/net/slk/bashcraft/pipe
git commit -m "Add item lists for list-producing commands"
```

---

### Task 2: `grep` and `store`

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/Snbt.java`
- Modify: `src/main/java/net/slk/bashcraft/exec/ExecResult.java`, `src/main/java/net/slk/bashcraft/exec/Builtins.java`, `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`
- Test: `src/test/java/net/slk/bashcraft/exec/SnbtTest.java`, `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`, `src/test/java/net/slk/bashcraft/exec/BuiltinsTest.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: `ExecResult(boolean success, boolean errored, int result, List<Feedback> output, PipeValue value)` (the 4- and 3-argument constructors remain, `value = null`); `Builtins.Kind` gains `GREP, STORE, EACH` (argument = text after the word); `Snbt.of(PipeValue)` → `String`.

- [ ] **Step 1: Write `SnbtTest.java` and the failing executor/builtin tests**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.PipeValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnbtTest {
    @Test
    void numbers() {
        assertEquals("5", Snbt.of(new PipeValue.Numeric("5")));
        assertEquals("-12", Snbt.of(new PipeValue.Numeric("-12")));
        assertEquals("1.5d", Snbt.of(new PipeValue.Numeric("1.5")));
        assertEquals("9.999999999E9d", Snbt.of(new PipeValue.Numeric("9999999999")));
    }

    @Test
    void snbtQuotesText() {
        assertEquals("\"say \\\"hi\\\" \\\\ ok\"", Snbt.of(new PipeValue.Text("say \"hi\" \\ ok")));
        assertEquals("\"312 ~ -1040\"", Snbt.of(new PipeValue.Coordinates("312", "~", "-1040")));
    }

    @Test
    void lists() {
        assertEquals("[1,2]", Snbt.of(new PipeValue.Items(List.of("1", "2"))));
        assertEquals("[\"Alex\",\"Steve\"]", Snbt.of(new PipeValue.Items(List.of("Alex", "Steve"))));
        assertEquals("[]", Snbt.of(new PipeValue.Items(List.of())));
    }

    @Test
    void snbtMixedNumbersBecomeDoubles() {
        assertEquals("[1.0d,2.5d]", Snbt.of(new PipeValue.Items(List.of("1", "2.5"))));
    }
}
```

Append to `BuiltinsTest.java` (before the final `}`):

```java
    @Test
    void pipeBuiltins() {
        assertEquals(new Builtins.Call(Builtins.Kind.GREP, "", "-v Steve"), Builtins.classify("grep -v Steve"));
        assertEquals(new Builtins.Call(Builtins.Kind.GREP, "", ""), Builtins.classify("grep"));
        assertEquals(new Builtins.Call(Builtins.Kind.STORE, "", "score @s best"), Builtins.classify("store score @s best"));
        assertEquals(new Builtins.Call(Builtins.Kind.EACH, "", "msg {value} hi"), Builtins.classify("each msg {value} hi"));
        assertEquals(Builtins.Kind.NONE, Builtins.classify("grepper x").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("storefront").kind());
    }
```

Append to `ChainExecutorTest.java` (before the final `}`):

```java
    private static Feedback players(String... names) {
        return Feedback.translatable("commands.list.players", "x", names.length, 20, Feedback.plain(String.join(", ", names)));
    }

    @Test
    void grepKeepsMatchingItems() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | grep steve | say ; list | grep -v alex | say ; list | grep -x ALEX | say");
        assertEquals(List.of("list", "say Steve", "list", "say Steve", "list", "say Alex"), r.ran);
    }

    @Test
    void grepStatusDrivesOperators() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | grep zed || say none ; list | grep -x ale || say exact only");
        assertEquals(List.of("list", "say none", "list", "say exact only"), r.ran);
    }

    @Test
    void pipeBuiltinsNeedInputAndArguments() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex"))));
        run(r, table(), "grep x ; list | grep ; list | store nowhere");
        assertEquals(List.of("[1] grep needs piped input", "[3] grep needs a pattern", "[5] store needs score HOLDER OBJECTIVE or storage ID PATH"), r.errors);
    }

    @Test
    void storeScoreRoundsDown() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("a", new ExecResult(true, 1, List.of(Feedback.plain("12.7"))))
                .on("b", new ExecResult(true, 1, List.of(Feedback.plain("-3.5"))))
                .on("c", new ExecResult(true, 1, List.of(Feedback.plain("hello"))));
        run(r, table(), "a | store score @s best ; b | store score @e[type=cow, limit=1] best ; c | store score @s best");
        assertEquals(List.of("a", "scoreboard players set @s best 12", "b", "scoreboard players set @e[type=cow, limit=1] best -4", "c"), r.ran);
        assertEquals(List.of("[6] store score needs a number"), r.errors);
    }

    @Test
    void storeStorageWritesSnbtAndPassesTheValueOn() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | store storage bashcraft:test names | say");
        assertEquals(List.of("list", "data modify storage bashcraft:test names set value [\"Alex\",\"Steve\"]", "say Alex Steve"), r.ran);
    }

    @Test
    void storeArgumentsDontGetThePipedValue() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("7"))));
        run(r, table(), "o=best ; a | store score @s $o");
        assertEquals("scoreboard players set @s best 7", r.ran.get(1));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class Snbt` / `GREP`.

- [ ] **Step 3: Extend `ExecResult.java`** — replace the record header and constructors with:

```java
/**
 * Outcome of one command.
 *
 * @param success no error and result &gt; 0 (vanilla's rule; decides && and ||)
 * @param errored the command failed to parse or threw; a result of 0 or less without an error is not an error
 * @param output  success messages, captured whether or not they were shown
 * @param value   a ready-made pipe value (builtins), used instead of extracting one from {@code output}; may be null
 */
public record ExecResult(boolean success, boolean errored, int result, List<Feedback> output, PipeValue value) {
    public ExecResult {
        output = List.copyOf(output);
    }

    public ExecResult(boolean success, boolean errored, int result, List<Feedback> output) {
        this(success, errored, result, output, null);
    }

    /** A result whose only failure mode is an error. */
    public ExecResult(boolean success, int result, List<Feedback> output) {
        this(success, !success, result, output, null);
    }

    public static ExecResult failure() {
        return new ExecResult(false, true, 0, List.of(), null);
    }
}
```
Add `import net.slk.bashcraft.pipe.PipeValue;`.

- [ ] **Step 4: Write `Snbt.java`**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.PipeValue;

import java.util.List;

/** Writes a pipe value as SNBT for {@code store storage}; text is always quoted by us, so it can't break out. */
public final class Snbt {
    private static final String INTEGER = "-?\\d+";
    private static final String NUMBER = "-?\\d+(\\.\\d+)?";

    private Snbt() {}

    public static String of(PipeValue value) {
        return switch (value) {
            case PipeValue.Numeric numeric -> number(numeric.text(), false);
            case PipeValue.Items items -> list(items.items());
            default -> string(value.asArgument());
        };
    }

    private static String list(List<String> items) {
        boolean numbers = !items.isEmpty() && items.stream().allMatch(item -> item.matches(NUMBER));
        boolean doubles = numbers && items.stream().anyMatch(item -> !fitsInt(item));
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(numbers ? number(items.get(i), doubles) : string(items.get(i)));
        }
        return out.append(']').toString();
    }

    private static String number(String text, boolean forceDouble) {
        if (!forceDouble && fitsInt(text)) {
            return text;
        }
        return Double.toString(Double.parseDouble(text)) + "d";
    }

    private static boolean fitsInt(String text) {
        if (!text.matches(INTEGER)) {
            return false;
        }
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String string(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
```

- [ ] **Step 5: Extend `Builtins.java`**
- Change `public enum Kind { ASSIGN, UNSET, ECHO, NONE }` to `public enum Kind { ASSIGN, UNSET, ECHO, GREP, STORE, EACH, NONE }` and the `Call` Javadoc to mention that GREP/STORE/EACH carry the text after the word.
- In `classify`, before the final `return new Call(Kind.NONE, "", command);`, add:

```java
        for (Kind kind : List.of(Kind.GREP, Kind.STORE, Kind.EACH)) {
            String word = kind.name().toLowerCase(java.util.Locale.ROOT);
            if (command.equals(word) || command.startsWith(word + " ")) {
                return new Call(kind, "", command.length() > word.length() + 1 ? command.substring(word.length() + 1) : "");
            }
        }
```
Add `import java.util.List;`.

- [ ] **Step 6: Wire `grep` and `store` into `ChainExecutor`**

`Piped` gains items — replace `private record Piped(PipeValue value, int result) {}` with:

```java
    /** A value piped into a node, the producing command's numeric result ({result}), and the items grep/each work on. */
    private record Piped(PipeValue value, int result, List<String> items) {}
```

In `Run.pipeline`, replace the block from `PipeValue value;` through the `previous = node(...)` line with:

```java
                Node stage = stages.get(i);
                Piped piped;
                try {
                    piped = piped(previous.result());
                } catch (PipeException e) {
                    runner.reportError(label(firstCommand(stage)) + "nothing to pipe from /" + previous.lastCommand());
                    return new Outcome(ExecResult.failure(), previous.lastCommand());
                }
                previous = node(stage, piped, i < stages.size() - 1 || feedsPipe);
```

Add to `Run`:

```java
        /** What the next stage receives: a builtin's ready-made value, else the value extracted from the output. */
        private Piped piped(ExecResult result) throws PipeException {
            if (result.value() != null) {
                return new Piped(result.value(), result.result(), result.value().items());
            }
            List<String> items = PipeResolver.items(result.output());
            return new Piped(PipeResolver.resolve(result.output()), result.result(), items);
        }
```

In `substitute`, replace `return PipeResolver.resolve(result.output()).asArgument();` with `return piped(result).value().asArgument();` (the surrounding `catch (PipeException e)` stays).

In `Run.command`, change the expansion condition and pipe input so `each` bodies aren't expanded here and `grep`/`store` arguments never get the piped value:

```java
            if (call.kind() != Builtins.Kind.UNSET && call.kind() != Builtins.Kind.EACH) {
                try {
                    boolean takesPipe = call.kind() != Builtins.Kind.GREP && call.kind() != Builtins.Kind.STORE;
                    Expander.Expansion expansion = Expander.expandParts(text, variables, lastStatus, this::substitute,
                            input == null || !takesPipe ? null : new Expander.PipeInput(input.value(), input.result()));
                    text = finalText(call, command.text(), expansion);
```

Before the budget check (`if (!DepthGuard.spend(...))`), add:

```java
            if (call.kind() == Builtins.Kind.GREP || call.kind() == Builtins.Kind.STORE || call.kind() == Builtins.Kind.EACH) {
                if (input == null) {
                    runner.reportError(label(command) + call.kind().name().toLowerCase(java.util.Locale.ROOT) + " needs piped input");
                    return finish(ExecResult.failure(), command);
                }
                if (call.kind() == Builtins.Kind.EACH) {
                    return finish(each(command, text, input, feedsPipe), command);
                }
            }
```

Extend the `switch (call.kind())` with:

```java
                case GREP -> grep(command, text, input);
                case STORE -> store(command, text, input, options);
                case EACH -> throw new IllegalStateException("each is handled above");
```

Add to `Run`:

```java
        /** grep [-v] [-x] TEXT: keep matching items (case-insensitive substring, or whole item with -x). */
        private ExecResult grep(Node.Command command, String arguments, Piped input) {
            boolean invert = false;
            boolean exact = false;
            String rest = arguments.strip();
            while (rest.startsWith("-v ") || rest.startsWith("-x ") || rest.equals("-v") || rest.equals("-x")) {
                invert |= rest.startsWith("-v");
                exact |= rest.startsWith("-x");
                rest = rest.length() > 2 ? rest.substring(3).strip() : "";
            }
            if (rest.isEmpty()) {
                runner.reportError(label(command) + "grep needs a pattern");
                return ExecResult.failure();
            }
            String pattern = rest.toLowerCase(java.util.Locale.ROOT);
            List<String> kept = new java.util.ArrayList<>();
            for (String item : input.items()) {
                String lower = item.toLowerCase(java.util.Locale.ROOT);
                boolean matches = exact ? lower.equals(pattern) : lower.contains(pattern);
                if (matches != invert) {
                    kept.add(item);
                }
            }
            return new ExecResult(!kept.isEmpty(), false, kept.size(), List.of(), new PipeValue.Items(kept));
        }

        /** store score HOLDER OBJECTIVE | store storage ID PATH, by running the vanilla command as the same source. */
        private ExecResult store(Node.Command command, String arguments, Piped input, RunOptions options) {
            String text = arguments.strip();
            String vanilla;
            if (text.startsWith("score ") && text.lastIndexOf(' ') > 6) {
                String holder = text.substring(6, text.lastIndexOf(' ')).strip();
                String objective = text.substring(text.lastIndexOf(' ') + 1);
                java.util.OptionalLong number = number(input.value());
                if (number.isEmpty()) {
                    runner.reportError(label(command) + "store score needs a number");
                    return ExecResult.failure();
                }
                vanilla = "scoreboard players set " + holder + " " + objective + " " + number.getAsLong();
            } else if (text.startsWith("storage ") && text.indexOf(' ', 8) > 8) {
                String id = text.substring(8, text.indexOf(' ', 8));
                String path = text.substring(text.indexOf(' ', 8) + 1).strip();
                vanilla = "data modify storage " + id + " " + path + " set value " + Snbt.of(input.value());
            } else {
                runner.reportError(label(command) + "store needs score HOLDER OBJECTIVE or storage ID PATH");
                return ExecResult.failure();
            }
            ExecResult result = runner.run(vanilla, options);
            return new ExecResult(result.success(), result.errored(), result.result(), List.of(), input.value());
        }

        /** A number for store score: a numeric value, or a one-item list whose item is a number; rounded down. */
        private static java.util.OptionalLong number(PipeValue value) {
            List<String> items = value.items();
            if (items.size() == 1 && items.getFirst().matches("-?\\d+(\\.\\d+)?")) {
                return java.util.OptionalLong.of((long) Math.floor(Double.parseDouble(items.getFirst())));
            }
            return java.util.OptionalLong.empty();
        }

        /** Implemented in Task 3. */
        private ExecResult each(Node.Command command, String body, Piped input, boolean feedsPipe) {
            runner.reportError(label(command) + "each needs a command");
            return ExecResult.failure();
        }
```

Wherever `new Piped(...)` was constructed with two arguments elsewhere (none remain after the pipeline change), the compiler will point it out; there are no other uses.

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (188 + 4 + 1 + 6 = 199).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add grep and store pipe builtins"
```

---

### Task 3: `each`

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`

**Interfaces:**
- Consumes: Task 2 (`Piped.items`, `ExecResult.value`, `piped(...)`).

- [ ] **Step 1: Add the failing tests** (append to `ChainExecutorTest.java` before the final `}`)

```java
    @Test
    void eachRunsOncePerItem() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        ExecResult result = run(r, table(), "list | each say hi {value} ; list | each tp");
        assertEquals(List.of("list", "say hi Alex", "say hi Steve", "list", "tp Alex", "tp Steve"), r.ran);
        assertTrue(result.success());
        assertEquals(2, result.result());
    }

    @Test
    void eachCollectsTheRunsValues() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))))
                .on("xp Alex", new ExecResult(true, 5, List.of(Feedback.plain("5"))))
                .on("xp Steve", new ExecResult(true, 7, List.of(Feedback.plain("7"))));
        run(r, table(), "list | each xp {value} | store storage bc:s levels");
        assertEquals("data modify storage bc:s levels set value [5,7]", r.ran.getLast());
        assertEquals(List.of(true, true, false), r.options.stream().skip(1).limit(3).map(RunOptions::swallowSuccess).toList());
    }

    @Test
    void eachFailsWhenARunFailsOrThereAreNoItems() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))))
                .on("empty", new ExecResult(true, 0, List.of(players())))
                .on("say Alex", FAIL);
        run(r, table(), "list | each say || say partial ; empty | each say || say none ; list | each");
        assertEquals(List.of("list", "say Alex", "say Steve", "say partial", "empty", "say none", "list"), r.ran);
        assertEquals(List.of("[5] each had no items", "[8] each needs a command"), r.errors);
    }

    @Test
    void eachRunsCountTowardTheBudget() throws Exception {
        try (DepthGuard.Scope outer = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 3, List.of(players("A", "B", "C"))));
            new ChainExecutor(r, table(), true, 3, 8).execute(NodeParser.parse("list | each say {value}", NodeParser.Mode.SH, 32));
            assertEquals(List.of("list", "say A", "say B"), r.ran);
            assertEquals(List.of("[2] too many commands across nested chains (max 3)"), r.errors);
        }
    }

    @Test
    void eachInAnElevatedChainChecksItems() throws Exception {
        FakeRunner r = new FakeRunner().on("names", new ExecResult(true, 2, List.of(players("Alex", "a\"b"))));
        run(r, elevated(Map.of(), Map.of()), "names | each say {value}");
        assertEquals(List.of("names", "say Alex"), r.ran);
        assertEquals(List.of("[2] a value from a player can only be one plain word or a list of numbers here"), r.errors);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.ChainExecutorTest'`
Expected: FAIL — `eachRunsOncePerItem` (the Task 2 stub reports `each needs a command`).

- [ ] **Step 3: Replace the `each` stub in `ChainExecutor.Run`**

```java
        /**
         * each COMMAND: run COMMAND once per item with that item as its pipe value, through the normal command path
         * (so expansion, the plain-value rule and escaping apply to every run). Output: the values the runs produced.
         */
        private ExecResult each(Node.Command command, String body, Piped input, boolean feedsPipe) {
            if (body.isBlank()) {
                runner.reportError(label(command) + "each needs a command");
                return ExecResult.failure();
            }
            if (input.items().isEmpty()) {
                runner.reportError(label(command) + "each had no items");
                return ExecResult.failure();
            }
            Node.Command run = new Node.Command(body.strip());
            numbers.put(run, numbers.get(command));
            boolean allSucceeded = true;
            int succeeded = 0;
            List<String> collected = new java.util.ArrayList<>();
            for (String item : input.items()) {
                ExecResult result = command(run, new Piped(PipeValue.of(item), input.result(), List.of(item)), feedsPipe).result();
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
                if (budgetExceeded) {
                    // Every further run would be refused the same way.
                    break;
                }
            }
            return new ExecResult(allSucceeded && succeeded > 0, false, succeeded, List.of(), new PipeValue.Items(collected));
        }
```

To stop after the budget is exhausted (so a long list reports one budget error, not one per item), add a field to `Run`: `private boolean budgetExceeded;`, set it to `true` in `command(...)`'s budget branch before returning the failure, and reset it to `false` at the top of `command(...)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (199 + 5 = 204).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add the each pipe builtin"
```

---

### Task 4: Game tests and README

**Files:**
- Create: `src/gametest/java/net/slk/bashcraft/test/BashcraftPlumbingCheck.java`
- Modify: `src/gametest/resources/fabric.mod.json`, `README.md`

- [ ] **Step 1: Write the game test**

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** store, grep and each end to end (spec C2 §6). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftPlumbingCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 15 12 120 15 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("scoreboard players set n bc 5");
            server.runCommand("sh scoreboard players get n bc | store score m bc");
            server.runCommand("execute if score m bc matches 5 run setblock 0 120 15 minecraft:stone");
            check(stone(server, 0), "store score copies a score");

            server.runCommand("sh echo hello | store storage bashcraft:test greeting");
            server.runCommand("execute if data storage bashcraft:test {greeting:\"hello\"} run setblock 1 120 15 minecraft:stone");
            check(stone(server, 1), "store storage writes a string");

            server.runCommand("tag @p add boss");
            server.runCommand("sh data get entity @p Tags | grep boss && setblock 2 120 15 minecraft:stone");
            check(stone(server, 2), "grep finds a tag in an NBT list");
            server.runCommand("sh tag @p list | grep nope || setblock 3 120 15 minecraft:stone");
            check(stone(server, 3), "grep without a match drives ||");

            server.runCommand("sh list | each scoreboard players set {value} bc 9");
            server.runCommand("execute if score Player0 bc matches 9 run setblock 4 120 15 minecraft:stone");
            check(stone(server, 4), "each runs a command per online player");

            server.runCommand("sh list | each scoreboard players get {value} bc | store storage bashcraft:test scores");
            server.runCommand("execute if data storage bashcraft:test {scores:[9]} run setblock 5 120 15 minecraft:stone");
            check(stone(server, 5), "each collects values into a list that store writes");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 15)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftPlumbingCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures. (Tasks 1–3 implement everything; to see the checks fail once, temporarily change `Builtins.classify` to return `NONE` for `store`, run, confirm `Check failed: store score copies a score`, and restore.)

- [ ] **Step 3: Update `README.md`** — add before `### Without /sh`:

````markdown
**Pipe stages:** `store`, `grep` and `each` work on what the previous command produced.

```
/sh scoreboard players get @s kills | store score @s best
/sh locate structure #minecraft:village | store storage bashcraft:places village
/sh list | grep Steve && say Steve is online
/sh list | each msg {value} hi
/sh list | each data get entity {value} XpLevel | store storage bashcraft:stats levels
```

List commands give real items: `/list` (players), `/tag … list`, `/team list`, `/scoreboard players list`, and
`/data get` on an NBT list. Other commands give one item per message. `grep [-v] [-x] TEXT` keeps items containing
TEXT (any case; `-x` whole item, `-v` the others) and fails if none are left. `each COMMAND` runs one command per item
(`{value}` is the item) and passes on the values they produce. `store score HOLDER OBJECTIVE` (numbers, rounded down)
and `store storage ID PATH` run the matching vanilla command as you, so they need its permissions.
````

- [ ] **Step 4: Commit**

```bash
git add src/gametest README.md
git commit -m "Test store, grep and each in game and document them"
```

---

### Task 5: Client support and dedicated server check

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/client/ChainStyles.java`, `src/main/java/net/slk/bashcraft/mixin/client/CommandSuggestionsMixin.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`

**Interfaces:**
- Consumes: `Builtins.Kind.EACH` (Task 2).

- [ ] **Step 1: Add the failing game check to `BashcraftChatCheck.java`** — insert before `context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));`:

```java
            context.runOnClient(client -> ChatProbe.open(client, "/sh list | each gamemode "));
            context.waitFor(client -> ChatProbe.completions(client).contains("creative"), 100);
            context.runOnClient(client -> ChatProbe.open(client, "/sh list | grep x | each tp @s ~ ~1 ~ | store storage bashcraft:t v"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "pipe builtins are not shown as errors");
```

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|Timed out|BUILD"`
Expected: FAIL — the `waitFor` for `creative` times out (builtins get no suggestions).

- [ ] **Step 3: Highlight `each`'s body in `ChainStyles.compute`** — replace the builtin skip

```java
            if (Builtins.classify(value.substring(span.start(), span.end())).kind() != Builtins.Kind.NONE) {
                continue;
            }
```
with:

```java
            int start = span.start();
            Builtins.Kind kind = Builtins.classify(value.substring(span.start(), span.end())).kind();
            if (kind == Builtins.Kind.EACH && span.end() - span.start() > 5) {
                start = span.start() + 5;
            } else if (kind != Builtins.Kind.NONE) {
                continue;
            }
```
and in the lines that follow, use `start` instead of `span.start()` for the reader cursor (`reader.setCursor(start);`).

- [ ] **Step 4: Treat `each`'s body as a command in `CommandSuggestionsMixin`**
- Replace `bashcraft$builtinAt` with:

```java
    /** Whether the cursor is in a builtin vanilla can't parse (x=…, unset, echo, grep, store; each's own word). */
    @Unique
    private boolean bashcraft$builtinAt(ChainLayout layout, int cursor) {
        ChainLayout.Window window = layout.window(cursor);
        String value = input.getValue();
        if (window.start() >= window.end()) {
            return false;
        }
        Builtins.Kind kind = Builtins.classify(value.substring(window.start(), Math.min(window.end(), value.length())).strip()).kind();
        return kind != Builtins.Kind.NONE && !(kind == Builtins.Kind.EACH && cursor >= window.start() + 5);
    }
```
- In `bashcraft$parseCommandUnderCursor`, after computing `window`, move its start past `each `:

```java
            String value = input.getValue();
            int start = window.start();
            if (window.start() < window.end() && cursorInEachBody(value, window)) {
                start = window.start() + 5;
            }
            StringReader command = new StringReader(Placeholders.mask(Expander.mask(value.substring(0, window.end()))));
            command.setCursor(start);
```
with the helper:

```java
    @Unique
    private boolean cursorInEachBody(String value, ChainLayout.Window window) {
        return Builtins.classify(value.substring(window.start(), Math.min(window.end(), value.length())).strip()).kind() == Builtins.Kind.EACH
                && input.getCursorPosition() >= window.start() + 5;
    }
```

- [ ] **Step 5: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 6: Dedicated server check**

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "sh echo a b | grep a | store storage bashcraft:t v ; data get storage bashcraft:t v"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-c2.log 2>&1
grep -E "storage|Done \(" build/server-c2.log | head
grep -E "WARN|ERROR" build/server-c2.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: the `data get` reply shows `["a b"]` (echo's one message is one item), `Done (`, `no bashcraft warnings`.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Highlight and complete each bodies; colour pipe builtins"
```
