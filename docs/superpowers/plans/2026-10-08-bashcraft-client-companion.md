# BashCraft Client Companion Implementation Plan (Sub-project B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With BashCraft on both sides, chains in chat and the command-block screen are highlighted per command, operators and groups are coloured, BashCraft syntax errors show in the usage area, and tab-completion works for the command under the cursor — including server-computed suggestions.

**Architecture:** A pure `ChainLayout` (token spans from the A tokenizer) decides what is a chain and which part of the line the cursor is in. A `bashcraft:hello` payload turns the client side on. A client mixin on vanilla's `CommandSuggestions` hands vanilla a reader truncated to the command under the cursor (absolute positions, so completion and insertion work unchanged) and draws the line from precomputed per-character styles. A server mixin on `ServerCommandSuggestionsProvider` does the same truncation for server-side suggestion requests.

**Tech Stack:** as before (Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 incl. Networking API v1, MixinExtras, JUnit 5.14.2, client game tests).

**Spec:** `docs/superpowers/specs/2026-10-08-bashcraft-client-companion-design.md`

## Global Constraints

- All earlier constraints apply (`JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`; `chain`/`pipe`/`exec`/`config` import no `net.minecraft.*`/`net.fabricmc.*`; `BashCraft: ` error prefix; no `Co-Authored-By`; Synergy read-only, no `gradlew --stop`, no pattern kills, dedicated server on port 25569).
- Payload `bashcraft:hello`, `protocol = 1`, fields `int protocol, boolean transparentMode`; sent on join only when `ServerPlayNetworking.canSend(handler, TYPE)`.
- Colours: commands as vanilla (`LITERAL` grey, arguments cycling AQUA, YELLOW, GREEN, LIGHT_PURPLE, GOLD, unparsed RED within the command), operators and group parentheses DARK_AQUA, placeholders LIGHT_PURPLE.
- New mixin configs are non-required with `defaultRequire: 0`: `bashcraft.client.mixins.json` (`client` section), `bashcraft.suggest.mixins.json`.
- Client hooks never throw into the UI: any `RuntimeException` is logged once and vanilla's behaviour is used.
- **Plan decision (refines spec §3):** `ChainLayout.commandAt(cursor)` becomes `ChainLayout.window(cursor)` → `Window(start, end)`. Inside a command or its trailing whitespace: `start` = command start, `end` = max(command end, cursor) — vanilla requests completions *at the cursor*, so the reader must reach it. On an operator or before a command: `start = end = cursor`, an empty command there, so vanilla suggests command names.
- **Plan decision:** `TransparentChains.detect` rejects unparseable lines, but the client must recognise an unfinished chain such as `/(/say a || /say b` (spec §1 criterion 3). Client and suggestion detection therefore use `ChainLayout.forInput`, which counts any line with an operator or group token (in the active mode) as a chain.

## Review Focus

1. **Cursor in trailing whitespace or right after an operator** (`/tp @s ~ ~ ~ && /summon |`, `… &&| /x`) must not throw (Brigadier `substring` past the reader) and must suggest sensibly. → Task 1 tests `windowCoversTrailingWhitespace`, `windowOnAnOperatorIsEmpty`.
2. **Unfinished input while typing** (unclosed quote or `(`) must still highlight the finished part and show the BashCraft error. → Task 1 test `partialLayoutOnErrors`; Task 4 game test "unclosed group hint".
3. **Placeholders** must not turn a pipe stage red and must not shift positions. → Task 2 tests `maskKeepsLength`, `escapedPlaceholdersAreNotMasked`.
4. **Plain vanilla lines and chat messages** must look exactly as in vanilla while the companion is active. → Task 1 test `plainLinesAreNotChains`; Task 4 game test "vanilla line unchanged".
5. **Servers without BashCraft** must leave the client vanilla. → Task 3 game test "leaving clears"; Task 4 game test "after clear, vanilla red".

---

## File Structure

```
src/main/java/net/slk/bashcraft/
  chain/Tokenizer.java            MOD  tokens carry raw start/end; lenient tokenize
  chain/NodeParser.java           MOD  uses Token.column()
  chain/ChainLayout.java          NEW  spans, error, window, forInput
  pipe/Placeholders.java          MOD  + mask(String), ranges(String)
  network/HelloPayload.java       NEW
  network/BashcraftNetwork.java   NEW  payload registration + join send
  client/ClientChains.java        NEW  session state + layout(value)
  client/ChainStyles.java         NEW  per-character styles + formatting
  client/ClientHooks.java         NEW  log-once failure helper
  BashcraftClient.java            NEW  client entrypoint
  Bashcraft.java                  MOD  register network
  mixin/client/CommandSuggestionsMixin.java              NEW
  mixin/suggest/ServerCommandSuggestionsProviderMixin.java NEW
src/main/resources/fabric.mod.json, bashcraft.client.mixins.json (NEW), bashcraft.suggest.mixins.json (NEW)
src/test/java/net/slk/bashcraft/chain/ChainLayoutTest.java   NEW
src/test/java/net/slk/bashcraft/pipe/PlaceholdersTest.java   MOD
src/gametest/java/net/slk/bashcraft/test/ChatProbe.java, BashcraftClientCheck.java, BashcraftChatCheck.java   NEW
```

---

