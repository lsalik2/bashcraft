# BashCraft M0 + M1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Fabric mod for Minecraft 26.3 that adds a `/sh <chain>` command supporting `&&`, `||`, `;` and `|` (pipes with value extraction), usable from chat, console, command blocks and functions.

**Architecture:** A pure-Java core (`chain` parser, `pipe` value extraction, `exec` executor) that never touches Minecraft and is unit-tested with JUnit, plus a thin Minecraft layer (`mc`, `command`, `mixin`) that runs one segment synchronously and captures its result and feedback. M0 is a throwaway spike proving synchronous execution works on 26.3; M1 builds the engine and `/sh`.

**Tech Stack:** Java 25, Minecraft 26.3 (unobfuscated, Mojang names), Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Fabric Loom 1.18-SNAPSHOT, Gradle 9.7.1, JUnit 5.14.2, Fabric client game tests.

**Spec:** `docs/superpowers/specs/2026-10-01-bashcraft-design.md`

**Not in this plan:** M2 (transparent mode mixins). Its hook points depend on what M0 finds, so it gets its own plan after M1 ships. The parser's `TRANSPARENT` mode is built and tested here because it is pure Java.

## Global Constraints

- Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.161.0+26.3`, Loom `1.18-SNAPSHOT`, Gradle wrapper `9.7.1`, Java release `25`.
- No mappings: code uses Mojang class names (`CommandSourceStack`, `Commands`, `Component`, …).
- Mod id `bashcraft`, root package `net.slk.bashcraft`, author `SLK`. Test mod id `bashcraft-test`, package `net.slk.bashcraft.test`.
- Server-side only behaviour; vanilla clients must be able to use `/sh`.
- Packages `chain`, `pipe`, `exec`, `config` must not import `net.minecraft.*` or `net.fabricmc.*` (they are unit-tested without the game).
- Every Gradle command runs with `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1` (no JDK is on PATH). Written below as `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew …`.
- Chaining never changes permissions: every segment runs with the invoking `CommandSourceStack`.
- User-facing errors start with `BashCraft: `.
- Commit messages: no `Co-Authored-By` trailers.
- Client game tests take ~1–2 minutes; a failing `check` throws `AssertionError` and fails the Gradle task — grep the output for `AssertionError`.

## Review Focus

1. **Apostrophes in plain text** (`sh say don't && say hi`) must not open a quote; players type contractions constantly. → Task 3 test `apostropheInsideWordIsNotAQuote`.
2. **JSON text with escaped quotes and operators inside** (`tellraw @a {"text":"a \" | b"}`) must reach the command verbatim. → Task 3 test `escapedQuoteInsideJsonDoesNotCloseIt`.
3. **Negative, decimal and NBT-suffixed numbers** (`-1040`, `-0.5`, `20.0f`) must survive piping unchanged (minus the NBT suffix). → Task 4 test `negativeAndDecimalCoordinatesSurvive`, Task 5 test `dataEntityNumberDropsNbtSuffix`.
4. **A piped command that prints nothing** (e.g. `say`, which broadcasts instead of replying) must give a clear error and let `||` recover. → Task 6 test `nothingToPipeReportsAndOrRecovers`.
5. **Recursive chains through functions** must stop at `maxDepth` instead of hanging the server. → Task 7 `DepthGuardTest`, Task 8 game test `recurse`.

---

## File Structure

```
build.gradle, settings.gradle, gradle.properties, gradlew, gradlew.bat, gradle/wrapper/*, .gitignore
src/main/resources/fabric.mod.json
src/main/resources/bashcraft.mixins.json
src/main/java/net/slk/bashcraft/
  Bashcraft.java                    entrypoint: load config, register /sh
  chain/Operator.java               AND | OR | SEQ
  chain/Pipeline.java               commands joined by |
  chain/Chain.java                  pipelines joined by operators
  chain/ChainSyntaxException.java   parse error with column
  chain/ChainParser.java            string -> Chain (SH and TRANSPARENT modes)
  pipe/Feedback.java                Minecraft-free view of a chat message
  pipe/PipeValue.java               Coordinates | Numeric | Text
  pipe/PipeException.java
  pipe/Placeholders.java            inject a value into the next command
  pipe/OutputAdapters.java          per-translation-key extraction
  pipe/FeedbackExtractor.java       fallback extraction
  pipe/PipeResolver.java            output -> PipeValue
  exec/ExecResult.java, exec/RunOptions.java, exec/CommandRunner.java
  exec/ChainExecutor.java           operator logic + pipes
  exec/DepthGuard.java              nesting limit
  config/BashcraftConfig.java       config/bashcraft.json
  mc/ComponentFeedback.java         Component -> Feedback
  mc/TeeSource.java                 per-segment CommandSource wrapper
  mc/MinecraftCommandRunner.java    runs one segment now
  command/ShCommand.java            /sh
  mixin/CommandsAccessor.java       Commands.CURRENT_EXECUTION_CONTEXT
  mixin/CommandSourceStackAccessor.java
  mixin/CommandSourceStackMixin.java  capture success / prefix failures for TeeSource
src/test/java/net/slk/bashcraft/...   JUnit tests (pure Java)
src/gametest/java/net/slk/bashcraft/test/...   client game tests
src/gametest/resources/fabric.mod.json, data/bashcraft-test/function/*.mcfunction
```

---

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle`, `gradle.properties`, `build.gradle`, `.gitignore`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (copied)
- Create: `src/main/resources/fabric.mod.json`, `src/main/resources/bashcraft.mixins.json`
- Create: `src/main/java/net/slk/bashcraft/Bashcraft.java`
- Create: `src/gametest/resources/fabric.mod.json`, `src/gametest/java/net/slk/bashcraft/test/Checks.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftSmokeCheck.java`

**Interfaces:**
- Produces: `net.slk.bashcraft.Bashcraft` with `MOD_ID = "bashcraft"` and `LOGGER` (slf4j); `net.slk.bashcraft.test.Checks.check(boolean, String)`; Gradle tasks `build`, `test`, `runClientGameTest`, `runServer`.

- [ ] **Step 1: Copy the Gradle wrapper from the Synergy project (same Gradle 9.7.1)**

```bash
cd ~/projects/bashcraft
cp -r ~/projects/Synergy/gradle ~/projects/Synergy/gradlew ~/projects/Synergy/gradlew.bat .
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```
Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`

- [ ] **Step 2: Write `settings.gradle`**

```groovy
pluginManagement {
	repositories {
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = 'bashcraft'
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# Fabric Properties (check https://fabricmc.net/develop before bumping)
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18-SNAPSHOT

# Mod Properties
mod_version=0.1.0
maven_group=net.slk.bashcraft
archives_base_name=bashcraft

# Dependencies
fabric_version=0.161.0+26.3
junit_version=5.14.2
```

- [ ] **Step 4: Write `build.gradle`**

```groovy
plugins {
	id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

version = project.mod_version
group = project.maven_group

base {
	archivesName = project.archives_base_name
}

fabricApi {
	// Client game tests (src/gametest) drive a real dev client against an integrated server.
	configureTests {
		createSourceSet = true
		modId = "bashcraft-test"
		enableGameTests = false
		enableClientGameTests = true
		eula = true
	}
}

dependencies {
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	implementation "net.fabricmc:fabric-loader:${project.loader_version}"
	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

	testImplementation platform("org.junit:junit-bom:${project.junit_version}")
	testImplementation "org.junit.jupiter:junit-jupiter"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

test {
	useJUnitPlatform()
}

processResources {
	inputs.property "version", project.version

	filesMatching("fabric.mod.json") {
		expand "version": inputs.properties.version
	}
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = 25
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}
```

- [ ] **Step 5: Write `.gitignore`**

```
.gradle/
build/
run/
out/
.idea/
*.iml
```

- [ ] **Step 6: Write `src/main/resources/fabric.mod.json`**

```json
{
	"schemaVersion": 1,
	"id": "bashcraft",
	"version": "${version}",
	"name": "BashCraft",
	"description": "Logical operators and pipes for Minecraft commands.",
	"authors": ["SLK"],
	"environment": "*",
	"entrypoints": {
		"main": ["net.slk.bashcraft.Bashcraft"]
	},
	"mixins": ["bashcraft.mixins.json"],
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~26.3",
		"java": ">=25",
		"fabric-api": "*"
	}
}
```

- [ ] **Step 7: Write `src/main/resources/bashcraft.mixins.json`** (empty for now; later tasks add entries)

```json
{
	"required": true,
	"package": "net.slk.bashcraft.mixin",
	"compatibilityLevel": "JAVA_25",
	"mixins": [],
	"injectors": {
		"defaultRequire": 1
	}
}
```

- [ ] **Step 8: Write `src/main/java/net/slk/bashcraft/Bashcraft.java`**

```java
package net.slk.bashcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bashcraft implements ModInitializer {
    public static final String MOD_ID = "bashcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("BashCraft loaded");
    }
}
```

- [ ] **Step 9: Write the game-test mod: `src/gametest/resources/fabric.mod.json`**

```json
{
	"schemaVersion": 1,
	"id": "bashcraft-test",
	"version": "1.0.0",
	"name": "BashCraft Client Game Tests",
	"environment": "*",
	"entrypoints": {
		"fabric-client-gametest": [
			"net.slk.bashcraft.test.BashcraftSmokeCheck"
		]
	},
	"depends": {
		"bashcraft": "*",
		"fabric-client-gametest-api-v1": "*"
	}
}
```

- [ ] **Step 10: Write `src/gametest/java/net/slk/bashcraft/test/Checks.java`**

```java
package net.slk.bashcraft.test;

/** Assertion helper for client game tests: a failed check crashes the test client and fails the Gradle task. */
final class Checks {
    private Checks() {}

    static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Check failed: " + what);
        }
    }
}
```

- [ ] **Step 11: Write `src/gametest/java/net/slk/bashcraft/test/BashcraftSmokeCheck.java`**

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import static net.slk.bashcraft.test.Checks.check;

