# For server admins

BashCraft is built so that installing it never weakens your server: a chain can only do what its commands could do
one by one. This page explains the rules that keep it that way, and the limits you can tune.

## Permissions

Chains never grant permissions. Every command in a chain checks the permission of whoever runs the chain, exactly as
if typed alone. A non-operator who writes `/sh op Steve` gets the same "unknown or incomplete command" as typing
`/op Steve`.

## Chat commands in typed chains

When a player types `/sh`, the chat commands `msg`, `me`, `say` and `teammsg` are refused inside it. Their messages
are normally signed by the player's client so that others can trust who wrote them — and the text inside a `/sh`
line can't be signed that way.

**Give it a try!**
<!-- try: errors expected -->
```text
/sh say hello
```

Use `echo` for your own feedback, or `tellraw`/`title` for messages to others. Chains run by functions, command
blocks and the console aren't affected.

## Values players control

In functions, command blocks, the console and `execute as … run sh`, the chain runs with more trust than the
player who fed it. So values a player controls — their own variables (`$my.x`), pipe values, placeholders and
`$( … )` output — must be **one plain word** (letters, digits and `_ . : ~ ^ + -`, no spaces) or **only numbers and
coordinates** (`312 ~ -1040`). Anything else is refused. That stops a player's text from breaking out of JSON or NBT,
or adding extra arguments to a command.

A plain value still chooses its one argument (`give @s $my.item`), so check values you rely on.

## Commands that run commands

Values put into a command that can run other commands — `sh …`, anything with `run` (`execute … run …`,
`return run …`), or `function …` — are escaped, so the inner chain sees them as plain text, never as `&&`, `;` or
`$( … )`. A value may never supply `sh`, `run` or `function` itself.

## Variables, aliases and history

- Anyone can read the global variables; writing them needs operator permission. Don't keep secrets there.
- Players' own variables and aliases are theirs alone: their aliases only run in chains they type.
- Changing global aliases needs operator permission.
- History is per player, in memory, until they leave.

## Waiting chains (jobs)

- A chain a player types wakes up as that player is *then*: where they stand and the permissions they have then. It
  ends when they leave.
- A chain started by a command block, function, the console or `execute as` wakes as it started — but never with
  more permission than the player who started it has by then. De-opping someone takes their waiting chains' powers
  away too.
- Players see and cancel only their own jobs; operators see and cancel everyone's.

**Give it a try!**

```text
/sh jobs
```

## Limits

| Limit | Default | What it bounds |
|---|---|---|
| `maxSegments` | 32 | commands in one chain (and in one alias body or `$( … )`) |
| `maxDepth` | 8 | chains nested inside chains |
| `maxTotalCommands` | 256 | commands one chain runs, including nested ones; per second for a player's waiting chains together |
| `maxSleepTicks` | 72000 | one `sleep` |
| `maxJobsPerPlayer` | 8 | waiting chains one player has |
| `maxJobs` | 64 | waiting chains on the whole server |
| `maxServerJobs` | 32 | waiting chains of command blocks, functions, the console and `execute as` together |

Every setting is described in [Configuration](11-configuration.md).

## Rules and limits

- Variables and aliases are stored as text in command storage (`bashcraft:vars`, `bashcraft:aliases`). Values are
  checked against the rules above every time they're used, and an alias body is parsed again each time it runs.
- Waiting chains are never saved; a restart ends them all.

---

← [Previous: Functions, command blocks and datapacks](09-functions-and-command-blocks.md) · [Guide index](README.md) · [Next: Configuration](11-configuration.md) →
