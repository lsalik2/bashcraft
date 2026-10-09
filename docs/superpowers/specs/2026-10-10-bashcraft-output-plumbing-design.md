# BashCraft — Output Plumbing Design Spec (Sub-project C2)

**Date:** 2026-10-10
**Status:** Draft, awaiting review
**Builds on:** M1/M2, A (parser), B (client companion), C1 (expansion, incl. its §9 hardening and plain-value rule)

## 1. Goal

Three builtin pipe stages that work on the values commands produce:

```
/sh locate structure #minecraft:village | store storage bashcraft:places village
/sh scoreboard players get @s kills | store score @s best
/sh list | grep Steve && say Steve is online
/sh list | each msg {value} hi
/sh list | each data get entity {value} XpLevel | store storage bashcraft:stats levels
```

**Success criteria**

1. `store` writes scores and storage by running vanilla commands as the same source (same permissions).
2. `grep` filters items and its status drives `&&`/`||`.
3. `each` runs a command once per item; its output is the list of values those runs produced.
4. List-producing vanilla commands yield real item lists.
5. No new chain syntax; no conflict with vanilla's `>` in `execute if score`.

## 2. Items and lists

- `PipeValue` gains a **list** variant: an ordered list of item strings.
- **List-aware extraction** (adapters keyed on translation keys, confirmed against 26.3 during planning): `/list` →
  player names; `/tag … list` → tags; `/team list …` → teams or members; `/scoreboard players list` → holders;
  `/data get` whose value is an NBT list → its elements (strings unquoted, numeric suffixes dropped).
- **Items of any value:** a list's items; otherwise one item per output message that yields a value (existing
  extraction), messages without a value skipped.
- **Inserting a list** into an ordinary command (`{value}` or append): items joined by single spaces. A list has no
  `{x}`/`{y}`/`{z}`.

## 3. Builtins

Recognised (like C1's builtins) on a command's raw text, only inside chains and `/sh` lines, and only as a pipe stage
after `|` (otherwise: `[n] grep needs piped input`, same for `store` and `each`).

| Builtin | Effect | Status / result | Output |
|---|---|---|---|
| `grep [-v] [-x] TEXT` | keep items containing `TEXT` (case-insensitive); `-x` exact (case-insensitive) match; `-v` keep non-matching | success iff ≥ 1 item remains; result = item count | the remaining items (list) |
| `each COMMAND` | run `COMMAND` once per item, with the item as the pipe value (`{value}`, or appended) | success iff every run succeeded (and there was ≥ 1 item); result = number of successful runs | list of the values extracted from each run's output (runs without a value contribute nothing) |
| `store score HOLDER OBJECTIVE` | runs `scoreboard players set HOLDER OBJECTIVE N`, N = the value as an integer (rounded down) | that command's status | the stored value |
| `store storage ID PATH` | runs `data modify storage ID PATH set value SNBT` | that command's status | the stored value |

- `TEXT`, `COMMAND`, `HOLDER`, `OBJECTIVE`, `ID`, `PATH` are the author's text and get C1 expansion first.
- **`store score`** needs a number (a numeric value, or a one-item list whose item is a number): else
  `[n] store score needs a number`.
- **SNBT for `store storage`:** number → integer literal if integral and in int range, else double (`1.5d`); text →
  double-quoted string with `\` and `"` escaped; list → `[…]` of numbers or strings per item (numbers only if every
  item is a number); coordinates → their text as a string (`"312 ~ -1040"`).
- **`grep`** with no `TEXT` → `[n] grep needs a pattern`. No regular expressions.
- **`each`**: each run is one command (use `sh …` for more); runs count toward `maxTotalCommands`; each run's
  success output is hidden when `each` feeds a further stage (as with any piped command).
- Builtins compose: `list | grep -v Steve | each tp {value} 0 80 0`.

## 4. Security

- `store` and `each` only run vanilla commands as the invoking source: permissions are checked as usual.
- Items inserted into `each`'s command are pipe values: C1's plain-value rule applies in elevated chains and C1's
  escaping for commands that can run commands applies.
- `store storage` builds its SNBT quoting itself; stored text can't break out of its string.
- `grep` uses plain substring matching, so a pattern can't cause catastrophic regex backtracking.

## 5. Client companion

`store`, `grep` and `each` are coloured as builtins. The command after `each ` is highlighted and completed like any
other command (its window starts after `each `).

## 6. Testing

- **Unit:** list adapters with constructed feedback; items of non-list values; `grep` with `-v`/`-x`, status and
  output; `each` runs per item, status, collected output, budget; `store` command composition (score rounding, SNBT
  quoting of `"`, `\`, unicode, lists); error cases.
- **Game (headless):** `scoreboard players get … | store score …`; `… | store storage …` checked with
  `execute if data`; `data get entity @p Tags | grep boss && setblock …`; `list | each …` placing a block per
  player; `grep` failing drives `||`.

## 7. Out of scope

Regex patterns; multi-command `each` bodies (use `sh`); `head`, `tail`, `sort`, `count` — later candidates.
