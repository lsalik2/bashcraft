# BashCraft — Parser and Grouping Design Spec (Sub-project A)

**Date:** 2026-10-05
**Status:** Draft, awaiting review
**Builds on:** `docs/superpowers/specs/2026-10-01-bashcraft-design.md` (M1 + M2, shipped)

## 1. Goal

Replace the flat chain parser with a real syntax tree and add grouping:

```
/sh (setblock 0 64 0 stone || setblock 1 64 0 stone) && say placed
/(/locate structure #minecraft:village || /locate structure #minecraft:pillager_outpost) | /tp @s {x} 150 {z}
```

This is the first of three sub-projects, built in order: **A** parser + grouping (this spec), **B** client companion
(chain-aware highlighting and completion, reusing this parser), **C** shell features (variables, `$(…)`,
redirection, …). A is designed so B and C extend it without changing its interfaces.

**Success criteria**

1. Every chain that works today parses and runs exactly as before (all existing unit and game tests pass unchanged
   in meaning).
2. Groups work with `/sh` and without it (chat, console, command blocks, function files).
3. `(A || B) | C` pipes the output of whichever of A or B ran last.
4. Parse errors give a column and a vanilla-style context line; nothing runs on a parse error.

## 2. Grammar

Loosest to tightest, bash-like:

```
list     := and_or ( ';' and_or )*
and_or   := pipeline ( ('&&' | '||') pipeline )*
pipeline := unit ( '|' unit )*
unit     := command | '(' list ')'
```

`&&` and `||` have equal precedence and associate left. Groups nest.

## 3. Lexing rules

The tokenizer turns the input into **text runs** and **operators** (`&&`, `||`, `|`, `;`, `(`, `)`), each with its
1-based column.

