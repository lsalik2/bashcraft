# BashCraft Expansion Implementation Plan (Sub-project C1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Variables (`x=…`, `$x`, `${x}`, `my.`/`global.` scopes, persisted in command storage), `$?`, `$(…)` command substitution and the builtins `unset` and `echo`, inside chains and `/sh` lines.

**Architecture:** The tokenizer keeps `$( … )` as one unsplittable span. A pure `Expander` scans a command's text for `$` references (honouring quotes and `\$`) and replaces them using a `Variables` interface and a substitution callback. A pure `Builtins` classifier recognises assignment/`unset`/`echo` on the *unexpanded* text. `ChainExecutor` runs, per command: classify → expand → inject pipe placeholders → builtin or vanilla, tracking `$?`, and runs `$(…)` as a nested chain whose output becomes a pipe value. `StorageVariables` backs `Variables` with command storage `bashcraft:vars` through a pure `VariableTable` that holds the scope rules and limits.

**Tech Stack:** as before (Java 25, Minecraft 26.3, Fabric, JUnit 5.14.2, client game tests headless on Xvfb).

**Spec:** `docs/superpowers/specs/2026-10-09-bashcraft-expansion-design.md`

## Global Constraints

- All earlier constraints (`JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`; pure packages `chain`/`pipe`/`exec`/`config` import no `net.minecraft.*`/`net.fabricmc.*`; `BashCraft: ` prefix; no `Co-Authored-By`; Synergy read-only; server port 25569; game tests run headless — the build does this automatically).
- Names `[A-Za-z_][A-Za-z0-9_]*`, optional `my.`/`global.` prefix. Limits: 256 variables per set, 1024 characters per value.
- Storage `bashcraft:vars` = `{global:{…}, players:{"<uuid>":{…}}}`, string values.
- Error texts (after the `[n] ` label): `$NAME is not set`, `bad ${…}`, `$(…) produced no value`, `$(…) nested deeper than N`, `too many variables (max 256)`, `value longer than 1024 characters`, `variables are not available here`, `unset: bad name NAME`.
- **Plan decision:** quote helpers move to a public `chain.Quotes` (`opensQuote`, `closingQuote`, `substitutionEnd`), used by `Tokenizer`, `ChainFormatter` and `exec.Expander`, so all three agree on quoting.
- **Plan decision:** builtins are recognised on the command's raw text before expansion (as bash recognises assignments), so a variable whose value contains `=` never turns a command into an assignment.
- **Plan decision:** in the client companion, builtin commands are coloured grey (with their `$` references purple) and are not handed to vanilla's parser, which would show `x=5` as an unknown command.

## Review Focus

1. **`$` in ordinary text** (`say costs $5`, a lone `$`, `'$x'` in single quotes) must stay literal. → Task 2 test `literalDollarsStay`.
2. **JSON with escaped quotes and `$` inside double quotes** must expand the variable and keep the JSON intact. → Task 2 test `doubleQuotesExpandSingleQuotesDont`.
3. **Values containing operators** (`x='a && /b'` then `say $x`) must not be split again. → Task 3 test `expandedValuesAreNotReparsed`.
4. **Deeply nested or recursive substitution** must stop at the depth/budget limits. → Task 3 test `substitutionDepthIsLimited`.
5. **Words that only start like builtins** (`echoes hi`, `unsetall`, `x == y`) must run as vanilla commands. → Task 3 test `builtinsNeedExactWords`.

---

## File Structure

```
src/main/java/net/slk/bashcraft/
  chain/Quotes.java          NEW  quote/substitution span helpers (public)
  chain/Tokenizer.java       MOD  $( … ) opaque, \$ kept; uses Quotes
  chain/ChainFormatter.java  MOD  copies $( … ) and \$ verbatim; uses Quotes
  exec/ExpansionException.java NEW
  exec/Variables.java        NEW  interface + Scope, Ref, ref(), NONE
  exec/VariableTable.java    NEW  pure maps with scope rules and limits
  exec/Expander.java         NEW  scan/expand/mask
  exec/Builtins.java         NEW  classify/unquote
  exec/CommandRunner.java    MOD  + echo
  exec/ChainExecutor.java    MOD  builtins, expansion, $?, $(…)
  mc/StorageVariables.java   NEW  Variables on command storage
  mc/MinecraftCommandRunner.java MOD + echo
  command/ShCommand.java     MOD  wires StorageVariables + maxDepth
  client/ChainStyles.java    MOD  $ references purple, builtins grey
  mixin/client/CommandSuggestionsMixin.java MOD masking + builtin handling
src/test/java/net/slk/bashcraft/...  NodeParserTest, ChainFormatterTest (MOD), ExpanderTest, VariableTableTest, BuiltinsTest (NEW), ChainExecutorTest + FakeRunner (MOD)
src/gametest/...  BashcraftVariableCheck (NEW), read_global.mcfunction (NEW), BashcraftChatCheck (MOD)
```

---

### Task 1: `$( … )` spans in the tokenizer and formatter

**Files:**
- Create: `src/main/java/net/slk/bashcraft/chain/Quotes.java`
- Modify: `src/main/java/net/slk/bashcraft/chain/Tokenizer.java`, `src/main/java/net/slk/bashcraft/chain/ChainFormatter.java`
- Test: `src/test/java/net/slk/bashcraft/chain/NodeParserTest.java`, `src/test/java/net/slk/bashcraft/chain/ChainFormatterTest.java`

**Interfaces:**
- Produces: `Quotes.opensQuote(String, int)`, `Quotes.closingQuote(String, int)`, `Quotes.substitutionEnd(String text, int dollar)` → index of the closing `)` or -1 (all `public static`). `Tokenizer.opensQuote/closingQuote` are removed.

- [ ] **Step 1: Add the failing tests** — append to `NodeParserTest.java` (before the final `}`):