### Task 1: Token spans and `ChainLayout`

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/chain/Tokenizer.java`, `src/main/java/net/slk/bashcraft/chain/NodeParser.java`
- Create: `src/main/java/net/slk/bashcraft/chain/ChainLayout.java`
- Test: `src/test/java/net/slk/bashcraft/chain/ChainLayoutTest.java`

**Interfaces:**
- Produces:
  - `Tokenizer.Token(Kind kind, String text, int start, int end)` with `int column()` (= start + 1); `Tokenizer.tokenizeLenient(String, NodeParser.Mode)` → `Tokenizer.Result(List<Token> tokens, ChainSyntaxException error)`.
  - `ChainLayout(List<Span> spans, Optional<ChainSyntaxException> error)`; `enum ChainLayout.Kind { COMMAND, OPERATOR, GROUP_OPEN, GROUP_CLOSE }`; `record ChainLayout.Span(Kind kind, int start, int end)`; `record ChainLayout.Window(int start, int end)`.
  - `ChainLayout.of(String line, int from, NodeParser.Mode mode)`; `ChainLayout.forInput(String value, boolean transparentMode)` → `Optional<ChainLayout>`; `boolean hasChainSyntax()`; `Window window(int cursor)`. All indices are into the full `line`/`value`, end-exclusive.

- [ ] **Step 1: Write the failing tests `ChainLayoutTest.java`**

```java
package net.slk.bashcraft.chain;

