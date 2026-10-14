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

    /** Any GitHub URL naming this repo's owner, or any raw.githubusercontent.com URL. */
    private static final Pattern REPO_URL = Pattern.compile("https://(?:raw\\.githubusercontent\\.com|github\\.com/lsalik2)/[^)\\s\"'>]*");
    /** The forms a link to this repo may take: its home, its issues, private reports, or a file or folder on main. */
    private static final Pattern GOOD = Pattern.compile(
            "https://github\\.com/lsalik2/bashcraft(?:/issues|/security/advisories/new)?|https://(?:raw\\.githubusercontent\\.com/lsalik2/bashcraft/main|github\\.com/lsalik2/bashcraft/(?:blob|tree)/main)/(.+)");
    /** A workflow's page or status badge. */
    private static final Pattern WORKFLOW = Pattern.compile("https://github\\.com/lsalik2/bashcraft/actions/workflows/([\\w.-]+\\.yml)(?:/badge\\.svg)?");

    private DocsLinks() {}

    /** URLs into this repo that use the wrong owner, name or branch, or point at a file that doesn't exist. */
    static List<String> badRepoUrls(String name, String text, Path root) {
        List<String> bad = new ArrayList<>();
        Matcher m = REPO_URL.matcher(text);
        while (m.find()) {
            String url = m.group().replaceFirst("#.*$", "");
            Matcher workflow = WORKFLOW.matcher(url);
            if (workflow.matches()) {
                if (!Files.exists(root.resolve(".github/workflows").resolve(workflow.group(1)))) {
                    bad.add(name + ": " + url + " names a missing workflow");
                }
                continue;
            }
            Matcher good = GOOD.matcher(url);
            if (!good.matches()) {
                bad.add(name + ": " + url + " is not a main-branch link to this repo");
            } else if (good.group(1) != null && !Files.exists(root.resolve(good.group(1)))) {
                bad.add(name + ": " + url + " points at a missing file");
            }
        }
        return bad;
    }

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
