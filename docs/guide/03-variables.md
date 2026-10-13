# Variables

Variables remember values for later — in the same line, in your next line, even after a restart. This page covers
setting and reading them, your own set versus the global one, `$?`, and `$( … )`, which puts one command's result
inside another.

## Set and read

`name=value` sets a variable; `$name` (or `${name}`) reads it.

**Give it a try!**

```text
/sh x=5 ; echo x is $x
/sh echo it is still $x
```

Variables last: the second line still knows `x`.

## Your set and the global set

There are two sets of variables. When you type a chain yourself, a plain `x` means *your own* variable (falling back
to the global one if you don't have it). `my.x` always means yours and `global.x` always means the shared one.
Writing the global set needs operator permission.

**Give it a try!**

```text
/sh global.motd=welcome ; echo $global.motd
```

In functions, command blocks, the console and `execute as … run sh`, a plain `x` means the global variable; a
player's own set is reached only as `my.x` there.

## Did it work? `$?`

`$?` is `0` if the previous command succeeded and `1` if it failed — handy after something that fails quietly, like
a `grep` that finds nothing.

**Give it a try!**

```text
/sh echo hi | grep nope ; echo the last status was $?
```

## A command's result inside another: `$( … )`

`$( … )` runs a chain and puts its result right there in the line, the same value a [pipe](04-pipes-and-builtins.md)
would pass on.

**Give it a try!**

```text
/sh echo this world is $(time query gametime) ticks old
```

## Quotes

Variables are filled in inside `"…"` but not inside `'…'`.

**Give it a try!**

```text
/sh echo '$x stays as text'
```

## Unset

`unset x` removes a variable. Reading a variable that isn't set doesn't quietly leave a blank — the command fails, so
you notice.

**Give it a try!**
<!-- try: errors expected -->
```text
/sh unset x ; echo $x
```

You'll see `$x is not set`.

## Rules and limits

- Up to 256 variables per set; names up to 64 characters (letters, digits and `_`, not starting with a digit);
  values up to 1024 characters; at most 256 `$` references and 32767 characters in one expanded command.
- An unset variable makes its command fail with `$name is not set`.
- `\$` is a literal `$`.
- Variables live in command storage `bashcraft:vars` (`global` and `players.<uuid>`), so datapacks can read and write
  them with `/data`. Only string values are read; other data a datapack keeps there is left alone.
- Anyone can read the global set with `echo $global.x` — don't keep secrets there.
- In functions, command blocks, the console and `execute as … run sh`, values a player controls must be one plain
  word or only numbers and coordinates, or the command is refused. See [For server admins](10-server-admins.md).
- In function macro lines (`$…`), vanilla owns `$(name)`, so BashCraft's `$( … )` can't be used there.
- Inside chains, `echo`, `unset`, `grep`, `store` and `each` are always BashCraft's, even if another mod has commands
  with those names.

---

← [Previous: Chains and groups](02-chains-and-groups.md) · [Guide index](README.md) · [Next: Pipes and builtins](04-pipes-and-builtins.md) →