import net.slk.bashcraft.chain.ChainLayout.Kind;
import net.slk.bashcraft.chain.ChainLayout.Span;
import net.slk.bashcraft.chain.ChainLayout.Window;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainLayoutTest {
    private static ChainLayout input(String value) {
        return ChainLayout.forInput(value, true).orElseThrow();
    }

    @Test
    void commandsAndOperatorsWithAbsolutePositions() {
        assertEquals(List.of(new Span(Kind.COMMAND, 1, 12), new Span(Kind.OPERATOR, 13, 15), new Span(Kind.COMMAND, 17, 30)),
                input("/tp @s ~ ~ ~ && /summon zombie").spans());
    }

    @Test
    void shLinesStartAfterSh() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 5), new Span(Kind.OPERATOR, 6, 7), new Span(Kind.COMMAND, 8, 9)),
                ChainLayout.forInput("/sh a | b", false).orElseThrow().spans());
    }

    @Test
    void groupsAndSlashes() {
        assertEquals(List.of(new Span(Kind.GROUP_OPEN, 1, 2), new Span(Kind.COMMAND, 3, 8), new Span(Kind.OPERATOR, 9, 11),
                        new Span(Kind.COMMAND, 13, 18), new Span(Kind.GROUP_CLOSE, 18, 19)),
                input("/(/say a || /say b)").spans());
    }

    @Test
    void quotesAndEscapesStayInsideTheCommand() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 16), new Span(Kind.OPERATOR, 17, 19), new Span(Kind.COMMAND, 20, 21)),
                input("/sh say \"a && b\" && c").spans());
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 15)), input("/sh say a \\&& b").spans());
    }

    @Test
    void partialLayoutOnErrors() {
        ChainLayout quote = input("/sh say a && say \"oops");
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 9), new Span(Kind.OPERATOR, 10, 12), new Span(Kind.COMMAND, 13, 16)), quote.spans());
        assertTrue(quote.error().orElseThrow().getMessage().startsWith("unclosed \""));

        ChainLayout group = input("/(/say a || /say b");
        assertEquals(4, group.spans().size());
        assertEquals("unclosed '(' at col 1", group.error().orElseThrow().getMessage());
    }

    @Test
    void plainLinesAreNotChains() {
        assertTrue(ChainLayout.forInput("/tp @s ~ ~ ~", true).isEmpty());
        assertTrue(ChainLayout.forInput("/say a && b", true).isEmpty());
        assertTrue(ChainLayout.forInput("/say I love you && (hug)", true).isEmpty());
        assertTrue(ChainLayout.forInput("/tp @s ~ ~ ~ && /summon zombie", false).isEmpty());
    }

    @Test
    void shLinesCountWithoutTransparentModeAndCommandBlocksNeedNoSlash() {
        assertTrue(ChainLayout.forInput("/sh tp @s ~ ~ ~", false).isPresent());
        assertEquals(new Span(Kind.COMMAND, 0, 11), ChainLayout.forInput("tp @s ~ ~ ~ && /summon zombie", true).orElseThrow().spans().getFirst());
    }

    @Test
    void windowInsideACommand() {
        assertEquals(new Window(1, 12), input("/tp @s ~ ~ ~ && /summon zombie").window(5));
    }

    @Test
    void windowCoversTrailingWhitespace() {
        assertEquals(new Window(17, 24), input("/tp @s ~ ~ ~ && /summon ").window(24));
        assertEquals(new Window(1, 13), input("/tp @s ~ ~ ~ && /summon ").window(13));
    }

    @Test
    void windowOnAnOperatorIsEmpty() {
        ChainLayout layout = input("/tp @s ~ ~ ~ && /summon ");
        assertEquals(new Window(14, 14), layout.window(14));
        assertEquals(new Window(16, 16), layout.window(16));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.ChainLayoutTest'`
Expected: compilation FAIL — `cannot find symbol: class ChainLayout`.

- [ ] **Step 3: Give tokens raw offsets in `Tokenizer.java`**

Replace the token record and the text bookkeeping:

```java
    /** @param start index of the token's first character (for text: first non-space); @param end exclusive */
    record Token(Kind kind, String text, int start, int end) {
        int column() {
            return start + 1;
        }
    }

    /** Tokens up to the first error, and that error (null when there is none). */
    record Result(List<Token> tokens, ChainSyntaxException error) {}
```

Replace the field `private int textColumn = -1;` with:

```java
    private int textStart = -1;
    private int textEnd = -1;
```

Add the lenient entry point after `tokenize`:

```java
    static Result tokenizeLenient(String input, NodeParser.Mode mode) {
        Tokenizer tokenizer = new Tokenizer(input, mode);
        try {
            return new Result(tokenizer.run(), null);
        } catch (ChainSyntaxException e) {
            tokenizer.flushText();
            return new Result(List.copyOf(tokenizer.tokens), e);
        }
    }
```

In `run()`, change every `appendText(x, i)` call to pass the raw end as a third argument:
- quote span: `appendText(input.substring(i, close + 1), i, close + 1);`
- escape: `appendText(String.valueOf(input.charAt(i + 1)), i, i + 2);`
- `(`, `)` and the final single character: `appendText(..., i, i + 1);`

Change the emits to pass lengths: `emit(Kind.OPEN, "(", open, 1);`, `emit(Kind.CLOSE, ")", i, 1);`, `emit(op, input.substring(i, i + length), i, length);`, and the END token to `tokens.add(new Token(Kind.END, "", input.length(), input.length()));`.

Replace `appendText`, `emit` and `flushText`:

```java
    private void appendText(String s, int start, int rawEnd) {
        if (!s.isBlank()) {
            if (textStart < 0) {
                textStart = start;
            }
            textEnd = rawEnd;
        }
        text.append(s);
    }

    private void emit(Kind kind, String tokenText, int index, int length) {
        flushText();
        tokens.add(new Token(kind, tokenText, index, index + length));
        textParens = 0;
        if (kind == Kind.OPEN) {
            groups++;
            usedGroups = true;
        } else if (kind == Kind.CLOSE) {
            groups--;
        }
        commandStart = kind != Kind.CLOSE;
    }

    private void flushText() {
        if (!text.toString().isBlank()) {
            tokens.add(new Token(Kind.TEXT, text.toString(), textStart, textEnd));
        }
        text.setLength(0);
        textStart = -1;
        textEnd = -1;
    }
```

In `NodeParser.java`, every `token.column()` / `end.column()` / `peek().column()` already compiles against the new `column()` method; no change is needed beyond recompiling. Run `grep -n "column" src/main/java/net/slk/bashcraft/chain/NodeParser.java` and confirm every use is a `.column()` call.

- [ ] **Step 4: Write `ChainLayout.java`**

```java
package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where each token of a chain sits in an input line, for highlighting and completion (spec B §3). Unfinished input
 * still yields the spans before the first error, plus that error.
 */
public record ChainLayout(List<Span> spans, Optional<ChainSyntaxException> error) {
    public enum Kind { COMMAND, OPERATOR, GROUP_OPEN, GROUP_CLOSE }

    /** {@code [start, end)} in the line given to {@link #of}. Command spans exclude the leading slash and whitespace. */
    public record Span(Kind kind, int start, int end) {}

    /** The part of the line to parse for the cursor: {@code [start, end)}. Empty when the cursor isn't in a command. */
    public record Window(int start, int end) {}

    public ChainLayout {
        spans = List.copyOf(spans);
    }

    /** Layout of {@code line.substring(from)} in {@code mode}; spans index into {@code line}. */
    public static ChainLayout of(String line, int from, NodeParser.Mode mode) {
        String text = line.substring(from);
        Tokenizer.Result result = Tokenizer.tokenizeLenient(text, mode);
        List<Span> spans = new ArrayList<>();
        for (Tokenizer.Token token : result.tokens()) {
            Kind kind = switch (token.kind()) {
                case TEXT -> Kind.COMMAND;
                case OPEN -> Kind.GROUP_OPEN;
                case CLOSE -> Kind.GROUP_CLOSE;
                case AND, OR, PIPE, SEQ -> Kind.OPERATOR;
                case END -> null;
            };
            if (kind == null) {
                continue;
            }
            int start = token.start();
            int end = token.end();
            if (kind == Kind.COMMAND && text.charAt(start) == '/') {
                start++;
                while (start < end && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
            }
            if (start < end) {
                spans.add(new Span(kind, from + start, from + end));
            }
        }
        ChainSyntaxException error = result.error();
        if (error == null) {
            try {
                NodeParser.parse(text, mode, Integer.MAX_VALUE);
            } catch (ChainSyntaxException e) {
                error = e;
            }
        }
        return new ChainLayout(spans, Optional.ofNullable(error));
    }

    /**
     * The chain in an input-box line ({@code /…} from chat, or a command block's text, where the slash is optional),
     * or empty when it isn't one. {@code sh …} lines always count; other lines only in transparent mode and only when
     * they contain an operator or group (spec B §4).
     */
    public static Optional<ChainLayout> forInput(String value, boolean transparentMode) {
        int start = value.startsWith("/") ? 1 : 0;
        if (value.startsWith("sh ", start)) {
            return Optional.of(of(value, start + 3, NodeParser.Mode.SH));
        }
        if (!transparentMode) {
            return Optional.empty();
        }
        ChainLayout layout = of(value, start, NodeParser.Mode.TRANSPARENT);
        return layout.hasChainSyntax() ? Optional.of(layout) : Optional.empty();
    }

    public boolean hasChainSyntax() {
        return spans.stream().anyMatch(span -> span.kind() != Kind.COMMAND);
    }

    /**
     * Which part of the line to parse for {@code cursor}. In a command or its trailing whitespace (up to the next
     * token): from the command's start to the later of its end and the cursor, since vanilla completes at the cursor.
     * Elsewhere (on an operator, before a command): the empty window at the cursor, i.e. a new command.
     */
    public Window window(int cursor) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (span.kind() != Kind.COMMAND) {
                continue;
            }
            int regionEnd = i + 1 < spans.size() ? spans.get(i + 1).start() : Integer.MAX_VALUE;
            if (cursor >= span.start() && cursor <= regionEnd) {
                return new Window(span.start(), Math.max(span.end(), cursor));
            }
        }
        return new Window(cursor, cursor);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (109 existing + 10 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain src/test/java/net/slk/bashcraft/chain
git commit -m "Add token spans and ChainLayout for client highlighting"
```

---

### Task 2: Placeholder masking

**Files:**
- Modify: `src/main/java/net/slk/bashcraft/pipe/Placeholders.java`
- Test: `src/test/java/net/slk/bashcraft/pipe/PlaceholdersTest.java`

**Interfaces:**
- Produces: `Placeholders.mask(String)` → `String` (same length; each unescaped placeholder replaced by zeros); `Placeholders.ranges(String)` → `List<int[]>` (`{start, end}` of each unescaped placeholder).

- [ ] **Step 1: Add the failing tests to `PlaceholdersTest.java`** (before the final `}`)

```java
    @Test
    void maskKeepsLength() {
        assertEquals("tp @s 000 150 000", Placeholders.mask("tp @s {x} 150 {z}"));
        assertEquals("say 0000000 00000000", Placeholders.mask("say {value} {result}"));
        assertEquals("summon zombie {NoAI:1b}", Placeholders.mask("summon zombie {NoAI:1b}"));
    }

    @Test
    void escapedPlaceholdersAreNotMasked() {
        assertEquals("say \\{x} 000", Placeholders.mask("say \\{x} {y}"));
        assertEquals(List.of(9, 12), Placeholders.ranges("say \\{x} {y}").stream().map(r -> List.of(r[0], r[1])).toList().getFirst());
    }

    @Test
    void rangesFindEveryPlaceholder() {
        assertEquals(List.of(List.of(6, 9), List.of(14, 17)),
                Placeholders.ranges("tp @s {x} 150 {z}").stream().map(r -> List.of(r[0], r[1])).toList());
    }
```
Add `import java.util.List;` to the test's imports.

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.PlaceholdersTest'`
Expected: compilation FAIL — `cannot find symbol: method mask(String)`.

- [ ] **Step 3: Add `mask` and `ranges` to `Placeholders.java`** (inside the class, after `inject`; add `import java.util.ArrayList;` and `import java.util.List;`)

```java
    /** {@code command} with each placeholder replaced by zeros of the same length, so a client-side parse accepts it. */
    public static String mask(String command) {
        StringBuilder out = new StringBuilder(command);
        for (int[] range : ranges(command)) {
            for (int i = range[0]; i < range[1]; i++) {
                out.setCharAt(i, '0');
            }
        }
        return out.toString();
    }

    /** {@code {start, end}} of each placeholder (escaped ones excluded), in order. */
    public static List<int[]> ranges(String command) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = TOKEN.matcher(command);
        while (m.find()) {
            if (!m.group().startsWith("\\")) {
                ranges.add(new int[] {m.start(), m.end()});
            }
        }
        return ranges;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (122).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/slk/bashcraft/pipe src/test/java/net/slk/bashcraft/pipe
git commit -m "Add placeholder masking for client-side parsing"
```

---

### Task 3: Hello handshake and client state

**Files:**
- Create: `src/main/java/net/slk/bashcraft/network/HelloPayload.java`, `src/main/java/net/slk/bashcraft/network/BashcraftNetwork.java`, `src/main/java/net/slk/bashcraft/client/ClientChains.java`, `src/main/java/net/slk/bashcraft/BashcraftClient.java`
- Modify: `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/main/resources/fabric.mod.json`, `src/gametest/resources/fabric.mod.json`
- Test: `src/gametest/java/net/slk/bashcraft/test/BashcraftClientCheck.java`

**Interfaces:**
- Consumes: `ChainLayout.forInput` (Task 1); `BashcraftConfig`, `TransparentHooks.applied()`.
- Produces: `HelloPayload(int protocol, boolean transparentMode)` with `TYPE`, `CODEC`, `PROTOCOL = 1`; `BashcraftNetwork.register()`; `ClientChains.accept(int, boolean)`, `clear()`, `active()`, `layout(String)` → `Optional<ChainLayout>`.

- [ ] **Step 1: Write the game test `BashcraftClientCheck.java`**

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.slk.bashcraft.client.ClientChains;

import static net.slk.bashcraft.test.Checks.check;

/** The server's hello turns the client companion on; leaving the world turns it off. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftClientCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitFor(client -> ClientChains.active(), 100);
            check(context.computeOnClient(client -> ClientChains.layout("/tp @s ~ ~ ~ && /summon zombie").isPresent()),
                    "the hello reports transparent mode, so prefix-free chains are recognised");
            check(context.computeOnClient(client -> ClientChains.layout("/tp @s ~ ~ ~").isEmpty()), "plain commands are not chains");
        }
        check(!context.computeOnClient(client -> ClientChains.active()), "leaving the world clears the companion");
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftClientCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: compilation FAIL — `package net.slk.bashcraft.client does not exist`.

- [ ] **Step 3: Write `network/HelloPayload.java` and `network/BashcraftNetwork.java`**

```java
package net.slk.bashcraft.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.slk.bashcraft.Bashcraft;

/** Server → client on join: this server runs BashCraft, and whether prefix-free chains are on (spec B §2). */
public record HelloPayload(int protocol, boolean transparentMode) implements CustomPacketPayload {
    public static final int PROTOCOL = 1;
    public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Bashcraft.MOD_ID, "hello"));
    public static final StreamCodec<ByteBuf, HelloPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HelloPayload::protocol,
            ByteBufCodecs.BOOL, HelloPayload::transparentMode,
            HelloPayload::new);

    @Override
    public Type<HelloPayload> type() {
        return TYPE;
    }
}
```

```java
package net.slk.bashcraft.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.TransparentHooks;