/** The mod loads into a singleplayer world. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftSmokeCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            check(singleplayer.getServer().computeOnServer(s -> FabricLoader.getInstance().isModLoaded("bashcraft")),
                    "bashcraft is loaded on the server");
        }
    }
}
```

- [ ] **Step 12: Build**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build`
Expected: `BUILD SUCCESSFUL`, and `build/libs/bashcraft-0.1.0.jar` exists.

- [ ] **Step 13: Run the smoke game test**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`, no `AssertionError` in output, log contains `BashCraft loaded`.

- [ ] **Step 14: Commit**

```bash
git add .gitignore settings.gradle gradle.properties build.gradle gradlew gradlew.bat gradle src
git commit -m "Scaffold BashCraft Fabric mod for 26.3"
```

---

### Task 2: M0 spike — synchronous segment execution

**Question:** On 26.3, commands dispatched from inside a running command are queued (`Commands.executeCommandInContext` reuses `CURRENT_EXECUTION_CONTEXT` when one exists). Can we run a command *to completion before returning* by temporarily clearing that ThreadLocal, and capture its result and feedback — from chat, a function, and a command block?

**Decision rule:** all checks in `BashcraftSpikeCheck` pass → approach confirmed; keep `CommandsAccessor`, delete the rest of the spike, continue to Task 3. Any check fails → record the failing probe in the notes file, commit the notes, **stop and report to your human partner** (Tasks 3–7 are pure Java and may still proceed; Task 8 must be re-planned).

**Files:**
- Create (kept): `src/main/java/net/slk/bashcraft/mixin/CommandsAccessor.java`
- Create (throwaway): `src/main/java/net/slk/bashcraft/spike/SpikeCommand.java`, `src/gametest/java/net/slk/bashcraft/test/BashcraftSpikeCheck.java`, `src/gametest/resources/data/bashcraft-test/function/spike.mcfunction`, `src/gametest/resources/data/bashcraft-test/function/mark.mcfunction`
- Modify: `src/main/resources/bashcraft.mixins.json`, `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/gametest/resources/fabric.mod.json`
- Create: `docs/superpowers/notes/2026-10-01-m0-spike.md`

**Interfaces:**
- Produces: `CommandsAccessor.bashcraft$currentExecutionContext()` → `ThreadLocal<ExecutionContext<CommandSourceStack>>` (used by Task 8).

- [ ] **Step 1: Write `mixin/CommandsAccessor.java`**

```java
package net.slk.bashcraft.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the ThreadLocal that makes nested command calls join the running execution context instead of running now. */
@Mixin(Commands.class)
public interface CommandsAccessor {
    @Accessor("CURRENT_EXECUTION_CONTEXT")
    static ThreadLocal<ExecutionContext<CommandSourceStack>> bashcraft$currentExecutionContext() {
        throw new AssertionError();
    }
}
```

Add `"CommandsAccessor"` to the `mixins` array in `bashcraft.mixins.json`.

- [ ] **Step 2: Write the throwaway `spike/SpikeCommand.java`**

```java
package net.slk.bashcraft.spike;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.slk.bashcraft.Bashcraft;
import net.slk.bashcraft.mixin.CommandsAccessor;

import java.util.ArrayList;
import java.util.List;

/** THROWAWAY (M0): /bashspike <command> runs a command in a fresh top-level context and records what came back. */
public final class SpikeCommand {
    public record Probe(String command, boolean callbackFired, boolean success, int result, List<String> messages) {}

    public static volatile Probe last;

    private SpikeCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bashspike").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("command", StringArgumentType.greedyString()).executes(SpikeCommand::run))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        String command = StringArgumentType.getString(ctx, "command");
        CommandSourceStack source = ctx.getSource();
        List<String> messages = new ArrayList<>();
        boolean[] fired = {false};
        boolean[] success = {false};
        int[] result = {0};
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component c) {
                messages.add(c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString());
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack stack = source.withSource(capture).withCallback((ok, value) -> {
            fired[0] = true;
            success[0] = ok;
            result[0] = value;
        });
        ThreadLocal<ExecutionContext<CommandSourceStack>> current = CommandsAccessor.bashcraft$currentExecutionContext();
        ExecutionContext<CommandSourceStack> saved = current.get();
        current.set(null);
        try {
            source.getServer().getCommands().performPrefixedCommand(stack, command);
        } finally {
            current.set(saved);
        }
        last = new Probe(command, fired[0], success[0], result[0], List.copyOf(messages));
        Bashcraft.LOGGER.info("bashspike: {}", last);
        return 1;
    }
}
```

Call `SpikeCommand.register();` at the end of `Bashcraft.onInitialize()` (import `net.slk.bashcraft.spike.SpikeCommand`).

- [ ] **Step 3: Write the spike functions**

`src/gametest/resources/data/bashcraft-test/function/spike.mcfunction`:
```
bashspike setblock 1 120 0 minecraft:stone
```
`src/gametest/resources/data/bashcraft-test/function/mark.mcfunction`:
```
setblock 2 120 0 minecraft:stone
```

- [ ] **Step 4: Write the throwaway `BashcraftSpikeCheck.java`**

```java
package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.slk.bashcraft.Bashcraft;
import net.slk.bashcraft.spike.SpikeCommand;

import static net.slk.bashcraft.test.Checks.check;

/** THROWAWAY (M0): can a command run another command to completion and see its result and feedback? */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftSpikeCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 0 4 120 0 minecraft:air");

            // Q1 + Q2: runs now, result and feedback captured (as a player, via /execute)
            server.runCommand("execute as @p run bashspike setblock 0 120 0 minecraft:stone");
            SpikeCommand.Probe q1 = SpikeCommand.last;
            Bashcraft.LOGGER.info("Q1 {}", q1);
            check(q1.callbackFired() && q1.success() && q1.result() == 1, "Q1 result is known before bashspike returns");
            check(q1.messages().contains("commands.setblock.success"), "Q2 success feedback is captured");

            // Q3: failure is reported as failure, with a message
            server.runCommand("bashspike setblock 0 120 0 minecraft:stone");
            SpikeCommand.Probe q3 = SpikeCommand.last;
            Bashcraft.LOGGER.info("Q3 {}", q3);
            check(!q3.success() && !q3.messages().isEmpty(), "Q3 a failing command reports failure and a message");

            // Q4: works when bashspike itself runs inside a function
            server.runCommand("function bashcraft-test:spike");
            SpikeCommand.Probe q4 = SpikeCommand.last;
            Bashcraft.LOGGER.info("Q4 {}", q4);
            check(q4.callbackFired() && q4.success() && stone(server, 1), "Q4 runs to completion inside a function");

            // Q5: a function called *by* the segment runs to completion
            server.runCommand("bashspike function bashcraft-test:mark");
            SpikeCommand.Probe q5 = SpikeCommand.last;
            Bashcraft.LOGGER.info("Q5 {}", q5);
            check(q5.callbackFired() && stone(server, 2), "Q5 a nested function completes before bashspike returns");

            // Q6: from a command block
            server.runCommand("setblock 3 121 0 minecraft:command_block{Command:\"bashspike setblock 3 120 0 minecraft:stone\",auto:1b}");
            context.waitTicks(5);
            SpikeCommand.Probe q6 = SpikeCommand.last;
            Bashcraft.LOGGER.info("Q6 {}", q6);
            check(q6.callbackFired() && q6.success() && stone(server, 3), "Q6 runs to completion from a command block");
        }
    }

    private static boolean stone(TestServerContext server, int x) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, 0)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftSpikeCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 5: Run the spike**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | tee build/spike.log | grep -E "Q[1-6] |AssertionError|BUILD"`
Expected: six `Q… Probe[…]` lines and `BUILD SUCCESSFUL`. If `AssertionError` appears, note which question failed.

- [ ] **Step 6: Record findings in `docs/superpowers/notes/2026-10-01-m0-spike.md`**

Use this template, pasting the six `Q… Probe[…]` log lines verbatim:

```markdown
# M0 spike: synchronous segment execution on 26.3

**Approach tested:** clear `Commands.CURRENT_EXECUTION_CONTEXT` around `performPrefixedCommand`, so the
segment gets its own top-level `ExecutionContext` and runs to completion before the call returns.
Result via `CommandSourceStack.withCallback`, feedback via `withSource`.

| Q | Question | Result |
|---|---|---|
| Q1 | Result known before returning (player via /execute) | PASS/FAIL |
| Q2 | Success feedback captured | PASS/FAIL |
| Q3 | Failure reported as failure + message | PASS/FAIL |
| Q4 | Works when the outer command runs inside a function | PASS/FAIL |
| Q5 | Nested function inside the segment completes | PASS/FAIL |
| Q6 | Works from a command block | PASS/FAIL |

Raw probes:
(paste the Q1–Q6 log lines)

**Decision:** (Approach confirmed — continue with Task 3. / Approach fails at Qn — stop, re-plan Task 8.)
```

- [ ] **Step 7: If everything passed, remove the throwaway code**

```bash
rm -r src/main/java/net/slk/bashcraft/spike
rm src/gametest/java/net/slk/bashcraft/test/BashcraftSpikeCheck.java
rm src/gametest/resources/data/bashcraft-test/function/spike.mcfunction src/gametest/resources/data/bashcraft-test/function/mark.mcfunction
```
Remove the `SpikeCommand.register();` line and its import from `Bashcraft.java`, and `BashcraftSpikeCheck` from `src/gametest/resources/fabric.mod.json`. Keep `CommandsAccessor`.

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build`
Expected: `BUILD SUCCESSFUL`

If anything failed: keep the spike code uncommitted, do not delete anything, and stop after Step 8.

- [ ] **Step 8: Commit**

```bash
git add -A docs/superpowers/notes src
git commit -m "M0 spike: confirm synchronous segment execution on 26.3"
```

---

### Task 3: Chain model and parser

**Files:**
- Create: `src/main/java/net/slk/bashcraft/chain/Operator.java`, `Pipeline.java`, `Chain.java`, `ChainSyntaxException.java`, `ChainParser.java`
- Test: `src/test/java/net/slk/bashcraft/chain/ChainParserTest.java`

**Interfaces:**
- Produces:
  - `enum Operator { AND, OR, SEQ }`
  - `record Pipeline(List<String> commands)`
  - `record Chain(List<Pipeline> pipelines, List<Operator> joins)` with `int segmentCount()`; `joins.size() == pipelines.size() - 1`
  - `class ChainSyntaxException extends Exception` with `int column()` (1-based); message ends with `" at col N"`
  - `ChainParser.parse(String input, ChainParser.Mode mode, int maxSegments) throws ChainSyntaxException`; `enum ChainParser.Mode { SH, TRANSPARENT }`
