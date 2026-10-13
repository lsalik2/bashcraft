# BashCraft — Publishing Prep Design Spec (Sub-project D: branding, docs, release kit)

**Date:** 2026-10-13
**Status:** Approved in conversation (the user waived written-spec review)
**Builds on:** M1/M2, A, B, C1–C5, C3 and the minors batch (everything shipped up to `6f4fe6d`)

## 1. Goal

BashCraft goes public on Modrinth and CurseForge as version 1.0.0, with a recognisable brand, a README that sells
it, a step-by-step guide that teaches it, and everything needed for a first manual upload.

**Success criteria**

1. The mod has its icon in-game (mod list) and on both sites, plus a README banner and gallery images, all in one
   consistent Minecraft-native style and all regenerable from committed scripts.
2. A reader new to BashCraft (and possibly to bash) can learn every feature from the guide by pasting the
   "Give it a try!" snippets into a fresh creative world — and every snippet is verified by an automated test.
3. Screenshots in the docs are captured automatically and can be refreshed with one command.
4. Metadata, license, changelog, listing text and a release checklist are ready; publishing is a manual upload.

## 2. Brand assets

All images use Minecraft's own textures and font (extracted at generation time from the local 26.3 jar; no raw
Mojang files are committed) and are drawn pixel-exact, exported with nearest-neighbour scaling at integer factors.

| Asset | Logical size | Exported | Where |
|---|---|---|---|
| Icon | 48×48 | 192×192 (jar), 384×384 (listings) | `src/main/resources/assets/bashcraft/icon.png`, `docs/images/brand/icon.png` |
| README banner | 480×192 | 960×384 | `docs/images/brand/banner.png` |
| Featured image (book) | 512×288 | 1024×576 | `docs/images/brand/featured.png` |
| Extra gallery image (cave) | 512×288 | 1024×576 | `docs/images/brand/gallery-cave.png` |

**Icon.** Vanilla `block/dirt.png`, 4 px texels, every channel ×0.45 (classic menu dirt, brightened). Text in
Minecraft's font (`font/ascii.png` per `font/include/default.json`) at 2×: line 1 `/sh` white `#ffffff`; line 2
`&&` aqua `#55ffff` then `_` white (the real `_` sits below the baseline). Both lines 34 px wide, 6 px between the
lines' ink; ink block 34×36 at (7,6). Shadow offset (+2,+2), colour = text colour / 16 per channel.

**README banner.** Vanilla `stone.png`, 4 px texels, ×0.5, block grid (64 px) centred horizontally (offset −16).
Ore blocks (whole blocks on the grid): coal (0,0), iron (5,0), copper (6,0), gold (4,1), redstone (2,2),
diamond (7,2) — chosen at random (seed 20) under the rules: ordered by real spawn height (coal ~Y95 > copper ~48 >
iron ~16 > gold ~−16 > redstone/diamond ~−59; never above an ore that spawns higher), one side busier by ≥ 2, no
evenly spaced row, at most one touching pair. Content 16 px from the top, centred: the icon as a tile (2 px black
outline at 200/255, 4 px drop shadow at 120/255) + "BashCraft" at 5× (white, shadow /16), 18 px apart; below, a
chat line (black at 150/255, 8 px padding) with `/sh tp @s ~ ~9 ~ && sleep 2s ; echo hi_` at 2× in the client
companion's colours (gray `#aaaaaa`, aqua `@s`, yellow `~ ~9 ~`, dark aqua `#00aaaa` operators, white cursor).

**Featured image (book and quill).** Vanilla `spruce_planks.png`, 4 px texels, ×0.85. Vanilla `gui/book.png` page
(x 20–165, y 1–180) at 1× with a 4 px drop shadow; "Page 1 of 1" and three recipes in black ink, headings
`#555555` — *Village trip* (`locate structure` / `  #village |` / `surface | tp @s`), *Feed everyone*
(`list | each give` / `  {value} bread 3`), *Quick home* (`alias home =` / `  tp @s 0 80 0`). Headings are plain
text: BashCraft chains have no `#` comments. Right of the page, 40 px away: the icon tile at 2× centred over
"BashCraft" at 4×, 18 px apart. The group is centred (63 px left/right, 54 px top/bottom); the brand block is
vertically centred on the page.