```java
    @Test
    void substitutionsAreNeverSplit() throws Exception {
        assertEquals(new Node.Sequence(List.of(cmd("say $(a && b)"), cmd("c")), List.of(Operator.AND)), sh("say $(a && b) && c"));
        assertEquals(new Node.Sequence(List.of(cmd("say $( (a) | b )"), cmd("c")), List.of(Operator.SEQ)), sh("say $( (a) | b ) ; c"));
        assertEquals(new Node.Sequence(List.of(cmd("say $(\"x)\" && y)"), cmd("c")), List.of(Operator.SEQ)), sh("say $(\"x)\" && y) ; c"));
    }

    @Test
    void transparentSubstitutionsAreNeverSplit() throws Exception {
        assertEquals(new Node.Sequence(List.of(cmd("say $(a && /b)"), cmd("c")), List.of(Operator.AND)),
                NodeParser.parse("say $(a && /b) && /c", TRANSPARENT, 32));
    }

    @Test
    void unclosedSubstitutionIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("say $(a"));
        assertEquals("unclosed '$(' at col 5", e.getMessage());
    }

    @Test
    void escapedDollarIsKeptAndOpensNothing() throws Exception {
        assertEquals(new Node.Sequence(List.of(cmd("say \\$(a"), cmd("b")), List.of(Operator.AND)), sh("say \\$(a && b"));
    }
```

Append to `ChainFormatterTest.java`:

