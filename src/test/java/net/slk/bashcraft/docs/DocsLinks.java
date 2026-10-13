package net.slk.bashcraft.docs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Relative links and images in a Markdown file that don't resolve (code blocks and URLs are skipped). */
final class DocsLinks {
    private static final Pattern LINK = Pattern.compile("!?\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)|<img[^>]*\\ssrc=\"([^\"]+)\"");

    private DocsLinks() {}

    static List<String> broken(Path file, List<String> lines, Path root) {
        List<String> broken = new ArrayList<>();
        boolean inCode = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                continue;
            }
            Matcher m = LINK.matcher(line);
            while (m.find()) {
                String target = m.group(1) != null ? m.group(1) : m.group(2);
                if (target.matches("[a-z]+:.*") || target.startsWith("#")) {
                    continue;
                }
                String path = target.replaceFirst("#.*$", "");
                if (!Files.exists(file.getParent().resolve(path).normalize())) {
                    broken.add(root.relativize(file.toAbsolutePath()) + ":" + (i + 1) + ": " + target);
                }
            }
        }
        return broken;
    }
}