- Segment strings are trimmed with one leading `/` removed. Quotes are kept verbatim. Outside quotes, `\` before `& | ; \` is removed; any other `\` is kept.

- [ ] **Step 1: Write the model classes**

`chain/Operator.java`:
```java
package net.slk.bashcraft.chain;

/** How a pipeline is joined to the one before it. */
public enum Operator {
    /** {@code &&}: run if the previous pipeline succeeded. */
    AND,
    /** {@code ||}: run if the previous pipeline failed. */
    OR,
    /** {@code ;}: always run. */
    SEQ
}
```

`chain/Pipeline.java`:
```java
package net.slk.bashcraft.chain;

import java.util.List;

/** Commands joined by {@code |}; each command's output feeds the next. */
public record Pipeline(List<String> commands) {
    public Pipeline {
        commands = List.copyOf(commands);
    }
}
```

`chain/Chain.java`:
```java
package net.slk.bashcraft.chain;

import java.util.List;

/** Pipelines joined by operators, evaluated left to right: {@code joins.get(i)} sits between pipeline i and i + 1. */
public record Chain(List<Pipeline> pipelines, List<Operator> joins) {
    public Chain {
        pipelines = List.copyOf(pipelines);
        joins = List.copyOf(joins);
        if (pipelines.isEmpty() || joins.size() != pipelines.size() - 1) {
            throw new IllegalArgumentException("a chain needs one fewer join than pipelines");
        }
    }

    public int segmentCount() {
        return pipelines.stream().mapToInt(p -> p.commands().size()).sum();
    }
}
```

`chain/ChainSyntaxException.java`:
```java
package net.slk.bashcraft.chain;

/** A chain that can't be parsed; nothing in it runs. */
public class ChainSyntaxException extends Exception {
    private final int column;

    public ChainSyntaxException(String message, int column) {
        super(message + " at col " + column);
        this.column = column;
    }

    /** 1-based column of the problem. */
    public int column() {
        return column;
    }
}
```

- [ ] **Step 2: Write the failing tests `ChainParserTest.java`**

```java
package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.slk.bashcraft.chain.ChainParser.Mode.SH;
import static net.slk.bashcraft.chain.ChainParser.Mode.TRANSPARENT;
import static org.junit.jupiter.api.Assertions.*;

class ChainParserTest {
    private static List<List<String>> commands(Chain chain) {
        return chain.pipelines().stream().map(Pipeline::commands).toList();
    }

    private static Chain sh(String input) throws ChainSyntaxException {
        return ChainParser.parse(input, SH, 32);
    }

    @Test
    void singleCommand() throws Exception {
        Chain chain = sh("tp @s ~ ~ ~");
        assertEquals(List.of(List.of("tp @s ~ ~ ~")), commands(chain));
        assertEquals(List.of(), chain.joins());
    }

