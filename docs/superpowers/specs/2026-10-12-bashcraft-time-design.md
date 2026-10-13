# BashCraft — Time Design Spec (Sub-project C3: sleep and background `&`)

**Date:** 2026-10-12
**Status:** Draft, awaiting review
**Builds on:** M1/M2, A, B, C1 (incl. §5 trust rules), C2, C5, C4

## 1. Goal

```
/sh echo 3 ; sleep 20 ; echo 2 ; sleep 20 ; echo 1 ; sleep 20 ; title @s title "go"
/sh (sleep 1200 ; title @s title "time's up") & echo timer started
/sh alias tick = 'effect give @s minecraft:speed 2 ; sleep 2s ; tick'
/sh jobs            /sh kill %1
```

**Success criteria**

1. A chain can wait: `sleep` pauses it without blocking the server, and the rest runs on a later tick.
2. `&` lets part of a chain wait on its own while the rest carries on.
3. Players list and cancel their own waiting chains; operators manage everyone's.
4. A waiting chain never gains anything: a typed chain resumes with the player's current permission, others with
   the permission they started with, and jobs are bounded in length, number and work per second.

## 2. `sleep`

- `sleep TIME`, a builtin recognised on a command's raw text like the others. `TIME` is expanded (C1) and read with
  `/schedule`'s syntax: a number, optionally followed by `t` (ticks, the default), `s` (×20) or `d` (×24000);
  fractions allowed, the product rounded to the nearest tick (`0.5s` = 10, `1.26t` = 1).
- Bounds: at least 1 tick, at most `maxSleepTicks` (config, default 72000 = one hour).
- Outcome: success, result = the number of ticks. The rest of the chain runs after that many ticks; `&&` after it
  runs then.
- **When a chain pauses, `/sh` returns at once** with the result of the last command that ran (the `sleep`), so a
  command block or `execute store` sees that. A function line that pauses lets the function go on to its next line.
  When the rest of the chain later finishes, its result goes nowhere; its output and errors are shown as usual.
- **A sleep that can't pause stops the chain**: `[n] <reason>`, and nothing after it runs (not even `;` or `||`
  items, here or in enclosing lists and alias bodies). Reasons:
  - `sleep needs a time like 20, 20t, 1s or 1d`, `sleep needs at least 1 tick`, `sleep is limited to N ticks`;
  - `sleep can't run inside a pipe` / `inside $(…)` / `inside each` — these need their answer at once;
  - `too many waiting chains (max N)` — the job limits of §6.
- A nested `sh` command is its own chain: if it pauses, the rest of it becomes its own job and the `sh` command
  returns at once, wherever it runs (even in a pipe).
- `sleep` and `jobs` are added to the builtin words alias names can't use.

## 3. `&`

- `X & Y` runs `X`; if `X` pauses, the rest of `X` becomes its own job and `Y` runs at once. If `X` finishes
  without pausing, `Y` runs next as after `;`. The status after `&` is success (as in bash).
- Precedence is bash's: `&` closes the `&&`/`||` list before it, like `;`. `a && b & c` backgrounds `a && b`;
  `a ; b & c` backgrounds only `b`; `a | b & c` backgrounds the pipeline. A trailing `&` is allowed (`… & `, and
  before `)`).
- **`/sh`:** every unquoted lone `&` is an operator now. `\&` or quotes keep it as text (`say Tom \& Jerry`).
  `&&` is unchanged, and `\&&` still means a literal `&&`. `& x`, `a & & b`, `a & ; b` are syntax errors
  (`unexpected '&'`, `unexpected ';'`).
- **Transparent mode:** `&` counts only when the next non-space character is `/` (directly or after `(`), like the
  other operators; `/say Tom & Jerry` and a trailing `&` stay text.

## 4. `jobs` and `kill %N`

- `jobs`: one item per waiting job, in number order, pipeable:
  `%N  <ticks left>t  <text>` — e.g. `%1  40t  echo hi ; sleep 40 ; echo bye`. Operators (game-master permission)
  see every job, with its owner: `%2  1180t  Steve: sleep 1200 ; title @s title "time's up"`.
- A job's text is the chain it belongs to, written back as `/sh` syntax: the whole chain for a pause outside `&`,
  the backgrounded part for `X &`. An alias timer shows the line that called it (`tick`).
- `kill %N…` cancels jobs. It is the builtin only when its first argument starts with `%`; any other `kill` is
  vanilla's. Players may cancel their own jobs; operators any. A job that doesn't exist or isn't the caller's:
  `[n] no job %N`. Outcome: success when every named job was cancelled; result = how many were.
- A job may cancel itself: the current wake runs until the job would next pause, and it isn't queued again.
- Job numbers: the smallest free number (1, 2, 3 …), shared by the whole server.

## 5. How pausing works

**Parser.** The tokenizer reads a lone `&` as a new `BACKGROUND` token (`&&` is matched first). The parser wraps the
`&&`/`||` list before each `&` into a new node `Node.Background(body)`; `;` joins it to what follows.
`ChainFormatter` writes it back as `body & …` (it already escapes `&` inside command text).

**Executor (pure).**

- Running a node can return a *paused* outcome carrying a **continuation**: a stack of frames, innermost first.
  A frame is a list (`Node.Sequence`), the index of its next item, the outcome so far (for `&&`/`||`), `$?`, the
  alias arguments in force, the `[n]` numbering, and how many alias levels deep it is.
