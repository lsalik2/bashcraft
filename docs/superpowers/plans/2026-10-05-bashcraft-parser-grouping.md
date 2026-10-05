# BashCraft Parser and Grouping Implementation Plan (Sub-project A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat chain parser with a tokenizer + recursive-descent parser producing a syntax tree, and add `( … )` grouping everywhere chains work.

**Architecture:** `Tokenizer` (text runs and operators, quoting/escapes, where `(` opens and `)` closes a group) feeds `NodeParser` (one method per grammar level) which builds a sealed `Node` tree. `ChainExecutor` evaluates the tree recursively. The new parser is introduced next to the old one, callers move over task by task, and the old `ChainParser`/`Chain`/`Pipeline` are deleted once nothing uses them, so the suite stays green at every commit.

**Tech Stack:** as M1/M2 — Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18-SNAPSHOT, JUnit 5.14.2, client game tests.

**Spec:** `docs/superpowers/specs/2026-10-05-bashcraft-parser-design.md`

## Global Constraints

- All earlier constraints apply: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`, packages `chain`/`pipe`/`exec`/`config` import no `net.minecraft.*`/`net.fabricmc.*`, user-facing errors start with `BashCraft: `, no `Co-Authored-By` trailers, Synergy is read-only (no `gradlew --stop`, no pattern kills, dedicated server on port 25569).
- Every existing behaviour is kept (spec §1 criterion 1). The two intended changes: `\(`/`\)` drop the backslash; a line starting with `(` or `/(` is a chain without `/sh`.
- Escapable characters: `& | ; \ ( )`. Group nesting cap: **16**.
- Error format: `BashCraft: <message> at col N`, then a grey line `[...]<up to 10 chars><--[HERE]`.
- **Plan decisions (deviations from spec wording, recorded here so executors don't re-litigate):**
  - `Node.Command` holds only `text` (no column): columns matter only for parse errors, which the parser reports itself; a column field would break `parse(toSh(n), SH).equals(n)` (spec §6).
  - A group that feeds a pipe hides the success output of *every* command in it, not just the last one that ran: which command runs last is only known afterwards, and output can't be un-shown. Errors are always shown.
  - `ChainFormatter` writes an extra `/` before command text that itself starts with `/`, so `say a && //say b` round-trips (fixes the M2 deferred minor).

## Review Focus

1. **Parentheses in ordinary text** — smileys (`say :)`), `say (hi)`, JSON strings containing `)` — must not break chains or open groups. → Task 1 tests `textParenthesesStayText`, `quotedParenthesesStayText`.
2. **Transparent lines with `(` mid-text** (`say (hi) && b`) must stay single vanilla commands. → Task 1 test `transparentTextParenthesesAreNotGroups`.
3. **The `\(` / `\)` behaviour change** must be exactly "backslash dropped", nothing else. → Task 1 test `backslashParenthesesAreLiteral`.
4. **Pathological input** (deeply nested `((((…`) must fail cleanly, not overflow the stack. → Task 1 test `nestingDeeperThan16IsAnError` plus `deepNestingDoesNotOverflow`.
5. **Function files with groups**, including continuation lines, must rewrite to `sh` lines that run the same tree. → Task 3 test `groupLinesBecomeShLines`.

---

## File Structure

```
src/main/java/net/slk/bashcraft/chain/
  Node.java                 NEW  sealed syntax tree: Command, Pipeline, Sequence, Group
  Tokenizer.java            NEW  text runs + operators with columns
  NodeParser.java           NEW  recursive descent → Node (Mode SH / TRANSPARENT)
  ChainSyntaxException.java MOD  + context(String input)
  ChainFormatter.java       MOD  toSh(Node)
  TransparentChains.java    MOD  detect → Optional<Node>
  FunctionLines.java        MOD  Node types
  ChainParser.java, Chain.java, Pipeline.java   DELETED in Task 3
src/main/java/net/slk/bashcraft/exec/ChainExecutor.java   MOD  execute(Node)
src/main/java/net/slk/bashcraft/command/ShCommand.java    MOD  NodeParser, context line
src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java  MOD
src/test/java/net/slk/bashcraft/chain/Flat.java           NEW  test helper: Node → old flat shape
src/test/java/net/slk/bashcraft/chain/NodeParserTest.java NEW  (old ChainParserTest ported + group tests)
src/gametest/java/net/slk/bashcraft/test/BashcraftGroupCheck.java  NEW
```

---

### Task 1: Syntax tree, tokenizer and parser

**Files:**
- Create: `src/main/java/net/slk/bashcraft/chain/Node.java`, `Tokenizer.java`, `NodeParser.java`
- Modify: `src/main/java/net/slk/bashcraft/chain/ChainSyntaxException.java`
- Test: `src/test/java/net/slk/bashcraft/chain/Flat.java`, `src/test/java/net/slk/bashcraft/chain/NodeParserTest.java`, `src/test/java/net/slk/bashcraft/chain/ChainSyntaxExceptionTest.java`

**Interfaces:**
- Consumes: existing `Operator { AND, OR, SEQ }`, `ChainSyntaxException(String message, int column)`.
- Produces:
  - `sealed interface Node` with `int commandCount()`, `boolean containsGroup()`; records `Node.Command(String text)`, `Node.Pipeline(List<Node> stages)`, `Node.Sequence(List<Node> items, List<Operator> joins)`, `Node.Group(Node body)`.
  - `NodeParser.parse(String input, NodeParser.Mode mode, int maxSegments) throws ChainSyntaxException` → `Node`; `enum NodeParser.Mode { SH, TRANSPARENT }`.
  - `Tokenizer.opensQuote(String, int)` and `Tokenizer.closingQuote(String, int)` (package-private static; used by `ChainFormatter` in Task 3).
  - `ChainSyntaxException.context(String input)` → `String`.
  - Test helper `Flat.commands(Node)` → `List<List<String>>`, `Flat.joins(Node)` → `List<Operator>`.

- [ ] **Step 1: Write the test helper `Flat.java` and the context test**

```java
package net.slk.bashcraft.chain;

import java.util.List;

/** Views a group-free tree in the old flat shape: pipelines (lists of command texts) and the joins between them. */
final class Flat {
    private Flat() {}

    static List<List<String>> commands(Node node) {
        List<Node> items = node instanceof Node.Sequence sequence ? sequence.items() : List.of(node);
        return items.stream().map(Flat::stage).toList();
    }

    static List<Operator> joins(Node node) {
        return node instanceof Node.Sequence sequence ? sequence.joins() : List.of();
    }

    private static List<String> stage(Node item) {
        if (item instanceof Node.Pipeline pipeline) {
            return pipeline.stages().stream().map(stage -> ((Node.Command) stage).text()).toList();
        }
        return List.of(((Node.Command) item).text());
    }
}
```

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainSyntaxExceptionTest {
    @Test
    void contextShowsTextBeforeTheError() {
        assertEquals("say <--[HERE]", new ChainSyntaxException("unclosed \"", 5).context("say \"oops"));
    }

    @Test
    void contextKeepsTenCharactersLikeVanilla() {
        String input = "setblock 0 64 0 stone && && say";
        assertEquals("... stone && <--[HERE]", new ChainSyntaxException("unexpected '&&'", 26).context(input));
    }

    @Test
    void contextAtEndOfInput() {
        assertEquals("/a &&<--[HERE]", new ChainSyntaxException("missing command after '&&'", 6).context("/a &&"));
    }
}
```

- [ ] **Step 2: Port the old parser tests into `NodeParserTest.java`**

```bash
sed -e 's/class ChainParserTest/class NodeParserTest/' \
    -e 's/import static net.slk.bashcraft.chain.ChainParser.Mode.SH;/import static net.slk.bashcraft.chain.NodeParser.Mode.SH;/' \
    -e 's/import static net.slk.bashcraft.chain.ChainParser.Mode.TRANSPARENT;/import static net.slk.bashcraft.chain.NodeParser.Mode.TRANSPARENT;/' \
    -e 's/ChainParser\.parse(/NodeParser.parse(/g' \
    -e 's/private static Chain sh(/private static Node sh(/' \
    -e 's/Chain chain = /Node chain = /g' \
    -e 's/commands(chain)/Flat.commands(chain)/g' \
    -e 's/commands(sh(/Flat.commands(sh(/g' \
    -e 's/commands(NodeParser.parse(/Flat.commands(NodeParser.parse(/g' \
    -e 's/chain\.joins()/Flat.joins(chain)/g' \
    -e 's/chain\.segmentCount()/chain.commandCount()/g' \
    src/test/java/net/slk/bashcraft/chain/ChainParserTest.java > src/test/java/net/slk/bashcraft/chain/NodeParserTest.java
python3 - <<'EOF'
p = 'src/test/java/net/slk/bashcraft/chain/NodeParserTest.java'
s = open(p).read()
start = s.index('    private static List<List<String>> commands(Chain chain) {')
end = s.index('    }\n', start) + len('    }\n\n')
s = s[:start] + s[end:]
open(p, 'w').write(s)
EOF
grep -c "ChainParser\|Chain chain\|commands(Chain" src/test/java/net/slk/bashcraft/chain/NodeParserTest.java
```
Expected: the final `grep -c` prints `0`.

- [ ] **Step 3: Append the group tests to `NodeParserTest.java`** (insert before the final closing `}` of the class)

```java
    private static Node.Command cmd(String text) {
        return new Node.Command(text);
    }

    @Test
    void groupBindsBeforeOperators() throws Exception {
        Node expected = new Node.Sequence(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.OR))),
                cmd("c")), List.of(Operator.AND));
        assertEquals(expected, sh("(a || b) && c"));
    }

    @Test
    void groupsNestAndPipe() throws Exception {
        Node expected = new Node.Group(new Node.Pipeline(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.SEQ))),
                cmd("c"))));
        assertEquals(expected, sh("((a ; b) | c)"));
        assertEquals(3, expected.commandCount());
        assertTrue(expected.containsGroup());
        assertFalse(sh("a | b && c").containsGroup());
    }

    @Test
    void slashBeforeAGroupIsAllowed() throws Exception {
        Node expected = new Node.Sequence(List.of(cmd("a"),
                new Node.Group(new Node.Sequence(List.of(cmd("b"), cmd("c")), List.of(Operator.SEQ)))), List.of(Operator.AND));
        assertEquals(expected, sh("a && /(b ; c)"));
    }

    @Test
    void textParenthesesStayText() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("say (b)"), cmd("c")), List.of(Operator.OR))), sh("(say (b) || c)"));
        assertEquals(new Node.Sequence(List.of(cmd("say (hi)"), cmd("c")), List.of(Operator.AND)), sh("say (hi) && c"));
        assertEquals(cmd("say :)"), sh("say :)"));
    }

    @Test
    void quotedParenthesesStayText() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("tellraw @a {\"text\":\":)\"}"), cmd("c")), List.of(Operator.OR))),
                sh("(tellraw @a {\"text\":\":)\"} || c)"));
    }

    @Test
    void backslashParenthesesAreLiteral() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("say )"), cmd("c")), List.of(Operator.OR))), sh("(say \\) || c)"));
        assertEquals(cmd("(not a group)"), sh("\\(not a group\\)"));
    }

    @Test
    void emptyGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("a && ()"));
        assertEquals("empty group at col 6", e.getMessage());
    }

    @Test
    void unclosedGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(a && b"));
        assertEquals("unclosed '(' at col 1", e.getMessage());
    }

    @Test
    void textAfterAGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(a) b"));
        assertEquals("unexpected text after ')' at col 5", e.getMessage());
    }

    @Test
    void nestingDeeperThan16IsAnError() throws Exception {
        assertEquals(1, sh("(".repeat(16) + "a" + ")".repeat(16)).commandCount());
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(".repeat(17) + "a" + ")".repeat(17)));
        assertEquals("groups nested deeper than 16 at col 17", e.getMessage());
    }

    @Test
    void deepNestingDoesNotOverflow() {
        assertThrows(ChainSyntaxException.class, () -> sh("(".repeat(100_000) + "a"));
    }

    @Test
    void transparentGroupsAtTheStartAndAfterOperators() throws Exception {
        Node expected = new Node.Sequence(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.OR))), cmd("c")), List.of(Operator.AND));
        assertEquals(expected, NodeParser.parse("(/a || /b) && /c", TRANSPARENT, 32));
        assertEquals(expected, NodeParser.parse("/(/a || /b) && /c", TRANSPARENT, 32));
        assertEquals(new Node.Sequence(List.of(cmd("say a"), new Node.Group(cmd("b"))), List.of(Operator.AND)),
                NodeParser.parse("say a && (b)", TRANSPARENT, 32));
    }

    @Test
    void transparentTextParenthesesAreNotGroups() throws Exception {
        assertEquals(cmd("say (hi) && b"), NodeParser.parse("say (hi) && b", TRANSPARENT, 32));
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: compilation FAIL — `cannot find symbol: class Node`.

- [ ] **Step 5: Write `Node.java`**

```java
package net.slk.bashcraft.chain;

import java.util.List;

/** A parsed chain (spec §4). The parser returns the smallest node: a lone command is a bare {@link Command}. */
public sealed interface Node {
    int commandCount();

    boolean containsGroup();

    /** One command: leading slash stripped, escapes resolved, quotes kept verbatim. */
    record Command(String text) implements Node {
        @Override
        public int commandCount() {
            return 1;
        }

        @Override
        public boolean containsGroup() {
            return false;
        }
    }

    /** Two or more stages joined by {@code |}; each stage is a Command or Group. */
    record Pipeline(List<Node> stages) implements Node {
        public Pipeline {
            stages = List.copyOf(stages);
        }

        @Override
        public int commandCount() {
            return stages.stream().mapToInt(Node::commandCount).sum();
        }

        @Override
        public boolean containsGroup() {
            return stages.stream().anyMatch(Node::containsGroup);
        }
    }

    /** Two or more items joined by {@code && || ;}, evaluated left to right; {@code joins.get(i)} sits between items i and i + 1. */
    record Sequence(List<Node> items, List<Operator> joins) implements Node {
        public Sequence {
            items = List.copyOf(items);
            joins = List.copyOf(joins);
            if (joins.size() != items.size() - 1) {
                throw new IllegalArgumentException("a sequence needs one fewer join than items");
            }
        }

        @Override
        public int commandCount() {
            return items.stream().mapToInt(Node::commandCount).sum();
        }

        @Override
        public boolean containsGroup() {
            return items.stream().anyMatch(Node::containsGroup);
        }
    }

    /** {@code ( … )}. */
    record Group(Node body) implements Node {
        @Override
        public int commandCount() {
            return body.commandCount();
        }

        @Override
        public boolean containsGroup() {
            return true;
        }
    }
}
```

- [ ] **Step 6: Add `context` to `ChainSyntaxException.java`** (inside the class, after `column()`)

```java
    /** Vanilla-style context line: up to 10 characters of input before the error, then {@code <--[HERE]}. */
    public String context(String input) {
        int cursor = Math.min(input.length(), Math.max(0, column - 1));
        return (cursor > 10 ? "..." : "") + input.substring(Math.max(0, cursor - 10), cursor) + "<--[HERE]";
    }
```

- [ ] **Step 7: Write `Tokenizer.java`**

```java
package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into text runs and operators (spec §3). Knows quoting and escapes, where {@code (} opens a
 * group (at a command position, optionally after {@code /}) and when {@code )} closes one (no unmatched {@code (} in
 * the current command's text, and a group is open).
 */
final class Tokenizer {
    enum Kind { TEXT, AND, OR, PIPE, SEQ, OPEN, CLOSE, END }

    /** @param column 1-based column of the token (for text: of its first non-space character) */
    record Token(Kind kind, String text, int column) {}

    private static final String ESCAPABLE = "&|;\\()";

    private final String input;
    private final NodeParser.Mode mode;
    private final List<Token> tokens = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private int textColumn = -1;
    private int textParens;
    private int groups;
    private boolean commandStart = true;

    private Tokenizer(String input, NodeParser.Mode mode) {
        this.input = input;
        this.mode = mode;
    }

    static List<Token> tokenize(String input, NodeParser.Mode mode) throws ChainSyntaxException {
        return new Tokenizer(input, mode).run();
    }

    private List<Token> run() throws ChainSyntaxException {
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (commandStart) {
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int open = groupOpenAt(i);
                if (open >= 0) {
                    emit(Kind.OPEN, "(", open);
                    i = open + 1;
                    continue;
                }
                commandStart = false;
            }
            if ((c == '"' || c == '\'') && opensQuote(input, i)) {
                int close = closingQuote(input, i);
                if (close < 0) {
                    throw new ChainSyntaxException("unclosed " + c, i + 1);
                }
                appendText(input.substring(i, close + 1), i);
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < input.length() && ESCAPABLE.indexOf(input.charAt(i + 1)) >= 0) {
                appendText(String.valueOf(input.charAt(i + 1)), i);
                i += 2;
                continue;
            }
            if (c == '(') {
                textParens++;
                appendText("(", i);
                i++;
                continue;
            }
            if (c == ')') {
                if (textParens > 0) {
                    textParens--;
                    appendText(")", i);
                } else if (groups > 0) {
                    emit(Kind.CLOSE, ")", i);
                } else {
                    appendText(")", i);
                }
                i++;
                continue;
            }
            Kind op = operatorAt(i);
            if (op != null) {
                int length = op == Kind.AND || op == Kind.OR ? 2 : 1;
                if (mode == NodeParser.Mode.SH || nextIsCommandStart(i + length)) {
                    emit(op, input.substring(i, i + length), i);
                    i += length;
                    continue;
                }
            }
            appendText(String.valueOf(c), i);
            i++;
        }
        flushText();
        tokens.add(new Token(Kind.END, "", input.length() + 1));
        return tokens;
    }

    /** Index of the '(' that opens a group at this command position ({@code (} or {@code /(}), else -1. */
    private int groupOpenAt(int i) {
        if (input.charAt(i) == '(') {
            return i;
        }
        if (input.charAt(i) == '/' && i + 1 < input.length() && input.charAt(i + 1) == '(') {
            return i + 1;
        }
        return -1;
    }

    private Kind operatorAt(int i) {
        if (input.startsWith("&&", i)) return Kind.AND;
        if (input.startsWith("||", i)) return Kind.OR;
        char c = input.charAt(i);
        if (c == '|') return Kind.PIPE;
        if (c == ';') return Kind.SEQ;
        return null;
    }

    /** Transparent mode: an operator counts only when the next non-space character is '/' or '('. */
    private boolean nextIsCommandStart(int from) {
        int j = from;
        while (j < input.length() && Character.isWhitespace(input.charAt(j))) {
            j++;
        }
        return j < input.length() && (input.charAt(j) == '/' || input.charAt(j) == '(');
    }

    private void appendText(String s, int index) {
        if (textColumn < 0 && !s.isBlank()) {
            textColumn = index + 1;
        }
        text.append(s);
    }

    private void emit(Kind kind, String tokenText, int index) {
        flushText();
        tokens.add(new Token(kind, tokenText, index + 1));
        textParens = 0;
        if (kind == Kind.OPEN) {
            groups++;
        } else if (kind == Kind.CLOSE) {
            groups--;
        }
        commandStart = kind != Kind.CLOSE;
    }

    private void flushText() {
        if (!text.toString().isBlank()) {
            tokens.add(new Token(Kind.TEXT, text.toString(), textColumn));
        }
        text.setLength(0);
        textColumn = -1;
    }

    /** A quote opens a quoted span unless it follows a letter or digit (so "don't" stays plain text). */
    static boolean opensQuote(String input, int i) {
        return i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1));
    }

    /** Index of the matching quote, skipping backslash-escaped characters; -1 if unclosed. */
    static int closingQuote(String input, int start) {
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
}
```

- [ ] **Step 8: Write `NodeParser.java`**

```java
package net.slk.bashcraft.chain;

import net.slk.bashcraft.chain.Tokenizer.Kind;
import net.slk.bashcraft.chain.Tokenizer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for chains (spec §2). {@code &&}, {@code ||} and {@code ;} are kept in one left-to-right
 * {@link Node.Sequence}: evaluating them in order with bash's status rules gives the same result as nesting
 * {@code &&}/{@code ||} inside {@code ;}.
 */
public final class NodeParser {
    public enum Mode {
        /** {@code /sh}: every unquoted operator counts. */
        SH,
        /** Typed directly: an operator only counts when the next non-space character is {@code /} or {@code (}. */
        TRANSPARENT
    }

    static final int MAX_GROUP_DEPTH = 16;

    private final List<Token> tokens;
    private final List<Integer> commandColumns = new ArrayList<>();
    private int pos;
    private int depth;
    private Token lastOperator;

    private NodeParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Node parse(String input, Mode mode, int maxSegments) throws ChainSyntaxException {
        NodeParser parser = new NodeParser(Tokenizer.tokenize(input, mode));
        Node node = parser.sequence();
        Token end = parser.peek();
        if (end.kind() != Kind.END) {
            throw new ChainSyntaxException("unexpected '" + end.text() + "'", end.column());
        }
        if (node.commandCount() > maxSegments) {
            throw new ChainSyntaxException("too many commands (max " + maxSegments + ")", parser.commandColumns.get(maxSegments));
        }
        return node;
    }

    private Node sequence() throws ChainSyntaxException {
        List<Node> items = new ArrayList<>(List.of(pipeline()));
        List<Operator> joins = new ArrayList<>();
        while (true) {
            Operator op = switch (peek().kind()) {
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
        }
        return items.size() == 1 ? items.getFirst() : new Node.Sequence(items, joins);
    }

    private Node pipeline() throws ChainSyntaxException {
        List<Node> stages = new ArrayList<>(List.of(unit()));
        while (peek().kind() == Kind.PIPE) {
            lastOperator = next();
            stages.add(unit());
        }
        return stages.size() == 1 ? stages.getFirst() : new Node.Pipeline(stages);
    }

    private Node unit() throws ChainSyntaxException {
        Token token = peek();
        switch (token.kind()) {
            case TEXT -> {
                next();
                String command = clean(token.text());
                if (command.isEmpty()) {
                    throw new ChainSyntaxException("missing command", token.column());
                }
                commandColumns.add(token.column());
                return new Node.Command(command);
            }
            case OPEN -> {
                next();
                if (++depth > MAX_GROUP_DEPTH) {
                    throw new ChainSyntaxException("groups nested deeper than " + MAX_GROUP_DEPTH, token.column());
                }
                if (peek().kind() == Kind.CLOSE) {
                    throw new ChainSyntaxException("empty group", token.column());
                }
                Node body = sequence();
                if (peek().kind() != Kind.CLOSE) {
                    throw new ChainSyntaxException("unclosed '('", token.column());
                }
                next();
                depth--;
                if (peek().kind() == Kind.TEXT) {
                    throw new ChainSyntaxException("unexpected text after ')'", peek().column());
                }
                return new Node.Group(body);
            }
            case END -> throw lastOperator == null
                    ? new ChainSyntaxException("empty command", 1)
                    : new ChainSyntaxException("missing command after '" + lastOperator.text() + "'", token.column());
            default -> throw new ChainSyntaxException("unexpected '" + token.text() + "'", token.column());
        }
    }

    /** Trimmed, with one leading slash removed. */
    private static String clean(String text) {
        String stripped = text.strip();
        return stripped.startsWith("/") ? stripped.substring(1).strip() : stripped;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        return tokens.get(pos++);
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`; all tests pass (79 existing + 3 context + 19 ported + 13 group tests = 114). If a ported test fails, the tokenizer changed existing behaviour — fix the tokenizer, never the ported test.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain src/test/java/net/slk/bashcraft/chain
git commit -m "Add syntax tree, tokenizer and recursive-descent parser with grouping"
```

---

### Task 2: Executor and /sh on the syntax tree

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/exec/ChainExecutor.java` (rewrite), `src/main/java/net/slk/bashcraft/command/ShCommand.java`, `src/main/java/net/slk/bashcraft/chain/Chain.java` (temporary `toNode()`), `src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`

**Interfaces:**
- Consumes: `Node`, `NodeParser`, `ChainSyntaxException.context` (Task 1); existing `CommandRunner`, `ExecResult`, `RunOptions`, `DepthGuard`, `PipeResolver`, `Placeholders`.
- Produces: `ChainExecutor.execute(Node)` → `ExecResult` (replaces `execute(Chain)`); `ShCommand.execute(CommandSourceStack, Node)` → `ExecResult`; temporary `Chain.toNode()` → `Node` (deleted in Task 3).

- [ ] **Step 1: Port the executor tests to the new parser**

```bash
sed -i -e 's/import net.slk.bashcraft.chain.ChainParser;/import net.slk.bashcraft.chain.NodeParser;/' \
       -e 's/ChainParser\.parse(/NodeParser.parse(/g' \
       -e 's/ChainParser\.Mode\.SH/NodeParser.Mode.SH/g' \
       src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java
grep -c "ChainParser" src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java
```
Expected: `0`.

- [ ] **Step 2: Add the group tests to `ChainExecutorTest.java`** (before the final `}`)

```java
    @Test
    void groupStatusDrivesTheNextOperator() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "(a || b) && c");
        assertEquals(List.of("a", "c"), r.ran);

        FakeRunner failing = new FakeRunner().on("b", FAIL);
        run(failing, "(a && b) || c");
        assertEquals(List.of("a", "b", "c"), failing.ran);
    }

    @Test
    void skippedGroupDoesNotRun() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "a || (b ; c)");
        assertEquals(List.of("a"), r.ran);
    }

    @Test
    void groupPipesItsLastCommandsOutput() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("locate structure #minecraft:nope", FAIL)
                .on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "(locate structure #minecraft:nope || locate structure #minecraft:village) | tp @s");
        assertEquals(List.of("locate structure #minecraft:nope", "locate structure #minecraft:village", "tp @s 312 ~ -1040"), r.ran);
    }

    @Test
    void pipingIntoAGroupReachesOnlyItsFirstCommand() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 5, List.of(Feedback.plain("5"))));
        run(r, "a | (b ; c)");
        assertEquals(List.of("a", "b 5", "c"), r.ran);
    }

    @Test
    void numberingAndHiddenOutputAcrossGroups() throws Exception {
        FakeRunner r = new FakeRunner().on("b", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "(a ; b) | c");
        assertEquals(List.of("[1] ", "[2] ", "[3] "), r.options.stream().map(RunOptions::failurePrefix).toList());
        assertEquals(List.of(true, true, false), r.options.stream().map(RunOptions::swallowSuccess).toList());
    }

    @Test
    void nothingToPipeFromAGroupNamesItsLastCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "(x ; a) | b");
        assertEquals(List.of("[3] nothing to pipe from /a"), r.errors);
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `incompatible types: Node cannot be converted to Chain` (the executor still takes `Chain`).

- [ ] **Step 4: Rewrite `ChainExecutor.java`**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Node;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Runs a chain's syntax tree with bash semantics (spec §5): && / || / ; by status, | feeds extracted values forward, groups nest. */
public final class ChainExecutor {
    private final CommandRunner runner;
    private final boolean swallowPipedOutput;
    private final int maxTotalCommands;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this(runner, swallowPipedOutput, Integer.MAX_VALUE);
    }

    /** @param maxTotalCommands budget shared by this chain and every chain nested inside it (see {@link DepthGuard#spend}) */
    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput, int maxTotalCommands) {
        this.runner = runner;
        this.swallowPipedOutput = swallowPipedOutput;
        this.maxTotalCommands = maxTotalCommands;
    }

    /** Returns the outcome of the last command that ran (a skipped item leaves the status unchanged). */
    public ExecResult execute(Node root) {
        Map<Node.Command, Integer> numbers = new IdentityHashMap<>();
        number(root, numbers);
        return new Run(numbers, root.commandCount() > 1).node(root, null, false).result();
    }

    /** [n] numbers commands left to right as written, whether or not they run. */
    private static void number(Node node, Map<Node.Command, Integer> numbers) {
        switch (node) {
            case Node.Command command -> numbers.put(command, numbers.size() + 1);
            case Node.Pipeline pipeline -> pipeline.stages().forEach(stage -> number(stage, numbers));
            case Node.Sequence sequence -> sequence.items().forEach(item -> number(item, numbers));
            case Node.Group group -> number(group.body(), numbers);
        }
    }

    private static Node.Command firstCommand(Node node) {
        return switch (node) {
            case Node.Command command -> command;
            case Node.Pipeline pipeline -> firstCommand(pipeline.stages().getFirst());
            case Node.Sequence sequence -> firstCommand(sequence.items().getFirst());
            case Node.Group group -> firstCommand(group.body());
        };
    }

    /** A value piped into a node and the producing command's numeric result ({result}). */
    private record Piped(PipeValue value, int result) {}

    /** What running a node produced; lastCommand is the text of the last command that ran. */
    private record Outcome(ExecResult result, String lastCommand) {}

    private final class Run {
        private final Map<Node.Command, Integer> numbers;
        private final boolean numbered;

        Run(Map<Node.Command, Integer> numbers, boolean numbered) {
            this.numbers = numbers;
            this.numbered = numbered;
        }

        Outcome node(Node node, Piped input, boolean feedsPipe) {
            return switch (node) {
                case Node.Command command -> command(command, input, feedsPipe);
                case Node.Pipeline pipeline -> pipeline(pipeline, input, feedsPipe);
                case Node.Sequence sequence -> sequence(sequence, input, feedsPipe);
                case Node.Group group -> node(group.body(), input, feedsPipe);
            };
        }

        /** A piped value goes to the first item only. Any item may run last, so all hide output when the result feeds a pipe. */
        private Outcome sequence(Node.Sequence sequence, Piped input, boolean feedsPipe) {
            Outcome status = node(sequence.items().getFirst(), input, feedsPipe);
            for (int i = 1; i < sequence.items().size(); i++) {
                boolean run = switch (sequence.joins().get(i - 1)) {
                    case AND -> status.result().success();
                    case OR -> !status.result().success();
                    case SEQ -> true;
                };
                if (run) {
                    status = node(sequence.items().get(i), null, feedsPipe);
                }
            }
            return status;
        }

        private Outcome pipeline(Node.Pipeline pipeline, Piped input, boolean feedsPipe) {
            List<Node> stages = pipeline.stages();
            Outcome previous = node(stages.getFirst(), input, true);
            for (int i = 1; i < stages.size(); i++) {
                // A result of 0 or less is still a value (a score, a coordinate, a distance); only an error stops the pipe.
                if (previous.result().errored()) {
                    return previous;
                }
                Node stage = stages.get(i);
                PipeValue value;
                try {
                    value = PipeResolver.resolve(previous.result().output());
                } catch (PipeException e) {
                    runner.reportError(label(firstCommand(stage)) + "nothing to pipe from /" + previous.lastCommand());
                    return new Outcome(ExecResult.failure(), previous.lastCommand());
                }
                previous = node(stage, new Piped(value, previous.result().result()), i < stages.size() - 1 || feedsPipe);
            }
            return previous;
        }

        private Outcome command(Node.Command command, Piped input, boolean feedsPipe) {
            String text = command.text();
            if (input != null) {
                try {
                    text = Placeholders.inject(text, input.value(), input.result());
                } catch (PipeException e) {
                    runner.reportError(label(command) + e.getMessage());
                    return new Outcome(ExecResult.failure(), command.text());
                }
            }
            if (!DepthGuard.spend(maxTotalCommands)) {
                runner.reportError(label(command) + "too many commands across nested chains (max " + maxTotalCommands + ")");
                return new Outcome(ExecResult.failure(), command.text());
            }
            String prefix = numbered ? label(command) : null;
            return new Outcome(runner.run(text, new RunOptions(feedsPipe && swallowPipedOutput, prefix)), command.text());
        }

        private String label(Node.Command command) {
            return numbered ? "[" + numbers.get(command) + "] " : "";
        }
    }
}
```

- [ ] **Step 5: Add the temporary converter to `Chain.java`** (inside the record; deleted in Task 3)

```java
    /** Temporary bridge for callers not yet on Node (removed with this class in Task 3). */
    public Node toNode() {
        List<Node> items = pipelines.stream()
                .map(p -> p.commands().size() == 1
                        ? (Node) new Node.Command(p.commands().getFirst())
                        : new Node.Pipeline(p.commands().stream().<Node>map(Node.Command::new).toList()))
                .toList();
        return items.size() == 1 ? items.getFirst() : new Node.Sequence(items, joins);
    }
```

- [ ] **Step 6: Move `ShCommand` onto the tree** — replace the `run` and `execute` methods:

```java
    /** Parses and runs a chain as {@code source}; returns the last command's outcome (a failure for parse or limit errors). */
    public static ExecResult run(CommandSourceStack source, String input) {
        Node chain;
        try {
            chain = NodeParser.parse(input, NodeParser.Mode.SH, BashcraftConfig.get().maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            source.sendFailure(Component.literal(e.context(input)).withStyle(ChatFormatting.GRAY));
            return ExecResult.failure();
        }
        return execute(source, chain);
    }

    /** Runs an already-parsed chain as {@code source} (used by /sh and by transparent mode). */
    public static ExecResult execute(CommandSourceStack source, Node chain) {
        BashcraftConfig config = BashcraftConfig.get();
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            source.sendFailure(Component.literal("BashCraft: chains nested deeper than " + config.maxDepth));
            return ExecResult.failure();
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            return new ChainExecutor(new MinecraftCommandRunner(source), config.swallowPipedOutput, config.maxTotalCommands).execute(chain);
        }
    }
```
Imports: replace `net.slk.bashcraft.chain.Chain` and `net.slk.bashcraft.chain.ChainParser` with `net.slk.bashcraft.chain.Node` and `net.slk.bashcraft.chain.NodeParser`; add `net.minecraft.ChatFormatting`.

- [ ] **Step 7: Bridge the transparent mixin** — in `CommandsTransparentMixin.java` change `TransparentChains.detect(command, config.maxSegments).ifPresent(chain -> {` to `TransparentChains.detect(command, config.maxSegments).map(Chain::toNode).ifPresent(chain -> {` and add `import net.slk.bashcraft.chain.Chain;`.

- [ ] **Step 8: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "FAILED|Check failed|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`; unit tests 120 (114 + 6); game tests unchanged and passing.

- [ ] **Step 9: Commit**

```bash
git add src
git commit -m "Run chains as syntax trees, with groups, in the executor and /sh"
```

---

### Task 3: Formatter, transparent detection and function lines on the tree; remove the flat parser

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/chain/ChainFormatter.java`, `TransparentChains.java`, `FunctionLines.java`, `src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java`
- Delete: `src/main/java/net/slk/bashcraft/chain/ChainParser.java`, `Chain.java`, `Pipeline.java`, `src/test/java/net/slk/bashcraft/chain/ChainParserTest.java`
- Test: `src/test/java/net/slk/bashcraft/chain/ChainFormatterTest.java`, `TransparentChainsTest.java`, `FunctionLinesTest.java`

**Interfaces:**
- Consumes: `Node`, `NodeParser`, `Tokenizer.opensQuote/closingQuote` (Task 1); `ShCommand.execute(CommandSourceStack, Node)` (Task 2).
- Produces: `ChainFormatter.toSh(Node)` → `String`; `TransparentChains.detect(String, int)` → `Optional<Node>`.

- [ ] **Step 1: Rewrite `ChainFormatterTest.java`**

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainFormatterTest {
    private static void roundTrips(String transparentLine) throws Exception {
        Node node = NodeParser.parse(transparentLine, NodeParser.Mode.TRANSPARENT, 32);
        String sh = ChainFormatter.toSh(node);
        assertEquals(node, NodeParser.parse(sh, NodeParser.Mode.SH, 32), sh);
    }

    @Test
    void formatsOperatorsAndPipes() throws Exception {
        assertEquals("a | b && c ; d || e", ChainFormatter.toSh(NodeParser.parse("a | /b && /c ; /d || /e", NodeParser.Mode.TRANSPARENT, 32)));
    }

    @Test
    void operatorsThatTransparentModeIgnoredAreEscaped() throws Exception {
        Node node = NodeParser.parse("say lunch; then && /say a | b", NodeParser.Mode.TRANSPARENT, 32);
        assertEquals("say lunch\\; then && say a \\| b", ChainFormatter.toSh(node));
        roundTrips("say lunch; then && /say a | b");
    }

    @Test
    void formatsGroupsAndEscapesTextParentheses() throws Exception {
        assertEquals("(a || b) && c", ChainFormatter.toSh(NodeParser.parse("(/a || /b) && /c", NodeParser.Mode.TRANSPARENT, 32)));
        assertEquals("say \\(hi\\) && x", ChainFormatter.toSh(NodeParser.parse("say (hi) && /x", NodeParser.Mode.TRANSPARENT, 32)));
    }

    @Test
    void roundTripsQuotesBackslashesAndPlaceholders() throws Exception {
        roundTrips("tellraw @a {\"text\":\"a \\\" | b\"} && /say don't; x");
        roundTrips("give @s stick[custom_name='a;b'] ; /tp @s \\{x} {y} 0");
        roundTrips("say a\\b && /say c\\\\d");
    }

    @Test
    void roundTripsGroups() throws Exception {
        roundTrips("(/say (b) || /c) | /tp @s {x} 64 {z}");
        roundTrips("((/a ; /b) | /c) && /(/d)");
        roundTrips("(/tellraw @a {\"text\":\":)\"} || /say \\) x)");
    }

    @Test
    void roundTripsADoubledSlash() throws Exception {
        roundTrips("say a && //say b");
    }
}
```

- [ ] **Step 2: Rewrite `TransparentChainsTest.java`**

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TransparentChainsTest {
    private static Optional<Node> detect(String line) {
        return TransparentChains.detect(line, 32);
    }

    @Test
    void slashFollowedOperatorsMakeAChain() {
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")),
                Flat.commands(detect("tp @s ~ ~ ~ && /summon minecraft:zombie").orElseThrow()));
    }

    @Test
    void plainTextIsNotAChain() {
        assertTrue(detect("say lunch; then work").isEmpty());
        assertTrue(detect("say a && b").isEmpty());
        assertTrue(detect("tp @s ~ ~ ~").isEmpty());
        assertTrue(detect("say (hi) && b").isEmpty());
    }

    @Test
    void groupsMakeAChainEvenWithOneCommand() {
        assertEquals(new Node.Group(new Node.Command("say hi")), detect("(/say hi)").orElseThrow());
        assertTrue(detect("/(/a || /b) && /c").isPresent());
    }

    @Test
    void shLinesAreLeftToTheShCommand() {
        assertTrue(detect("sh a && /b").isEmpty());
        assertTrue(detect("/sh a && /b").isEmpty());
    }

    @Test
    void unparseableLinesGoToVanilla() {
        assertTrue(detect("say \"oops && /say b").isEmpty());
        assertTrue(detect("say a && /").isEmpty());
        assertTrue(detect("(/a && /b").isEmpty());
        assertTrue(TransparentChains.detect("a ; /b ; /c", 2).isEmpty());
    }
}
```

- [ ] **Step 3: Add the group test to `FunctionLinesTest.java`** (before the final `}`)

```java
    @Test
    void groupLinesBecomeShLines() {
        assertEquals(List.of("sh (say a ; say b) && say c"), rewrite("(say a ; /say b) && /say c"));
        assertEquals(List.of("sh (say a || say b) && say c", ""), rewrite("(say a || \\", "/say b) && /say c"));
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: compilation FAIL — `ChainFormatter.toSh` and `TransparentChains.detect` still use `Chain`.

- [ ] **Step 5: Rewrite `ChainFormatter.java`**

```java
package net.slk.bashcraft.chain;

/** Writes a chain back as {@code /sh} syntax: {@code NodeParser.parse(toSh(n), SH)} equals {@code n}. */
public final class ChainFormatter {
    private ChainFormatter() {}

    public static String toSh(Node node) {
        return switch (node) {
            case Node.Command command -> command(command.text());
            case Node.Pipeline pipeline -> String.join(" | ", pipeline.stages().stream().map(ChainFormatter::toSh).toList());
            case Node.Sequence sequence -> {
                StringBuilder out = new StringBuilder(toSh(sequence.items().getFirst()));
                for (int i = 1; i < sequence.items().size(); i++) {
                    out.append(switch (sequence.joins().get(i - 1)) {
                        case AND -> " && ";
                        case OR -> " || ";
                        case SEQ -> " ; ";
                    }).append(toSh(sequence.items().get(i)));
                }
                yield out.toString();
            }
            case Node.Group group -> "(" + toSh(group.body()) + ")";
        };
    }

    /** Escapes text; text that itself starts with '/' gets one more, since the parser strips one. */
    private static String command(String text) {
        String escaped = escape(text);
        return text.startsWith("/") ? "/" + escaped : escaped;
    }

    /** Backslash-escapes {@code & | ; \ ( )} outside quoted spans, using the tokenizer's own quote rule. */
    private static String escape(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && Tokenizer.opensQuote(command, i)) {
                int close = Tokenizer.closingQuote(command, i);
                int end = close < 0 ? command.length() : close + 1;
                out.append(command, i, end);
                i = end;
                continue;
            }
            if ("&|;\\()".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
```

- [ ] **Step 6: Move `TransparentChains.java` onto the tree** — replace the `detect` method:

```java
    /**
     * The chain in {@code line} if it has more than one command or any group. Empty for plain vanilla lines, for
     * {@code sh} lines (the command handles those) and for lines that don't parse — those go to vanilla untouched.
     */
    public static Optional<Node> detect(String line, int maxSegments) {
        String command = line.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.equals("sh") || command.startsWith("sh ")) {
            return Optional.empty();
        }
        try {
            Node node = NodeParser.parse(line, NodeParser.Mode.TRANSPARENT, maxSegments);
            return node.commandCount() > 1 || node.containsGroup() ? Optional.of(node) : Optional.empty();
        } catch (ChainSyntaxException e) {
            return Optional.empty();
        }
    }
```

- [ ] **Step 7: Update `FunctionLines.java`** — in `rewriteLine`, change `Optional<Chain> chain = TransparentChains.detect(line, maxSegments);` to `Optional<Node> chain = TransparentChains.detect(line, maxSegments);`.

- [ ] **Step 8: Remove the mixin bridge** — in `CommandsTransparentMixin.java` change `.map(Chain::toNode).ifPresent(` back to `.ifPresent(` and delete `import net.slk.bashcraft.chain.Chain;`.

- [ ] **Step 9: Delete the flat parser**

```bash
git rm -q src/main/java/net/slk/bashcraft/chain/ChainParser.java src/main/java/net/slk/bashcraft/chain/Chain.java \
          src/main/java/net/slk/bashcraft/chain/Pipeline.java src/test/java/net/slk/bashcraft/chain/ChainParserTest.java
grep -rn "ChainParser\|chain\.Chain\b\|chain\.Pipeline\b\|toNode" src || echo "no references left"
```
Expected: `no references left`.

- [ ] **Step 10: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "FAILED|Check failed|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`; unit tests 106 (120 − 19 deleted old parser tests + 3 formatter + 1 transparent + 1 function-lines net new); game tests passing.

- [ ] **Step 11: Commit**

```bash
git add -A src
git commit -m "Format, detect and rewrite chains as syntax trees; remove the flat parser"
```

---

### Task 4: Group game tests, dedicated server check and README

**Files:**
- Create: `src/gametest/java/net/slk/bashcraft/test/BashcraftGroupCheck.java`, `src/gametest/resources/data/bashcraft-test/function/grouped.mcfunction`
- Modify: `src/gametest/resources/fabric.mod.json`, `README.md`

**Interfaces:**
- Consumes: everything above, through `/sh`, transparent mode and function files.

- [ ] **Step 1: Write the function and the game test**

`src/gametest/resources/data/bashcraft-test/function/grouped.mcfunction`:
```
(setblock 5 120 9 minecraft:stone ; /setblock 6 120 9 minecraft:stone) && /setblock 7 120 9 minecraft:stone
```

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import static net.slk.bashcraft.test.Checks.check;

/** Grouping end to end: /sh, transparent console and chat, function files, and a grouped locate fallback. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftGroupCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 9 12 120 9 minecraft:air");

            server.runCommand("sh (setblock 0 120 9 minecraft:stone || setblock 1 120 9 minecraft:stone) && setblock 2 120 9 minecraft:stone");
            check(stone(server, 0) && !stone(server, 1) && stone(server, 2), "/sh groups: || inside, && after");

            server.runCommand("(/setblock 0 120 9 minecraft:stone || /setblock 3 120 9 minecraft:stone) && /setblock 4 120 9 minecraft:stone");
            check(stone(server, 3) && stone(server, 4), "a line starting with a group chains without /sh");

            server.runCommand("function bashcraft-test:grouped");
            check(stone(server, 5) && stone(server, 6) && stone(server, 7), "grouped chains in function files");

            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard objectives add t2 trigger");
            server.runCommand("scoreboard players enable @a t1");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("(/trigger t1 set 3 ; /trigger t2 set 4)"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t1 matches 3 if score @p t2 matches 4 run setblock 8 120 9 minecraft:stone");
            check(stone(server, 8), "a grouped chain typed by the player");
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("bashcraft")).create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("execute as @p run sh (locate structure #minecraft:nonexistent_tag || locate structure #minecraft:village) | tp @s {x} 200 {z}");
            check(server.computeOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                return Math.abs(player.getY() - 200) < 0.01 && (Math.abs(player.getX()) > 1 || Math.abs(player.getZ()) > 1);
            }), "a group pipes the output of the locate that succeeded");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 9)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftGroupCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no `Check failed`. (Tasks 1–3 already implement grouping, so these pass on first run; to see each check fail once, temporarily replace `NodeParser.MAX_GROUP_DEPTH` with `0` — every group then errors — run, confirm `Check failed: /sh groups…`, and restore it.)

- [ ] **Step 3: Dedicated server check**

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "(/say one || /say two) && /say three"; sleep 3; echo "sh (say \"oops"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-groups.log 2>&1
grep -E "\[Server\]|BashCraft|HERE|Done \(" build/server-groups.log
```
Expected: `[Server] one`, `[Server] three` (no `two`), `BashCraft: unclosed " at col 6`, a context line `(say <--[HERE]`, and `Done (`.

- [ ] **Step 4: Update `README.md`** — add after the operator table's explanation paragraph (before `### Without /sh`):

````markdown
**Groups:** `( … )` groups commands, and groups nest:

```
/sh (setblock ~ ~ ~ stone || setblock ~1 ~ ~ stone) && say placed
/(/locate structure #minecraft:village || /locate structure #minecraft:pillager_outpost) | /tp @s {x} 150 {z}
```

A group's result — and its output, when piped — is that of the last command that ran in it. A value piped into a
group goes to its first command. `(` only opens a group where a command could start; elsewhere it's text, as in
`say (hi)`. Inside a group, a `)` without a matching `(` in the same command must be written `\)`.
````

Also replace the Quoting line's escape list `` `\&&`, `\|`, `\;` are literal `` with `` `\&&`, `\|`, `\;`, `\(`, `\)` are literal ``, and in "Without `/sh`" add after its first code block: `` A line can also start with a group: `/(/a || /b) && /c`. ``

- [ ] **Step 5: Commit**

```bash
git add src/gametest README.md
git commit -m "Test grouping in game and document it"
```
