# Contributing to BashCraft

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting

- **Bugs, ideas and questions:** open an [issue](https://github.com/lsalik2/bashcraft/issues) and pick a form. The
  exact line you typed and the exact chat messages help most.
- **Security problems** (a way around permissions or limits): report them privately, as [SECURITY.md](SECURITY.md)
  explains.

## Building

You need JDK 25. Point `JAVA_HOME` at it, then:

    ./gradlew build        # the jar lands in build/libs

## Testing

    ./gradlew test                  # unit tests, including the docs checks
    ./gradlew runClientGameTest     # in-game tests, every guide example included
    python3 -m unittest discover -s docs/images/brand/generator   # brand image scripts
    python3 -m unittest discover -s .github/scripts               # release script
    python3 docs/images/brand/generator/generate.py --check       # brand images match their scripts

The in-game tests start a real Minecraft client and open its window. On Linux with Xvfb installed they run on a
virtual display instead (`-Pheadless=true` forces it, `-Pheadless=false` shows the window). Headless runs draw with
Mesa's software Vulkan, so install both: on Debian or Ubuntu, `sudo apt install xvfb mesa-vulkan-drivers`. CI runs
everything on every pull request, so it's fine to rely on it if the in-game tests won't run on your machine.

When something visible changes:

    ./gradlew updateScreenshots                         # re-capture the guide's screenshots in docs/images/screens
    python3 docs/images/brand/generator/generate.py     # re-render the brand images after editing their scripts

## Where things are

- `src/main/java/net/slk/bashcraft/`
  - `chain` (the parser), `exec` (the executor), `pipe` and `config` are plain Java: they never import Minecraft or
    Fabric, which is what lets the unit tests cover them.
  - `mc`, `command`, `client`, `mixin` and `network` are the Minecraft side.
- `src/test` holds the unit tests and `src/gametest` the in-game tests.
- `src/docs` is the parser for the guide's examples, shared by both.
- `docs/guide` is the guide; `docs/images/brand/generator` draws the brand art from Minecraft's own textures.

## The guide's "Give it a try!" examples

Every example block in the guide and the README is run in a real game by the in-game tests, one fresh creative world
per file, and any red message fails the build. So each block:

- has one command per line, each starting with `/`;
- uses no chat commands (`say`, `msg`, `me`, `tell` …) inside a typed chain, since BashCraft refuses those;
- has no `#` comments, which BashCraft doesn't support;
- sleeps at most 100 ticks per line;
- is marked `<!-- try: errors expected -->` on the line before its fence when it's meant to show an error.

A block looks like this in Markdown:

    **Give it a try!**

    ```text
    /sh echo hello && echo world
    ```

## Changes

- Add a line under `## Unreleased` in [CHANGELOG.md](CHANGELOG.md) for anything players or admins will notice.
  The release workflow turns that section into the next version's notes.
- Minecraft, Fabric and Gradle version bumps are done by the maintainer.
- Contributions are under the [MIT license](LICENSE), like the rest of the code.