/** The hello payload: registered on both sides, sent on join to clients that have BashCraft. */
public final class BashcraftNetwork {
    private BashcraftNetwork() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler, HelloPayload.TYPE)) {
                boolean transparent = BashcraftConfig.get().transparentMode && TransparentHooks.applied();
                sender.sendPacket(new HelloPayload(HelloPayload.PROTOCOL, transparent));
            }
        });
    }
}
```

- [ ] **Step 4: Write `client/ClientChains.java` and `BashcraftClient.java`**

```java
package net.slk.bashcraft.client;

import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.network.HelloPayload;

import java.util.Optional;

/** Whether the current server runs BashCraft (it sent a hello), and how it wants input lines read. */
public final class ClientChains {
    private static volatile boolean active;
    private static volatile boolean transparentMode;

    private ClientChains() {}

    public static void accept(int protocol, boolean transparent) {
        active = protocol == HelloPayload.PROTOCOL;
        transparentMode = transparent;
    }

    /** Runs on disconnect: the next server must say hello again. */
    public static void clear() {
        active = false;
        transparentMode = false;
    }

    public static boolean active() {
        return active;
    }

    /** The chain in an input-box line, when the companion is active and the line is one. */
    public static Optional<ChainLayout> layout(String value) {
        return active ? ChainLayout.forInput(value, transparentMode) : Optional.empty();
    }
}
```

```java
package net.slk.bashcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.slk.bashcraft.client.ClientChains;
import net.slk.bashcraft.network.HelloPayload;

