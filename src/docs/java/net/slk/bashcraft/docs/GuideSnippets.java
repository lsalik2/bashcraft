package net.slk.bashcraft.docs;

import net.slk.bashcraft.exec.ExpansionException;
import net.slk.bashcraft.exec.SleepTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The docs' "Give it a try!" snippets (spec D §5–6): a line {@code **Give it a try!**}, optionally
 * {@code <!-- try: errors expected -->}, then a {@code ```text} fence holding chat commands, one per line.
 */
public final class GuideSnippets {
    public static final String HEADING = "**Give it a try!**";
    public static final String ERRORS_EXPECTED = "<!-- try: errors expected -->";
    private static final Pattern CHAT_COMMAND = Pattern.compile(
            "(?:^/sh\\s+|&&|\\|\\||;|\\||\\(|=\\s*'?)\\s*/?(say|msg|me|teammsg|tell|w)(?=\\s|$|')");
    private static final Pattern COMMENT = Pattern.compile("(?:^|\\s)#(?:\\s|$)");
    private static final Pattern SLEEP = Pattern.compile("(?:^|[\\s;&|(/])sleep\\s+([^\\s;&|)]+)");
    private static final int MAX_SLEEP_TICKS = 100;

    /** One snippet: its file, the 1-based line of its heading, its commands, and whether it should show an error. */
    public record Snippet(String file, int line, List<String> commands, boolean errorsExpected) {
        public boolean sleeps() {
            return commands.stream().anyMatch(c -> SLEEP.matcher(c).find());
        }
    }

    private GuideSnippets() {}

    /** README.md, the guide pages, the publishing files and CHANGELOG.md — the ones that exist — in a fixed order. */
    public static List<Path> docFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(root.resolve("README.md"));
        for (String dir : List.of("docs/guide", "docs/publishing")) {
            Path d = root.resolve(dir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    s.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
                }
            }
        }
        files.add(root.resolve("CHANGELOG.md"));
        return files.stream().filter(Files::exists).toList();
    }

    /** Every snippet in a Markdown file, in order; a malformed one throws, naming file:line. */
    public static List<Snippet> parse(String file, List<String> lines) {
        List<Snippet> snippets = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().equals(HEADING)) {
                continue;
            }
            int j = i + 1;
            while (j < lines.size() && lines.get(j).isBlank()) {
                j++;
            }
            boolean errorsExpected = j < lines.size() && lines.get(j).strip().equals(ERRORS_EXPECTED);
            if (errorsExpected) {
                j++;
            }
            if (j >= lines.size() || !lines.get(j).strip().equals("```text")) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": \"Give it a try!\" must be followed by a ```text block");
            }
            List<String> commands = new ArrayList<>();
            int k = j + 1;
            while (k < lines.size() && !lines.get(k).strip().equals("```")) {
                if (!lines.get(k).isBlank()) {
                    commands.add(lines.get(k).strip());
                }
                k++;
            }
            if (k >= lines.size()) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": unclosed ```text block");
            }
            if (commands.isEmpty()) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": empty ```text block");
            }
            snippets.add(new Snippet(file, i + 1, List.copyOf(commands), errorsExpected));
            i = k;
        }
        return snippets;
    }

    /** What breaks the snippet contract, one sentence each; empty when the snippet is fine. */
    public static List<String> problems(Snippet snippet) {
        List<String> problems = new ArrayList<>();
        for (int n = 0; n < snippet.commands().size(); n++) {
            String command = snippet.commands().get(n);
            String where = snippet.file() + ":" + snippet.line() + ": line " + (n + 1);
            if (!command.startsWith("/")) {
                problems.add(where + " must start with /: " + command);
                continue;
            }
            Matcher chat = CHAT_COMMAND.matcher(command);
            if (!snippet.errorsExpected() && chat.find()) {
                problems.add(where + " uses " + chat.group(1) + " in a typed chain (refused there): " + command);
            }
            if (COMMENT.matcher(command).find()) {
                problems.add(where + " uses # as a comment (chains have no comments): " + command);
            }
            // A reader pastes lines one at a time, so each line must be done (and its errors shown) within the cap.
            long ticks = 0;
            Matcher sleep = SLEEP.matcher(command);
            while (sleep.find()) {
                try {
                    ticks += SleepTime.ticks(sleep.group(1));
                } catch (ExpansionException e) {
                    // A variable or other expanded time: the in-game test still runs it.
                }
            }
            if (ticks > MAX_SLEEP_TICKS) {
                problems.add(where + " sleeps " + ticks + " ticks (keep sleeps to " + MAX_SLEEP_TICKS + " ticks a line): " + command);
            }
        }
        return problems;
    }
}
