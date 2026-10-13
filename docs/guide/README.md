# BashCraft guide

BashCraft adds a small bash-style shell to Minecraft's command line: you can chain commands, pass results from one
command to the next, keep values in variables, make your own shortcuts and set timers. This guide teaches all of it,
one idea at a time.

Every page has **Give it a try!** boxes: copy the lines into chat in a creative world with cheats on, one at a time,
and watch what happens. The boxes on a page build on each other, so try them in order.

New to bash? Start with page 00. Already know it? Jump straight to page 01.

0. [New to bash?](00-new-to-bash.md) — an optional five-minute primer on commands, `&&`, `||`, pipes and variables.
1. [Getting started](01-getting-started.md) — install BashCraft, run your first chain, read its errors.
2. [Chains and groups](02-chains-and-groups.md) — `&&`, `||`, `;`, `( … )`, quotes and escapes.
3. [Variables](03-variables.md) — remember values, `$?`, and `$( … )` to use one command's result in another.
4. [Pipes and builtins](04-pipes-and-builtins.md) — pass results along with `|`, and `grep`, `each`, `store`, `surface`.
5. [Aliases and history](05-aliases-and-history.md) — your own commands, with arguments; rerun what you typed.
6. [Time](06-time.md) — `sleep`, timers that run on their own with `&`, and `jobs` to see and cancel them.
7. [Chains without /sh](07-without-sh.md) — type chains straight into chat (transparent mode).
8. [The client companion](08-client-companion.md) — colours, error hints and tab completion in the chat box.
9. [Functions, command blocks and datapacks](09-functions-and-command-blocks.md) — chains beyond chat.
10. [For server admins](10-server-admins.md) — permissions, trust rules, waiting chains and limits.
11. [Configuration](11-configuration.md) — every setting in `config/bashcraft.json`.
12. [Cookbook](12-cookbook.md) — ready-made chains to paste and adapt.
13. [Troubleshooting](13-troubleshooting.md) — what the messages mean, and answers to common questions.
