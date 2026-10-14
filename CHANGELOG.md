# Changelog

## Unreleased

## 1.0.0 — first release

**Chains**
- `&&`, `||` and `;` with bash's rules; groups `( … )` that nest; quotes and `\` escapes.
- `[n]` numbers on errors, so you can tell which command in a chain failed.
- Chains in chat, the console, command blocks and function files; `/sh` reports the chain's result to command blocks
  and `/execute store`.
- Transparent mode: chains typed without `/sh`, when every command after an operator starts with `/`.

**Pipes**
- `|` passes coordinates, numbers or text to the next command, appended or placed with `{x} {y} {z} {value} {result}`.
- `grep`, `each`, `store` and `surface`; real lists from `/list`, `/tag … list`, `/team list`,
  `/scoreboard players list` and NBT lists.

**Variables**
- `x=…`, `$x`, `${x}`; your own set and the global set (`my.`, `global.`); `$?`; `$( … )`; `unset`; `echo`.
- Kept in command storage `bashcraft:vars`, shared with datapacks.

**Aliases and history**
- `alias NAME = BODY` with `$1`…`$9`, `$@`, `$#` and `${N:-default}`; global aliases for operators; `unalias`.
- `!!`, `!PREFIX` and `history` for the lines you type.

**Time**
- `sleep` with `/schedule`'s units; `&` for parts of a chain that wait on their own; `jobs` and `kill %N`.

**Client companion (optional)**
- Chat-box colours for chains, BashCraft error hints, tab completion inside chains and command blocks, alias names.

**Safety**
- Chains never grant permissions; chat commands are refused inside typed `/sh` lines; values players control must be
  plain in trusted chains; limits on chain length, nesting, commands, sleeps and waiting chains, all configurable.