```java
    @Test
    void roundTripsSubstitutions() throws Exception {
        roundTrips("say $(/a && /b) && /tp @s $(/x | /y) 0");
        roundTrips("say \\$x && /say $( (/a) ; /b )");
        assertEquals("say $(a && b) && c", ChainFormatter.toSh(NodeParser.parse("say $(a && b) && /c", NodeParser.Mode.TRANSPARENT, 32)));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: FAIL — `substitutionsAreNeverSplit` (the `&&` inside `$(` splits), `unclosedSubstitutionIsAnError`, `roundTripsSubstitutions`.

- [ ] **Step 3: Write `Quotes.java`**

```java
package net.slk.bashcraft.chain;

/** Quoting rules shared by the tokenizer, the formatter and expansion, so they always agree. */
public final class Quotes {
    private Quotes() {}

    /** A quote opens a quoted span unless it follows a letter or digit (so "don't" stays plain text). */
    public static boolean opensQuote(String input, int i) {
        return i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1));
    }

    /** Index of the matching quote, skipping backslash-escaped characters; -1 if unclosed. */
    public static int closingQuote(String input, int start) {
        char quote = input.charAt(start);
        for (int j = start + 1; j < input.length(); j++) {
            char c = input.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j;
            }
        }
        return -1;
    }

    /** Index of the ')' closing the {@code $(} at {@code dollar}, honouring quotes, escapes and nesting; -1 if unclosed. */
    public static int substitutionEnd(String text, int dollar) {
        int depth = 0;
        for (int j = dollar + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if ((c == '"' || c == '\'') && opensQuote(text, j)) {
                int close = closingQuote(text, j);
                if (close < 0) {
                    return -1;
                }
                j = close;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return j;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 4: Update `Tokenizer.java`**
- Delete its `opensQuote` and `closingQuote` methods and replace their uses with `Quotes.opensQuote(...)` / `Quotes.closingQuote(...)`.
- In `run()`, directly after the `if (commandStart) { … }` block, insert:

```java
            if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == '$') {
                // \$ is expansion's escape: keep it for the expander, and don't let a following ( open anything.
                appendText("\\$", i, i + 2);
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '(') {
                int close = Quotes.substitutionEnd(input, i);
                if (close < 0) {
                    throw new ChainSyntaxException("unclosed '$('", i + 1);
                }
                appendText(input.substring(i, close + 1), i, close + 1);
                i = close + 1;
                continue;
            }
```

- [ ] **Step 5: Update `ChainFormatter.escape`** — replace `Tokenizer.opensQuote`/`Tokenizer.closingQuote` with `Quotes.…`, and insert at the top of the loop body (before the quote check):

```java
            if (c == '\\' && i + 1 < command.length() && command.charAt(i + 1) == '$') {
                out.append("\\$");
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                int close = Quotes.substitutionEnd(command, i);
                int end = close < 0 ? command.length() : close + 1;
                out.append(command, i, end);
                i = end;
                continue;
            }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (123 + 5 = 128).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain src/test/java/net/slk/bashcraft/chain
git commit -m "Keep \$( … ) substitutions whole in the tokenizer and formatter"
```

---

### Task 2: Variables and the expander

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/ExpansionException.java`, `Variables.java`, `VariableTable.java`, `Expander.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ExpanderTest.java`, `src/test/java/net/slk/bashcraft/exec/VariableTableTest.java`

**Interfaces:**
- Consumes: `Quotes` (Task 1).
- Produces:
  - `ExpansionException(String message)` (checked).
  - `Variables` with `enum Scope { OWN, GLOBAL, ANY }`, `record Ref(Scope scope, String name)`, `Optional<String> get(Scope, String)`, `void set(Scope, String, String) throws ExpansionException`, `void unset(Scope, String)`, `static Ref ref(String qualified, Scope unscoped)`, `Variables NONE`.
  - `VariableTable(Map<String,String> own, Map<String,String> global)` implements `Variables`; `MAX_VARIABLES = 256`, `MAX_LENGTH = 1024`. Pass the same map twice when the source has no own set.
  - `Expander.Reference(int start, int end, Kind kind, String body)`, `enum Expander.Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, BAD }`; `Expander.scan(String)` → `List<Reference>`; `Expander.expand(String text, Variables variables, int lastStatus, Expander.Substituter substituter) throws ExpansionException`; `interface Expander.Substituter { String run(String chain) throws ExpansionException; }`; `Expander.mask(String)` → same-length text with references replaced by `0`s (escaped dollars kept).

- [ ] **Step 1: Write the failing tests**

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpanderTest {
    private static Variables vars(String... pairs) {
        Map<String, String> own = new HashMap<>();
        Map<String, String> global = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String name = pairs[i];
            if (name.startsWith("global.")) {
                global.put(name.substring(7), pairs[i + 1]);
            } else {
                own.put(name, pairs[i + 1]);
            }
        }
        return new VariableTable(own, global);
    }

    private static String expand(String text, Variables variables) throws ExpansionException {
        return Expander.expand(text, variables, 0, chain -> "<" + chain + ">");
    }

    @Test
    void namesAndBraces() throws Exception {
        assertEquals("say 1 1y", expand("say $x ${x}y", vars("x", "1")));
        assertEquals("tp @s 1.y", expand("tp @s $x.y", vars("x", "1")));
    }

    @Test
    void scopesAndLookupOrder() throws Exception {
        Variables v = vars("r", "own", "global.r", "shared", "global.g", "g");
        assertEquals("own shared own g", expand("$r $global.r $my.r $g", v));
        assertEquals("shared", expand("${global.r}", v));
    }

    @Test
    void statusOfThePreviousCommand() throws Exception {
        assertEquals("say 1", Expander.expand("say $?", vars(), 1, chain -> ""));
    }

    @Test
    void literalDollarsStay() throws Exception {
        assertEquals("say costs $5, $ and $-", expand("say costs $5, $ and $-", vars()));
        assertEquals("say $x", expand("say \\$x", vars()));
        assertEquals("say '$x'", expand("say '$x'", vars("x", "1")));
    }

    @Test
    void doubleQuotesExpandSingleQuotesDont() throws Exception {
        assertEquals("tellraw @a {\"text\":\"a \\\" 1\"}", expand("tellraw @a {\"text\":\"a \\\" $x\"}", vars("x", "1")));
        assertEquals("say \"it's 1\"", expand("say \"it's $x\"", vars("x", "1")));
    }

    @Test
    void substitutionsGoToTheCallback() throws Exception {
        assertEquals("tp @s <a && b>", expand("tp @s $(a && b)", vars()));
        assertEquals("tp @s <(x) | y>", expand("tp @s $((x) | y)", vars()));
    }

    @Test
    void unsetAndBadReferencesAreErrors() {
        assertEquals("$y is not set", assertThrows(ExpansionException.class, () -> expand("say $y", vars())).getMessage());
        assertEquals("$global.y is not set", assertThrows(ExpansionException.class, () -> expand("say ${global.y}", vars())).getMessage());
        assertEquals("bad ${…}", assertThrows(ExpansionException.class, () -> expand("say ${1x}", vars())).getMessage());
    }

    @Test
    void maskKeepsLengthAndEscapes() {
        assertEquals("tp @s 00 0000 000000 \\$z 00", Expander.mask("tp @s $x ${y} $(a b) \\$z $?"));
        assertEquals("say '$x'", Expander.mask("say '$x'"));
    }
}
```

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VariableTableTest {
    @Test
    void unscopedReadsOwnThenGlobalAndWritesOwn() throws Exception {
        Map<String, String> own = new HashMap<>();
        Map<String, String> global = new HashMap<>(Map.of("x", "g"));
        VariableTable table = new VariableTable(own, global);
        assertEquals(Optional.of("g"), table.get(Variables.Scope.ANY, "x"));
        table.set(Variables.Scope.ANY, "x", "o");
        assertEquals(Optional.of("o"), table.get(Variables.Scope.ANY, "x"));
        assertEquals(Optional.of("g"), table.get(Variables.Scope.GLOBAL, "x"));
        table.unset(Variables.Scope.OWN, "x");
        assertEquals(Optional.empty(), table.get(Variables.Scope.OWN, "x"));
    }

    @Test
    void sourcesWithoutAPlayerShareTheGlobalSet() throws Exception {
        Map<String, String> global = new HashMap<>();
        VariableTable table = new VariableTable(global, global);
        table.set(Variables.Scope.OWN, "x", "1");
        assertEquals(Optional.of("1"), table.get(Variables.Scope.GLOBAL, "x"));
    }

    @Test
    void limits() throws Exception {
        Map<String, String> own = new HashMap<>();
        VariableTable table = new VariableTable(own, new HashMap<>());
        assertEquals("value longer than 1024 characters",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.OWN, "x", "a".repeat(1025))).getMessage());
        for (int i = 0; i < 256; i++) {
            table.set(Variables.Scope.OWN, "v" + i, "1");
        }
        assertEquals("too many variables (max 256)",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.OWN, "extra", "1")).getMessage());
        table.set(Variables.Scope.OWN, "v0", "2");
        assertEquals(Optional.of("2"), table.get(Variables.Scope.OWN, "v0"));
    }

    @Test
    void refSplitsScopes() {
        assertEquals(new Variables.Ref(Variables.Scope.OWN, "x"), Variables.ref("my.x", Variables.Scope.ANY));
        assertEquals(new Variables.Ref(Variables.Scope.GLOBAL, "x"), Variables.ref("global.x", Variables.Scope.ANY));
        assertEquals(new Variables.Ref(Variables.Scope.ANY, "x"), Variables.ref("x", Variables.Scope.ANY));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class VariableTable`.

- [ ] **Step 3: Write `ExpansionException.java`, `Variables.java`, `VariableTable.java`**

```java
package net.slk.bashcraft.exec;

/** A command's {@code $} references couldn't be expanded, or a variable couldn't be written; the command doesn't run. */
public class ExpansionException extends Exception {
    public ExpansionException(String message) {
        super(message);
    }
}
```

```java
package net.slk.bashcraft.exec;

import java.util.Optional;

/** Where chain variables live (spec C1 §5). Unscoped reads look in the source's own set, then the global one. */
public interface Variables {
    enum Scope { OWN, GLOBAL, ANY }

    record Ref(Scope scope, String name) {}

    Optional<String> get(Scope scope, String name);

    void set(Scope scope, String name, String value) throws ExpansionException;

    void unset(Scope scope, String name);

    /** Splits {@code my.x} / {@code global.x} / {@code x}; an unscoped name gets {@code unscoped}. */
    static Ref ref(String qualified, Scope unscoped) {
        if (qualified.startsWith("my.")) {
            return new Ref(Scope.OWN, qualified.substring(3));
        }
        if (qualified.startsWith("global.")) {
            return new Ref(Scope.GLOBAL, qualified.substring(7));
        }
        return new Ref(unscoped, qualified);
    }

    /** No variables at all (tests and callers without storage). */
    Variables NONE = new Variables() {
        @Override
        public Optional<String> get(Scope scope, String name) {
            return Optional.empty();
        }

        @Override
        public void set(Scope scope, String name, String value) throws ExpansionException {
            throw new ExpansionException("variables are not available here");
        }

        @Override
        public void unset(Scope scope, String name) {}
    };
}
```

```java
package net.slk.bashcraft.exec;

import java.util.Map;
import java.util.Optional;

/**
 * Two variable sets in memory with the scope rules and limits (spec C1 §5). For a source without a player, pass the
 * same map as both sets.
 */
public final class VariableTable implements Variables {
    public static final int MAX_VARIABLES = 256;
    public static final int MAX_LENGTH = 1024;

    private final Map<String, String> own;
    private final Map<String, String> global;

    public VariableTable(Map<String, String> own, Map<String, String> global) {
        this.own = own;
        this.global = global;
    }

    @Override
    public Optional<String> get(Scope scope, String name) {
        return switch (scope) {
            case OWN -> Optional.ofNullable(own.get(name));
            case GLOBAL -> Optional.ofNullable(global.get(name));
            case ANY -> Optional.ofNullable(own.get(name)).or(() -> Optional.ofNullable(global.get(name)));
        };
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        Map<String, String> target = scope == Scope.GLOBAL ? global : own;
        if (value.length() > MAX_LENGTH) {
            throw new ExpansionException("value longer than " + MAX_LENGTH + " characters");
        }
        if (!target.containsKey(name) && target.size() >= MAX_VARIABLES) {
            throw new ExpansionException("too many variables (max " + MAX_VARIABLES + ")");
        }
        target.put(name, value);
    }

    @Override
    public void unset(Scope scope, String name) {
        (scope == Scope.GLOBAL ? global : own).remove(name);
    }
}
```

- [ ] **Step 4: Write `Expander.java`**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code $} references in one command's text (spec C1 §3): {@code $NAME}, {@code ${NAME}}, scopes,
 * {@code $?}, {@code $(…)} and {@code \$}. Double-quoted text expands; single-quoted text doesn't.
 */
public final class Expander {
    public enum Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, BAD }

    /** {@code [start, end)} of one reference; body is the qualified name, the inner chain, or an error message. */
    public record Reference(int start, int end, Kind kind, String body) {}

    /** Runs the chain inside {@code $( … )} and returns the value it produced. */
    @FunctionalInterface
    public interface Substituter {
        String run(String chain) throws ExpansionException;
    }

    private static final Pattern NAME = Pattern.compile("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*");

    private Expander() {}

    public static List<Reference> scan(String text) {
        List<Reference> references = new ArrayList<>();
        boolean inDouble = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '$') {
                    references.add(new Reference(i, i + 2, Kind.ESCAPED_DOLLAR, "$"));
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                if (inDouble) {
                    inDouble = false;
                } else if (Quotes.opensQuote(text, i)) {
                    inDouble = true;
                }
                i++;
                continue;
            }
            if (c == '\'' && !inDouble && Quotes.opensQuote(text, i)) {
                int close = Quotes.closingQuote(text, i);
                i = close < 0 ? text.length() : close + 1;
                continue;
            }
            if (c == '$') {
                Reference reference = reference(text, i);
                if (reference != null) {
                    references.add(reference);
                    i = reference.end();
                    continue;
                }
            }
            i++;
        }
        return references;
    }

    private static Reference reference(String text, int i) {
        if (i + 1 >= text.length()) {
            return null;
        }
        char next = text.charAt(i + 1);
        if (next == '?') {
            return new Reference(i, i + 2, Kind.STATUS, "?");
        }
        if (next == '(') {
            int close = Quotes.substitutionEnd(text, i);
            return close < 0
                    ? new Reference(i, text.length(), Kind.BAD, "unclosed $(")
                    : new Reference(i, close + 1, Kind.SUBSTITUTION, text.substring(i + 2, close));
        }
        if (next == '{') {
            int close = text.indexOf('}', i + 2);
            if (close >= 0 && NAME.matcher(text.substring(i + 2, close)).matches()) {
                return new Reference(i, close + 1, Kind.VARIABLE, text.substring(i + 2, close));
            }
            return new Reference(i, close < 0 ? text.length() : close + 1, Kind.BAD, "bad ${…}");
        }
        Matcher m = NAME.matcher(text).region(i + 1, text.length());
        return m.lookingAt() ? new Reference(i, m.end(), Kind.VARIABLE, m.group()) : null;
    }

    public static String expand(String text, Variables variables, int lastStatus, Substituter substituter) throws ExpansionException {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Reference reference : scan(text)) {
            out.append(text, last, reference.start());
            out.append(switch (reference.kind()) {
                case ESCAPED_DOLLAR -> "$";
                case STATUS -> Integer.toString(lastStatus);
                case VARIABLE -> {
                    Variables.Ref ref = Variables.ref(reference.body(), Variables.Scope.ANY);
                    yield variables.get(ref.scope(), ref.name())
                            .orElseThrow(() -> new ExpansionException("$" + reference.body() + " is not set"));
                }
                case SUBSTITUTION -> substituter.run(reference.body());
                case BAD -> throw new ExpansionException(reference.body());
            });
            last = reference.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** Same-length text with every reference except {@code \$} replaced by zeros, for client-side parsing. */
    public static String mask(String text) {
        StringBuilder out = new StringBuilder(text);
        for (Reference reference : scan(text)) {
            if (reference.kind() != Kind.ESCAPED_DOLLAR) {
                for (int i = reference.start(); i < reference.end(); i++) {
                    out.setCharAt(i, '0');
                }
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (128 + 12 = 140).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add variables and the \$ expander"
```

---

### Task 3: Builtins and expansion in the executor

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/Builtins.java`
- Modify: `src/main/java/net/slk/bashcraft/exec/CommandRunner.java`, `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java`, `src/test/java/net/slk/bashcraft/exec/FakeRunner.java`, `src/main/java/net/slk/bashcraft/mc/MinecraftCommandRunner.java` (compile only: `echo` stub replaced in Task 4)
- Test: `src/test/java/net/slk/bashcraft/exec/BuiltinsTest.java`, `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `Builtins.classify(String raw)` → `Builtins.Call(Kind kind, String name, String argument)`; `enum Builtins.Kind { ASSIGN, UNSET, ECHO, NONE }`; for `NONE` the argument is the whole text; `Builtins.unquote(String)`.
  - `CommandRunner.echo(String text, RunOptions options)` → `ExecResult`.
  - `new ChainExecutor(CommandRunner runner, Variables variables, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth)`; the older constructors delegate with `Variables.NONE` and `maxDepth = Integer.MAX_VALUE`.

- [ ] **Step 1: Write `BuiltinsTest.java` and extend `FakeRunner`**

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinsTest {
    @Test
    void assignments() {
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "x", "5"), Builtins.classify("x=5"));
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "global.home", "\"0 80 0\""), Builtins.classify("global.home=\"0 80 0\""));
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "x", ""), Builtins.classify("x="));
    }

    @Test
    void unsetAndEcho() {
        assertEquals(new Builtins.Call(Builtins.Kind.UNSET, "my.x", ""), Builtins.classify("unset my.x"));
        assertEquals(new Builtins.Call(Builtins.Kind.ECHO, "", "a  b"), Builtins.classify("echo a  b"));
        assertEquals(new Builtins.Call(Builtins.Kind.ECHO, "", ""), Builtins.classify("echo"));
    }

    @Test
    void builtinsNeedExactWords() {
        assertEquals(Builtins.Kind.NONE, Builtins.classify("echoes hi").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("unsetall").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("x == y").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("1x=2").kind());
        assertEquals(new Builtins.Call(Builtins.Kind.NONE, "", "say hi"), Builtins.classify("say hi"));
    }

    @Test
    void unquoteRemovesOneMatchingPair() {
        assertEquals("0 80 0", Builtins.unquote("\"0 80 0\""));
        assertEquals("a", Builtins.unquote("'a'"));
        assertEquals("\"a'", Builtins.unquote("\"a'"));
        assertEquals("\"", Builtins.unquote("\""));
    }
}
```

In `FakeRunner.java` add a field and the method:

```java
    final List<String> echoed = new ArrayList<>();

    @Override
    public ExecResult echo(String text, RunOptions options) {
        echoed.add(text);
        this.options.add(options);
        return new ExecResult(true, 1, List.of(net.slk.bashcraft.pipe.Feedback.plain(text)));
    }
