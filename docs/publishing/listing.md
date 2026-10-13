# BashCraft — listing for Modrinth and CurseForge

Everything to fill in when creating the project on either site. Both accept Markdown, so the description below can be
pasted into both as is.

- Name: BashCraft
- Version: 1.0.0
- Summary: A bash shell for the command line: set timers, chain commands and much more.
- License: MIT
- Icon: `docs/images/brand/icon.png` (384×384)
- Environment: server required, client optional (the same jar on a client adds the chat companion)
- Categories — Modrinth: Utility, Management. CurseForge: Server Utility, Utility & QoL.
- Loader and game version: Fabric, Minecraft 26.3; depends on Fabric API.
- Links:
  - Source: https://github.com/lsalik2/bashcraft
  - Issues: https://github.com/lsalik2/bashcraft/issues
  - Guide / wiki: https://github.com/lsalik2/bashcraft/tree/main/docs/guide

## Gallery

Upload in this order; the first one is the featured image.

| Image | Title | Caption |
|---|---|---|
| `docs/images/brand/featured.png` | The BashCraft spellbook | Recipes you can paste straight into chat. |
| `docs/images/brand/gallery-cave.png` | A bash shell for the command line | Chain commands, pass results along and set timers. |
| `docs/images/screens/countdown.png` | Timers | `sleep` pauses a chain without freezing the game. |
| `docs/images/screens/chat-highlighting.png` | Chat companion | With the optional client install, chains are coloured as you type. |
| `docs/images/screens/pipe-surface.png` | Pipes | `locate … \| surface \| tp @s` lands you in the nearest village. |
| `docs/images/screens/jobs.png` | Jobs | See and cancel waiting chains with `jobs` and `kill %N`. |
| `docs/images/screens/command-block.png` | Command blocks | Chains work in command blocks and functions too. |

## Description

Paste everything below this line into the description field.

---

![BashCraft](https://raw.githubusercontent.com/lsalik2/bashcraft/main/docs/images/brand/banner.png)

**A bash shell for the command line: set timers, chain commands and much more.**

BashCraft brings the best small ideas of bash to Minecraft commands. Run a command only if another worked, pass one
command's result to the next, keep values in variables, make your own commands, and set timers — from chat, command
blocks or functions. Players don't need to install anything.

### What it does

- **Chains** — `&&` runs the next command if the last one worked, `||` if it failed, `;` always.
- **Pipes** — `|` passes a result along: find a village, land on its surface, teleport there.
- **Variables** — remember values in one line and use them in the next, even after a restart.
- **Timers** — `sleep` pauses a chain without freezing the game; `&` lets a timer run on its own.
- **Aliases** — name a chain and use it like a command, with arguments.
- **Groups** — `( … )` bundles commands so an operator applies to all of them.

**Give it a try!**

```text
/sh tp @s ~ ~5 ~ && echo up you go
/sh locate structure #village | surface | tp @s
/sh echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1
/sh alias up = tp @s ~ ~10 ~
/sh up
```

![A countdown in chat](https://raw.githubusercontent.com/lsalik2/bashcraft/main/docs/images/screens/countdown.png)

### Install

Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API and Java 25. Put the jar in the server's `mods` folder (in
singleplayer, in your own). Plain clients can join and use everything; the same jar on a client is optional and adds
a chat companion that colours chains as you type them.

### Learn more

The [guide](https://github.com/lsalik2/bashcraft/tree/main/docs/guide) teaches every feature step by step, with
snippets to paste into chat — starting with an optional
[five-minute primer](https://github.com/lsalik2/bashcraft/blob/main/docs/guide/00-new-to-bash.md) if you're new to
bash. Questions or bugs: [issues](https://github.com/lsalik2/bashcraft/issues).

### License

MIT. The BashCraft icon and the images in docs/images/brand/ contain Minecraft textures and font © Mojang AB; they are
not covered by the MIT license. Not an official Minecraft product. Not approved by or associated with Mojang or
Microsoft.
