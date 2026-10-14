# Security

BashCraft promises server admins that chains never give anyone more power than they already have. If you find a way
around that, please report it privately so it can be fixed before it's public.

## What to report

- A chain, alias, variable, pipe, `$( … )` or waiting job that runs a command with more permission than the player
  (or command block, function or console) that started it.
- A way past BashCraft's limits (chain length, nesting, commands per second, sleeps, waiting chains) that could slow
  down or stop a server.
- A way to send chat that a typed `/sh` line should refuse, or to bypass chat restrictions through the client.
- Anything else in [the server admins' page](docs/guide/10-server-admins.md) that doesn't hold.

## How to report

Use **[Report a vulnerability](https://github.com/lsalik2/bashcraft/security/advisories/new)** on the repository's
Security tab. Please don't open a public issue. Include the BashCraft, Minecraft and Fabric API versions, the exact
lines or files involved, and what happened.

## Supported versions

Fixes go into the latest release.