```

- [ ] **Step 2: Add the failing executor tests to `ChainExecutorTest.java`** (before the final `}`; add `import java.util.HashMap;`, `import java.util.Map;`, `import java.util.Optional;`)

```java
    private static VariableTable table() {
        Map<String, String> global = new HashMap<>();
        return new VariableTable(new HashMap<>(), global);
    }

    private static ExecResult run(FakeRunner runner, Variables variables, String chain) throws Exception {
        return new ChainExecutor(runner, variables, true, Integer.MAX_VALUE, 8).execute(NodeParser.parse(chain, NodeParser.Mode.SH, 32));
    }

    @Test
    void assignmentIsVisibleToTheNextCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        VariableTable v = table();
        run(r, v, "x=11 && setblock $x 64 0 stone");
        assertEquals(List.of("setblock 11 64 0 stone"), r.ran);
        assertEquals(Optional.of("11"), v.get(Variables.Scope.OWN, "x"));
    }

    @Test
    void quotesAreStrippedAndScopesHonoured() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "home=\"0 80 0\" ; global.k=3 ; my.k=4 ; tp @s $home ; say $k $global.k");
        assertEquals(List.of("tp @s 0 80 0", "say 4 3"), r.ran);
    }

    @Test
    void unsetVariableFailsOnlyThatCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "say $nope || say fallback");
        assertEquals(List.of("[1] $nope is not set"), r.errors);
        assertEquals(List.of("say fallback"), r.ran);
    }

    @Test
    void statusOfThePreviousCommand() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        run(r, table(), "say $? ; a ; say $?");
        assertEquals(List.of("say 0", "a", "say 1"), r.ran);
    }

    @Test
    void substitutionInsertsTheExtractedValueAndHidesItsOutput() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, table(), "tp @s $(locate structure #minecraft:village)");
        assertEquals(List.of("locate structure #minecraft:village", "tp @s 312 ~ -1040"), r.ran);
        assertTrue(r.options.get(0).swallowSuccess());
        assertFalse(r.options.get(1).swallowSuccess());
    }

    @Test
    void substitutionWithoutAValueFails() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "say $(say hi) || say x");
        assertEquals(List.of("[1] $(…) produced no value"), r.errors);
        assertEquals(List.of("say hi", "say x"), r.ran);
    }

    @Test
    void unsetBuiltin() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x=1 ; unset x ; say $x");
        assertEquals(List.of("[3] $x is not set"), r.errors);
    }

    @Test
    void echoIsPipeable() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x=7 ; echo $x | scoreboard players set n obj");
        assertEquals(List.of("7"), r.echoed);
        assertEquals(List.of("scoreboard players set n obj 7"), r.ran);
    }

    @Test
    void assignmentFromAPipe() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        VariableTable v = table();
        run(r, v, "locate structure #minecraft:village | home=");
        assertEquals(Optional.of("312 ~ -1040"), v.get(Variables.Scope.OWN, "home"));
    }

    @Test
    void expandedValuesAreNotReparsed() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='a && /b' ; say $x");
        assertEquals(List.of("say a && /b"), r.ran);
    }

    @Test
    void substitutionDepthIsLimited() throws Exception {
        FakeRunner r = new FakeRunner();
        new ChainExecutor(r, table(), true, Integer.MAX_VALUE, 2).execute(NodeParser.parse("say $(echo $(echo $(echo 1)))", NodeParser.Mode.SH, 32));
        // The innermost failure is reported first; each enclosing $( … ) then reports that it got no value.
        assertEquals("$(…) nested deeper than 2", r.errors.getFirst());
        assertTrue(r.echoed.isEmpty());
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void substitutionCommandsCountTowardTheBudget() throws Exception {
        try (DepthGuard.Scope outer = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner();
            new ChainExecutor(r, table(), true, 2, 8).execute(NodeParser.parse("say $(echo 1 ; echo 2)", NodeParser.Mode.SH, 32));
            assertEquals(List.of("1", "2"), r.echoed);
            assertEquals(List.of("too many commands across nested chains (max 2)"), r.errors);
        }
    }

    @Test
    void withoutVariablesAssignmentIsAnError() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "x=1");
        assertEquals(List.of("variables are not available here"), r.errors);
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class Builtins` / `method echo`.

- [ ] **Step 4: Write `Builtins.java`**

```java
package net.slk.bashcraft.exec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell builtins recognised inside chains (spec C1 §4), on the command's text before expansion. */
public final class Builtins {
    public enum Kind { ASSIGN, UNSET, ECHO, NONE }

