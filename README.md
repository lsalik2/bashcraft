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

Success means the command didn't error and returned more than 0 (the command-block rule).

**Pipes** extract coordinates (`/locate`), numbers (`/time query`, `/scoreboard players get`, numeric `/data get`)
or text, and append them to the next command — or put them where you write `{x}` `{y}` `{z}` `{value}`.
`{result}` is the previous command's numeric result. `/locate` reports Y as `~`, so `| tp @s` keeps your height;
use `tp @s {x} 120 {z}` to choose one.

**Quoting:** `"…"` and `'…'` are never split; `\&&`, `\|`, `\;` are literal; `\{x}` is a literal `{x}`.

Works in chat, the console, command blocks and functions (write `sh …` in `.mcfunction` files).
Chains never grant extra permissions.

## Config

`config/bashcraft.json`: `maxSegments` (32), `maxDepth` (8), `swallowPipedOutput` (true),
`transparentMode` (true; used by the upcoming prefix-free mode).

## Building

```
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build          # jar in build/libs
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test           # unit tests
JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew runClientGameTest   # in-game tests (~2 min)
```
