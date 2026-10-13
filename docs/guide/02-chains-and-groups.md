# Chains and groups

Chains are commands joined by operators. This page covers the three joining operators, groups that bundle commands
together, and how to write an operator character as plain text.

## `&&`, `||` and `;`

| Operator | Runs the next command … |
|---|---|
| `a && b` | only if `a` succeeded |
| `a \|\| b` | only if `a` failed |
| `a ; b` | always |

They read left to right, so you can mix them freely.

**Give it a try!**

```text
/sh echo a && echo b ; echo c
```

## Fallbacks with `||`

`||` is perfect for "try this, otherwise that".

**Give it a try!**
<!-- try: errors expected -->
```text
/sh give @s minecraft:no_such_item || give @s minecraft:apple
```

The first `give` fails (you'll see its error), so you get an apple instead.

## Groups

Parentheses bundle commands into one unit, so an operator applies to the whole group. Here the fallback is inside
the group, and `&& echo c` runs if the group as a whole worked:

**Give it a try!**

```text
/sh (echo a || echo b) && echo c
/sh (setblock ~ ~3 ~ minecraft:glass || setblock ~ ~4 ~ minecraft:glass) && echo placed
```

The second line puts glass above your head — one block higher if the first spot can't take it — and then says so.
Groups can nest inside each other, up to 16 deep.

## A group's result

A group counts as whatever its last command that actually ran did. That's also what it passes on through a
[pipe](04-pipes-and-builtins.md). A value piped *into* a group goes to its first command.

## Quotes and escapes

Text inside `"…"` or `'…'` is never split, so operators inside quotes are just text. A backslash makes the next
character literal.

**Give it a try!**

```text
/sh echo "a && b"
/sh echo use \&& for a literal one
```

The first line prints its quotes too — quotes are passed to the command unchanged. The second prints
`use && for a literal one`.

## Rules and limits

- Precedence, loosest to tightest: `;` → `&&` and `||` (equal, left to right) → `|`. So `a | b && c ; d` means
  `((a | b) && c) ; d`, as in bash.
- `(` opens a group only where a command could start; elsewhere it's text, as in `echo (hi)`.
- Groups nest at most 16 deep: `groups nested deeper than 16`.
- Inside a group, a `)` that doesn't close a `(` in the same command must be written `\)`. Once a line uses a group,
  a stray `)` anywhere in it is an error, not text.
- `\&`, `\&&`, `\|`, `\;`, `\(` and `\)` are literal; `\{x}` is a literal `{x}`.
- A quote right after a letter or digit doesn't start a quoted span, so `don't` is plain text.
- In `/sh`, a lone `&` is an operator too (it runs part of a chain in the background — see [Time](06-time.md)); write
  `\&` for the character.
- A line that can't be read shows `BashCraft: <problem> at col N` with the spot marked, and nothing runs.

---

← [Previous: Getting started](01-getting-started.md) · [Guide index](README.md) · [Next: Variables](03-variables.md) →