    /** For ASSIGN: name and raw value; UNSET: name; ECHO: raw text; NONE: the whole command as argument. */
    public record Call(Kind kind, String name, String argument) {}

    private static final Pattern ASSIGN = Pattern.compile("((?:my\\.|global\\.)?[A-Za-z_][A-Za-z0-9_]*)=(.*)", Pattern.DOTALL);

    private Builtins() {}

    public static Call classify(String command) {
        Matcher m = ASSIGN.matcher(command);
        if (m.matches()) {
            return new Call(Kind.ASSIGN, m.group(1), m.group(2));
        }
        if (command.equals("unset") || command.startsWith("unset ")) {
            return new Call(Kind.UNSET, command.substring(5).strip(), "");
        }
        if (command.equals("echo") || command.startsWith("echo ")) {
            return new Call(Kind.ECHO, "", command.length() > 5 ? command.substring(5) : "");
        }
        return new Call(Kind.NONE, "", command);
    }

    /** Removes one pair of matching surrounding quotes. */
    public static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
```

- [ ] **Step 5: Add `echo` to `CommandRunner.java`**

```java
    /** Sends {@code text} to the user as ordinary success output (so it can be piped), honouring {@code options}. */
    ExecResult echo(String text, RunOptions options);
```

In `MinecraftCommandRunner.java` add a temporary implementation so the project compiles (replaced in Task 4):

```java
    @Override
    public ExecResult echo(String text, RunOptions options) {
        throw new UnsupportedOperationException("echo is wired up in Task 4");
    }
```

- [ ] **Step 6: Update `ChainExecutor.java`**

Fields and constructors — replace the existing three fields and two constructors with:

```java
    private final CommandRunner runner;
    private final Variables variables;
    private final boolean swallowPipedOutput;
    private final int maxTotalCommands;
    private final int maxDepth;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this(runner, Variables.NONE, swallowPipedOutput, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput, int maxTotalCommands) {
        this(runner, Variables.NONE, swallowPipedOutput, maxTotalCommands, Integer.MAX_VALUE);
    }

