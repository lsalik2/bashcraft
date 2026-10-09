# BashCraft — Expansion Design Spec (Sub-project C1)

**Date:** 2026-10-09
**Status:** Draft, awaiting review
**Builds on:** M1/M2 (`2026-10-01`), A (`2026-10-05`, parser and grouping), B (`2026-10-08`, client companion)
**Part of C:** C1 expansion → C2 output plumbing → C5 surface-Y → C4 conveniences → C3 time.

## 1. Goal

Shell-style expansion inside chains:

```
/sh x=11 && setblock $x 64 0 stone
/sh home="0 80 0" ; tp @s $home
/sh tp @s $(locate structure #minecraft:village) 
/sh say done ; say status was $?
/sh echo $global.round | scoreboard players set round game
```

**Success criteria**

1. Variables set in a chain are visible to later commands in the same chain and persist across lines.
2. Each player has a private set; console, command blocks and functions share a global set; `$x` checks the
   player's set first.
3. `$(…)` inserts the value its inner chain produces (same extraction as pipes).
4. Plain vanilla lines are never changed; inside chains, unset variables fail loudly instead of running with blanks.
5. Datapacks can read and write the shared set through `/data … storage bashcraft:vars global`.

## 2. Where expansion applies

Only to commands inside a chain or a `/sh` line (the same lines the executor runs). A plain vanilla command is
never expanded, even if it contains `$`. Expansion of a command happens immediately before that command runs, so
earlier assignments in the same chain are visible.

## 3. Syntax

| Form | Meaning |
|---|---|
| `NAME=value` | assign in the source's own set (see §5) |
| `my.NAME=value`, `global.NAME=value` | assign in that set explicitly |
| `$NAME`, `${NAME}` | value: own set, then global |
| `$my.NAME`, `$global.NAME`, `${my.NAME}`, `${global.NAME}` | value from that set only |
| `$?` | status of the previous command in this chain run: `0` success, `1` failure (bash style); `0` before any command |
| `$( … )` | run the inner chain, insert the value extracted from its output |
| `\$` | literal `$` |

- `NAME` = `[A-Za-z_][A-Za-z0-9_]*`. Anything else after `$` (`$5`, `$ `, a lone `$`, `$-`) is literal text.
- **Assignment** is recognised only when a command's whole text is `NAME=value` (optionally scoped). The value is
  expanded first; if it is wrapped in one pair of matching `"…"` or `'…'`, those quotes are removed. An empty value
  is allowed.
- **Quotes:** inside `"…"` expansion happens (so JSON text such as `{"text":"$x"}` works); inside `'…'` it does not.
  The quote characters themselves stay (they are Minecraft syntax), except for the assignment rule above.
- **Unset variable:** the command fails with `[n] $x is not set` and does not run.
- **`$(…)`:** the inner text is parsed as a chain (`SH` mode, any syntax including nesting and groups) and run as
  the same source. Its success output is hidden (like a piped command); its value is extracted exactly like a pipe
  value (`PipeValue.asArgument()`). If the inner chain errors or yields nothing to extract, the outer command fails
  with `[n] $(…) produced no value`. Inner commands count toward `maxTotalCommands` and the nesting depth. `$?`
  after a `$(…)`-containing command reflects that command, not the inner chain.

## 4. Builtins

Recognised only inside chains and `/sh` lines, checked before vanilla:

| Builtin | Effect | Result |
|---|---|---|
| `NAME=value` (and scoped forms) | set variable | success, result 1 |
| `unset NAME` / `unset my.NAME` / `unset global.NAME` | remove variable (own set if unscoped) | success, result 1 (also when it was not set) |
| `echo TEXT` | sends the expanded TEXT to the source as normal success feedback (so it can be piped) | success, result 1 |

A mod command named `echo` or `unset` is shadowed inside chains (documented in the README); outside chains it
works as before.

## 5. Storage and scope

- Command storage `bashcraft:vars`: `{global:{NAME:"value",…}, players:{"<uuid>":{NAME:"value",…}}}`; values are
  strings.
