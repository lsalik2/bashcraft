# BashCraft

Small utility mod for introducing logical operators and bash features into the command system in Minecraft.
Fabric, Minecraft 26.3, server-side (vanilla clients can use it).

## Usage

```
/sh tp @s ~ ~10 ~ && summon minecraft:zombie
/sh locate structure #minecraft:village | tp @s
/sh locate structure #minecraft:village | tp @s {x} 120 {z}
```

| Operator | Meaning |
|---|---|
| `a && b` | run `b` if `a` succeeded |
| `a \|\| b` | run `b` if `a` failed |
| `a ; b` | always run `b` |
| `a \| b` | feed a value from `a`'s output into `b` |

Success means the command didn't error and returned more than 0 (the command-block rule). A function without
`return` counts as success if it ran, even when a line inside it failed (as in vanilla). `/sh` reports the chain's
result to command blocks and `/execute store`, so a failed chain has SuccessCount 0.

**Pipes** extract coordinates (`/locate`), numbers (`/time query`, `/scoreboard players get`, numeric `/data get`)
or text, and append them to the next command — or put them where you write `{x}` `{y}` `{z}` `{value}`.
`{result}` is the previous command's numeric result. Zero and negative values pipe like any other. `/locate` reports Y as `~`, so `| tp @s` keeps your height;
use `tp @s {x} 120 {z}` to choose one.

**Quoting:** `"…"` and `'…'` are never split; `\&`, `\&&`, `\|`, `\;`, `\(`, `\)` are literal; `\{x}` is a literal `{x}`.
A quote right after a letter or digit doesn't start a quoted span, so `don't` is plain text.

Works in chat, the console, command blocks and functions (write `sh …` in `.mcfunction` files).
Chains never grant extra permissions. When a player types `/sh`, chat commands (`msg`, `me`, `say`, `teammsg`)
are refused inside it, because their messages couldn't be signed. `return` inside a segment ends only that segment,
not the function around the `sh` line.

**Groups:** `( … )` groups commands, and groups nest:

```
/sh (setblock ~ ~ ~ stone || setblock ~1 ~ ~ stone) && say placed
/(/locate structure #minecraft:village || /locate structure #minecraft:pillager_outpost) | /tp @s {x} 150 {z}
```

A group's result — and its output, when piped — is that of the last command that ran in it. A value piped into a
group goes to its first command. `(` only opens a group where a command could start; elsewhere it's text, as in
`say (hi)`. Inside a group, a `)` without a matching `(` in the same command must be written `\)`; once a line
uses a group, a stray `)` anywhere in it is an error rather than text.

**Variables:** inside chains and `/sh` lines.

```
/sh x=11 && setblock $x 64 0 stone
/sh home="0 80 0" ; tp @s $home
/sh tp @s $(locate structure #minecraft:village)
/sh say done ; say status was $?
```

`$x` / `${x}` read a variable; `my.x` and `global.x` pick a set. When you type a chain yourself, `x` means your own
set (falling back to the global one); in functions, command blocks, the console and `execute as … run sh`, `x` means
the global set and a player's set is reached only as `my.x`. Writing the global set needs operator permission. `$?` is `0` if the previous command
succeeded, else `1`. `$( … )` runs a chain and inserts its value like a pipe would. `unset x` removes a variable and
`echo text` prints it (and can be piped). Variables persist in command storage `bashcraft:vars`
(`global` and `players.<uuid>`), so datapacks can use them with `/data`. Expansion works in `"…"` but not in `'…'`;
`\$` is a literal `$`. An unset variable makes that command fail instead of running with a blank. Inside chains,
`echo`, `unset`, `grep`, `store` and `each` are BashCraft's even if a mod has commands with those names.

Limits: 256 variables per set, names up to 64 characters, values up to 1024 characters, at most 256 `$` references
and 32767 characters per expanded command. `$` reads only string values; other data a datapack keeps in
`bashcraft:vars global` is left untouched. In function macro lines (`$…`), vanilla owns `$(name)`, so BashCraft's
`$( … )` can't be used there.

**Security notes:**
- Anyone can read the global set with `echo $global.x`; don't keep secrets there.
- In functions, command blocks, the console and `execute as … run sh`, player-controlled values — `$my.x`, pipe
  values, placeholders and `$( … )` output — must be one plain word (letters, digits and `_ . : ~ ^ + -`, no spaces)
  or only numbers and coordinates (`312 ~ -1040`), or the command is refused. That stops a player's text from breaking
  out of JSON (`{"text":"…"}`), NBT or selectors, or adding arguments such as `store …`. A plain value still chooses
  its one argument (`give @s $my.item`), so check values you rely on.
- Values inserted into a command that can run other commands — `sh …`, anything with `run` (`execute … run …`,
  `return run …`), `function …` (macro arguments), or a command whose name is itself a `$` reference — are escaped, so
  an inner chain or macro sees them as plain text, never as `&&`, `;`, `$( … )`; a value containing a quote is refused
  there. A value may never supply `sh`, `run` or `function` itself (`execute as @a $r …` with `r=run` is refused). To
  have an inner chain expand a name itself, write `\$name`. (Plain text containing the word `run` also gets this
  escaping.)

**Pipe stages:** `store`, `grep` and `each` work on what the previous command produced.

```
/sh scoreboard players get @s kills | store score @s best
/sh locate structure #minecraft:village | store storage bashcraft:places village
/sh list | grep Steve && say Steve is online
/sh list | each give {value} minecraft:bread
/sh list | each data get entity {value} XpLevel | store storage bashcraft:stats levels
```

List commands give real items: `/list` (players), `/tag … list`, `/team list`, `/scoreboard players list`, and
`/data get` on an NBT list. Other commands give one item per message. `grep [-v] [-x] TEXT` keeps items containing
TEXT (any case; `-x` whole item, `-v` the others) and fails if none are left. `each COMMAND` runs one command per item
(`{value}` is the item) and passes on the values they produce. `store score HOLDER OBJECTIVE` (numbers, rounded down)
and `store storage ID PATH` run the matching vanilla command as you, so they need its permissions.

`surface` replaces the Y of piped coordinates with the ground height there (ignoring leaves), so
`/sh locate structure #minecraft:village | surface | tp @s` lands you on the ground instead of keeping your height.
It loads that chunk (generating it if needed), so it needs operator permission, like `/locate`, never reaches beyond
the world border, and isn't available in dimensions with a ceiling such as the Nether. `echo 100 ~ -20 | surface` works too.

**Aliases and history:**

```
/sh alias home = tp @s 0 80 0
/sh alias heal = 'effect give $1 minecraft:regeneration 5 ${2:-1} && say healed $1'
/sh home && heal @s 3
/sh !!          /sh !tp          /sh history | grep locate
```

An alias is a named chain (quote it to include `&&`, `;`, `|`). `$1`…`$9`, `$@` (all), `$#` (count) and
`${N:-default}` are its arguments; with none of those, arguments are appended to its last command. `alias` lists
aliases, `unalias NAME` removes one. Your aliases only run in chains you type; operators can define
`alias global.NAME = …`, which works everywhere (functions, command blocks). Alias names can't be existing commands.
`/sh !!` reruns your last line and `/sh !PREFIX` the last one starting with PREFIX; `history` lists this session's
lines (kept until you leave).

**Time:** `sleep` pauses a chain, and `&` lets part of one wait on its own.

```
/sh echo 3 ; sleep 20 ; echo 2 ; sleep 20 ; echo 1 ; sleep 20 ; title @s title "go"
/sh (sleep 1200 ; title @s title "time's up") & echo timer started
/sh alias tick = 'effect give @s minecraft:speed 2 ; sleep 2s ; tick'
/sh jobs          /sh kill %1
```

`sleep TIME` takes `/schedule`'s units: ticks by default, or `20t`, `1s`, `0.5s`, `1d` (at least 1 tick, at most an
hour by default). The server never waits: `/sh` returns at once (reporting the `sleep`), a function goes on to its
next line, and the rest of the chain runs on a later tick. `X & Y` runs `X`, and if `X` sleeps, the rest of `X` waits
on its own while `Y` runs at once; as in bash, `&` takes the whole `&&`/`||` list before it. In `/sh` a lone `&` is
an operator, so write `\&` for a literal one (`\&&` is still a literal `&&`); without `/sh`, `&` needs a `/` after it
like the other operators. `sleep` can't be used inside a pipe, `$( … )` or `each`, and a `sleep` that can't wait
stops the chain there.