**Extra gallery image (cave).** The banner's style at 512×288 (grid offset −16 vertically), stacked: icon tile at
2×, "BashCraft" at 6×, the same chat line; eight ores (2 coal, 2 iron, copper, gold, redstone, diamond), same rules,
seed 8.

**Generation.** `branding/` holds small pure-Python 3 scripts (no third-party packages): a PNG reader and writer,
the font loader, the compositing helpers, and `generate.py`, which finds the Minecraft jar in Loom's cache (or takes
its path as an argument), reads the vanilla files it needs straight from the jar, and writes the four assets.
Re-running it reproduces the committed images byte for byte.

## 3. Metadata and legal

- `gradle.properties`: `mod_version=1.0.0`.
- `fabric.mod.json`: `description` "A bash shell for the command line: set timers, chain commands and much more.";
  `authors` `["lsalik2"]`; `license` `"MIT"`; `icon` `"assets/bashcraft/icon.png"`; `contact`: `homepage` = the
  guide on GitHub, `sources` = the repo, `issues` = its issue tracker.
- `LICENSE`: MIT, "Copyright (c) 2026 lsalik2". It covers the code and the docs text. A note at its end (and in the
  README) says the images in `docs/images/` (brand art and screenshots) and the mod icon contain Minecraft textures and font © Mojang
  and are not covered by it, and the README carries "Not an official Minecraft product. Not approved by or
  associated with Mojang or Microsoft."
- `.gitignore` gains `.superpowers/`.

## 4. README (the storefront)

Banner; the summary; **What it does** — six short examples (chains, pipes, variables, timers, aliases, groups)
each with one line of explanation, and one screenshot; **Install** (Fabric Loader + Fabric API on the server;
the same jar on clients is optional and adds the chat companion); **Quick start** (three commands to paste);
**Learn more** — links to every guide page; **Compatibility** (Minecraft 26.3, Fabric Loader ≥ 0.19.5, Fabric API,
Java 25; vanilla clients can join and use it); **Configuration** at a glance (link to the reference);
**License** with the notice. The current reference-style README content moves into the guide.

## 5. The guide (`docs/guide/`)

| File | Page |
|---|---|
| `00-new-to-bash.md` | New to bash? — optional, short, clearly marked "skip if you know bash": commands and exit status, `&&`/`||`/`;`, pipes, variables, as they look in BashCraft |
| `01-getting-started.md` | Install, `/sh`, a first chain, what success means, `[n]` errors, where chains work |
| `02-chains-and-groups.md` | `&&`, `||`, `;`, groups `( … )`, results, quoting and escapes |
| `03-variables.md` | `x=…`, `$x`/`${x}`, `my.`/`global.`, `$?`, `$( … )`, the plain-value rule, `unset`, `echo` |
| `04-pipes-and-builtins.md` | `|`, what gets extracted, `{x} {y} {z} {value} {result}`, `grep`, `each`, `store`, `surface` |
| `05-aliases-and-history.md` | `alias`, `$1…$9 $@ $# ${N:-default}`, the append rule, `unalias`, global aliases, `!!`, `!prefix`, `history` |
| `06-time.md` | `sleep`, units, `&`, `jobs`, `kill %N`, what happens while waiting, limits |
| `07-without-sh.md` | Transparent mode: chains typed directly, the `/` rule, groups, function lines |
| `08-client-companion.md` | Highlighting, error hints, tab completion, alias names; vanilla clients |
| `09-functions-and-command-blocks.md` | `sh` in `.mcfunction` files, macro lines, command blocks, `execute store`, results |
| `10-server-admins.md` | Permissions and trust rules, chat-command refusal, limits, jobs and ownership, safety |
| `11-configuration.md` | Every `config/bashcraft.json` key with default and meaning |
| `12-cookbook.md` | Ready-made chains for common tasks |
| `13-troubleshooting.md` | Common errors and what they mean; FAQ |
| `README.md` | Index of the guide |