    @Test
    void leadingSlashesAreOptionalAndStripped() throws Exception {
        Chain chain = sh("/tp @s ~ ~ ~ && /summon minecraft:zombie");
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), commands(chain));
        assertEquals(List.of(Operator.AND), chain.joins());
    }

    @Test
    void pipeBindsTighterThanListOperators() throws Exception {
        Chain chain = sh("/a | /b && /c ; /d || /e");
        assertEquals(List.of(List.of("a", "b"), List.of("c"), List.of("d"), List.of("e")), commands(chain));
        assertEquals(List.of(Operator.AND, Operator.SEQ, Operator.OR), chain.joins());
        assertEquals(5, chain.segmentCount());
    }

    @Test
    void doubleQuotesProtectOperators() throws Exception {
        assertEquals(List.of(List.of("say \"a && b\""), List.of("say c")), commands(sh("say \"a && b\" && say c")));
    }

    @Test
    void escapedQuoteInsideJsonDoesNotCloseIt() throws Exception {
        String json = "tellraw @a {\"text\":\"a \\\" | b\"}";
        assertEquals(List.of(List.of(json), List.of("say done")), commands(sh(json + " ; say done")));
    }

    @Test
    void apostropheInsideWordIsNotAQuote() throws Exception {
        assertEquals(List.of(List.of("say don't"), List.of("say hi")), commands(sh("say don't && say hi")));
    }

    @Test
    void singleQuotesAfterPunctuationProtectOperators() throws Exception {
        assertEquals(List.of(List.of("give @s stick[custom_name='a|b']"), List.of("say ok")),
                commands(sh("give @s stick[custom_name='a|b'] && say ok")));
    }

    @Test
    void backslashEscapesAnOperator() throws Exception {
        assertEquals(List.of(List.of("say use && here")), commands(sh("say use \\&& here")));
    }

    @Test
    void backslashBeforeOtherCharactersIsKept() throws Exception {
        assertEquals(List.of(List.of("tp @s \\{x} 64 0")), commands(sh("tp @s \\{x} 64 0")));
    }

    @Test
    void singleAmpersandIsLiteral() throws Exception {
        assertEquals(List.of(List.of("say a & b")), commands(sh("say a & b")));
    }

    @Test
    void emptyInputIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("   "));
        assertTrue(e.getMessage().startsWith("empty command"), e.getMessage());
        assertEquals(1, e.column());
    }

    @Test
    void leadingOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("&& /a"));
        assertEquals("unexpected '&&' at col 1", e.getMessage());
    }

    @Test
    void doubledOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("/a && && /b"));
        assertEquals("unexpected '&&' at col 7", e.getMessage());
    }

    @Test
    void danglingOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("/a &&"));
        assertEquals("missing command after '&&' at col 6", e.getMessage());
    }

    @Test
    void unclosedQuoteIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("say \"oops"));
        assertEquals("unclosed \" at col 5", e.getMessage());
    }

    @Test
    void tooManySegmentsIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> ChainParser.parse("a ; b | c", SH, 2));
        assertTrue(e.getMessage().startsWith("too many commands (max 2)"), e.getMessage());
    }

    @Test
    void transparentModeSplitsWhenNextSegmentStartsWithSlash() throws Exception {
        Chain chain = ChainParser.parse("tp @s ~ ~ ~ && /summon minecraft:zombie", TRANSPARENT, 32);
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), commands(chain));
    }

    @Test
    void transparentModeLeavesPlainTextAlone() throws Exception {
        assertEquals(List.of(List.of("say lunch; then work")), commands(ChainParser.parse("say lunch; then work", TRANSPARENT, 32)));
        assertEquals(List.of(List.of("say a && b")), commands(ChainParser.parse("say a && b", TRANSPARENT, 32)));
    }

    @Test
    void transparentModePipeWithoutSpaces() throws Exception {
        Chain chain = ChainParser.parse("locate structure minecraft:village|/tp @s", TRANSPARENT, 32);
        assertEquals(List.of(List.of("locate structure minecraft:village", "tp @s")), commands(chain));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: compilation FAIL — `cannot find symbol: class ChainParser`.

- [ ] **Step 4: Write `chain/ChainParser.java`**

```java
package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into pipelines and operators, bash-style.
 * Quotes ({@code "…"}, or {@code '…'} not preceded by a letter or digit) are never split and are kept verbatim.
 * Outside quotes, {@code \} before {@code & | ; \} yields that character; any other backslash is kept.
 */
public final class ChainParser {
    public enum Mode {
        /** {@code /sh}: every unquoted operator counts. */
        SH,
        /** Typed directly: an operator only counts when the next non-space character is {@code /}. */
        TRANSPARENT
    }

    private static final String ESCAPABLE = "&|;\\";

    private ChainParser() {}

    public static Chain parse(String input, Mode mode, int maxSegments) throws ChainSyntaxException {
        List<Pipeline> pipelines = new ArrayList<>();
        List<Operator> joins = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lastOp = null;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if ((c == '"' || c == '\'') && opensQuote(input, i)) {
                int close = closingQuote(input, i);
                if (close < 0) {
                    throw new ChainSyntaxException("unclosed " + c, i + 1);
                }
                current.append(input, i, close + 1);
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < input.length() && ESCAPABLE.indexOf(input.charAt(i + 1)) >= 0) {
                current.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            String op = operatorAt(input, i);
            if (op == null || (mode == Mode.TRANSPARENT && !nextNonSpaceIsSlash(input, i + op.length()))) {
                current.append(c);
                i++;
                continue;
            }
            String segment = segment(current);
            if (segment.isEmpty()) {
                throw new ChainSyntaxException("unexpected '" + op + "'", i + 1);
            }
            commands.add(segment);
            checkLimit(pipelines, commands, maxSegments, i + 1);
            if (!op.equals("|")) {
                pipelines.add(new Pipeline(commands));
                commands = new ArrayList<>();
                joins.add(switch (op) {
                    case "&&" -> Operator.AND;
                    case "||" -> Operator.OR;
                    default -> Operator.SEQ;
                });
            }
            current.setLength(0);
            lastOp = op;
            i += op.length();
        }
        String segment = segment(current);
        if (segment.isEmpty()) {
            if (lastOp == null) {
                throw new ChainSyntaxException("empty command", 1);
            }
            throw new ChainSyntaxException("missing command after '" + lastOp + "'", input.length() + 1);
        }
        commands.add(segment);
        checkLimit(pipelines, commands, maxSegments, input.length() + 1);
        pipelines.add(new Pipeline(commands));
        return new Chain(pipelines, joins);
    }

    private static String segment(StringBuilder raw) {
        String text = raw.toString().strip();
        return text.startsWith("/") ? text.substring(1).strip() : text;
    }

    private static void checkLimit(List<Pipeline> pipelines, List<String> commands, int max, int column) throws ChainSyntaxException {
        int count = commands.size() + pipelines.stream().mapToInt(p -> p.commands().size()).sum();
        if (count > max) {
            throw new ChainSyntaxException("too many commands (max " + max + ")", column);
        }
    }

    /** A quote opens a quoted span unless it follows a letter or digit (so "don't" stays plain text). */
    private static boolean opensQuote(String input, int i) {
        return i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1));
    }

    /** Index of the matching quote, skipping backslash-escaped characters; -1 if unclosed. */
    private static int closingQuote(String input, int start) {
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

    private static String operatorAt(String input, int i) {
        if (input.startsWith("&&", i)) return "&&";
        if (input.startsWith("||", i)) return "||";
        char c = input.charAt(i);
        if (c == '|') return "|";
        if (c == ';') return ";";
        return null;
    }

    private static boolean nextNonSpaceIsSlash(String input, int from) {
        int j = from;
        while (j < input.length() && Character.isWhitespace(input.charAt(j))) {
            j++;
        }
        return j < input.length() && input.charAt(j) == '/';
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.chain.*'`
Expected: `BUILD SUCCESSFUL`, 19 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/chain src/test/java/net/slk/bashcraft/chain
git commit -m "Add chain parser with quoting, escapes and transparent mode"
```

---

### Task 4: Pipe values and placeholder injection

**Files:**
- Create: `src/main/java/net/slk/bashcraft/pipe/Feedback.java`, `PipeValue.java`, `PipeException.java`, `Placeholders.java`
- Test: `src/test/java/net/slk/bashcraft/pipe/PlaceholdersTest.java`, `src/test/java/net/slk/bashcraft/pipe/FeedbackTest.java`

**Interfaces:**
- Produces:
  - `record Feedback(String key, List<Object> args, String text)` — `key` is null for plain text; args are `Feedback` or `String`. Factories `Feedback.plain(String)`, `Feedback.translatable(String key, String text, Object... args)`; `Optional<String> argText(int)`; `Optional<Feedback> find(String key)`.
  - `sealed interface PipeValue` with `String asArgument()`, `Optional<String> placeholder(String name)`, `String describe()`; records `PipeValue.Coordinates(String x, String y, String z)`, `PipeValue.Numeric(String text)`, `PipeValue.Text(String text)`.
  - `class PipeException extends Exception` (message constructor).
  - `Placeholders.inject(String command, PipeValue value, int previousResult) throws PipeException` → `String`.

- [ ] **Step 1: Write `Feedback.java`, `PipeValue.java`, `PipeException.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Minecraft-free view of one chat message.
 *
 * @param key  translation key, or null for plain text
 * @param args translation arguments: nested {@code Feedback} or {@code String}
 * @param text the rendered message
 */
public record Feedback(String key, List<Object> args, String text) {
    public Feedback {
        args = List.copyOf(args);
    }

    public static Feedback plain(String text) {
        return new Feedback(null, List.of(), text);
    }

    public static Feedback translatable(String key, String text, Object... args) {
        List<Object> list = new ArrayList<>();
        for (Object arg : args) {
            list.add(arg instanceof Feedback ? arg : String.valueOf(arg));
        }
        return new Feedback(key, list, text);
    }

    /** Rendered text of argument {@code index}, if present. */
    public Optional<String> argText(int index) {
        if (index < 0 || index >= args.size()) {
            return Optional.empty();
        }
        Object arg = args.get(index);
        return Optional.of(arg instanceof Feedback f ? f.text() : (String) arg);
    }

    /** Depth-first search, this message first, for a message with the given translation key. */
    public Optional<Feedback> find(String wanted) {
        if (wanted.equals(key)) {
            return Optional.of(this);
        }
        for (Object arg : args) {
            if (arg instanceof Feedback f) {
                Optional<Feedback> found = f.find(wanted);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
```

```java
package net.slk.bashcraft.pipe;

import java.util.Optional;

/** A value extracted from a command's output, ready to feed into the next command. */
public sealed interface PipeValue {
    /** Text appended to the next command when it has no placeholders. */
    String asArgument();

    /** Value for {@code {name}} (one of x, y, z, value), if this kind of value has it. */
    Optional<String> placeholder(String name);

    /** Short description for error messages, e.g. "a number". */
    String describe();

    record Coordinates(String x, String y, String z) implements PipeValue {
        @Override
        public String asArgument() {
            return x + " " + y + " " + z;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return switch (name) {
                case "x" -> Optional.of(x);
                case "y" -> Optional.of(y);
                case "z" -> Optional.of(z);
                case "value" -> Optional.of(asArgument());
                default -> Optional.empty();
            };
        }

        @Override
        public String describe() {
            return "coordinates";
        }
    }

    record Numeric(String text) implements PipeValue {
        @Override
        public String asArgument() {
            return text;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(text) : Optional.empty();
        }

        @Override
        public String describe() {
            return "a number";
        }
    }

    record Text(String text) implements PipeValue {
        @Override
        public String asArgument() {
            return text;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(text) : Optional.empty();
        }

        @Override
        public String describe() {
            return "text";
        }
    }
}
```

```java
package net.slk.bashcraft.pipe;

/** A pipe value couldn't be produced or used. */
public class PipeException extends Exception {
    public PipeException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write the failing tests**

`FeedbackTest.java`:
```java
package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackTest {
    @Test
    void argTextRendersNestedAndPlainArguments() {
        Feedback inner = Feedback.plain("[kills]");
        Feedback f = Feedback.translatable("commands.scoreboard.players.get.success", "Steve has 7 [kills]", "Steve", 7, inner);
        assertEquals(Optional.of("7"), f.argText(1));
        assertEquals(Optional.of("[kills]"), f.argText(2));
        assertEquals(Optional.empty(), f.argText(3));
    }

    @Test
    void findSearchesNestedArguments() {
        Feedback coords = Feedback.translatable("chat.coordinates", "1, ~, 2", 1, "~", 2);
        Feedback f = Feedback.translatable("outer", "x", Feedback.translatable("chat.square_brackets", "[1, ~, 2]", coords));
        assertEquals(Optional.of(coords), f.find("chat.coordinates"));
        assertEquals(Optional.empty(), f.find("missing"));
    }
}
```

`PlaceholdersTest.java`:
```java
package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholdersTest {
    private static final PipeValue VILLAGE = new PipeValue.Coordinates("312", "~", "-1040");

    @Test
    void appendsWhenThereAreNoPlaceholders() throws Exception {
        assertEquals("tp @s 312 ~ -1040", Placeholders.inject("tp @s", VILLAGE, 845));
    }

    @Test
    void appendIgnoresTrailingWhitespace() throws Exception {
        assertEquals("tp @s 312 ~ -1040", Placeholders.inject("tp @s   ", VILLAGE, 845));
    }

    @Test
    void substitutesCoordinatePlaceholdersInsteadOfAppending() throws Exception {
        assertEquals("tp @s 312 100 -1040", Placeholders.inject("tp @s {x} 100 {z}", VILLAGE, 845));
    }

    @Test
    void negativeAndDecimalCoordinatesSurvive() throws Exception {
        PipeValue v = new PipeValue.Coordinates("-12.5", "64", "-0.5");
        assertEquals("tp @s -12.5 64 -0.5", Placeholders.inject("tp @s {x} {y} {z}", v, 1));
    }

    @Test
    void valueAndResultPlaceholders() throws Exception {
        assertEquals("scoreboard players set n obj 12.5", Placeholders.inject("scoreboard players set n obj {value}", new PipeValue.Numeric("12.5"), 3));
        assertEquals("say 845", Placeholders.inject("say {result}", new PipeValue.Text("hi"), 845));
    }

    @Test
    void nbtBracesAreNotPlaceholders() throws Exception {
        assertEquals("summon zombie 312 64 -1040 {NoAI:1b}", Placeholders.inject("summon zombie {x} 64 {z} {NoAI:1b}", VILLAGE, 1));
    }

    @Test
    void escapedPlaceholderIsLiteral() throws Exception {
        assertEquals("say {x} hi", Placeholders.inject("say \\{x} {value}", new PipeValue.Text("hi"), 1));
        assertEquals("say {x} hi", Placeholders.inject("say \\{x}", new PipeValue.Text("hi"), 1));
    }

    @Test
    void placeholderTheValueLacksIsAnError() {
        PipeException e = assertThrows(PipeException.class, () -> Placeholders.inject("tp @s {x} 0 0", new PipeValue.Numeric("5"), 5));
        assertEquals("{x} is not available from a number", e.getMessage());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.*'`
Expected: compilation FAIL — `cannot find symbol: variable Placeholders`.

- [ ] **Step 4: Write `Placeholders.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds a piped value into the next command. Only {x} {y} {z} {value} {result} are placeholders;
 * other braces (NBT, JSON) are untouched. With no placeholders the value is appended.
 */
public final class Placeholders {
    private static final Pattern TOKEN = Pattern.compile("\\\\?\\{(x|y|z|value|result)}");

    private Placeholders() {}

    public static String inject(String command, PipeValue value, int previousResult) throws PipeException {
        Matcher m = TOKEN.matcher(command);
        StringBuilder out = new StringBuilder();
        boolean substituted = false;
        while (m.find()) {
            String replacement;
            if (m.group().startsWith("\\")) {
                replacement = m.group().substring(1);
            } else {
                substituted = true;
                String name = m.group(1);
                replacement = name.equals("result")
                        ? Integer.toString(previousResult)
                        : value.placeholder(name).orElseThrow(() -> new PipeException("{" + name + "} is not available from " + value.describe()));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String result = out.toString();
        return substituted ? result : result.stripTrailing() + " " + value.asArgument();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.*'`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/pipe src/test/java/net/slk/bashcraft/pipe
git commit -m "Add pipe values and placeholder injection"
```

---

### Task 5: Pipe resolution (adapters + fallback extractor)

**Files:**
- Create: `src/main/java/net/slk/bashcraft/pipe/OutputAdapters.java`, `FeedbackExtractor.java`, `PipeResolver.java`
- Test: `src/test/java/net/slk/bashcraft/pipe/PipeResolverTest.java`

**Interfaces:**
- Consumes: `Feedback`, `PipeValue`, `PipeException` (Task 4).
- Produces: `PipeResolver.resolve(List<Feedback> output) throws PipeException` → `PipeValue` (uses the last message; throws when there is no usable output).

Translation keys and argument positions (verified against the 26.3 `en_us.json` and `LocateCommand`):
- `commands.locate.{structure,biome,poi}.success`: arg 1 wraps `chat.coordinates` with args (x, y-or-`~`, z)
- `commands.data.entity.query` arg 1, `commands.data.block.query` arg 3, `commands.data.storage.query` arg 1: NBT text
- `commands.time.query` arg 0; `commands.scoreboard.players.get.success` arg 1

- [ ] **Step 1: Write the failing tests `PipeResolverTest.java`**

```java
package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipeResolverTest {
    /** Mirrors LocateCommand: success(key, name, [chat.coordinates(x, y, z)], distance). */
    static Feedback locate(String key, Object y) {
        Feedback coords = Feedback.translatable("chat.coordinates", "312, " + y + ", -1040", 312, y, -1040);
        Feedback brackets = Feedback.translatable("chat.square_brackets", "[312, " + y + ", -1040]", coords);
        return Feedback.translatable(key, "The nearest minecraft:village is at [312, " + y + ", -1040] (845 blocks away)",
                "minecraft:village", brackets, 845);
    }

    private static PipeValue resolve(Feedback... output) throws PipeException {
        return PipeResolver.resolve(List.of(output));
    }

    @Test
    void locateStructureGivesCoordinatesWithTildeY() throws Exception {
        assertEquals(new PipeValue.Coordinates("312", "~", "-1040"), resolve(locate("commands.locate.structure.success", "~")));
    }

    @Test
    void locateBiomeKeepsY() throws Exception {
        assertEquals(new PipeValue.Coordinates("312", "64", "-1040"), resolve(locate("commands.locate.biome.success", 64)));
    }

    @Test
    void dataEntityNumberDropsNbtSuffix() throws Exception {
        Feedback f = Feedback.translatable("commands.data.entity.query", "Steve has the following entity data: 20.0f", "Steve", Feedback.plain("20.0f"));
        assertEquals(new PipeValue.Numeric("20.0"), resolve(f));
    }

    @Test
    void dataEntityStringDropsQuotes() throws Exception {
        Feedback f = Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("\"minecraft:stone\""));
        assertEquals(new PipeValue.Text("minecraft:stone"), resolve(f));
    }

    @Test
    void dataBlockUsesFourthArgument() throws Exception {
        Feedback f = Feedback.translatable("commands.data.block.query", "1, 2, 3 has the following block data: 5b", 1, 2, 3, Feedback.plain("5b"));
        assertEquals(new PipeValue.Numeric("5"), resolve(f));
    }

    @Test
    void timeQuery() throws Exception {
        assertEquals(new PipeValue.Numeric("1234"), resolve(Feedback.translatable("commands.time.query", "The time is 1234", 1234)));
    }

    @Test
    void scoreboardGet() throws Exception {
        Feedback f = Feedback.translatable("commands.scoreboard.players.get.success", "Steve has 7 [kills]", "Steve", 7, Feedback.plain("[kills]"));
        assertEquals(new PipeValue.Numeric("7"), resolve(f));
    }

    @Test
    void fallbackFindsCoordinateTripleInText() throws Exception {
        assertEquals(new PipeValue.Coordinates("10", "64", "-20"), resolve(Feedback.plain("Spawn is at 10, 64, -20")));
    }

    @Test
    void fallbackTakesLastNumber() throws Exception {
        assertEquals(new PipeValue.Numeric("20"), resolve(Feedback.plain("There are 3 of a max of 20 players online")));
    }

    @Test
    void fallbackIgnoresDigitsInsideIds() throws Exception {
        assertEquals(new PipeValue.Text("Found minecraft:village2"), resolve(Feedback.plain("Found minecraft:village2")));
    }

    @Test
    void usesTheLastMessage() throws Exception {
        assertEquals(new PipeValue.Numeric("9"), resolve(Feedback.plain("first 1"), Feedback.plain("second 9")));
    }

    @Test
    void noOutputIsAnError() {
        assertThrows(PipeException.class, () -> PipeResolver.resolve(List.of()));
        assertThrows(PipeException.class, () -> resolve(Feedback.plain("   ")));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.PipeResolverTest'`
Expected: compilation FAIL — `cannot find symbol: variable PipeResolver`.

- [ ] **Step 3: Write `OutputAdapters.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact extraction for known messages, keyed on translation key so it works in any server language. */
final class OutputAdapters {
    private static final Pattern NBT_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?)[bBsSlLfFdD]?");

    private static final Map<String, Function<Feedback, Optional<PipeValue>>> ADAPTERS = Map.of(
            "commands.locate.structure.success", OutputAdapters::coordinates,
            "commands.locate.biome.success", OutputAdapters::coordinates,
            "commands.locate.poi.success", OutputAdapters::coordinates,
            "commands.data.entity.query", f -> data(f, 1),
            "commands.data.block.query", f -> data(f, 3),
            "commands.data.storage.query", f -> data(f, 1),
            "commands.time.query", f -> numeric(f, 0),
            "commands.scoreboard.players.get.success", f -> numeric(f, 1));

    private OutputAdapters() {}

    static Optional<PipeValue> adapt(Feedback feedback) {
        Function<Feedback, Optional<PipeValue>> adapter = feedback.key() == null ? null : ADAPTERS.get(feedback.key());
        return adapter == null ? Optional.empty() : adapter.apply(feedback);
    }

    /** The first {@code chat.coordinates} anywhere in the message. */
    static Optional<PipeValue> coordinates(Feedback feedback) {
        return feedback.find("chat.coordinates")
                .filter(c -> c.args().size() == 3)
                .map(c -> new PipeValue.Coordinates(c.argText(0).orElseThrow(), c.argText(1).orElseThrow(), c.argText(2).orElseThrow()));
    }

    private static Optional<PipeValue> numeric(Feedback feedback, int index) {
        return feedback.argText(index).map(PipeValue.Numeric::new);
    }

    /** NBT numbers lose their type suffix; quoted strings lose their quotes; anything else is text. */
    private static Optional<PipeValue> data(Feedback feedback, int index) {
        return feedback.argText(index).map(String::strip).map(text -> {
            Matcher m = NBT_NUMBER.matcher(text);
            if (m.matches()) {
                return new PipeValue.Numeric(m.group(1));
            }
            boolean quoted = text.length() >= 2
                    && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"));
            return new PipeValue.Text(quoted ? text.substring(1, text.length() - 1) : text);
        });
    }
}
```

- [ ] **Step 4: Write `FeedbackExtractor.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fallback for messages without an adapter: coordinates, then the last number, then the whole text. */
final class FeedbackExtractor {
    private static final String NUM = "-?\\d+(?:\\.\\d+)?";
    private static final Pattern TRIPLE = Pattern.compile("(" + NUM + "|~)\\s*,\\s*(" + NUM + "|~)\\s*,\\s*(" + NUM + "|~)");
    /** A standalone number: not glued to letters, ids or other digits. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.:-])" + NUM + "(?![\\w:])");

    private FeedbackExtractor() {}

    static PipeValue extract(Feedback feedback) {
        Optional<PipeValue> coordinates = OutputAdapters.coordinates(feedback);
        if (coordinates.isPresent()) {
            return coordinates.get();
        }
        String text = feedback.text();
        Matcher triple = TRIPLE.matcher(text);
        if (triple.find()) {
            return new PipeValue.Coordinates(triple.group(1), triple.group(2), triple.group(3));
        }
        Matcher number = NUMBER.matcher(text);
        String last = null;
        while (number.find()) {
            last = number.group();
        }
        return last != null ? new PipeValue.Numeric(last) : new PipeValue.Text(text.strip());
    }
}
```

- [ ] **Step 5: Write `PipeResolver.java`**

```java
package net.slk.bashcraft.pipe;

import java.util.List;

/** Turns a command's captured output into the value piped to the next command. */
public final class PipeResolver {
    private PipeResolver() {}

    /** Uses the last message: its adapter if there is one, otherwise the fallback extractor. */
    public static PipeValue resolve(List<Feedback> output) throws PipeException {
        if (output.isEmpty()) {
            throw new PipeException("produced no output");
        }
        Feedback last = output.getLast();
        PipeValue value = OutputAdapters.adapt(last).orElseGet(() -> FeedbackExtractor.extract(last));
        if (value instanceof PipeValue.Text text && text.text().isBlank()) {
            throw new PipeException("produced no output");
        }
        return value;
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.pipe.*'`
Expected: `BUILD SUCCESSFUL`, 22 tests passed.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/slk/bashcraft/pipe src/test/java/net/slk/bashcraft/pipe
git commit -m "Add pipe resolution with output adapters and fallback extraction"
```

---

### Task 6: Chain executor

**Files:**
- Create: `src/main/java/net/slk/bashcraft/exec/ExecResult.java`, `RunOptions.java`, `CommandRunner.java`, `ChainExecutor.java`
- Test: `src/test/java/net/slk/bashcraft/exec/ChainExecutorTest.java`, `src/test/java/net/slk/bashcraft/exec/FakeRunner.java`

**Interfaces:**
- Consumes: `Chain`, `Pipeline`, `Operator`, `ChainParser` (Task 3); `Feedback`, `PipeResolver`, `Placeholders`, `PipeException`, `PipeValue` (Tasks 4–5).
- Produces:
  - `record ExecResult(boolean success, int result, List<Feedback> output)`, `ExecResult.failure()`
  - `record RunOptions(boolean swallowSuccess, String failurePrefix)` — `failurePrefix` null for single-command chains, else `"[n] "`
  - `interface CommandRunner { ExecResult run(String command, RunOptions options); void reportError(String message); }`
  - `new ChainExecutor(CommandRunner runner, boolean swallowPipedOutput).execute(Chain)` → `ExecResult` of the last segment that ran
- Error texts (passed to `reportError`, which adds `BashCraft: `): `"[2] nothing to pipe from /a"`, `"[2] {x} is not available from a number"` (no `[n] ` for single-segment chains, which can't pipe anyway).

- [ ] **Step 1: Write the result types and runner interface**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.Feedback;

import java.util.List;

/**
 * Outcome of one command.
 *
 * @param success no error and result &gt; 0 (vanilla's rule)
 * @param output  success messages, captured whether or not they were shown
 */
public record ExecResult(boolean success, int result, List<Feedback> output) {
    public ExecResult {
        output = List.copyOf(output);
    }

    public static ExecResult failure() {
        return new ExecResult(false, 0, List.of());
    }
}
```

```java
package net.slk.bashcraft.exec;

/**
 * How to run one segment.
 *
 * @param swallowSuccess hide success messages (the segment feeds a pipe)
 * @param failurePrefix  prepended to error messages, e.g. "[2] "; null when the chain has one command
 */
public record RunOptions(boolean swallowSuccess, String failurePrefix) {}
```

```java
package net.slk.bashcraft.exec;

/** Runs single commands for {@link ChainExecutor}; the Minecraft implementation lives in {@code mc}. */
public interface CommandRunner {
    /** Runs the command to completion and returns its outcome. Errors are shown to the user by the runner. */
    ExecResult run(String command, RunOptions options);

    /** Shows a BashCraft error (one not produced by a command) to the user. */
    void reportError(String message);
}
```

- [ ] **Step 2: Write `FakeRunner.java` and the failing tests `ChainExecutorTest.java`**

```java
package net.slk.bashcraft.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records what ran; commands succeed with result 1 and no output unless told otherwise. */
final class FakeRunner implements CommandRunner {
    final List<String> ran = new ArrayList<>();
    final List<RunOptions> options = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    private final Map<String, ExecResult> results = new HashMap<>();

    FakeRunner on(String command, ExecResult result) {
        results.put(command, result);
        return this;
    }

    @Override
    public ExecResult run(String command, RunOptions options) {
        ran.add(command);
        this.options.add(options);
        return results.getOrDefault(command, new ExecResult(true, 1, List.of()));
    }

    @Override
    public void reportError(String message) {
        errors.add(message);
    }
}
```

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.ChainParser;
import net.slk.bashcraft.pipe.Feedback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainExecutorTest {
    private static final ExecResult FAIL = ExecResult.failure();

    private static ExecResult run(FakeRunner runner, String chain) throws Exception {
        return new ChainExecutor(runner, true).execute(ChainParser.parse(chain, ChainParser.Mode.SH, 32));
    }

    private static Feedback locateVillage() {
        Feedback coords = Feedback.translatable("chat.coordinates", "312, ~, -1040", 312, "~", -1040);
        return Feedback.translatable("commands.locate.structure.success", "The nearest minecraft:village is at [312, ~, -1040] (845 blocks away)",
                "minecraft:village", Feedback.translatable("chat.square_brackets", "[312, ~, -1040]", coords), 845);
    }

    @Test
    void andRunsNextAfterSuccess() throws Exception {
        FakeRunner r = new FakeRunner();
        assertTrue(run(r, "a && b").success());
        assertEquals(List.of("a", "b"), r.ran);
    }

    @Test
    void andSkipsNextAfterFailure() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertFalse(run(r, "a && b").success());
        assertEquals(List.of("a"), r.ran);
    }

    @Test
    void orRunsOnlyAfterFailure() throws Exception {
        FakeRunner failing = new FakeRunner().on("a", FAIL);
        run(failing, "a || b");
        assertEquals(List.of("a", "b"), failing.ran);

        FakeRunner passing = new FakeRunner();
        ExecResult result = run(passing, "a || b");
        assertEquals(List.of("a"), passing.ran);
        assertTrue(result.success(), "status of a skipped segment is the previous one's");
    }

    @Test
    void semicolonAlwaysRuns() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertTrue(run(r, "a && x ; b").success());
        assertEquals(List.of("a", "b"), r.ran);
    }

    @Test
    void mixedOperatorsEvaluateLeftToRight() throws Exception {
        FakeRunner r = new FakeRunner().on("b", FAIL);
        run(r, "a && b || c");
        assertEquals(List.of("a", "b", "c"), r.ran);
    }

    @Test
    void pipeAppendsExtractedCoordinates() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "locate structure minecraft:village | tp @s");
        assertEquals("tp @s 312 ~ -1040", r.ran.get(1));
    }

    @Test
    void pipePlaceholdersAndResult() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "locate structure minecraft:village | tp @s {x} 120 {z} ; say done");
        assertEquals("tp @s 312 120 -1040", r.ran.get(1));

        FakeRunner r2 = new FakeRunner().on("a", new ExecResult(true, 7, List.of(Feedback.plain("ok"))));
        run(r2, "a | say {result}");
        assertEquals("say 7", r2.ran.get(1));
    }

    @Test
    void pipedOutputIsSwallowedUnlessDisabled() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "a | b");
        assertTrue(r.options.get(0).swallowSuccess());
        assertFalse(r.options.get(1).swallowSuccess());

        FakeRunner shown = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        new ChainExecutor(shown, false).execute(ChainParser.parse("a | b", ChainParser.Mode.SH, 32));
        assertFalse(shown.options.get(0).swallowSuccess());
    }

    @Test
    void failurePrefixesNumberSegmentsAcrossPipelines() throws Exception {
        FakeRunner single = new FakeRunner();
        run(single, "a");
        assertNull(single.options.get(0).failurePrefix());

        FakeRunner r = new FakeRunner().on("b", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "a ; b | c");
        assertEquals(List.of("[1] ", "[2] ", "[3] "), r.options.stream().map(RunOptions::failurePrefix).toList());
    }

    @Test
    void failedPipeSourceStopsThePipelineSilently() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertFalse(run(r, "a | b").success());
        assertEquals(List.of("a"), r.ran);
        assertEquals(List.of(), r.errors, "the command's own error was already shown");
    }

    @Test
    void nothingToPipeReportsAndOrRecovers() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "say hi | tp @s || say fallback");
        assertEquals(List.of("[2] nothing to pipe from /say hi"), r.errors);
        assertEquals(List.of("say hi", "say fallback"), r.ran);
    }

    @Test
    void missingPlaceholderIsReported() throws Exception {
        FakeRunner r = new FakeRunner().on("time query gametime",
                new ExecResult(true, 50, List.of(Feedback.translatable("commands.time.query", "The time is 50", 50))));
        assertFalse(run(r, "time query gametime | tp @s {x} 0 0").success());
        assertEquals(List.of("[2] {x} is not available from a number"), r.errors);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: compilation FAIL — `cannot find symbol: class ChainExecutor`.

- [ ] **Step 4: Write `ChainExecutor.java`**

```java
package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Chain;
import net.slk.bashcraft.chain.Pipeline;
import net.slk.bashcraft.pipe.PipeException;
import net.slk.bashcraft.pipe.PipeResolver;
import net.slk.bashcraft.pipe.PipeValue;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.List;

/** Runs a chain with bash semantics: && / || / ; decided by the last status, | feeds extracted values forward. */
public final class ChainExecutor {
    private final CommandRunner runner;
    private final boolean swallowPipedOutput;

    public ChainExecutor(CommandRunner runner, boolean swallowPipedOutput) {
        this.runner = runner;
        this.swallowPipedOutput = swallowPipedOutput;
    }

    /** Returns the outcome of the last segment that ran (a skipped pipeline leaves the status unchanged). */
    public ExecResult execute(Chain chain) {
        boolean numbered = chain.segmentCount() > 1;
        int firstIndex = 1;
        ExecResult status = null;
        for (int p = 0; p < chain.pipelines().size(); p++) {
            Pipeline pipeline = chain.pipelines().get(p);
            boolean run = p == 0 || switch (chain.joins().get(p - 1)) {
                case AND -> status.success();
                case OR -> !status.success();
                case SEQ -> true;
            };
            if (run) {
                status = runPipeline(pipeline, firstIndex, numbered);
            }
            firstIndex += pipeline.commands().size();
        }
        return status;
    }

    private ExecResult runPipeline(Pipeline pipeline, int firstIndex, boolean numbered) {
        List<String> commands = pipeline.commands();
        ExecResult previous = null;
        for (int i = 0; i < commands.size(); i++) {
            String prefix = numbered ? "[" + (firstIndex + i) + "] " : null;
            String label = prefix == null ? "" : prefix;
            String command = commands.get(i);
            if (previous != null) {
                if (!previous.success()) {
                    return previous;
                }
                PipeValue value;
                try {
                    value = PipeResolver.resolve(previous.output());
                } catch (PipeException e) {
                    runner.reportError(label + "nothing to pipe from /" + commands.get(i - 1));
                    return ExecResult.failure();
                }
                try {
                    command = Placeholders.inject(command, value, previous.result());
                } catch (PipeException e) {
                    runner.reportError(label + e.getMessage());
                    return ExecResult.failure();
                }
            }
            boolean feedsPipe = i < commands.size() - 1;
            previous = runner.run(command, new RunOptions(feedsPipe && swallowPipedOutput, prefix));
        }
        return previous;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.exec.*'`
Expected: `BUILD SUCCESSFUL`, 12 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/exec src/test/java/net/slk/bashcraft/exec
git commit -m "Add chain executor with operator and pipe semantics"
```

---

### Task 7: Config and nesting limit

**Files:**
- Create: `src/main/java/net/slk/bashcraft/config/BashcraftConfig.java`, `src/main/java/net/slk/bashcraft/exec/DepthGuard.java`
- Test: `src/test/java/net/slk/bashcraft/config/BashcraftConfigTest.java`, `src/test/java/net/slk/bashcraft/exec/DepthGuardTest.java`

**Interfaces:**
- Produces:
  - `BashcraftConfig` with public fields `transparentMode` (true), `maxSegments` (32), `maxDepth` (8), `swallowPipedOutput` (true); `static BashcraftConfig load(Path file)`; `static BashcraftConfig get()`
  - `DepthGuard.enter(int maxDepth)` → `Optional<DepthGuard.Scope>` (empty when the limit is reached); `Scope implements AutoCloseable` (`close()` without exceptions)

- [ ] **Step 1: Write the failing tests**

```java
package net.slk.bashcraft.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashcraftConfigTest {
    @TempDir
    Path dir;

    @Test
    void missingFileGivesDefaultsAndIsWritten() throws Exception {
        Path file = dir.resolve("config/bashcraft.json");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertTrue(config.transparentMode);
        assertEquals(32, config.maxSegments);
        assertEquals(8, config.maxDepth);
        assertTrue(config.swallowPipedOutput);
        assertTrue(Files.readString(file).contains("\"maxSegments\": 32"));
        assertSame(config, BashcraftConfig.get());
    }

    @Test
    void partialFileKeepsOtherDefaults() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{\"maxDepth\": 3}");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(3, config.maxDepth);
        assertEquals(32, config.maxSegments);
    }

    @Test
    void brokenFileFallsBackToDefaultsAndIsLeftAlone() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{not json");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(32, config.maxSegments);
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void limitsAreClampedToAtLeastOne() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{\"maxSegments\": 0, \"maxDepth\": -4}");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(1, config.maxSegments);
        assertEquals(1, config.maxDepth);
    }
}
```

```java
package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DepthGuardTest {
    @Test
    void refusesBeyondMaxAndRecoversAfterClose() {
        Optional<DepthGuard.Scope> first = DepthGuard.enter(2);
        Optional<DepthGuard.Scope> second = DepthGuard.enter(2);
        assertTrue(first.isPresent() && second.isPresent());
        assertTrue(DepthGuard.enter(2).isEmpty(), "third level is refused");

        second.get().close();
        Optional<DepthGuard.Scope> again = DepthGuard.enter(2);
        assertTrue(again.isPresent(), "closing frees a level");

        again.get().close();
        first.get().close();
    }

    @Test
    void closingTwiceOnlyFreesOneLevel() {
        DepthGuard.Scope scope = DepthGuard.enter(1).orElseThrow();
        scope.close();
        scope.close();
        DepthGuard.Scope next = DepthGuard.enter(1).orElseThrow();
        assertTrue(DepthGuard.enter(1).isEmpty());
        next.close();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test --tests 'net.slk.bashcraft.config.*' --tests 'net.slk.bashcraft.exec.DepthGuardTest'`
Expected: compilation FAIL — `cannot find symbol: class BashcraftConfig`.

- [ ] **Step 3: Write `config/BashcraftConfig.java`**

```java
package net.slk.bashcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings from {@code config/bashcraft.json}. Missing keys keep their defaults. */
public final class BashcraftConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("bashcraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BashcraftConfig instance = new BashcraftConfig();

    /** Enable the prefix-free hooks (milestone 2). */
    public boolean transparentMode = true;
    /** Max commands in one chain. */
    public int maxSegments = 32;
    /** Max nesting of chains inside chains (e.g. through functions). */
    public int maxDepth = 8;
    /** Hide success messages of commands that feed a pipe. */
    public boolean swallowPipedOutput = true;

    public static BashcraftConfig get() {
        return instance;
    }

    /** Reads the file, writing defaults if it doesn't exist. A broken file is logged and left alone; defaults are used. */
    public static BashcraftConfig load(Path file) {
        BashcraftConfig config = new BashcraftConfig();
        try {
            if (Files.exists(file)) {
                BashcraftConfig read = GSON.fromJson(Files.readString(file), BashcraftConfig.class);
                if (read != null) {
                    config = read;
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(config));
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("BashCraft: could not read {}, using defaults: {}", file, e.getMessage());
            config = new BashcraftConfig();
        }
        config.maxSegments = Math.max(1, config.maxSegments);
        config.maxDepth = Math.max(1, config.maxDepth);
        instance = config;
        return config;
    }
}
```

- [ ] **Step 4: Write `exec/DepthGuard.java`**

```java
package net.slk.bashcraft.exec;

import java.util.Optional;

/** Limits how deeply chains nest on this thread (a function running {@code sh} that calls itself, for example). */
public final class DepthGuard {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private DepthGuard() {}

    /** Enters one more level, or returns empty if {@code maxDepth} levels are already active. */
    public static Optional<Scope> enter(int maxDepth) {
        int[] depth = DEPTH.get();
        if (depth[0] >= maxDepth) {
            return Optional.empty();
        }
        depth[0]++;
        return Optional.of(new Scope());
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                DEPTH.get()[0]--;
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all unit tests (Tasks 3–7) pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/slk/bashcraft/config src/main/java/net/slk/bashcraft/exec/DepthGuard.java src/test/java/net/slk/bashcraft/config src/test/java/net/slk/bashcraft/exec/DepthGuardTest.java
git commit -m "Add config file and chain nesting limit"
```

---

### Task 8: Minecraft integration and `/sh`

**Prerequisite:** Task 2's notes say "Approach confirmed". If not, stop — this task must be re-planned.

**Files:**
- Create: `src/main/java/net/slk/bashcraft/mc/ComponentFeedback.java`, `mc/TeeSource.java`, `mc/MinecraftCommandRunner.java`, `command/ShCommand.java`, `mixin/CommandSourceStackAccessor.java`, `mixin/CommandSourceStackMixin.java`
- Modify: `src/main/java/net/slk/bashcraft/Bashcraft.java`, `src/main/resources/bashcraft.mixins.json`, `src/gametest/resources/fabric.mod.json`
- Test: `src/gametest/java/net/slk/bashcraft/test/BashcraftChainCheck.java`, `src/gametest/resources/data/bashcraft-test/function/chain.mcfunction`, `src/gametest/resources/data/bashcraft-test/function/recurse.mcfunction`

**Interfaces:**
- Consumes: `CommandsAccessor` (Task 2); `ChainParser`, `Chain`, `ChainSyntaxException` (Task 3); `Feedback` (Task 4); `ExecResult`, `RunOptions`, `CommandRunner`, `ChainExecutor` (Task 6); `BashcraftConfig`, `DepthGuard` (Task 7).
- Produces: `/sh <chain>` (no permission requirement of its own); `ShCommand.run(CommandSourceStack, String)` → `int` (last segment's result, or 0 on failure) — M2's hooks will call this.

**Why the mixin:** `CommandSourceStack.sendSuccess` drops messages when the stack is silent (tick/load functions use `withSuppressedOutput()`), so a plain wrapping `CommandSource` would see nothing to pipe there. `CommandSourceStackMixin` captures success messages for a `TeeSource` before that check, and applies the `[n] ` failure prefix.

- [ ] **Step 1: Write the game test first — `BashcraftChainCheck.java` and its functions**

`src/gametest/resources/data/bashcraft-test/function/chain.mcfunction`:
```
sh setblock 7 120 0 minecraft:stone && setblock 8 120 0 minecraft:stone
```

`src/gametest/resources/data/bashcraft-test/function/recurse.mcfunction`:
```
scoreboard players add depth bc 1
sh function bashcraft-test:recurse
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

/** /sh end to end: operators, pipes, command blocks, functions and the nesting limit. */
@SuppressWarnings("UnstableApiUsage")
public class BashcraftChainCheck implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("fill 0 120 0 12 120 2 minecraft:air");
            server.runCommand("scoreboard objectives add bc dummy");

            server.runCommand("sh setblock 0 120 0 minecraft:stone && setblock 1 120 0 minecraft:stone");
            check(stone(server, 0, 0) && stone(server, 1, 0), "&& runs the second command after a success");
            server.runCommand("sh setblock 0 120 0 minecraft:stone && setblock 2 120 0 minecraft:stone");
            check(!stone(server, 2, 0), "&& skips the second command after a failure");
            server.runCommand("sh setblock 0 120 0 minecraft:stone || setblock 3 120 0 minecraft:stone");
            check(stone(server, 3, 0), "|| runs the second command after a failure");
            server.runCommand("sh setblock 0 120 0 minecraft:stone ; setblock 4 120 0 minecraft:stone");
            check(stone(server, 4, 0), "; always runs the second command");

            server.runCommand("scoreboard players set n bc 11");
            server.runCommand("sh scoreboard players get n bc | setblock {value} 120 1 minecraft:stone");
            check(stone(server, 11, 1), "a score pipes into a placeholder");

            server.runCommand("setblock 5 121 0 minecraft:command_block{Command:\"sh setblock 5 120 0 minecraft:stone && setblock 6 120 0 minecraft:stone\",auto:1b}");
            context.waitTicks(5);
            check(stone(server, 5, 0) && stone(server, 6, 0), "chains run from a command block");

            server.runCommand("function bashcraft-test:chain");
            check(stone(server, 7, 0) && stone(server, 8, 0), "chains run from a function");

            server.runCommand("function bashcraft-test:recurse");
            server.runCommand("sh scoreboard players get depth bc | setblock {value} 120 2 minecraft:stone");
            check(stone(server, 9, 2), "recursion through functions stops after maxDepth (8) nested chains: 9 runs");

            // Last: moves the player away from the test blocks. Needs a village within locate range (almost every seed).
            server.runCommand("execute as @p run sh locate structure minecraft:village | tp @s {x} 200 {z}");
            check(server.computeOnServer(s -> {
                ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                return Math.abs(player.getY() - 200) < 0.01 && (Math.abs(player.getX()) > 1 || Math.abs(player.getZ()) > 1);
            }), "locate pipes coordinates into tp");
        }
    }

    private static boolean stone(TestServerContext server, int x, int z) {
        return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, 120, z)).is(Blocks.STONE));
    }
}
```

Add `"net.slk.bashcraft.test.BashcraftChainCheck"` to the `fabric-client-gametest` list in `src/gametest/resources/fabric.mod.json`.

- [ ] **Step 2: Run the game test to verify it fails**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest 2>&1 | grep -E "AssertionError|Unknown or incomplete command|BUILD"`
Expected: `AssertionError: Check failed: && runs the second command after a success` (there is no `/sh` yet; the functions also fail to load with an unknown-command error).