- **`my.` set:** the player's set when the command source's entity is a `ServerPlayer` (so `execute as @p run sh …`
  can use that player's set); otherwise the global set (console, command blocks, functions without a player entity).
- **Unscoped names** use the player's set (read: player's, then global; write: player's) **only when that player typed
  the chain**. In every other chain — functions, command blocks, the console, `execute as` — unscoped names use the
  global set, so a player's own variable can never shadow one an elevated chain relies on; such chains reach a
  player's set only through explicit `my.` names.
- **Writing the global set** (`global.x=…`, or an unscoped write that lands in it) needs operator (game master)
  permission: elevated chains build commands from it, so a non-op player must not control it. Error:
  `global variables need operator permission`.
- *(Both rules added after a security review on 2026-10-09; the first draft let any player write the global set and
  let a player's own set shadow it inside `execute as` chains.)*
- **Limits:** at most 256 variables per set and 1024 characters per value; exceeding either fails the command
  (`[n] too many variables` / `[n] value longer than 1024 characters`).

## 6. Architecture

| Unit | Responsibility | Package |
|---|---|---|
| `Tokenizer` (modified) | `$(` opens an opaque span to its matching `)` (quotes and nested parentheses respected); never split, never a group | `chain` |
| `Expander` | expands one command's text: `$NAME`, `${…}`, scopes, `$?`, `$(…)` (via callback), quote rules, `\$` | `exec` |
| `Variables` (interface) | `get(scope, name)`, `set(scope, name, value)`, `unset(scope, name)` with `Scope { OWN, GLOBAL, ANY }` | `exec` |
| `Builtins` | recognises and runs assignment, `unset`, `echo` | `exec` |
| `ChainExecutor` (modified) | per command: expand → inject pipe placeholders → builtin or vanilla; tracks `$?` | `exec` |
| `StorageVariables` | `Variables` on command storage for a given source | `mc` |
| `CommandRunner` (modified) | gains `void echo(String text, RunOptions options)` so `echo` output is captured and shown like vanilla success output | `exec` / `mc` |
| Client (`ChainStyles`, masking) | `$NAME`, `${…}`, `$?`, `$(…)` spans coloured light purple and masked with zeros for client-side parsing | `client`, `pipe` |

`ChainFormatter`, `FunctionLines` and transparent detection keep working unchanged: `$(…)` is ordinary command text
to them (the tokenizer keeps it whole).

## 7. Testing

- **Unit:** expansion rules (names, braces, scopes, `$?`, `$5` literal, lone `$`, `\$`, double vs single quotes,
  unset error); tokenizer keeps `$( a && b )` whole and nested `$( (a) | b )`; formatter round-trip with `$(…)`;
  builtins with a fake `Variables` (assign, quote stripping, scoped, unset, limits); executor: assignment visible to
  the next command, `$?` after success/failure, `$(…)` capture, `$(…)` failure, budget counting inner commands; echo
  output piped; client mask of `$` spans.
- **Game (headless):** `sh x=11 && setblock $x 120 0 stone`; a variable persisting to the next line; player and global
  sets separate (`execute as @p run sh my.x=…` vs console `sh x=…`); `sh setblock $(scoreboard players get n bc) …`;
  a datapack function reading `/data get storage bashcraft:vars global.x`; `echo` piped into a placeholder.

## 8. Out of scope

Arithmetic `$((…))`, arrays, listing variables (`vars`), a `command` builtin to reach shadowed mod commands — later
candidates.

## 9. Hardening (after security review, 2026-10-09)

Values inserted by expansion or pipes into a command that can run other commands are backslash-escaped
(`\ & | ; ( ) $`), and a value containing `"` or `'` is refused there (`a value with quotes can't go into a command
that runs other commands`). Such a command is one whose *expanded* text contains the word `sh`, `function` or `run`, or whose
name is itself a `$` reference. If one of those words comes from an inserted value (`execute as @a $r sh …` with
`r=run`, or `$c …` with `c=sh`), the command is refused: `a value can't decide which commands run`. Outside double quotes, the expander treats `\'` and `\"` like the tokenizer does (the
quote opens).

**Plain-value rule (2026-10-09, after the injection review).** Escaping can't protect every parser a value later
reaches (JSON text components, SNBT, selectors, macros, other mods' commands). So in an *elevated* chain — one not
typed by the player it reads from: functions, command blocks, the console, `execute as … run sh` — every
player-controlled value must be either one word matching `[A-Za-z0-9_.:~^+-]*` (no spaces, so it fills exactly one
argument and can't add keywords such as `store`, `if` or `as`), or only numbers and relative coordinates separated by
single spaces (`312 ~ -1040`, `^ ^1.5 ^`); otherwise the command fails with `a value from a player can only be one
plain word or a list of numbers here`. Player-controlled means: a variable
read from a player's own set, and any pipe value, placeholder or `$( … )` output. Global-set values (written only by
operators) and everything in a chain the player typed themselves are not restricted. The check applies to builtins
too, so player text can't be copied into the global set. The escaping above remains as a second layer.