`jobs` lists your waiting chains (operators see everyone's) and `kill %N` cancels one (any other `kill` is vanilla's).
A chain you typed carries on as you are when it wakes — where you stand and the permissions you have then — and ends
if you leave. Other chains (command blocks, functions, the console) carry on as they started. Waiting chains follow
`/tick freeze` and `/tick rate` like `/schedule`, and are not kept across restarts.

### Without `/sh`

Chains also work typed directly, as long as every command after an operator starts with `/`:

```
/tp @s ~ ~ ~ && /summon minecraft:zombie
/locate structure #minecraft:village | /tp @s {x} 150 {z}
```

A line can also start with a group: `/(/a || /b) && /c`. After an operator, a group counts only when a `/` command
comes first inside it (`&& (/b || /c)`), so `/say I love you && (hug)` is still a plain `/say`.

`/say lunch; then work` is still a plain `/say` (no `/` after the `;`). This works in chat, the console, command
blocks and function files (`say hi && /say there`). A vanilla client may colour such a line red; it is still sent.
Set `transparentMode` to `false` to turn this off (function files pick up the change on `/reload`); `/sh` always works.

Macro lines (`$…`) in functions are never chained: a macro value could otherwise add commands to the line. Writing
`$sh …` yourself is possible, but then any `;`, `|` or `&&` inside a substituted value counts as an operator.

## Client companion (optional)

Install the same jar on your client too, and on servers that run BashCraft the chat box understands chains:
each command is highlighted the way vanilla highlights it (no red after `&&`), operators and parentheses are
coloured, BashCraft syntax errors show above the chat box, and tab-completion works for the command you're typing —
also inside `/sh …` lines and in command blocks. On servers without BashCraft the client behaves exactly as vanilla.

## Config

`config/bashcraft.json`: `maxSegments` (32), `maxDepth` (8), `maxTotalCommands` (256, across nested chains),
`swallowPipedOutput` (true),
`transparentMode` (true), `maxSleepTicks` (72000, one hour), `maxJobsPerPlayer` (8), `maxJobs` (64, waiting chains
on the whole server).

## Building

```
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build          # jar in build/libs
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test           # unit tests
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest   # in-game tests, headless on Xvfb if installed (-Pheadless=false for a window)
```
