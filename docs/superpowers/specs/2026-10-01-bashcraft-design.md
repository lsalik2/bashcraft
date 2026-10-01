# BashCraft — Design Spec

**Date:** 2026-10-01
**Status:** Draft, awaiting review

## 1. Goal

A small Fabric utility mod that brings shell-style command composition to Minecraft:

- Logical operators: `/tp @s ~ ~ ~ && /summon minecraft:zombie`
- Pipes: `/locate structure minecraft:village | /tp @s` teleports the player to the village.

**Audience:** players typing in chat, server admins/operators, and map/datapack authors — no single
focus, so it must work from **chat, server console, command blocks, and `.mcfunction` files**.

**Success criteria**

1. Both examples above work in chat on a 26.3 server.
2. The same chains work in a command block and inside a datapack function.
3. Chaining never grants a source more permission than it already has.
4. If the transparent (prefix-free) hooks break on a future Minecraft update, `/sh` still works.

## 2. Target platform

| Item | Value |
|---|---|
| Minecraft | 26.3 (latest stable as of 2026-10-01) |
| Fabric Loader | 0.19.5 |
| Fabric API / Loom | Latest builds for 26.3 — pinned in the implementation plan |
| Mappings | None — Minecraft 26.x ships unobfuscated; code uses Mojang names (Yarn ends at 1.21.11) |
| Java | Whatever 26.3 requires — confirmed in the plan. **No JDK is currently on PATH in the dev environment.** |
| Side | Server-side only. Vanilla clients can connect and use it. |

Single Minecraft version only; multi-version support is a future item.

## 3. Syntax

### Operators (v1)

| Operator | Meaning |
|---|---|
| `a && b` | Run `b` only if `a` succeeded |
| `a \|\| b` | Run `b` only if `a` failed |
| `a ; b` | Always run `b` |
| `a \| b` | Run `a`, extract a value from its output, feed it into `b` |

**Precedence** (bash-like, loosest to tightest): `;` → `&&`/`||` (equal, left-associative) → `|`.

So `/a | /b && /c ; /d` = `((/a | /b) && /c) ; /d`.

**Success** follows vanilla semantics: the command did not throw, and its result is `> 0`
(the same rule command blocks and `/execute if` use). A pipeline's status is its last segment's status.

### Lexing rules

- Text inside `"…"` or `'…'` is never split (so `/say "a && b"` is one command). Quotes are passed through
  to the command unchanged.
- `\` escapes the next character (`/say use \&& here`). The backslash is removed.
- Leading/trailing whitespace on each segment is trimmed. A leading `/` on a segment is optional.
- Parse errors — unclosed quote, empty segment (`/a && && /b`), dangling operator (`/a &&`) — are reported
  **before anything runs**; nothing executes.

### Two entry points

1. **`/sh <chain>`** (milestone 1). A normal Brigadier command with a greedy-string argument.
   Segment slashes optional: `/sh locate structure minecraft:village | tp @s`.
2. **Transparent mode** (milestone 2). The chain is typed directly, no prefix. To avoid breaking legal
   vanilla text such as `/say lunch; then work`, in transparent mode an operator only counts when the
   segment after it **starts with `/`**:
   - `/tp @s ~ ~ ~ && /summon minecraft:zombie` → chain
   - `/say lunch; then work` → plain vanilla `/say`
   - In `.mcfunction` files, where lines have no leading `/`, transparent mode applies only when every
     segment after an operator starts with `/` as well (e.g. `say hi && /say there`). Authors who prefer
     can always use `sh …` in functions.

   Transparent mode can be disabled in config.

## 4. Architecture

```
 chat / console / command block / .mcfunction
        │                           │
   /sh <chain>  (M1)            mixins (M2, can be disabled)
        └────────────┬──────────────┘
                     ▼
   ChainParser ──► ChainExecutor ──► MinecraftCommandRunner
   (pure Java)          │                 (calls the real dispatcher)
                        ▼
              PipeResolver = OutputAdapters + FeedbackExtractor