**Page shape.** A short intro; sections that each explain one idea and end with a **Give it a try!** snippet; a
final **Rules and limits** section with exact behaviour, limits and error texts. Every page links to the previous
and next page.

**Give it a try! format.** A line `**Give it a try!**`, then a fenced block with the info string `text`, holding
exactly the lines to paste into chat, one command per line, each starting with `/`. An HTML comment
`<!-- try: errors expected -->` directly before the fence marks a snippet that is meant to show an error (a refusal,
an `||` fallback). Nothing else in the docs uses that heading.

**Snippet contract.** Snippets are written for a fresh creative singleplayer world with cheats on (the player is an
operator) and run in page order (later snippets on a page may rely on earlier ones; pages don't rely on each
other). They never destroy anything beyond the player's immediate surroundings (no `kill @e` without a type, no
huge `fill`), never use `say`, `msg`, `me` or `teammsg` in a typed chain (refused there), never use `#` as a comment,
and keep each `sleep` at 5 seconds (100 ticks) or less. A line that needs operator permission says so in the text
around it.

## 6. Verifying the docs

**Static checks (unit tests).** For the README, every guide page and the publishing files: every relative link and
image path exists; every "Give it a try!" heading is followed by a `text` fence whose lines all start with `/`; no
snippet line uses `say`/`msg`/`me`/`teammsg` inside `sh` or a chain, a `#` comment, or a `sleep` above 100 ticks;
every page links to its neighbours.

**Snippet game test (headless).** A client game test reads every guide page; for each page it creates a fresh
creative world, makes the test player an operator, and sends each snippet line as a chat command from the client,
in order, waiting 10 ticks after each line and 120 ticks after a snippet containing `sleep`. It records the game
messages the client receives; any message containing red text fails the test with the page, snippet and message —
unless the snippet is marked `<!-- try: errors expected -->`, which instead requires at least one red message.

The parser for snippets is shared by both tests (one source file compiled into both source sets).

## 7. Screenshots

A client game test stages each scene in its own world and calls `takeScreenshot` at 1280×720 with GUI scale 2,
writing into the run directory; `./gradlew updateScreenshots` runs the client game tests and copies the images into
`docs/images/screens/`. Scenes (each also asserts no unexpected red messages):

1. `chat-highlighting` — the chat box open with a coloured chain typed (client companion).
2. `countdown` — chat after `echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1`.
3. `jobs` — a background timer and the `jobs` listing.
4. `pipe-surface` — standing at a village after `locate … | surface | tp @s`.
5. `alias` — defining and calling an alias with an argument.
6. `command-block` — a command block's editor holding a chain.
7. `numbered-error` — a chain with a failing command and its `[n]` error.
8. `transparent` — a chain typed without `/sh`.

## 8. Publishing kit (`docs/publishing/`)

- `listing.md` — the shared listing: name, summary, the description (Markdown, accepted by both sites; images via
  raw GitHub URLs), categories (Modrinth: Utility, Management; CurseForge: Server Utility, Utility & QoL),
  environment (server required, client optional), links (source, issues, guide), license MIT, and the gallery
  images with titles and captions.
- `release-checklist.md` — make the repo public, build, smoke-test the jar, create each project, fill in the
  listing, upload icon and gallery, upload the 1.0.0 file (Minecraft 26.3, Fabric, depends on Fabric API, release
  channel, changelog), tag `v1.0.0`, create a GitHub release.
- `CHANGELOG.md` (repo root) — the 1.0.0 entry: every feature, grouped as in the guide.

## 9. Out of scope

Automated publishing (a later follow-up), `#` comments, translations, a website, multi-version builds.
