# BashCraft M2 (Transparent Mode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chains work without the `/sh` prefix — typed in chat, the console, command blocks and `.mcfunction` files — when every operator is followed by a `/`-prefixed command.

**Architecture:** Two optional mixins feed the existing engine. `Commands.performCommand` (the funnel for chat — signed and unsigned — console and command blocks) detects a transparent chain and runs it through `ShCommand.execute`. Function files are compiled at load, so `ServerFunctionLibrary.readLines` rewrites chain lines into ordinary `sh …` lines before vanilla parses them. Detection and rewriting are pure Java. The mixins live in their own non-required mixin config; if a Minecraft update breaks them, transparent mode switches off with a warning and `/sh` keeps working.

**Tech Stack:** as M1 (Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18-SNAPSHOT, JUnit 5.14.2, client game tests), plus MixinExtras (bundled with Fabric Loader).

**Spec:** `docs/superpowers/specs/2026-10-01-bashcraft-design.md` (§3 "Two entry points", §6, §7 `transparentMode`)

## Research (26.3 sources, done while planning)

- Client: `ClientPacketListener.sendCommand` sends any line, valid or not (unsigned packet unless the *whole line* parses with signable arguments, then signed). No client mod needed; the vanilla client may colour the line red.
- Server: both `performUnsignedChatCommand` and `performSignedChatCommand` end in `Commands.performCommand(ParseResults, String)`, as do console input and command blocks (`performPrefixedCommand`). The string has no leading `/`.
- Functions: `CommandFunction.fromLines` (and macro instantiation) call `CommandFunction.parseCommand`, a static method on an **interface** — avoided as a mixin target. `ServerFunctionLibrary.readLines` (private static, class) returns the raw lines and is hooked instead. Vanilla joins `\`-continued lines before parsing; the rewrite does the same.

## Global Constraints

- All M1 constraints still apply (versions, package rules, `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`, `BashCraft: ` error prefix, no `Co-Authored-By` trailers).
- Transparent rule (spec §3): an operator only counts when the next non-space character is `/`. Lines that are not chains must reach vanilla unchanged.
- A line whose first word is `sh` is never treated as a transparent chain.
- Commands run *by* a chain (source is a `TeeSource`) are never re-split, so escaped operators stay literal.
- `transparentMode: false` disables both hooks.
- Transparent mixins: separate config, `"required": false`, `defaultRequire: 0`; a startup check logs a warning when they did not apply.
- Synergy (`~/projects/Synergy`) is read-only: no `gradlew --stop`, no pattern kills, dedicated server on port 25569.

## Review Focus

1. **Plain vanilla text containing operators** (`/say lunch; then work`, `/say a && b`) must run exactly as vanilla. → Task 1 tests `plainTextIsNotAChain`; Task 3 game test "no slash, no chain".
2. **A function file that must still load** when a line looks like a chain but isn't (comments, `/`-prefixed lines, lines ending in `\`). → Task 2 tests.
3. **Re-splitting**: a segment containing an escaped operator (`sh say a \&& /b`) must not be split again by the hook. → Task 3 game test "escaped operators stay literal".
4. **Player-typed chains with chat commands** (`/tp @s ~ ~ ~ && /msg Bob hi`) must be refused exactly as in `/sh`. → covered by M1's runner guard; Task 3 game test "transparent chat refuses signable".
5. **Command blocks** must see the chain's result (SuccessCount 0 on failure). → Task 3 game test.

---

### Task 1: Transparent chain detection and `sh` formatting

**Files:**
- Create: `src/main/java/net/slk/bashcraft/chain/TransparentChains.java`, `src/main/java/net/slk/bashcraft/chain/ChainFormatter.java`
- Modify: `src/main/java/net/slk/bashcraft/chain/ChainParser.java` (make `opensQuote` and `closingQuote` package-private)
- Test: `src/test/java/net/slk/bashcraft/chain/TransparentChainsTest.java`, `src/test/java/net/slk/bashcraft/chain/ChainFormatterTest.java`

**Interfaces:**
- Produces: `TransparentChains.detect(String line, int maxSegments)` → `Optional<Chain>` (present only for a chain of ≥ 2 commands; never for `sh …` lines or lines that fail to parse); `ChainFormatter.toSh(Chain)` → `String` such that `ChainParser.parse(toSh(c), SH, n).equals(c)`.

- [ ] **Step 1: Write the failing tests**

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TransparentChainsTest {
    private static Optional<Chain> detect(String line) {
        return TransparentChains.detect(line, 32);
    }

    @Test
    void slashFollowedOperatorsMakeAChain() {
        Chain chain = detect("tp @s ~ ~ ~ && /summon minecraft:zombie").orElseThrow();
        assertEquals(List.of(new Pipeline(List.of("tp @s ~ ~ ~")), new Pipeline(List.of("summon minecraft:zombie"))), chain.pipelines());
    }

    @Test
    void plainTextIsNotAChain() {
        assertTrue(detect("say lunch; then work").isEmpty());
        assertTrue(detect("say a && b").isEmpty());
        assertTrue(detect("tp @s ~ ~ ~").isEmpty());
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
        assertTrue(TransparentChains.detect("a ; /b ; /c", 2).isEmpty());
    }
}
```

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainFormatterTest {
    private static void roundTrips(String transparentLine) throws Exception {
        Chain chain = ChainParser.parse(transparentLine, ChainParser.Mode.TRANSPARENT, 32);
        String sh = ChainFormatter.toSh(chain);
        assertEquals(chain, ChainParser.parse(sh, ChainParser.Mode.SH, 32), sh);
    }

    @Test
    void formatsOperatorsAndPipes() throws Exception {
        Chain chain = ChainParser.parse("a | /b && /c ; /d || /e", ChainParser.Mode.TRANSPARENT, 32);
        assertEquals("a | b && c ; d || e", ChainFormatter.toSh(chain));
    }

    @Test
    void operatorsThatTransparentModeIgnoredAreEscaped() throws Exception {
        Chain chain = ChainParser.parse("say lunch; then && /say a | b", ChainParser.Mode.TRANSPARENT, 32);
        assertEquals("say lunch\\; then && say a \\| b", ChainFormatter.toSh(chain));
        roundTrips("say lunch; then && /say a | b");
    }

    @Test
    void roundTripsQuotesBackslashesAndPlaceholders() throws Exception {
        roundTrips("tellraw @a {\"text\":\"a \\\" | b\"} && /say don't; x");
        roundTrips("give @s stick[custom_name='a;b'] ; /tp @s \\{x} {y} 0");
        roundTrips("say a\\b && /say c\\\\d");
        roundTrips("setblock $(x) 120 6 minecraft:stone && /setblock 7 120 6 minecraft:stone");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: compilation FAIL — `cannot find symbol: variable TransparentChains`.

- [ ] **Step 3: Make the quote helpers package-private** in `ChainParser.java`: change `private static boolean opensQuote(` to `static boolean opensQuote(` and `private static int closingQuote(` to `static int closingQuote(`.

- [ ] **Step 4: Write `TransparentChains.java`**

```java
package net.slk.bashcraft.chain;

import java.util.Optional;

/** Decides whether a line typed without {@code /sh} is a chain (spec §3, transparent mode). */
public final class TransparentChains {
    private TransparentChains() {}

    /**
     * The chain in {@code line} if it has more than one command. Empty for plain vanilla lines, for {@code sh} lines
     * (the command handles those) and for lines that don't parse as a chain — those go to vanilla untouched.
     */
    public static Optional<Chain> detect(String line, int maxSegments) {
        String command = line.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.equals("sh") || command.startsWith("sh ")) {
            return Optional.empty();
        }
        try {
            Chain chain = ChainParser.parse(line, ChainParser.Mode.TRANSPARENT, maxSegments);
            return chain.segmentCount() > 1 ? Optional.of(chain) : Optional.empty();
        } catch (ChainSyntaxException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 5: Write `ChainFormatter.java`**

```java
package net.slk.bashcraft.chain;

import java.util.List;

/** Writes a chain back as {@code /sh} syntax, escaping operator characters that are part of a command's text. */
public final class ChainFormatter {
    private ChainFormatter() {}

    public static String toSh(Chain chain) {
        StringBuilder out = new StringBuilder();
        for (int p = 0; p < chain.pipelines().size(); p++) {
            if (p > 0) {
                out.append(switch (chain.joins().get(p - 1)) {
                    case AND -> " && ";
                    case OR -> " || ";
                    case SEQ -> " ; ";
                });
            }
            List<String> commands = chain.pipelines().get(p).commands();
            for (int i = 0; i < commands.size(); i++) {
                if (i > 0) {
                    out.append(" | ");
                }
                out.append(escape(commands.get(i)));
            }
        }
        return out.toString();
    }

    /** Backslash-escapes {@code & | ; \} outside quoted spans, using the parser's own quote rule. */
    private static String escape(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && ChainParser.opensQuote(command, i)) {
                int close = ChainParser.closingQuote(command, i);
                int end = close < 0 ? command.length() : close + 1;
                out.append(command, i, end);
                i = end;
                continue;
            }
            if ("&|;\\".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (64 from M1 + 7 new).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain src/test/java/net/slk/bashcraft/chain
git commit -m "Add transparent chain detection and sh formatting"
```

---

### Task 2: Function line rewriting

**Files:**
- Create: `src/main/java/net/slk/bashcraft/chain/FunctionLines.java`
- Test: `src/test/java/net/slk/bashcraft/chain/FunctionLinesTest.java`

**Interfaces:**
- Consumes: `TransparentChains.detect`, `ChainFormatter.toSh` (Task 1).
- Produces: `FunctionLines.rewrite(List<String> lines, int maxSegments)` → `List<String>` of the same size: chain lines become `sh …` (macro lines `$sh …`); `\`-continued chains are joined onto their first line and the following lines become blank (line numbers stay aligned); everything else is unchanged.

- [ ] **Step 1: Write the failing tests**

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FunctionLinesTest {
    private static List<String> rewrite(String... lines) {
        return FunctionLines.rewrite(List.of(lines), 32);
    }

    @Test
    void chainLinesBecomeShLines() {
        assertEquals(List.of("sh say hi && say there"), rewrite("say hi && /say there"));
    }

    @Test
    void otherLinesAreUntouched() {
        List<String> lines = List.of("# a && /comment", "", "say lunch; then work", "sh say a && say b", "/say a && /say b", "  tp @s ~ ~ ~  ");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }

    @Test
    void macroLinesKeepTheirDollar() {
        assertEquals(List.of("$sh setblock $(x) 120 6 minecraft:stone && setblock 7 120 6 minecraft:stone"),
                rewrite("$setblock $(x) 120 6 minecraft:stone && /setblock 7 120 6 minecraft:stone"));
    }

    @Test
    void continuedChainsAreJoinedAndLineNumbersKept() {
        assertEquals(List.of("sh say a && say b", "", "say c"), rewrite("say a && \\", "/say b", "say c"));
    }

    @Test
    void continuedNonChainsAreUntouched() {
        List<String> lines = List.of("say a \\", "b", "say c");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }

    @Test
    void danglingContinuationIsLeftForVanillaToReport() {
        List<String> lines = List.of("say a && \\", "/say b \\");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.FunctionLinesTest'`
Expected: compilation FAIL — `cannot find symbol: variable FunctionLines`.

- [ ] **Step 3: Write `FunctionLines.java`**

```java
package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrites transparent chains in a function file into {@code sh} lines before vanilla compiles it
 * (vanilla would reject them at load). Lines that aren't chains are returned untouched.
 */
public final class FunctionLines {
    private FunctionLines() {}

    public static List<String> rewrite(List<String> lines, int maxSegments) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int last = i;
            // Vanilla joins lines ending in '\' before parsing; join the same way so the chain sees the whole command.
            StringBuilder joined = new StringBuilder(line);
            while (endsWithBackslash(joined) && last + 1 < lines.size()) {
                joined.setLength(joined.length() - 1);
                joined.append(lines.get(++last).strip());
            }
            String rewritten = endsWithBackslash(joined) ? null : rewriteLine(joined.toString(), maxSegments);
            if (rewritten == null) {
                out.addAll(lines.subList(i, last + 1));
            } else {
                out.add(rewritten);
                for (int k = i; k < last; k++) {
                    out.add("");
                }
            }
            i = last;
        }
        return out;
    }

    /** The rewritten line, or null to keep the original. */
    private static String rewriteLine(String line, int maxSegments) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("/")) {
            return null;
        }
        boolean macro = line.startsWith("$");
        Optional<Chain> chain = TransparentChains.detect(macro ? line.substring(1) : line, maxSegments);
        return chain.map(c -> (macro ? "$sh " : "sh ") + ChainFormatter.toSh(c)).orElse(null);
    }

    private static boolean endsWithBackslash(CharSequence line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\\';
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain/FunctionLines.java src/test/java/net/slk/bashcraft/chain/FunctionLinesTest.java
git commit -m "Rewrite transparent chains in function files into sh lines"
```

---

### Task 3: Transparent hooks (commands and functions)

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java`, `src/main/java/net/slk/bashcraft/mixin/transparent/ServerFunctionLibraryMixin.java`, `src/main/java/net/slk/bashcraft/mc/TransparentHooks.java`, `src/main/resources/bashcraft.transparent.mixins.json`
- Modify: `src/main/java/net/slk/bashcraft/command/ShCommand.java` (extract `execute`), `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/main/resources/fabric.mod.json`, `src/gametest/resources/fabric.mod.json`
- Test: `src/gametest/java/net/slk/bashcraft/test/BashcraftTransparentCheck.java`, `src/gametest/resources/data/bashcraft-test/function/transparent.mcfunction`, `src/gametest/resources/data/bashcraft-test/function/transparent_macro.mcfunction`

**Interfaces:**
- Consumes: `TransparentChains.detect` (Task 1), `FunctionLines.rewrite` (Task 2), M1's `ShCommand`, `TeeSource`, `CommandSourceStackAccessor`, `BashcraftConfig`, `ExecResult`.
- Produces: `ShCommand.execute(CommandSourceStack, Chain)` → `ExecResult`; `TransparentHooks.applied()` → `boolean`.

- [ ] **Step 1: Write the game test and its functions**

`src/gametest/resources/data/bashcraft-test/function/transparent.mcfunction`:
```
# a comment with && /operators stays a comment
setblock 4 120 6 minecraft:stone && /setblock 5 120 6 minecraft:stone
```
`src/gametest/resources/data/bashcraft-test/function/transparent_macro.mcfunction`:
```
$setblock $(x) 120 6 minecraft:stone && /setblock 7 120 6 minecraft:stone
```

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.mc.TransparentHooks;

import static net.slk.bashcraft.test.Checks.check;

/** Transparent mode: chains without /sh from the console, command blocks, functions and player chat. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftTransparentCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            check(server.computeOnServer(s -> TransparentHooks.applied()), "transparent hooks applied");
            server.runCommand("fill 0 120 6 12 120 8 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("setblock 0 120 6 minecraft:stone && /setblock 1 120 6 minecraft:stone");
            check(stone(server, 0, 6) && stone(server, 1, 6), "the console chains without /sh");

            server.runCommand("setblock 2 121 6 minecraft:command_block{Command:\"setblock 2 120 6 minecraft:stone && /setblock 3 120 6 minecraft:stone\",auto:1b}");
            server.runCommand("setblock 3 121 7 minecraft:command_block{Command:\"setblock 0 120 6 minecraft:stone && /say x\",auto:1b}");
            context.waitTicks(5);
            check(stone(server, 2, 6) && stone(server, 3, 6), "command blocks chain without /sh");
            check(server.computeOnServer(s -> s.overworld().getBlockEntity(new BlockPos(3, 121, 7)) instanceof CommandBlockEntity block
                    && block.getCommandBlock().getSuccessCount() == 0), "a command block's failed transparent chain has SuccessCount 0");

            server.runCommand("function bashcraft-test:transparent");
            check(stone(server, 4, 6) && stone(server, 5, 6), "function files chain without sh");
            server.runCommand("function bashcraft-test:transparent_macro {x:6}");
            check(stone(server, 6, 6) && stone(server, 7, 6), "macro function lines chain without sh");

            server.runCommand("scoreboard players set n bc 9");
            server.runCommand("scoreboard players get n bc | /setblock {value} 120 7 minecraft:stone");
            check(stone(server, 9, 7), "transparent pipes");

            server.runCommand("setblock 10 120 6 minecraft:stone && setblock 11 120 6 minecraft:stone");
            check(!stone(server, 10, 6) && !stone(server, 11, 6), "no slash, no chain: the line goes to vanilla as one command");

            server.runCommand("sh setblock 11 120 7 minecraft:stone \\&& /setblock 12 120 7 minecraft:stone");
            check(!stone(server, 12, 7), "escaped operators stay literal (a running chain's segments are not re-split)");

            // Typed by the (non-op) test player.
            server.runCommand("scoreboard objectives add t1 trigger");
            server.runCommand("scoreboard objectives add t2 trigger");
            server.runCommand("scoreboard players enable @a t1");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("trigger t1 set 5 && /trigger t2 set 6"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t1 matches 5 if score @p t2 matches 6 run setblock 8 120 6 minecraft:stone");
            check(stone(server, 8, 6), "player chat chains without /sh");
            server.runCommand("scoreboard players enable @a t2");
            context.runOnClient(client -> client.getConnection().sendCommand("msg Player0 hi && /trigger t2 set 7"));
            context.waitTicks(5);
            server.runCommand("execute if score @p t2 matches 7 run setblock 0 120 7 minecraft:stone");
            check(!stone(server, 0, 7), "transparent chat refuses chat commands that need signing");

            server.runOnServer(s -> BashcraftConfig.get().transparentMode = false);
            server.runCommand("setblock 0 120 8 minecraft:stone && /setblock 1 120 8 minecraft:stone");
            server.runOnServer(s -> BashcraftConfig.get().transparentMode = true);
            check(!stone(server, 0, 8) && !stone(server, 1, 8), "transparentMode false turns the hook off");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftTransparentCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD"`
Expected: compilation FAIL — `cannot find symbol: class TransparentHooks` (and once that exists, `Check failed: transparent hooks applied`). If `server.runCommand` turns out not to go through `Commands.performCommand`, the console checks fail even with the hook in place: then route those checks through `s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), …)` inside `runOnServer` and record a ruling.

- [ ] **Step 3: Extract `ShCommand.execute`** — replace the body of `ShCommand.run` after parsing so both entry points share it:

```java
    /** Parses and runs a chain as {@code source}; returns the last segment's outcome (a failure for parse or limit errors). */
    public static ExecResult run(CommandSourceStack source, String input) {
        Chain chain;
        try {
            chain = ChainParser.parse(input, ChainParser.Mode.SH, BashcraftConfig.get().maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            return ExecResult.failure();
        }
        return execute(source, chain);
    }

    /** Runs an already-parsed chain as {@code source} (used by /sh and by transparent mode). */
    public static ExecResult execute(CommandSourceStack source, Chain chain) {
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

- [ ] **Step 4: Write the mixins and their config**

`src/main/java/net/slk/bashcraft/mixin/transparent/CommandsTransparentMixin.java`:
```java
package net.slk.bashcraft.mixin.transparent;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.slk.bashcraft.chain.TransparentChains;
import net.slk.bashcraft.command.ShCommand;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.mc.TeeSource;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat (signed and unsigned), console and command blocks all end here: run transparent chains instead of failing them. */
@Mixin(Commands.class)
public abstract class CommandsTransparentMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void bashcraft$transparentChain(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        BashcraftConfig config = BashcraftConfig.get();
        CommandSourceStack source = parse.getContext().getSource();
        // A chain's own segments are never re-split, so an escaped \&& stays literal.
        if (!config.transparentMode || ((CommandSourceStackAccessor) source).bashcraft$source() instanceof TeeSource) {
            return;
        }
        TransparentChains.detect(command, config.maxSegments).ifPresent(chain -> {
            ci.cancel();
            ExecResult result = ShCommand.execute(source, chain);
            source.callback().onResult(result.success(), result.success() ? result.result() : 0);
        });
    }
}
```

`src/main/java/net/slk/bashcraft/mixin/transparent/ServerFunctionLibraryMixin.java`:
```java
package net.slk.bashcraft.mixin.transparent;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.ServerFunctionLibrary;
import net.slk.bashcraft.chain.FunctionLines;
import net.slk.bashcraft.config.BashcraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Function files are compiled at load, where a chain line would be rejected: rewrite chains into sh lines first. */
@Mixin(ServerFunctionLibrary.class)
public abstract class ServerFunctionLibraryMixin {
    @ModifyReturnValue(method = "readLines", at = @At("RETURN"))
    private static List<String> bashcraft$transparentFunctionLines(List<String> lines) {
        BashcraftConfig config = BashcraftConfig.get();
        return config.transparentMode ? FunctionLines.rewrite(lines, config.maxSegments) : lines;
    }
}
```

`src/main/resources/bashcraft.transparent.mixins.json`:
```json
{
	"required": false,
	"package": "net.slk.bashcraft.mixin.transparent",
	"compatibilityLevel": "JAVA_25",
	"mixins": [
		"CommandsTransparentMixin",
		"ServerFunctionLibraryMixin"
	],
	"injectors": {
		"defaultRequire": 0
	}
}
```

In `src/main/resources/fabric.mod.json` change `"mixins": ["bashcraft.mixins.json"],` to `"mixins": ["bashcraft.mixins.json", "bashcraft.transparent.mixins.json"],`.

- [ ] **Step 5: Write `mc/TransparentHooks.java` and the startup check**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.Commands;
import net.minecraft.server.ServerFunctionLibrary;

import java.util.Arrays;

/** Whether the optional transparent-mode mixins applied; they may fail after a Minecraft update without breaking /sh. */
public final class TransparentHooks {
    private TransparentHooks() {}

    public static boolean applied() {
        return hasHandler(Commands.class) && hasHandler(ServerFunctionLibrary.class);
    }

    /** Mixin merges handlers into the target with their name embedded, e.g. handler$zza000$bashcraft$transparentChain. */
    private static boolean hasHandler(Class<?> target) {
        return Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("bashcraft$transparent"));
    }
}
```

In `Bashcraft.onInitialize()`, after `ShCommand.register();`:
```java
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (BashcraftConfig.get().transparentMode && !TransparentHooks.applied()) {
                LOGGER.warn("BashCraft: transparent mode hooks did not apply to this Minecraft version; use /sh instead");
            }
        });
```
Imports: `net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents`, `net.slk.bashcraft.mc.TransparentHooks`.

- [ ] **Step 6: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "Check failed|error:|BUILD|BashCraft: transparent"`
Expected: `BUILD SUCCESSFUL`; no `Check failed`; no "hooks did not apply" warning. M1's `BashcraftChainCheck` must still pass (its `sh` lines are untouched by the hooks).

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Add transparent mode: chains without /sh in chat, console, command blocks and functions"
```

---

### Task 4: Dedicated server check and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Dedicated server check** (isolated port, console transparent chain)

```bash
grep -q "server-port=25569" run/server.properties || printf 'server-port=25569\nonline-mode=false\n' > run/server.properties
(sleep 75; echo "say one && /say two"; sleep 3; echo "say lunch; then work"; sleep 3; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer > build/server-m2.log 2>&1
grep -E "\[Server\]|transparent|Done \(" build/server-m2.log
grep -E "WARN|ERROR" build/server-m2.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: `[Server] one`, `[Server] two`, `[Server] lunch; then work`, `Done (`, `no bashcraft warnings`.

- [ ] **Step 2: Update `README.md`** — replace the line `` `transparentMode` (true; used by the upcoming prefix-free mode). `` with `` `transparentMode` (true). `` and add after the Usage section's operator table:

````markdown
### Without `/sh`

Chains also work typed directly, as long as every command after an operator starts with `/`:

```
/tp @s ~ ~ ~ && /summon minecraft:zombie
/locate structure #minecraft:village | /tp @s {x} 150 {z}
```

`/say lunch; then work` is still a plain `/say` (no `/` after the `;`). This works in chat, the console, command
blocks and function files (`say hi && /say there`, macro lines too). A vanilla client may colour such a line red;
it is still sent. Set `transparentMode` to `false` to turn this off; `/sh` always works.
````

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Document transparent mode"
```