- [ ] **Step 3: Write `mc/ComponentFeedback.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.slk.bashcraft.pipe.Feedback;

import java.util.ArrayList;
import java.util.List;

/** Converts chat components into the Minecraft-free {@link Feedback} the pipe code reads. */
public final class ComponentFeedback {
    private ComponentFeedback() {}

    public static Feedback toFeedback(Component component) {
        String text = component.getString();
        if (component.getContents() instanceof TranslatableContents translatable) {
            List<Object> args = new ArrayList<>();
            for (Object arg : translatable.getArgs()) {
                args.add(arg instanceof Component c ? toFeedback(c) : String.valueOf(arg));
            }
            return new Feedback(translatable.getKey(), args, text);
        }
        // Component.empty().append(message) wraps the real message in a single sibling.
        if (component.getContents() == PlainTextContents.EMPTY && component.getSiblings().size() == 1) {
            Feedback inner = toFeedback(component.getSiblings().getFirst());
            return new Feedback(inner.key(), inner.args(), text);
        }
        return Feedback.plain(text);
    }
}
```

- [ ] **Step 4: Write `mc/TeeSource.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.exec.RunOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The CommandSource one chain segment runs with: forwards output to the invoker's source and
 * records success messages. {@code CommandSourceStackMixin} feeds {@link #capture} and applies {@link RunOptions}.
 */
public final class TeeSource implements CommandSource {
    private final CommandSource target;
    private final RunOptions options;
    private final List<Component> captured = new ArrayList<>();

    public TeeSource(CommandSource target, RunOptions options) {
        this.target = target;
        this.options = options;
    }

    public RunOptions options() {
        return options;
    }

    public List<Component> captured() {
        return captured;
    }

    public void capture(Component message) {
        captured.add(message);
    }

    @Override
    public void sendSystemMessage(Component message) {
        target.sendSystemMessage(message);
    }

    @Override
    public boolean acceptsSuccess() {
        return target.acceptsSuccess();
    }

    @Override
    public boolean acceptsFailure() {
        return target.acceptsFailure();
    }

    @Override
    public boolean shouldInformAdmins() {
        return target.shouldInformAdmins();
    }

    @Override
    public boolean alwaysAccepts() {
        return target.alwaysAccepts();
    }
}
```