**Unchanged from M1/M2**
- `"…"` and `'…'` spans are never split and are kept verbatim; a quote only opens when it does not follow a letter
  or digit (`don't` is text). Inside quotes, `\` escapes the next character for the purpose of finding the closing
  quote and both characters are kept.
- Outside quotes, `\` before an escapable character yields that character; any other `\` is kept.
- A single `&` is text. Command text keeps its original spacing; segments are trimmed and one leading `/` removed.
- Placeholders (`{x}` `{y}` `{z}` `{value}` `{result}`, `\{x}`) are untouched by the parser.

**New**
- Escapable characters become `& | ; \ ( )`. **Behaviour change:** `\(` and `\)` now drop the backslash (before, it
  was kept).
- **Group open.** `(` opens a group only at a *command position*: the start of the input, or after an operator or
  another `(`, ignoring whitespace and an optional `/` before it. Anywhere else, `(` is text.
- **Text parentheses.** Within a command's text, `(` and `)` are counted in pairs. A `)` closes the innermost open
  group only when the command's text has no unmatched `(`. Outside any group an unmatched `)` is text (`say :)`).
  Inside a group, an unmatched `)` in text must be written `\)`.
- **Transparent mode** (no `/sh`): an operator counts only when the next non-space character is `/` or `(`. A line
  whose first non-space characters are `(` or `/(` is parsed as a chain (no vanilla command starts with `(`).
  Typed in chat this is `/(/a || /b) && /c`, which the client sends as the command `(/a || /b) && /c`.

**Structural errors** (reported with the column; nothing runs)
- empty group `()`;
- unclosed `(` (column of the `(`);
- text directly after a closing `)` (only an operator, `)` or end of input may follow);
- group nesting deeper than **16** (fixed cap, protects the server stack);
- the existing errors: empty input, leading/doubled/dangling operator, unclosed quote, more than `maxSegments`
  commands (counting every command in the tree).

**Error format.** Like vanilla: `BashCraft: <message> at col N` in red, then a grey line with up to 10 characters of
input before the error position followed by `<--[HERE]`.

## 4. Syntax tree

`net.slk.bashcraft.chain.Node`, sealed, replacing the flat `Chain`:

| Record | Meaning |
|---|---|
| `Command(String text, int column)` | one command; slash stripped, escapes resolved |
| `Pipeline(List<Node> stages)` | ≥ 2 stages joined by `\|`; each stage a `Command` or `Group` |
| `Sequence(List<Node> items, List<Operator> joins)` | ≥ 2 items joined by `&&` `\|\|` `;`; each item a `Pipeline`, `Command` or `Group`; `joins.size() == items.size() - 1` |
| `Group(Node body)` | `( … )` |

A single command parses to a bare `Command`. The parser returns the smallest node (no single-element
`Pipeline`/`Sequence`). `Node.commandCount()` counts `Command` leaves.

## 5. Execution semantics

`ChainExecutor.execute(Node)` evaluates recursively with an optional piped-in value and returns an `ExecResult`.

- **Command:** inject the piped value (placeholders or append, unchanged), then run.
- **Sequence:** `&&` runs the next item after a success, `||` after a failure, `;` always; a skipped item leaves
  the status unchanged. Result: the last item that ran.
- **Pipeline:** each stage's output feeds the next stage; an *error* stops the pipeline (a result ≤ 0 does not);
  "nothing to pipe" and missing-placeholder errors behave as today.
- **Group:** runs its body; its result, including captured output, is that of the last command that ran in it.
- **Piping into a group:** the value is given to the first command that runs inside the group; later commands in
  the group do not receive it.
- **Hidden output:** a command's success output is hidden whenever its result feeds a pipe — including the last
  command run in a group that is a non-final pipeline stage. (With `swallowPipedOutput: false`, nothing is hidden.)
- **`[n]` numbering:** commands are numbered left to right through the whole tree, 1-based; numbering is shown only
  when the tree has more than one command.
- **Limits:** `maxSegments` (parse), `maxTotalCommands` and `maxDepth` (runtime) are unchanged.

## 6. Callers

| Caller | Change |
|---|---|
| `ShCommand` | parses with `Mode.SH` to `Node`; `execute(source, Node)` |
| `TransparentChains.detect` | returns `Optional<Node>`; a line is a chain when it has > 1 command **or** any group; `sh` lines and parse failures still go to vanilla |
| `ChainFormatter.toSh(Node)` | writes groups as `( … )`; escapes `& \| ; \ ( )` in text where needed, so `parse(toSh(n), SH)` equals `n` |
| `FunctionLines` | unchanged behaviour, uses the new types; macro lines still never chained |
| Transparent mixin | unchanged apart from types |

## 7. Out of scope

Variables, `$(…)`, redirection, xargs, filters, aliases, history, delays, surface-Y (sub-project C); client
highlighting and completion (sub-project B). The tokenizer treats `$`, `>`, `<` as ordinary text so vanilla syntax
using them (macros, `execute if score a x > b y`) is unaffected.

## 8. Testing

- **Migration:** all 79 existing unit tests keep passing. Tests asserting flat chains use a test helper that converts
  a `Node` back to the "list of pipelines" shape.
- **Tokenizer:** columns, `\(`/`\)`, quoted parentheses, group-open positions (start, after operator, after `/`),
  text parentheses.
- **Parser:** precedence with groups, nesting, `(/say (b) || /c)`, every structural error with its column,
  transparent groups (`(…` / `/(…` at the start, `&& (`).
- **Executor (fake runner):** group status, group output piped (locate fallback), piping into a group reaches only
  its first command, `[n]` numbering across groups, hidden output of a piped group's last command, skipped groups.
- **Formatter:** round-trip equality including groups and escaped parentheses.
- **Error format:** context line and `<--[HERE]` with vanilla's 10-character window.
- **Game tests:** `/sh (setblock … || setblock …) && setblock …`; locate fallback in the seeded world
  `(/locate structure #minecraft:nonexistent_tag || /locate structure #minecraft:village) | /tp @s {x} 200 {z}`;
  a grouped transparent chain from the console and in a function file; a grouped chain typed by the player
  (`/trigger` pattern).
