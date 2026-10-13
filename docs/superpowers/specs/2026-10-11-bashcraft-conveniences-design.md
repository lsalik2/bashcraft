# BashCraft — Conveniences Design Spec (Sub-project C4: aliases and history)

**Date:** 2026-10-11
**Status:** Draft, awaiting review
**Builds on:** M1/M2, A, B, C1 (incl. §5 trust rules and §9 hardening), C2, C5

## 1. Goal

```
/sh alias home = tp @s 0 80 0
/sh alias heal = 'effect give $1 minecraft:regeneration 5 ${2:-1} && say healed $1'
/sh home && heal @s 3
/sh alias global.spawn = tp @s 0 100 0        (operators)
/sh !!            /sh !tp            /sh history | grep locate
```

**Success criteria**

1. Players define personal aliases; operators define global ones; a player's alias never runs in an elevated chain.
2. Aliases are named chains with positional arguments.
3. `!!` / `!prefix` rerun a player's own recent lines.
4. With the client companion, alias calls are not shown as errors.

## 2. Alias builtins

Recognised (like other builtins) on a command's raw text, inside chains and `/sh` lines.

| Builtin | Effect |
|---|---|
| `alias NAME = BODY` | define/replace a personal alias |
| `alias global.NAME = BODY` | define/replace a global alias (game-master permission) |
| `unalias NAME` / `unalias global.NAME` | remove (personal / global; global needs game-master permission); a missing one fails with `no alias NAME` / `no alias global.NAME`, a missing name with `unalias needs NAME` (added after review) |
| `alias` | list: output is one item per alias, `NAME = BODY` (personal first, then `global.NAME = BODY`); pipeable |

- `BODY` is the rest of the command after `=`, with one surrounding pair of matching quotes removed. It is **never
  expanded** at definition and must parse as a chain (`NodeParser` SH mode) of at most `maxSegments` commands, else
  `[n] alias body: <parse error>`; a body stored before that limit fails when called with `alias NAME: <parse error>`
  (added after review).
- `NAME` = `[a-z_][a-z0-9_-]{0,31}`. Refused if it is a registered command (a root literal of the server's
  dispatcher) or a BashCraft builtin word (`alias unalias echo unset grep store each surface history sh`):
  `[n] alias name NAME is already a command`.
- Limits: 64 aliases per set, body ≤ 1024 characters.
- Storage: command storage `bashcraft:aliases` = `{global:{NAME:"BODY"}, players:{"<uuid>":{NAME:"BODY"}}}`, using the
  same live per-key access as variables (no copies, other data untouched, empty player sets pruned).

## 3. Calling an alias

- A command whose first word (before the first whitespace) names an alias is an alias call. Lookup: the player's
  personal aliases **only if the chain was typed by that player** (C1's "not elevated"), then global aliases.
  Builtins and real commands can't be alias names, so there is no ambiguity.
- **Arguments:** the rest of the command, expanded like any command text (C1), split into words on whitespace with
  quoted spans kept whole (one pair of surrounding quotes removed per word).
- **Body:** parsed as a chain and run as a nested chain with the caller's source; its output is shown normally (and
  feeds a pipe if the call does). Positional references in the body:
  `$1`…`$9`, `$@` (all words joined by spaces), `$#` (count), `${N:-default}` (word N or `default`). A missing `$N`
  without default is an error `[n] $N is not set`.
- **Append rule:** if the body contains no positional reference, the words are appended (space-separated) to the
  body's last command (the rightmost command in the tree).
- Positional values are inserted values: C1's escaping (commands that can run commands) and, in elevated chains,
  the plain-value rule apply to them.
- Nested alias calls count toward `maxDepth` and `maxTotalCommands`.

## 4. History

- Per player, in memory for the session (cleared on disconnect), last 50 lines.
- Recorded: every `/sh` line and transparent chain the player types themselves, as it ran (after `!` resolution).
  Not recorded: lines from functions, command blocks, the console, `execute as`.
- `/sh !!` reruns the last line; `/sh !PREFIX` reruns the most recent line starting with `PREFIX`. Only as a whole
  `/sh` line. No match: `BashCraft: no history matching PREFIX`. `/sh !` alone: `BashCraft: ! needs a prefix (!!
  reruns your last line)` (added after review).
- `history` builtin: one item per line, most recent last (pipeable).
- In an elevated chain, `!…` and `history` fail with `history is only for chains you type`.

## 5. Client companion

- New payload `bashcraft:aliases` (server → client): the alias names the player may call (personal + global), sent on
  join and whenever their set or the global set changes, only to clients that can receive it.
- The client colours an alias call's command word and arguments as a builtin (grey, `$` references purple) instead of
  parsing it with vanilla; no completion for alias arguments. `alias`, `unalias`, `history` are builtins.

## 6. Security

- A player's aliases and history only ever run in chains that player typed (same permissions as typing the commands).
- Only game-master sources define or remove global aliases.
- Alias names can't shadow commands or builtins.
- Alias arguments are values under C1's rules; alias bodies are parsed as chains (never spliced as text into the
  caller's line).

## 7. Testing

- **Unit:** alias definition (name rules, command/builtin names refused, body parse check, quote removal, limits);
  positional expansion (`$1`, `$@`, `$#`, defaults, missing); append rule; lookup scopes (typed vs elevated);
  recursion limit; history record/lookup/`!!`/`!prefix`/no match; elevated refusals.
- **Game (headless):** personal and global aliases from chat; a global alias from a function; non-op refused a
  global alias; a name that is a command refused; `/sh !!` rerun; client shows an alias call without red.

## 8. Out of scope

Alias argument completion, persistent history, `!n`, `^old^new`.
