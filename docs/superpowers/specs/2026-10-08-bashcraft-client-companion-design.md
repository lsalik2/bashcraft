# BashCraft — Client Companion Design Spec (Sub-project B)

**Date:** 2026-10-08
**Status:** Draft, awaiting review
**Builds on:** `2026-10-01-bashcraft-design.md` (M1/M2) and `2026-10-05-bashcraft-parser-design.md` (A, shipped)

## 1. Goal

With BashCraft installed on the client as well as the server, chains are highlighted and completed like ordinary
commands instead of turning red after the first operator:

1. **Per-command highlighting** — each command coloured as vanilla colours it; red only where *that* command fails
   to parse.
2. **Operator and group colouring**, and BashCraft's own syntax errors shown in the usage area above the input.
3. **Tab-completion and usage hints** for the command under the cursor, including suggestions the server computes
   (functions, players, loot tables…).
4. All of the above for `/sh …` lines as well as prefix-free chains.

**Success criteria**

1. `/tp @s ~ ~ ~ && /summon ` in chat shows no red, and suggests entity types for `summon`.
2. `/sh say a && function bashcraft-test:` suggests the test functions (server-side suggestions).
3. `/(/say a || /say b` shows a BashCraft "unclosed '('" hint instead of vanilla's red line.
4. On a server without BashCraft — or with BashCraft on the server but not the client — chat behaves exactly as
   vanilla.
5. A failure inside the client hooks never breaks the chat screen; that line falls back to vanilla.

## 2. Activation (handshake)

- Payload `bashcraft:hello` (server → client, Fabric Networking API): `int protocol` (= 1) and
  `boolean transparentMode`.
- The server sends it on join, only if the client can receive the channel (`ServerPlayNetworking.canSend`), so
  vanilla clients never see it.
- The client stores it in a session state that is cleared on disconnect. Singleplayer receives it from the
  integrated server like any other server.
- Client features are active only after a hello with a known protocol. Prefix-free chains are recognised only when
  the hello reports `transparentMode = true`; `/sh` lines whenever active.

## 3. Token positions — `ChainLayout`

A public, Minecraft-free API in `net.slk.bashcraft.chain`:

```
ChainLayout.of(String line, NodeParser.Mode mode) → ChainLayout
  spans(): List<Span>              Span(Kind kind, int start, int end)   // indices into `line`, end exclusive
  error(): Optional<ChainSyntaxException>
  commandAt(int cursor): Optional<Span>
Kind = COMMAND | OPERATOR | GROUP_OPEN | GROUP_CLOSE
```

- COMMAND spans exclude surrounding whitespace and the leading `/`; they cover the raw text (escapes included).
- On a syntax error, spans up to the error are still returned, plus the error (so highlighting survives while the
  player is still typing).
- `commandAt(cursor)`: the command whose span contains the cursor or ends at it; if the cursor is on an operator or
  whitespace, the next command, or the previous one if there is no next.
- Built on the A tokenizer (its tokens gain raw start/end offsets); the existing parser API is unchanged.

## 4. What the client treats as a chain

Given the input box text (chat: with leading `/`; command-block screen: without), and only while active:

- **`sh` line:** the command text starts with `sh ` → chain region starts after `sh `, mode SH.
- **Prefix-free chain:** `transparentMode` is on and `TransparentChains.detect` accepts the command text → region
  starts after the `/` (or at 0 in the command-block screen), mode TRANSPARENT.
- Anything else → vanilla behaviour, untouched.

## 5. Client hooks (`CommandSuggestions`)

Two hooks on the vanilla suggestion helper used by chat and the command-block screen:

- **Parse** (where `updateCommandInfo` calls `dispatcher.parse(reader, provider)`): for a chain, parse instead the
  command under the cursor with a reader whose string is the input truncated at that command's end and whose cursor
  is at that command's start. Ranges stay absolute in the input, so vanilla's completion request, suggestion list,
  usage hints and suggestion insertion work unchanged.
- **Format** (`formatChat`): for a chain, build the coloured text from the layout — each COMMAND span parsed the same
  way and coloured with vanilla's scheme (grey literal, cycling argument colours, red unparsed remainder *within the
  span*); OPERATOR and GROUP spans in dark aqua; placeholders `{x}` `{y}` `{z}` `{value}` `{result}` in light purple.
- **Placeholders for parsing:** before a command span is parsed client-side, each placeholder is replaced by zeros
  of the same length (`{x}` → `000`), so `tp @s {x} 150 {z}` parses; the displayed text is unchanged.
- **Appended pipe values:** a pipe stage like `tp @s` is simply incomplete client-side; vanilla shows its usage hint,
  not red.
- **BashCraft errors:** when the layout has an error, the usage area shows `BashCraft: <message>` (vanilla error
  style) instead of vanilla's message for the whole line.
- Client mixins live in a separate non-required mixin config (`bashcraft.client.mixins.json`, `client` section).
  Any exception inside a hook is logged once and the hook returns vanilla's result.

## 6. Server-side suggestions

`ServerCommandSuggestionsProvider` parses the client's request from the start of the string. A server hook detects
a chain or `sh` line in the request (same rules as §4, using the server's real `transparentMode`) and parses only
the **last** command with the truncated-reader trick (its end is the request's end, since the client sends input up
to the cursor), so returned ranges are absolute and the client applies them unchanged. Non-chain requests are
untouched. This hook lives in its own non-required mixin config, `bashcraft.suggest.mixins.json`.

## 7. Out of scope

Books, signs and other text fields; a client option to disable the companion; colour customisation; suggestions
for BashCraft syntax itself (e.g. suggesting `&&`).

## 8. Testing

- **Unit (pure Java):** `ChainLayout` spans (operators, groups, escapes, quotes, leading `/`, whitespace) and partial
  layouts on each syntax error; `commandAt` for cursor inside/at end of/between commands; placeholder masking
  (length-preserving, escaped `\{x}` untouched); chain detection for input text (`/sh`, prefix-free, command-block
  without slash, transparent off); last-command selection for server suggestion requests; the colour plan as
  abstract ranges (kind + range) so it is testable without Minecraft styles.
- **Client game tests:** in a world where the hello arrives — open a `ChatScreen` with `/tp @s ~ ~ ~ && /summon `,
  check the formatted text has no red style and the suggestion list offers `minecraft:zombie`; `/sh say a && function
  bashcraft-test:` offers a test function (server round trip); `/(/say a || /say b` shows the BashCraft hint; a
  screenshot of a coloured chain is saved for visual review. After clearing the client's session state (the same
  reset that runs on disconnect, standing in for a server without BashCraft), the same input is formatted exactly as
  vanilla formats it.
