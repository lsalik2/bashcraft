package net.slk.bashcraft.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The docs' snippets and links (spec D §6): the contract for every "Give it a try!" and no broken link or image. */
class DocsTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    @Test
    void parsesSnippetsAndTheErrorsExpectedMarker() {
        List<GuideSnippets.Snippet> snippets = GuideSnippets.parse("page.md", lines("""
                Intro text.

                **Give it a try!**

                ```text
                /sh echo hi

                /sh echo there
                ```

                **Give it a try!**
                <!-- try: errors expected -->
                ```text
                /sh say no
                ```
                """));
        assertEquals(List.of(
                new GuideSnippets.Snippet("page.md", 3, List.of("/sh echo hi", "/sh echo there"), false),
                new GuideSnippets.Snippet("page.md", 11, List.of("/sh say no"), true)), snippets);
    }

    @Test
    void malformedSnippetsNameTheirLine() {
        assertEquals("page.md:2: \"Give it a try!\" must be followed by a ```text block",
                assertThrows(IllegalArgumentException.class, () -> GuideSnippets.parse("page.md", lines("x\n**Give it a try!**\n```\n/sh a\n```"))).getMessage());
        assertEquals("page.md:1: unclosed ```text block",
                assertThrows(IllegalArgumentException.class, () -> GuideSnippets.parse("page.md", lines("**Give it a try!**\n```text\n/sh a"))).getMessage());
        assertEquals("page.md:1: empty ```text block",
                assertThrows(IllegalArgumentException.class, () -> GuideSnippets.parse("page.md", lines("**Give it a try!**\n```text\n```"))).getMessage());
    }

    @Test
    void flagsWhatBreaksTheSnippetContract() {
        GuideSnippets.Snippet bad = new GuideSnippets.Snippet("p.md", 1, List.of(
                "sh echo no slash", "/sh echo hi && say hi", "/sh alias x = 'msg @a hi'", "/sh echo a # note",
                "/sh sleep 6s ; echo late", "/sh locate structure #village | tp @s"), false);
        assertEquals(List.of(
                "p.md:1: line 1 must start with /: sh echo no slash",
                "p.md:1: line 2 uses say in a typed chain (refused there): /sh echo hi && say hi",
                "p.md:1: line 3 uses msg in a typed chain (refused there): /sh alias x = 'msg @a hi'",
                "p.md:1: line 4 uses # as a comment (chains have no comments): /sh echo a # note",
                "p.md:1: line 5 sleeps 120 ticks (keep sleeps to 100 ticks a line): /sh sleep 6s ; echo late"),
                GuideSnippets.problems(bad));
        GuideSnippets.Snippet refusal = new GuideSnippets.Snippet("p.md", 1, List.of("/sh say hi"), true);
        assertEquals(List.of(), GuideSnippets.problems(refusal), "a snippet that shows the refusal may use say");
        assertTrue(new GuideSnippets.Snippet("p.md", 1, List.of("/sh sleep 1s"), false).sleeps());
    }

    @Test
    void linkCheckSkipsCodeAndExternalUrls() throws IOException {
        Path dir = Files.createTempDirectory("docs");
        Files.writeString(dir.resolve("there.md"), "x");
        assertEquals(List.of("page.md:2: missing.md"), DocsLinks.broken(dir.resolve("page.md"), lines("""
                [ok](there.md#part) [web](https://example.com) [top](#top)
                ![image](missing.md)
                ```text
                [not a link](nowhere.md)
                ```
                """), dir));
    }

    @Test
    void everyDocSnippetKeepsTheContract() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : GuideSnippets.docFiles(ROOT)) {
            for (GuideSnippets.Snippet snippet : GuideSnippets.parse(ROOT.relativize(file).toString(), Files.readAllLines(file))) {
                problems.addAll(GuideSnippets.problems(snippet));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void everyRelativeLinkAndImageResolves() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path file : GuideSnippets.docFiles(ROOT)) {
            broken.addAll(DocsLinks.broken(file, Files.readAllLines(file), ROOT));
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void repoUrlsPointAtRepoFiles() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path file : GuideSnippets.docFiles(ROOT)) {
            bad.addAll(DocsLinks.badRepoUrls(ROOT.relativize(file).toString(), Files.readString(file), ROOT));
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void guidePagesLinkToTheirNeighbours() throws IOException {
        Path guide = ROOT.resolve("docs/guide");
        if (!Files.isDirectory(guide)) {
            return;
        }
        List<Path> pages;
        try (var files = Files.list(guide)) {
            pages = files.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.md")).sorted().toList();
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String text = Files.readString(pages.get(i));
            for (int j : new int[] {i - 1, i + 1}) {
                if (j >= 0 && j < pages.size() && !text.contains("](" + pages.get(j).getFileName() + ")")) {
                    missing.add(pages.get(i).getFileName() + " -> " + pages.get(j).getFileName());
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void sleepsAreCappedPerLineAndReadUpToTheNextOperator() {
        assertEquals(List.of("p.md:1: line 1 sleeps 4000 ticks (keep sleeps to 100 ticks a line): /sh sleep 200s;echo x"),
                GuideSnippets.problems(new GuideSnippets.Snippet("p.md", 1, List.of("/sh sleep 200s;echo x"), false)));
        assertEquals(List.of("p.md:1: line 1 sleeps 300 ticks (keep sleeps to 100 ticks a line): /sh sleep 5s ; sleep 5s ; sleep 5s ; echo x"),
                GuideSnippets.problems(new GuideSnippets.Snippet("p.md", 1, List.of("/sh sleep 5s ; sleep 5s ; sleep 5s ; echo x"), false)));
        assertEquals(List.of(), GuideSnippets.problems(new GuideSnippets.Snippet("p.md", 1, List.of("/sh echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1"), false)));
    }

    @Test
    void repoUrlCheckCatchesWrongBranchesTyposAndMissingFiles() throws IOException {
        Path root = Files.createTempDirectory("repo");
        Files.createDirectories(root.resolve("docs/images"));
        Files.writeString(root.resolve("docs/images/a.png"), "x");
        Files.writeString(root.resolve("docs/guide.md"), "x");
        String text = String.join("\n",
                "![ok](https://raw.githubusercontent.com/lsalik2/bashcraft/main/docs/images/a.png)",
                "[ok](https://github.com/lsalik2/bashcraft/blob/main/docs/guide.md#part) [ok](https://github.com/lsalik2/bashcraft) [ok](https://github.com/lsalik2/bashcraft/issues)",
                "![branch](https://raw.githubusercontent.com/lsalik2/bashcraft/master/docs/images/a.png)",
                "![typo](https://raw.githubusercontent.com/lsalik2/bashcarft/main/docs/images/a.png)",
                "![missing](https://raw.githubusercontent.com/lsalik2/bashcraft/main/docs/images/b.png)");
        assertEquals(List.of(
                "listing.md: https://raw.githubusercontent.com/lsalik2/bashcraft/master/docs/images/a.png is not a main-branch link to this repo",
                "listing.md: https://raw.githubusercontent.com/lsalik2/bashcarft/main/docs/images/a.png is not a main-branch link to this repo",
                "listing.md: https://raw.githubusercontent.com/lsalik2/bashcraft/main/docs/images/b.png points at a missing file"),
                DocsLinks.badRepoUrls("listing.md", text, root));
    }
}