- [ ] **Step 5: Write the mixins**

`mixin/CommandSourceStackAccessor.java`:
```java
package net.slk.bashcraft.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source")
    CommandSource bashcraft$source();
}
```

`mixin/CommandSourceStackMixin.java`:
```java
package net.slk.bashcraft.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.mc.TeeSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** For chain segments (source is a TeeSource): capture success output even when silent, hide it when piped, number errors. */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Shadow @Final private CommandSource source;
    @Shadow @Final private boolean silent;

    @Inject(method = "sendSuccess", at = @At("HEAD"), cancellable = true)
    private void bashcraft$captureSuccess(Supplier<Component> message, boolean broadcast, CallbackInfo ci) {
        if (source instanceof TeeSource tee) {
            tee.capture(message.get());
            if (tee.options().swallowSuccess()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "sendFailure", at = @At("HEAD"), cancellable = true)
    private void bashcraft$prefixFailure(Component message, CallbackInfo ci) {
        if (source instanceof TeeSource tee && tee.options().failurePrefix() != null) {
            ci.cancel();
            if (tee.acceptsFailure() && !silent) {
                tee.sendSystemMessage(Component.empty().append(tee.options().failurePrefix()).append(message).withStyle(ChatFormatting.RED));
            }
        }
    }
}
```