/** Client side: listens for the server's hello (spec B §2). */
public class BashcraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE,
                (payload, context) -> ClientChains.accept(payload.protocol(), payload.transparentMode()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientChains.clear());
    }
}
```

- [ ] **Step 5: Wire it up**
- In `Bashcraft.onInitialize()`, after `ShCommand.register();` add `BashcraftNetwork.register();` (import `net.slk.bashcraft.network.BashcraftNetwork`).
- In `src/main/resources/fabric.mod.json`, change `"main": ["net.slk.bashcraft.Bashcraft"]` to `"main": ["net.slk.bashcraft.Bashcraft"],` followed by a new line `"client": ["net.slk.bashcraft.BashcraftClient"]` inside `entrypoints`.

- [ ] **Step 6: Run the game tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no `Check failed`.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Add the hello handshake that turns on the client companion"
```

---

### Task 4: Client highlighting, completion and errors

**Files:**
- Create: `src/main/java/net/slk/bashcraft/client/ChainStyles.java`, `src/main/java/net/slk/bashcraft/client/ClientHooks.java`, `src/main/java/net/slk/bashcraft/mixin/client/CommandSuggestionsMixin.java`, `src/main/resources/bashcraft.client.mixins.json`
- Modify: `src/main/resources/fabric.mod.json`, `src/gametest/resources/fabric.mod.json`
- Test: `src/gametest/java/net/slk/bashcraft/test/ChatProbe.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`

**Interfaces:**
- Consumes: `ChainLayout` (Task 1), `Placeholders.mask/ranges` (Task 2), `ClientChains` (Task 3).
- Produces: `ChainStyles.compute(String value, ChainLayout layout, CommandDispatcher<S> dispatcher, S source)` → `ChainStyles`; `ChainStyles.matches(String)`, `ChainStyles.format(String text, int offset)` → `FormattedCharSequence`; `ClientHooks.failed(RuntimeException)`.

- [ ] **Step 1: Write the test helper `ChatProbe.java`** (reflection works because 26.3 is unobfuscated)

