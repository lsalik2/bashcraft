# Configuration

BashCraft reads `config/bashcraft.json` when the game or server starts. The file is written with the defaults the
first time; edit it and restart to change a setting.

| Setting | Default | Meaning |
|---|---|---|
| `transparentMode` | `true` | Chains typed without `/sh` (see [Chains without /sh](07-without-sh.md)). Function files pick up a change on `/reload`. |
| `maxSegments` | `32` | Most commands in one chain, alias body or `$( … )`. |
| `maxDepth` | `8` | How deep chains may nest inside chains (functions running `sh`, aliases calling aliases, `$( … )`). |
| `maxTotalCommands` | `256` | Most commands one chain may run, counting every chain nested inside it. A player's waiting chains share this budget per second. |
| `swallowPipedOutput` | `true` | Hide the output of commands that feed a pipe, so only the end of the pipe shows. |
| `maxSleepTicks` | `72000` | Longest single `sleep`, in ticks (one hour). |
| `maxJobsPerPlayer` | `8` | Waiting chains one player may have. |
| `maxJobs` | `64` | Waiting chains on the whole server. |
| `maxServerJobs` | `32` | Waiting chains that command blocks, functions, the console and `execute as` may hold together, so players keep the rest. |

## Rules and limits

- Numbers below 1 count as 1.
- A file that can't be read is logged with the reason, and the defaults are used; the file is left as it was.
- Settings missing from the file get their defaults.

---

← [Previous: For server admins](10-server-admins.md) · [Guide index](README.md) · [Next: Cookbook](12-cookbook.md) →