Set the `mixins` array in `bashcraft.mixins.json` to:
```json
	"mixins": [
		"CommandSourceStackAccessor",
		"CommandSourceStackMixin",
		"CommandsAccessor"
	],
```

- [ ] **Step 6: Write `mc/MinecraftCommandRunner.java`**

```java
package net.slk.bashcraft.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.exec.CommandRunner;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.exec.RunOptions;
import net.slk.bashcraft.mixin.CommandSourceStackAccessor;
import net.slk.bashcraft.mixin.CommandsAccessor;
import net.slk.bashcraft.pipe.Feedback;

import java.util.List;

/** Runs chain segments as the invoking source, to completion, capturing result and feedback. */
public final class MinecraftCommandRunner implements CommandRunner {
    private final CommandSourceStack invoker;

    public MinecraftCommandRunner(CommandSourceStack invoker) {
        this.invoker = invoker;
    }

    @Override
    public ExecResult run(String command, RunOptions options) {
        TeeSource tee = new TeeSource(((CommandSourceStackAccessor) invoker).bashcraft$source(), options);
        boolean[] success = {false};
        int[] result = {0};
        CommandSourceStack stack = invoker.withSource(tee).withCallback((ok, value) -> {
            success[0] = ok;
            result[0] = value;
        });
        runNow(stack, command);
        List<Feedback> output = tee.captured().stream().map(ComponentFeedback::toFeedback).toList();
        return new ExecResult(success[0] && result[0] > 0, result[0], output);
    }

    @Override
    public void reportError(String message) {
        invoker.sendFailure(Component.literal("BashCraft: " + message));
    }

    /**
     * Gives the command its own top-level execution context so it finishes before this returns;
     * otherwise it would be queued behind the running command (see docs/superpowers/notes/2026-10-01-m0-spike.md).
     */
    private static void runNow(CommandSourceStack stack, String command) {
        ThreadLocal<ExecutionContext<CommandSourceStack>> current = CommandsAccessor.bashcraft$currentExecutionContext();
        ExecutionContext<CommandSourceStack> saved = current.get();
        current.set(null);
        try {
            stack.getServer().getCommands().performPrefixedCommand(stack, command);
        } finally {
            current.set(saved);
        }
    }
}
```

