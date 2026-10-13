# New to bash?

> **Already know bash?** Skip to [Getting started](01-getting-started.md). This page is a five-minute primer for
> everyone else.

Bash is the command language of Linux and macOS terminals. Its best ideas are tiny: run one command *if* another
worked, pass a result along, remember a value for later. BashCraft brings exactly those ideas to Minecraft
commands. Everything below goes in chat, after `/sh`.

## Commands succeed or fail

Every Minecraft command either works or doesn't — a `tp` to a player who isn't online fails, a `give` of an item
that doesn't exist fails. BashCraft lets you decide what runs next based on that. The simplest command to play with
is `echo`, which just prints its text.

**Give it a try!**

```text
/sh echo hello
```

You'll see `hello` in chat.

## Do this, then that: `&&`

`&&` runs the next command only if the one before it worked.

**Give it a try!**

```text
/sh echo first && echo second
```

Both lines appear, because `echo` always works.

## Do this, or else that: `||`

`||` runs the next command only if the one before it *failed*. It's how you write a fallback.

**Give it a try!**
<!-- try: errors expected -->
```text
/sh give @s minecraft:no_such_item || echo that item does not exist
```

The red error is the point: `give` failed, so the `echo` after `||` ran.

## Always do both: `;`

`;` runs the next command no matter what.

**Give it a try!**

```text
/sh echo one ; echo two
```

## Pass a result along: `|`

`|` (the pipe) takes what a command printed and hands it to the next one. `{value}` marks where it goes.

**Give it a try!**

```text
/sh time query gametime | echo this world is {value} ticks old
```

## Remember a value: variables

`name=value` stores a value; `$name` reads it back — later in the same line or in another one.

**Give it a try!**

```text
/sh fruit=apple
/sh echo I like $fruit
```

## Wait a moment: `sleep`

`sleep` pauses the rest of a line without freezing the game.

**Give it a try!**

```text
/sh echo ready ; sleep 2s ; echo go!
```

That's the whole idea. The rest of the guide shows each piece properly, in Minecraft.

---

[Guide index](README.md) · [Next: Getting started](01-getting-started.md) →