    /**
     * @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend})
     * @param maxDepth         nesting limit for {@code $( … )}, shared with nested {@code sh}
     */
    public ChainExecutor(CommandRunner runner, Variables variables, boolean swallowPipedOutput, int maxTotalCommands, int maxDepth) {
        this.runner = runner;
        this.variables = variables;
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
        this.maxDepth = maxDepth;
    }
```

In `Run`, add the status field after `numbered`: `private int lastStatus;`.

Replace `Run.command(...)` with:

```java
        private Outcome command(Node.Command command, Piped input, boolean feedsPipe) {
            Builtins.Call call = Builtins.classify(command.text());
            String text = call.argument();
            if (call.kind() != Builtins.Kind.UNSET) {
                try {
                    text = Expander.expand(text, variables, lastStatus, this::substitute);
                    if (input != null) {
                        text = Placeholders.inject(text, input.value(), input.result());
                    }
                } catch (ExpansionException | PipeException e) {
                    runner.reportError(label(command) + e.getMessage());
                    return finish(ExecResult.failure(), command);
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return finish(ExecResult.failure(), command);
            }
            String prefix = numbered ? label(command) : null;
            RunOptions options = new RunOptions(feedsPipe && swallowPipedOutput, prefix);
            ExecResult result = switch (call.kind()) {
                case NONE -> runner.run(text, options);
                case ECHO -> runner.echo(text, options);
                case ASSIGN -> assign(command, call.name(), Builtins.unquote(text.strip()));
                case UNSET -> unset(command, call.name());
            };
            return finish(result, command);
        }

        private Outcome finish(ExecResult result, Node.Command command) {
            lastStatus = result.success() ? 0 : 1;
            return new Outcome(result, command.text());
        }

        private ExecResult assign(Node.Command command, String name, String value) {
            Variables.Ref ref = Variables.ref(name, Variables.Scope.OWN);
            try {
                variables.set(ref.scope(), ref.name(), value);
                return new ExecResult(true, 1, List.of());
            } catch (ExpansionException e) {
                runner.reportError(label(command) + e.getMessage());
                return ExecResult.failure();
            }
        }

        private ExecResult unset(Node.Command command, String name) {
            if (!name.matches("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*")) {
                runner.reportError(label(command) + "unset: bad name " + name);
                return ExecResult.failure();
            }
            Variables.Ref ref = Variables.ref(name, Variables.Scope.OWN);
            variables.unset(ref.scope(), ref.name());
            return new ExecResult(true, 1, List.of());
        }

        /** Runs the chain inside $( … ) with its output hidden and returns the value extracted from it. */
        private String substitute(String chain) throws ExpansionException {
            Optional<DepthGuard.Scope> scope = DepthGuard.enter(maxDepth);
            if (scope.isEmpty()) {
                throw new ExpansionException("$(…) nested deeper than " + maxDepth);
            }
            try (DepthGuard.Scope ignored = scope.get()) {
                Node node;
                try {
                    node = NodeParser.parse(chain, NodeParser.Mode.SH, Integer.MAX_VALUE);
                } catch (ChainSyntaxException e) {
                    throw new ExpansionException("$(…): " + e.getMessage());
                }
                Map<Node.Command, Integer> innerNumbers = new IdentityHashMap<>();
                number(node, innerNumbers);
                ExecResult result = new Run(innerNumbers, false).node(node, null, true).result();
                if (result.errored()) {
                    throw new ExpansionException("$(…) produced no value");
                }
                try {
                    return PipeResolver.resolve(result.output()).asArgument();
                } catch (PipeException e) {
                    throw new ExpansionException("$(…) produced no value");
                }
            }
        }
```

Add imports: `net.slk.bashcraft.chain.ChainSyntaxException`, `net.slk.bashcraft.chain.NodeParser`, `java.util.Optional`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (140 + 4 + 13 = 157).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/main/java/net/slk/bashcraft/mc/MinecraftCommandRunner.java src/test/java/net/slk/bashcraft/exec
git commit -m "Run builtins, expand variables, \$? and \$( … ) in the executor"
```

---

### Task 4: Storage, echo and `/sh` wiring in Minecraft

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mc/StorageVariables.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftVariableCheck.java`, `src/gametest/resources/data/bashcraft-test/function/read_global.mcfunction`
- Modify: `src/main/java/net/slk/bashcraft/mc/MinecraftCommandRunner.java`, `src/main/java/net/slk/bashcraft/command/ShCommand.java`, `src/gametest/resources/fabric.mod.json`, `README.md`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces: `StorageVariables.of(CommandSourceStack)` → `Variables`.

- [ ] **Step 1: Write the game test and its function**

`src/gametest/resources/data/bashcraft-test/function/read_global.mcfunction`:
```
execute if data storage bashcraft:vars global{x:"1"} run setblock 6 120 10 minecraft:stone
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

/** Variables, $?, $( … ) and echo end to end (spec C1 §7). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftVariableCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 10 12 120 12 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("sh x=1 && setblock $x 120 10 minecraft:stone");
            check(stone(server, 1, 10), "an assignment is visible to the next command");
            server.runCommand("sh setblock $global.x 120 11 minecraft:stone");
            check(stone(server, 1, 11), "variables persist to the next line; the console writes the global set");
            server.runCommand("function bashcraft-test:read_global");
            check(stone(server, 6, 10), "datapacks read the global set from command storage");

            server.runCommand("execute as @p run sh my.p=3");
            server.runCommand("sh setblock $p 120 10 minecraft:stone || setblock 4 120 10 minecraft:stone");
            check(!stone(server, 3, 10) && stone(server, 4, 10), "the console can't see a player's own variables");
            server.runCommand("execute as @p run sh setblock $p 120 12 minecraft:stone");
            check(stone(server, 3, 12), "the player sees their own variable");

            server.runCommand("scoreboard players set n bc 5");
            server.runCommand("sh setblock $(scoreboard players get n bc) 120 10 minecraft:stone");
            check(stone(server, 5, 10), "$( … ) inserts a command's value");

            server.runCommand("sh echo 7 | setblock {value} 120 10 minecraft:stone");
            check(stone(server, 7, 10), "echo output pipes");

            server.runCommand("sh setblock 1 120 10 minecraft:stone ; scoreboard players set s bc $?");
            server.runCommand("execute if score s bc matches 1 run setblock 8 120 10 minecraft:stone");
            check(stone(server, 8, 10), "$? is 1 after a failed command");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftVariableCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: `Check failed: an assignment is visible to the next command` (`/sh` still uses `Variables.NONE`).

- [ ] **Step 3: Write `mc/StorageVariables.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.VariableTable;
import net.slk.bashcraft.exec.Variables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Chain variables in command storage {@code bashcraft:vars} = {@code {global:{…}, players:{"<uuid>":{…}}}} (spec C1 §5).
 * A source whose entity is a player has that player's own set; every other source's own set is the global one.
 */
public final class StorageVariables implements Variables {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("bashcraft", "vars");

    private final CommandStorage storage;
    private final String player;

    private StorageVariables(CommandStorage storage, String player) {
        this.storage = storage;
        this.player = player;
    }

    public static StorageVariables of(CommandSourceStack source) {
        String player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer.getUUID().toString() : null;
        return new StorageVariables(source.getServer().getCommandStorage(), player);
    }

    @Override
    public Optional<String> get(Scope scope, String name) {
        return load().table().get(scope, name);
    }

    @Override
    public void set(Scope scope, String name, String value) throws ExpansionException {
        Loaded loaded = load();
        loaded.table().set(scope, name, value);
        save(loaded);
    }

    @Override
    public void unset(Scope scope, String name) {
        Loaded loaded = load();
        loaded.table().unset(scope, name);
        save(loaded);
    }

    private record Loaded(CompoundTag root, Map<String, String> own, Map<String, String> global, VariableTable table) {}

    private Loaded load() {
        CompoundTag root = storage.get(ID).copy();
        Map<String, String> global = strings(root.getCompoundOrEmpty("global"));
        Map<String, String> own = player == null ? global : strings(root.getCompoundOrEmpty("players").getCompoundOrEmpty(player));
        return new Loaded(root, own, global, new VariableTable(own, global));
    }

    private void save(Loaded loaded) {
        loaded.root().put("global", tag(loaded.global()));
        if (player != null) {
            CompoundTag players = loaded.root().getCompoundOrEmpty("players");
            players.put(player, tag(loaded.own()));
            loaded.root().put("players", players);
        }
        storage.set(ID, loaded.root());
    }

    private static Map<String, String> strings(CompoundTag tag) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : tag.keySet()) {
            tag.getString(key).ifPresent(value -> map.put(key, value));
        }
        return map;
    }

