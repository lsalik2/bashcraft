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

**Quoting:** `"…"` and `'…'` are never split; `\&&`, `\|`, `\;` are literal; `\{x}` is a literal `{x}`.
A quote right after a letter or digit doesn't start a quoted span, so `don't` is plain text.

Works in chat, the console, command blocks and functions (write `sh …` in `.mcfunction` files).
Chains never grant extra permissions. When a player types `/sh`, chat commands (`msg`, `me`, `say`, `teammsg`)
are refused inside it, because their messages couldn't be signed. `return` inside a segment ends only that segment,
not the function around the `sh` line.

### Without `/sh`

Chains also work typed directly, as long as every command after an operator starts with `/`:

```
/tp @s ~ ~ ~ && /summon minecraft:zombie
/locate structure #minecraft:village | /tp @s {x} 150 {z}
```

`/say lunch; then work` is still a plain `/say` (no `/` after the `;`). This works in chat, the console, command
blocks and function files (`say hi && /say there`). A vanilla client may colour such a line red; it is still sent.
Set `transparentMode` to `false` to turn this off (function files pick up the change on `/reload`); `/sh` always works.

Macro lines (`$…`) in functions are never chained: a macro value could otherwise add commands to the line. Writing
`$sh …` yourself is possible, but then any `;`, `|` or `&&` inside a substituted value counts as an operator.

## Config

`config/bashcraft.json`: `maxSegments` (32), `maxDepth` (8), `maxTotalCommands` (256, across nested chains),
`swallowPipedOutput` (true),
`transparentMode` (true).

## Building

```
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build          # jar in build/libs
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test           # unit tests
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest   # in-game tests (~2 min)
```
