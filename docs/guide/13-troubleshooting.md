# Troubleshooting

What BashCraft's messages mean, and how to fix the usual surprises.

## `unexpected '&&' at col N` or `missing command after '&&'`

The line can't be read as a chain: an operator is missing a command on one side, or brackets don't match. The
message points at the spot, and nothing in the line ran.

**Give it a try!**
<!-- try: errors expected -->
```text
/sh echo a &&
```

## `$name is not set`

The chain read a variable that doesn't exist. BashCraft stops that command rather than running it with a blank. Set
the variable first, or check its spelling (`my.` and `global.` matter).

**Give it a try!**
<!-- try: errors expected -->
```text
/sh echo $nobody_set_this
```

## `chat commands like msg, me and say can't run inside /sh when a player types it`

Messages you type are signed by your client, and text inside `/sh` can't be. Use `echo` for yourself, or `tellraw`
and `title` for others. See [For server admins](10-server-admins.md).

## `a value can't decide which commands run`

A variable or pipe value tried to supply `sh`, `run` or `function` — the words that make a command run other
commands. Write those words yourself in the chain.

## `too many commands …` / `aliases nested deeper than N`

The chain ran more commands than `maxTotalCommands`, or aliases called each other more than `maxDepth` levels deep —
usually an alias that calls itself without a `sleep`. See [Configuration](11-configuration.md).

## `sleep can't run inside a pipe` (or `$( … )`, or `each`)

Those need their answer straight away, so they can't wait. Move the `sleep` outside, before or after them.

## `too many waiting chains (max N)`

You (or the server) already have as many waiting chains as allowed. `/sh jobs` lists them and `/sh kill %N` cancels
one.

## My chain is red in chat

Typed without `/sh`, a chain looks wrong to a plain Minecraft client, which colours everything after the first
command red because it doesn't know BashCraft. The line still works. (`/sh` lines don't turn red: to a plain client
everything after `/sh` is just text.) The [client companion](08-client-companion.md) colours both properly.

## Typed without `/sh`, my line didn't chain

In [transparent mode](07-without-sh.md) every command after an operator must start with `/`: `/a && /b`, not
`/a && b`.

## `@s` does nothing in my command block

A command block isn't an entity, so `@s` matches nothing there. Use `@p` or another selector.

## FAQ

**Do players need the mod?** No. Only the server does; plain clients can use everything. The client companion is
optional.

**Does it need operator permission?** Only for what the commands themselves need. `/sh echo hi` works for everyone.

**What survives a restart?** Variables and aliases (they're in command storage). Waiting chains and history don't.

---

← [Previous: Cookbook](12-cookbook.md) · [Guide index](README.md)