    private static CompoundTag tag(Map<String, String> map) {
        CompoundTag tag = new CompoundTag();
        map.forEach(tag::putString);
        return tag;
    }
}
```

- [ ] **Step 4: Implement `echo` in `MinecraftCommandRunner.java`** — replace the Task 3 stub:

```java
    /** Sends the text through the same tee as a command's success output, so it is captured, hidden or numbered alike. */
    @Override
    public ExecResult echo(String text, RunOptions options) {
        TeeSource tee = new TeeSource(((CommandSourceStackAccessor) invoker).bashcraft$source(), options);
        invoker.withSource(tee).sendSuccess(() -> Component.literal(text), false);
        List<Feedback> output = tee.captured().stream().map(ComponentFeedback::toFeedback).toList();
        return new ExecResult(true, 1, output);
    }
```

- [ ] **Step 5: Wire `ShCommand.execute`** — replace the executor construction with:

```java
            return new ChainExecutor(new MinecraftCommandRunner(source), StorageVariables.of(source),
                    config.swallowPipedOutput, config.maxTotalCommands, config.maxDepth).execute(chain);
```
Add `import net.slk.bashcraft.mc.StorageVariables;`.

- [ ] **Step 6: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 7: Update `README.md`** — add before `### Without /sh`:

````markdown
**Variables:** inside chains and `/sh` lines.