```java
package net.slk.bashcraft.test;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Looks inside the open chat screen's suggestion helper. Test-only. */
final class ChatProbe {
    static final int RED = TextColor.fromLegacyFormat(ChatFormatting.RED).getValue();

    private ChatProbe() {}

    static void open(Minecraft client, String text) {
        client.gui.setScreen(new ChatScreen(text, false));
    }

    static CommandSuggestions suggestions(Minecraft client) {
        return (CommandSuggestions) read(ChatScreen.class, client.gui.screen(), "commandSuggestions");
    }

    static String value(Minecraft client) {
        return ((EditBox) read(ChatScreen.class, client.gui.screen(), "input")).getValue();
    }

    /** Every colour used to draw the whole input line. */
    static Set<Integer> colors(Minecraft client) {
        try {
            Method format = CommandSuggestions.class.getDeclaredMethod("formatChat", String.class, int.class);
            format.setAccessible(true);
            FormattedCharSequence text = (FormattedCharSequence) format.invoke(suggestions(client), value(client), 0);
            Set<Integer> colors = new HashSet<>();
            if (text != null) {
                text.accept((index, style, codePoint) -> {
                    if (style.getColor() != null) {
                        colors.add(style.getColor().getValue());
                    }
                    return true;
                });
            }
            return colors;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The current completion list, empty while it is still pending. */
    @SuppressWarnings("unchecked")
    static List<String> completions(Minecraft client) {
        CompletableFuture<Suggestions> pending = (CompletableFuture<Suggestions>) read(CommandSuggestions.class, suggestions(client), "pendingSuggestions");
        if (pending == null || !pending.isDone() || pending.isCompletedExceptionally()) {
            return List.of();
        }
        return pending.join().getList().stream().map(Suggestion::getText).toList();
    }

    /** The usage/error lines above the input box, as plain text. */
    @SuppressWarnings("unchecked")
    static List<String> usage(Minecraft client) {
        List<FormattedCharSequence> lines = (List<FormattedCharSequence>) read(CommandSuggestions.class, suggestions(client), "commandUsage");
        return lines.stream().map(line -> {
            StringBuilder out = new StringBuilder();
            line.accept((index, style, codePoint) -> {
                out.appendCodePoint(codePoint);
                return true;
            });
            return out.toString();
        }).toList();
    }

    private static Object read(Class<?> owner, Object target, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
```

- [ ] **Step 2: Write the game test `BashcraftChatCheck.java`**

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.slk.bashcraft.client.ClientChains;

import static net.slk.bashcraft.test.Checks.check;

/** The chat box highlights and completes chains like ordinary commands (spec B §1). */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftChatCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitFor(client -> ClientChains.active(), 100);

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~ && /summon "));
            context.waitFor(client -> !ChatProbe.completions(client).isEmpty(), 100);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "a valid chain has no red");
            check(context.computeOnClient(client -> ChatProbe.completions(client).stream().anyMatch(s -> s.endsWith("zombie"))),
                    "the second command gets summon's suggestions");

            context.runOnClient(client -> ChatProbe.open(client, "/locate structure #minecraft:village | /tp @s {x} 150 {z}"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "placeholders don't turn a pipe stage red");
            context.takeScreenshot("bashcraft-chain-highlight");

            context.runOnClient(client -> ChatProbe.open(client, "/(/say a || /say b"));
            context.waitFor(client -> !ChatProbe.usage(client).isEmpty(), 100);
            check(context.computeOnClient(client -> ChatProbe.usage(client).getFirst().startsWith("BashCraft: unclosed '('")),
                    "an unclosed group shows the BashCraft hint");

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~"));
            context.waitTicks(2);
            check(context.computeOnClient(client -> !ChatProbe.colors(client).contains(ChatProbe.RED)), "a vanilla line is unchanged");

            context.runOnClient(client -> ChatProbe.open(client, "/tp @s ~ ~ ~ && /summon "));
            context.runOnClient(client -> {
                ClientChains.clear();
                ChatProbe.suggestions(client).updateCommandInfo();
            });
            check(context.computeOnClient(client -> ChatProbe.colors(client).contains(ChatProbe.RED)),
                    "without a hello (no BashCraft server) the chain is vanilla red again");
            context.runOnClient(client -> client.gui.setScreen(null));
        }
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftChatCheck"` to the `fabric-client-gametest` list.

- [ ] **Step 3: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: `Check failed: a valid chain has no red` (vanilla still parses the whole line).

- [ ] **Step 4: Write `client/ClientHooks.java`**

```java
package net.slk.bashcraft.client;

import net.slk.bashcraft.Bashcraft;

import java.util.concurrent.atomic.AtomicBoolean;

/** Client hooks fall back to vanilla on any error; the first one is logged so it can be reported. */
public final class ClientHooks {
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ClientHooks() {}

    public static void failed(RuntimeException e) {
        if (LOGGED.compareAndSet(false, true)) {
            Bashcraft.LOGGER.error("BashCraft: chat highlighting failed, falling back to vanilla for this line", e);
        }
    }
}
```

- [ ] **Step 5: Write `client/ChainStyles.java`**