- [ ] **Step 7: Write `command/ShCommand.java`**

```java
package net.slk.bashcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.slk.bashcraft.chain.Chain;
import net.slk.bashcraft.chain.ChainParser;
import net.slk.bashcraft.chain.ChainSyntaxException;
import net.slk.bashcraft.config.BashcraftConfig;
import net.slk.bashcraft.exec.ChainExecutor;
import net.slk.bashcraft.exec.DepthGuard;
import net.slk.bashcraft.exec.ExecResult;
import net.slk.bashcraft.mc.MinecraftCommandRunner;

import java.util.Optional;

/** {@code /sh <chain>}. Needs no permission itself; each command in the chain checks its own. */
public final class ShCommand {
    private ShCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("sh")
                        .then(Commands.argument("chain", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "chain"))))));
    }

    /** Parses and runs a chain as {@code source}. Returns the last segment's result, or 0 if it failed. */
    public static int run(CommandSourceStack source, String input) {
        BashcraftConfig config = BashcraftConfig.get();
        Chain chain;
        try {
            chain = ChainParser.parse(input, ChainParser.Mode.SH, config.maxSegments);
        } catch (ChainSyntaxException e) {
            source.sendFailure(Component.literal("BashCraft: " + e.getMessage()));
            return 0;
        }
        Optional<DepthGuard.Scope> scope = DepthGuard.enter(config.maxDepth);
        if (scope.isEmpty()) {
            source.sendFailure(Component.literal("BashCraft: chains nested deeper than " + config.maxDepth));
            return 0;
        }
        try (DepthGuard.Scope ignored = scope.get()) {
            ExecResult result = new ChainExecutor(new MinecraftCommandRunner(source), config.swallowPipedOutput).execute(chain);
            return result.success() ? result.result() : 0;
        }
    }
}
```

- [ ] **Step 8: Wire up the entrypoint — replace `Bashcraft.onInitialize()`**

```java
    @Override
    public void onInitialize() {
        BashcraftConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bashcraft.json"));
        ShCommand.register();
        LOGGER.info("BashCraft loaded");
    }
```
Add imports `net.fabricmc.loader.api.FabricLoader`, `net.slk.bashcraft.command.ShCommand`, `net.slk.bashcraft.config.BashcraftConfig`.

- [ ] **Step 9: Run all tests**

Run: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest 2>&1 | grep -E "AssertionError|Mixin|BUILD"`
Expected: `BUILD SUCCESSFUL`, no `AssertionError`, no mixin apply errors. If only "locate pipes coordinates into tp" fails, check the log for `Could not find a structure` — a seed with no village in range; rerun once (new random seed) before debugging.

- [ ] **Step 10: Commit**

```bash
git add src
git commit -m "Add /sh command running chains from chat, command blocks and functions"
```

---

### Task 9: Dedicated server check and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Dedicated server check** (mixins must apply without a client)

```bash
mkdir -p run && echo "eula=true" > run/eula.txt
(sleep 90; echo stop) | JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 timeout 300 ./gradlew runServer 2>&1 | tee build/server.log | grep -E "Done \(|BashCraft loaded"
grep -E "WARN|ERROR" build/server.log | grep -i -E "bashcraft|mixin" || echo "no bashcraft warnings"
```
Expected: both `BashCraft loaded` and `Done (` printed; `no bashcraft warnings`. (If `Done (` appears after 90 s on a cold start, raise the sleep.)

- [ ] **Step 2: Replace `README.md`**

````markdown
# BashCraft

Small utility mod for introducing logical operators and bash features into the command system in Minecraft.
Fabric, Minecraft 26.3, server-side (vanilla clients can use it).

## Usage

```
/sh tp @s ~ ~10 ~ && summon minecraft:zombie
/sh locate structure minecraft:village | tp @s
/sh locate structure minecraft:village | tp @s {x} 120 {z}
```

| Operator | Meaning |
|---|---|
| `a && b` | run `b` if `a` succeeded |
| `a \|\| b` | run `b` if `a` failed |
| `a ; b` | always run `b` |
| `a \| b` | feed a value from `a`'s output into `b` |

Success means the command didn't error and returned more than 0 (the command-block rule).

**Pipes** extract coordinates (`/locate`), numbers (`/time query`, `/scoreboard players get`, numeric `/data get`)
or text, and append them to the next command — or put them where you write `{x}` `{y}` `{z}` `{value}`.
`{result}` is the previous command's numeric result. `/locate` reports Y as `~`, so `| tp @s` keeps your height;
use `tp @s {x} 120 {z}` to choose one.

**Quoting:** `"…"` and `'…'` are never split; `\&&`, `\|`, `\;` are literal; `\{x}` is a literal `{x}`.

Works in chat, the console, command blocks and functions (write `sh …` in `.mcfunction` files).
Chains never grant extra permissions.

## Config

`config/bashcraft.json`: `maxSegments` (32), `maxDepth` (8), `swallowPipedOutput` (true),
`transparentMode` (true; used by the upcoming prefix-free mode).

## Building

```
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build          # jar in build/libs
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test           # unit tests
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest   # in-game tests (~2 min)
```
````

- [ ] **Step 3: Manual check in a real client** (vanilla-client behaviour can't be automated here)

Run `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClient`, create a creative world, and in chat:
1. `/sh locate structure minecraft:village | tp @s {x} 150 {z}` → teleported; the locate message is **not** shown.
2. `/sh say a && setblock ~ -100 ~ stone` → the second error shows as `[2] …` in red.
3. `/sh say "oops` → `BashCraft: unclosed " at col 5`.
Record the result of each in the commit message body.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "Document /sh usage"
```