```
/sh x=11 && setblock $x 64 0 stone
/sh home="0 80 0" ; tp @s $home
/sh tp @s $(locate structure #minecraft:village)
/sh say done ; say status was $?
```

`$x` / `${x}` read a variable; `my.x` and `global.x` pick a set (players have their own, the console, command
blocks and functions share the global one; `$x` checks your own first). `$?` is `0` if the previous command
succeeded, else `1`. `$( … )` runs a chain and inserts its value like a pipe would. `unset x` removes a variable and
`echo text` prints it (and can be piped). Variables persist in command storage `bashcraft:vars`
(`global` and `players.<uuid>`), so datapacks can use them with `/data`. Expansion works in `"…"` but not in `'…'`;
`\$` is a literal `$`. An unset variable makes that command fail instead of running with a blank. Inside chains,
`echo` and `unset` are BashCraft's even if a mod has commands with those names.
````

- [ ] **Step 8: Commit**

```bash
git add src README.md
git commit -m "Store chain variables in command storage and wire echo and /sh"
```

---

### Task 5: Client highlighting of `$` references and builtins

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/client/ChainStyles.java`, `src/main/java/net/slk/bashcraft/mixin/client/CommandSuggestionsMixin.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`

**Interfaces:**
- Consumes: `Expander.scan/mask`, `Builtins.classify` (Tasks 2–3).

- [ ] **Step 1: Add the failing game check to `BashcraftChatCheck.java`** — insert before `context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));`:

```java
            context.runOnClient(client -> ChatProbe.open(client, "/sh x=5 && setblock $x 64 $(scoreboard players get n bc) minecraft:stone"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "variables, substitutions and assignments are not shown as errors");
            context.takeScreenshot("bashcraft-variables");
```

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: `Check failed: variables, substitutions and assignments are not shown as errors`.

- [ ] **Step 3: Update `ChainStyles.compute`**
- Change the masking line to `String masked = Placeholders.mask(Expander.mask(value));`.
- At the top of the `for (ChainLayout.Span span : layout.spans())` loop body, after the non-command `continue`, add:

```java
            if (Builtins.classify(value.substring(span.start(), span.end())).kind() != Builtins.Kind.NONE) {
                continue;
            }
```
- After the placeholder loop, add:

```java
        for (Expander.Reference reference : Expander.scan(value)) {
            if (reference.kind() != Expander.Kind.ESCAPED_DOLLAR && reference.kind() != Expander.Kind.BAD) {
                fill(styles, reference.start(), reference.end(), PLACEHOLDER);
            }
        }
```
Imports: `net.slk.bashcraft.exec.Builtins`, `net.slk.bashcraft.exec.Expander`.

- [ ] **Step 4: Update `CommandSuggestionsMixin`**
- In `bashcraft$parseCommandUnderCursor`, change `Placeholders.mask(input.getValue().substring(0, window.end()))` to `Placeholders.mask(Expander.mask(input.getValue().substring(0, window.end())))`.
- In `bashcraft$noSuggestionsAfterGroup`, extend the condition so builtins get no suggestions:

```java
            if (layout != null && (!layout.commandCanStartAt(cursor) || bashcraft$builtinAt(layout, cursor))) {
                return Suggestions.empty();
            }
```
- Add the helper and clear vanilla's usage lines for builtins at the start of `bashcraft$chainError` (before the `layout.error()` check):

```java
    @Unique
    private boolean bashcraft$builtinAt(ChainLayout layout, int cursor) {
        ChainLayout.Window window = layout.window(cursor);
        String value = input.getValue();
        return window.start() < window.end()
                && Builtins.classify(value.substring(window.start(), Math.min(window.end(), value.length())).strip()).kind() != Builtins.Kind.NONE;
    }
```

```java
        if (layout != null && bashcraft$builtinAt(layout, input.getCursorPosition())) {
            commandUsage.clear();
        }
```
Imports: `net.slk.bashcraft.exec.Builtins`, `net.slk.bashcraft.exec.Expander`.

- [ ] **Step 5: Run all tests and look at the screenshot**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD|chat highlighting failed"`
Expected: `BUILD SUCCESSFUL`, no failures. Read `build/run/clientGameTest/screenshots/*bashcraft-variables.png`: `$x` and `$( … )` purple, `x=5` grey, no red.

- [ ] **Step 6: Dedicated server check**

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "sh n=4 && say value $n ; say status $?"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-c1.log 2>&1
grep -E "\[Server\]|Done \(" build/server-c1.log
grep -E "WARN|ERROR" build/server-c1.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: `[Server] value 4`, `[Server] status 0`, `Done (`, `no bashcraft warnings`.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Highlight variables, substitutions and builtins in the chat box"
```