- When an item of a list pauses, the list pushes its own frame (if it has items left) and passes the pause up. A
  `Group` passes it through. A `Background` whose body pauses hands that continuation to the job table as a new job
  and carries on with success.
- A refused sleep returns a *stopped* outcome, which passes up through everything without running anything else.
- Pipes, `$( … )` and `each` run their insides as not pausable.
- `ShellContext` gains `jobs`, an interface the executor uses to queue a continuation with a delay and a text; the
  queue may refuse (limits).
- `ChainExecutor.resume(continuation)` runs the frames innermost first; each frame's outcome decides the `&&`/`||`
  of the frame outside it. A pause during a resume appends the outer frames still waiting, and the job keeps its
  number.
- A player's waiting chains share one `maxTotalCommands` budget per second (20 ticks of the job clock); a server
  job gets a fresh budget each wake (changed after review: per wake let 8 one-tick timers multiply what a player can
  run). The alias levels a resumed job is still inside — those with
  frames, i.e. commands still to run — count toward `maxDepth` as if it had never paused, so
  `alias t = echo hi ; sleep 20 ; t ; echo after` stops at `aliases nested deeper than N` instead of growing
  forever. A list with nothing left adds no frame, so a timer that calls itself last stays one level deep and keeps
  one frame, however long it runs.

**Job table.** `JobTable` (pure): jobs with number, owner, text, wake tick and continuation; numbering, limits,
visibility, cancelling. `ServerJobs` (Minecraft side, one per server) holds the table and the sources, and counts
ticks on `END_SERVER_TICK` only while `tickRateManager().runsNormally()` — `/tick freeze` pauses sleeps,
`/tick step` advances them, `/tick rate` changes their speed, as for `/schedule`. Due jobs wake in order of wake
tick, then number.

**Resuming.**

- Source: a chain a player typed (`Sources.typist`, through any nesting) resumes as
  `player.createCommandSourceStack()` — the player's current position, dimension and permission. Any other chain
  resumes as the source it started with, unwrapped from pipe tees so output reaches the real target (command block,
  console). A player-typed chain started through `execute at … run sh` therefore resumes at the player's own
  position.
- A job is dropped silently when its player has disconnected or its source entity is gone; all jobs are dropped when
  the server stops (they are not saved).
- Each wake rebuilds the variables, aliases and history context from that source (C1 trust rules, C4 lookup).

## 6. Limits and ownership

| Config | Default | Limits |
|---|---|---|
| `maxSleepTicks` | 72000 | one sleep |
| `maxJobsPerPlayer` | 8 | waiting chains a player typed |
| `maxJobs` | 64 | all waiting chains |
| `maxServerJobs` | 32 | the server's jobs together, so players keep the rest (added after review) |
| `maxTotalCommands` (existing) | 256 | commands per second for a player's waiting chains together; per wake for the server's |

A job belongs to the player who typed its chain; any other job (command block, function, console, `execute as`)
belongs to the server and is shown with its source's name (`@`, `Server`). A job counts toward its owner's limit
from when it is queued until it finishes or is cancelled.

## 7. Security

- A typed chain gets the player's current permission each time it wakes: a waiting chain can't keep operator
  powers after a de-op. Other sources keep their own permission, as `/schedule` and functions do.
  A chain a player started as another entity (`execute as … run sh`, a function they ran) never wakes with more
  permission than that player has then (added after review).
- Each wake re-applies C1's rules (plain-value rule, escaping) and the msg/me/say refusal from that source.
- Players see and cancel only their own jobs; another player's job answers like a missing one.
- A player's jobs end when they leave, as does any job whose saved source is that player.
- Jobs are bounded: length (`maxSleepTicks`), number (per player and total), work per second (`maxTotalCommands`),
  and nesting (`maxDepth`, across pauses).

## 8. Minecraft side and client

- `Bashcraft` registers `END_SERVER_TICK` (wake jobs), `DISCONNECT` (cancel that player's jobs) and
  `SERVER_STOPPED` (clear the table).
- `Builtins` gains `SLEEP`, `JOBS` and `KILL` (`KILL` only for `kill %…`). The client colours chains through
  `Builtins.classify`, so these get the builtin colour; `&` gets the operator colour.
- README: a "Time" section. Examples use `echo`/`title`, since `say` is refused in chains a player types.

## 9. Testing

- **Unit:** tokenizing and parsing `&` (precedence, trailing, `\&`, transparent rule, formatter round trip); time
  parsing and bounds; executor with a fake job sink — pause/resume through lists, groups, `&&`, `||`, keeping `$?`
  and alias arguments; an alias timer keeps one frame; depth counted across a pause; fresh budget per wake;
  refused sleeps stop the chain (inside pipe, `$( … )`, `each`, limits); `&` detaches a job; a job that pauses
  again keeps its number; `JobTable` numbering, limits, visibility, `kill`, cancelling by owner.
- **Game (headless, Xvfb, bashcraft dev server only):** `echo a ; sleep 20 ; echo b` shows `b` about 20 ticks
  later; `&` ordering; `jobs` and `kill %1`; a non-op player can't cancel another's job; a function line that sleeps
  goes on to its next line; an operator's waiting chain loses its powers after a de-op.

## 10. Out of scope

`wait`, `fg`/`bg`, keeping jobs across restarts, pausing inside pipes, `$( … )` or `each`, standalone `/sleep` or
`/jobs` commands (use `/sh jobs`).