```java
package net.slk.bashcraft.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.ParsedArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Per-character styles for a chain in an input box (spec B §5): each command coloured as vanilla colours it, red only
 * where that command fails to parse; operators and group parentheses dark aqua; placeholders light purple.
 * Computed once per change of the text; {@link #format} runs every frame.
 */
public final class ChainStyles {
    private static final Style LITERAL = Style.EMPTY.withColor(ChatFormatting.GRAY);
    private static final Style UNPARSED = Style.EMPTY.withColor(ChatFormatting.RED);
    private static final Style SYNTAX = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
    private static final Style PLACEHOLDER = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
    private static final List<Style> ARGUMENTS = Stream.of(
            ChatFormatting.AQUA, ChatFormatting.YELLOW, ChatFormatting.GREEN, ChatFormatting.LIGHT_PURPLE, ChatFormatting.GOLD)
            .map(Style.EMPTY::withColor).toList();

    private final String value;
    private final Style[] styles;

    private ChainStyles(String value, Style[] styles) {
        this.value = value;
        this.styles = styles;
    }

    public static <S> ChainStyles compute(String value, ChainLayout layout, CommandDispatcher<S> dispatcher, S source) {
        Style[] styles = new Style[value.length()];
        Arrays.fill(styles, LITERAL);
        String masked = Placeholders.mask(value);
        for (ChainLayout.Span span : layout.spans()) {
            if (span.kind() != ChainLayout.Kind.COMMAND) {
                fill(styles, span.start(), span.end(), SYNTAX);
                continue;
            }
            StringReader reader = new StringReader(masked.substring(0, span.end()));
            reader.setCursor(span.start());
            ParseResults<S> parse = dispatcher.parse(reader, source);
            int color = -1;
            for (ParsedArgument<S, ?> argument : parse.getContext().getLastChild().getArguments().values()) {
                if (++color >= ARGUMENTS.size()) {
                    color = 0;
                }
                fill(styles, argument.getRange().getStart(), argument.getRange().getEnd(), ARGUMENTS.get(color));
            }
            if (parse.getReader().canRead()) {
                fill(styles, parse.getReader().getCursor(), span.end(), UNPARSED);
            }
        }
        for (int[] range : Placeholders.ranges(value)) {
            fill(styles, range[0], range[1], PLACEHOLDER);
        }
        return new ChainStyles(value, styles);
    }

    public boolean matches(String text) {
        return value.equals(text);
    }

    /** The visible part {@code text} of the line, which starts at {@code offset} in the whole line. */
    public FormattedCharSequence format(String text, int offset) {
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            Style style = styleAt(offset + i);
            int j = i + 1;
            while (j < text.length() && styleAt(offset + j) == style) {
                j++;
            }
            parts.add(FormattedCharSequence.forward(text.substring(i, j), style));
            i = j;
        }
        return FormattedCharSequence.composite(parts);
    }

    private Style styleAt(int index) {
        return index >= 0 && index < styles.length ? styles[index] : LITERAL;
    }

    private static void fill(Style[] styles, int start, int end, Style style) {
        Arrays.fill(styles, Math.max(0, start), Math.min(styles.length, Math.max(start, end)), style);
    }
}
```

- [ ] **Step 6: Write `mixin/client/CommandSuggestionsMixin.java`**

```java
package net.slk.bashcraft.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.client.ChainStyles;
import net.slk.bashcraft.client.ClientChains;
import net.slk.bashcraft.client.ClientHooks;
import net.slk.bashcraft.pipe.Placeholders;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Chains in chat and the command-block screen: highlight, complete and report errors per command (spec B §5). */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow @Final private boolean commandsOnly;
    @Shadow @Final private List<FormattedCharSequence> commandUsage;
    @Shadow private int commandUsagePosition;
    @Shadow private ParseResults<ClientSuggestionProvider> currentParse;

    @Shadow
    private void recomputeUsageBoxWidth() {}

    @Unique private ChainLayout bashcraft$layout;
    @Unique private ChainStyles bashcraft$styles;

    @Inject(method = "updateCommandInfo", at = @At("HEAD"))
    private void bashcraft$detectChain(CallbackInfo ci) {
        bashcraft$layout = null;
        bashcraft$styles = null;
        String value = input.getValue();
        if (!commandsOnly && !value.startsWith("/")) {
            return;
        }
        try {
            ChainLayout layout = ClientChains.layout(value).orElse(null);
            Minecraft minecraft = Minecraft.getInstance();
            if (layout != null && minecraft.player != null) {
                bashcraft$styles = ChainStyles.compute(value, layout, minecraft.player.connection.getCommands(),
                        minecraft.player.connection.getSuggestionsProvider());
                bashcraft$layout = layout;
                // The parse depends on where the cursor is, not just on the text.
                currentParse = null;
            }
        } catch (RuntimeException e) {
            ClientHooks.failed(e);
            bashcraft$layout = null;
            bashcraft$styles = null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults bashcraft$parseCommandUnderCursor(CommandDispatcher dispatcher, StringReader reader, Object source, Operation<ParseResults> original) {
        ChainLayout layout = bashcraft$layout;
        if (layout == null) {
            return original.call(dispatcher, reader, source);
        }
        try {
            ChainLayout.Window window = layout.window(input.getCursorPosition());
            StringReader command = new StringReader(Placeholders.mask(input.getValue().substring(0, window.end())));
            command.setCursor(window.start());
            return original.call(dispatcher, command, source);
        } catch (RuntimeException e) {
            ClientHooks.failed(e);
            return original.call(dispatcher, reader, source);
        }
    }

    @Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
    private void bashcraft$formatChain(String text, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
        ChainStyles styles = bashcraft$styles;
        if (styles != null && styles.matches(input.getValue())) {
            try {
                cir.setReturnValue(styles.format(text, offset));
            } catch (RuntimeException e) {
                ClientHooks.failed(e);
            }
        }
    }

    @Inject(method = "updateUsageInfo", at = @At("HEAD"), cancellable = true)
    private void bashcraft$chainError(ParseResults<ClientSuggestionProvider> parse, Suggestions suggestions, CallbackInfo ci) {
        ChainLayout layout = bashcraft$layout;
        if (layout == null || layout.error().isEmpty()) {
            return;
        }
        commandUsage.clear();
        commandUsage.add(Component.literal("BashCraft: " + layout.error().get().getMessage()).withStyle(ChatFormatting.RED).getVisualOrderText());
        commandUsagePosition = 0;
        recomputeUsageBoxWidth();
        ci.cancel();
    }
}
```

