# Release checklist

For publishing a new BashCraft version by hand. Written for 1.0.0; for later versions, change the version number.

1. **Check everything is green.**
   - `python3 branding/generate.py --check` (the brand images match their scripts)
   - `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew test runClientGameTest` (unit tests, docs checks, every guide
     snippet in game)
2. **Refresh the screenshots** if anything visible changed:
   `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew updateScreenshots`, look at `docs/images/screens/`, commit.
3. **Merge to `main` and push.** The listing's images, `fabric.mod.json`'s homepage and every guide link point at
   `main` on GitHub, so the release has to be there before anything is published.
4. **Check what goes public.** `docs/superpowers/` (the design history: specs and plans) is kept on the development
   machine only and ignored by git; `git ls-files docs/superpowers` must print nothing.
5. **Make the repository public** (GitHub → Settings → General → Danger Zone → Change visibility), so the listing's
   images and links work.
6. **Build and smoke-test the jar.**
   - `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 ./gradlew build` → `build/libs/bashcraft-1.0.0.jar` (it carries `LICENSE_bashcraft`).
   - Start the dev server (port 25569) with it, join from a client as an operator, and run the README's quick start.
7. **Modrinth** — create the project with the fields in [listing.md](listing.md): name, summary, icon
   (`docs/images/brand/icon.png`), categories, environment (server required, client optional), links, license MIT.
   Paste the description. Upload the gallery in the listed order with its titles and captions. Create version
   1.0.0: the jar, Minecraft 26.3, Fabric loader, a required dependency on Fabric API, release channel, and the
   1.0.0 section of `CHANGELOG.md` as the changelog. Submit for review.
8. **CurseForge** — the same fields: the project, the description, the gallery, then the 1.0.0 file (Minecraft 26.3,
   Fabric, Java 25, required dependency Fabric API, release type, changelog).
9. **Tag and release on GitHub.**
   - `git tag v1.0.0 && git push origin v1.0.0`
   - Create a GitHub release for `v1.0.0` with the jar attached and the changelog section as its notes.
10. **After both sites approve,** add their download links to the README's Install section.