```

| Unit | Responsibility | Depends on |
|---|---|---|
| `ChainParser` | String → `Chain` AST (lists of pipelines joined by operators). Pure Java. | nothing |
| `ChainExecutor` | Walks the AST, applies `&&`/`\|\|`/`;` logic, drives pipes, enforces limits. | `CommandRunner`, `PipeResolver` |
| `CommandRunner` (interface) | `ExecResult run(String command, Source src)` → `{success, result, feedback: List<Component>, error}` | — |
| `MinecraftCommandRunner` | Real implementation: runs one segment with the original `CommandSourceStack` (same permissions), capturing the result value and feedback messages. | Minecraft |
| `PipeResolver` | `ExecResult` → `PipeValue`, then injects the value into the next segment's text. | `OutputAdapters`, `FeedbackExtractor` |
| `ShCommand` | Registers `/sh`. | Fabric command API |
| `mixin/*` | Transparent mode hooks (M2). | Minecraft internals |
| `BashcraftConfig` | Settings (Section 7). | — |

**Output visibility.** A segment whose output feeds a pipe has its success feedback swallowed (like stdout
in bash). Errors are always shown. Non-piped segments and the last segment show feedback normally.

**Permissions.** Every segment runs with the invoking `CommandSourceStack`, unchanged. `/sh` itself
requires no permission level beyond what each inner command requires.

**Known technical risk.** Since 1.20.3, command execution runs through a queued `ExecutionContext`. Commands
dispatched from inside another command may be queued rather than run immediately, but the executor needs
each result before deciding what to run next. **Milestone 0 is a spike on 26.3** to find the right way to
execute a segment synchronously (or restructure the executor around the queue) before building on it.

## 5. Pipe semantics

### `PipeValue` types

| Type | Fields | Appended as | Placeholders |
|---|---|---|---|
| `Coordinates` | x, y, z (any may be `~`) | `x y z` | `{x}` `{y}` `{z}` `{value}` |
| `Number` | int or decimal | `n` | `{value}` |
| `Entity` | name / UUID | UUID if known, else name | `{value}` |
| `Text` | plain string | the string | `{value}` |

`{result}` is always available and is the previous segment's integer result.

### Injection

- Only the exact tokens `{x}`, `{y}`, `{z}`, `{value}`, `{result}` are placeholders. Any other braces
  (NBT such as `{NoAI:1b}`, JSON text) are left untouched.
- If the next segment contains any placeholder, values are substituted and **nothing is appended**.
  `/locate structure minecraft:village | /tp @s {x} 100 {z}`
- Otherwise the value is appended after a space. `/locate … | /tp @s` → `/tp @s 312 ~ -1040`.
- A placeholder the value doesn't have (e.g. `{x}` from a `Number`) is an error for that segment.
- `\{x}` produces the literal text `{x}`.

### Resolution order

1. **Output adapters** — keyed on the feedback message's **translation key**, not on English text, so they
   work in any server language. v1 adapters:
   | Translation key(s) | Produces |
   |---|---|
   | `commands.locate.structure.success`, `commands.locate.biome.success`, `commands.locate.poi.success` | `Coordinates` |
   | `commands.data.entity.query`, `commands.data.block.query`, `commands.data.storage.query` | `Number` if numeric, else `Text` |
   | `commands.time.query` | `Number` |
   | `commands.scoreboard.players.get.success` | `Number` |
   (Exact keys verified against 26.3 during implementation.)
2. **Feedback extractor (fallback)** — walks the arguments of the last translatable feedback component,
   then its rendered string:
   1. A coordinate triple `[x, y, z]` or `x, y, z` → `Coordinates`
   2. Otherwise, the last number → `Number`
   3. Otherwise, the whole plain text → `Text`
3. No feedback at all → the pipe segment **fails** with "nothing to pipe from `<command>`", which counts as
   a failure for `&&`/`||`.

### Known limitation

`/locate` reports Y as `~`, so `/locate … | /tp @s` keeps the player's current height and may land them
underground or in the air. Users can write `/tp @s {x} 120 {z}`. Automatic surface-Y resolution is a
future item (it requires loading possibly-unexplored chunks).

## 6. Error handling

| Situation | Behaviour |
|---|---|
| Parse error | Red message with the column, e.g. `BashCraft: unexpected '&&' at col 14`. Nothing runs. |
| Segment throws / result is 0 | Shown as the normal vanilla error, prefixed with the segment number when the chain has >1 segment. Chain continues per operator rules. |
| Pipe can't extract / bad placeholder | Segment fails with an explanatory error; counts as failure. |
| Chain too long | Rejected at parse time (`maxSegments`, default 32). |
| Recursion (`/sh` inside a chain, or a function calling chains) | Allowed up to `maxDepth` (default 8); beyond that the segment fails. |
| Mixin fails to apply (M2) | Logged as a warning at startup; transparent mode is disabled; `/sh` unaffected. Mixins use `require = 0`. |

**Command blocks / functions.** The chain's returned result and success are those of the last segment that
actually ran, so comparators and `/execute store` behave predictably.

**Vanilla client caveat (M2).** Vanilla clients parse commands locally for highlighting and argument
signing and may draw a transparent-mode chain in red. Whether they still send it on 26.3 is verified in M2;
if they refuse, transparent mode is documented as console / command-block / function only, and chat users use
`/sh`.

## 7. Configuration

`config/bashcraft.json`:

| Key | Default | Purpose |
|---|---|---|
| `transparentMode` | `true` | Enable the M2 prefix-free hooks |
| `maxSegments` | `32` | Max segments per chain |
| `maxDepth` | `8` | Max nested chain depth |
| `swallowPipedOutput` | `true` | Hide success feedback of piped segments |

## 8. Testing

- **Unit tests (JUnit 5), no Minecraft runtime:**
  - `ChainParser`: precedence, quoting, escapes, optional slashes, transparent-mode `/` rule, every parse error.
  - `ChainExecutor` with a fake `CommandRunner`: short-circuiting for `&&`, `||`, `;`; pipeline status;
    limits; depth.
  - Placeholder substitution and appending.
- **Adapter/extractor tests:** built from constructed `Component` objects (Minecraft classes on the test
  classpath via Loom).
- **Fabric GameTests:** real chains on a test server — locate-pipe-tp, `&&`/`||` with a failing command,
  command-block result, and a datapack function using `sh`.
- **Manual checklist** per milestone: chat with a vanilla client, console, command block, function.

## 9. Milestones

- **M0 — Spike:** confirm synchronous single-segment execution with result + feedback capture on 26.3.
  Output is an answer; spike code is throwaway.
- **M1 — Engine + `/sh`:** parser, executor, pipe resolver, adapters, config, tests. Works everywhere via `/sh`.
- **M2 — Transparent mode:** mixins for chat/console/command blocks and function loading; vanilla client check.

## 10. Future plans (out of scope for v1)

- Grouping: `( /a || /b ) && /c`
- Exit-status variable `$?` and user variables (`x=…`, `$x`), possibly backed by command storage
- Command substitution `$( … )` inside arguments
- Redirection: `> score <holder> <objective>`, `> storage <id> <path>`
- `xargs`-style fan-out: run the next command once per entity / per line of output
- Filters: a `grep`-like segment to select which feedback line or value is piped
- Aliases / user-defined shell functions
- History shortcuts (`!!`, `!locate`)
- Background / delayed execution (`&`, `sleep <ticks>`) via the scheduler
- Automatic surface-Y for `locate` pipes
- Optional client-side companion for syntax highlighting and tab-completion inside chains
- Multi-version support