`src/main/resources/bashcraft.client.mixins.json`:
```json
{
	"required": false,
	"package": "net.slk.bashcraft.mixin.client",
	"compatibilityLevel": "JAVA_25",
	"client": [
		"CommandSuggestionsMixin"
	],
	"injectors": {
		"defaultRequire": 0
	}
}
```

In `src/main/resources/fabric.mod.json`, add `"bashcraft.client.mixins.json"` to the `mixins` array.

- [ ] **Step 7: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD|chat highlighting failed"`
Expected: `BUILD SUCCESSFUL`, no `Check failed`, no "chat highlighting failed". View `build/run/clientGameTest/screenshots/bashcraft-chain-highlight.png` (Read tool) and confirm the chain is coloured with no red.

- [ ] **Step 8: Commit**

```bash
git add src
git commit -m "Highlight, complete and report errors for chains in the chat box"
```

---

### Task 5: Server-side suggestions, dedicated server check and README

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mixin/suggest/ServerCommandSuggestionsProviderMixin.java`, `src/main/resources/bashcraft.suggest.mixins.json`
- Modify: `src/main/resources/fabric.mod.json`, `src/gametest/java/net/slk/bashcraft/test/BashcraftChatCheck.java`, `README.md`

**Interfaces:**
- Consumes: `ChainLayout.forInput`, `window` (Task 1); `Placeholders.mask` (Task 2); `BashcraftConfig`, `TransparentHooks.applied()`.

- [ ] **Step 1: Add the failing game check to `BashcraftChatCheck.java`** — insert before the line `context.runOnClient(client -> ChatProbe.open(client, "/(/say a || /say b"));`:

```java
            context.runOnClient(client -> ChatProbe.open(client, "/sh say a && function bashcraft-test:"));
            context.waitFor(client -> ChatProbe.completions(client).contains("bashcraft-test:chain"), 100);
```

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: FAIL — `waitFor` times out (the server parses the whole `sh` line and suggests nothing for `function`).

- [ ] **Step 3: Write the server mixin and its config**

```java
package net.slk.bashcraft.mixin.suggest;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.TransparentHooks;
import net.slk.bashcraft.pipe.Placeholders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/** Server-computed suggestions for a chain: parse only its last command, keeping positions absolute (spec B §6). */
@Mixin(ServerCommandSuggestionsProvider.class)
public abstract class ServerCommandSuggestionsProviderMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "tryProcessRequest", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults bashcraft$suggestForLastCommand(CommandDispatcher dispatcher, StringReader reader, Object source, Operation<ParseResults> original) {
        String request = reader.getString();
        boolean transparent = BashcraftConfig.get().transparentMode && TransparentHooks.applied();
        Optional<ChainLayout> layout = ChainLayout.forInput(request, transparent);
        if (layout.isEmpty()) {
            return original.call(dispatcher, reader, source);
        }
        StringReader last = new StringReader(Placeholders.mask(request));
        last.setCursor(layout.get().window(request.length()).start());
        return original.call(dispatcher, last, source);
    }
}
```

`src/main/resources/bashcraft.suggest.mixins.json`:
```json
{
	"required": false,
	"package": "net.slk.bashcraft.mixin.suggest",
	"compatibilityLevel": "JAVA_25",
	"mixins": [
		"ServerCommandSuggestionsProviderMixin"
	],
	"injectors": {
		"defaultRequire": 0
	}
}
```

Add `"bashcraft.suggest.mixins.json"` to the `mixins` array in `src/main/resources/fabric.mod.json`.

- [ ] **Step 4: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|Timed out|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 5: Dedicated server check** (client mixins must be skipped cleanly on a server; the suggestion mixin must apply)

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "say one && /say two"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-client-companion.log 2>&1
grep -E "\[Server\]|Done \(" build/server-client-companion.log
grep -E "WARN|ERROR" build/server-client-companion.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: `[Server] one`, `[Server] two`, `Done (`, `no bashcraft warnings`.

- [ ] **Step 6: Update `README.md`** — add before `## Config`:

````markdown
## Client companion (optional)

Install the same jar on your client too, and on servers that run BashCraft the chat box understands chains:
each command is highlighted the way vanilla highlights it (no red after `&&`), operators and parentheses are
coloured, BashCraft syntax errors show above the chat box, and tab-completion works for the command you're typing —
also inside `/sh …` lines and in command blocks. On servers without BashCraft the client behaves exactly as vanilla.
````

- [ ] **Step 7: Commit**

```bash
git add src README.md
git commit -m "Suggest for the last command of a chain on the server; document the client companion"
```
