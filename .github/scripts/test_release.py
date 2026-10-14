import pathlib
import subprocess
import sys
import tempfile
import unittest

import release

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = pathlib.Path(__file__).resolve().parent / "release.py"

CHANGELOG = """# Changelog

## Unreleased

**Fixes**
- `each` runs builtins without red in chat.

## 1.0.0 — first release

**Chains**
- `&&`, `||` and `;`.
"""

PROPERTIES = """org.gradle.jvmargs=-Xmx1G

# Mod Properties
mod_version=1.0.0
maven_group=net.slk.bashcraft
"""


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.write(CHANGELOG, PROPERTIES)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, changelog, properties):
        (self.root / "CHANGELOG.md").write_bytes(changelog.encode())
        (self.root / "gradle.properties").write_bytes(properties.encode())

    def files(self):
        return ((self.root / "CHANGELOG.md").read_bytes(), (self.root / "gradle.properties").read_bytes(),
                (self.root / "build" / "release-notes.md").exists())

    def test_prepare_renames_unreleased_and_bumps_the_version(self):
        notes = release.prepare(self.root, "1.1.0", "2026-11-02")
        self.assertEqual("**Fixes**\n- `each` runs builtins without red in chat.\n", notes)
        self.assertEqual(notes, (self.root / "build" / "release-notes.md").read_text())
        self.assertEqual(CHANGELOG.replace("## Unreleased\n", "## Unreleased\n\n## 1.1.0 — 2026-11-02\n", 1),
                         (self.root / "CHANGELOG.md").read_text())
        self.assertEqual(PROPERTIES.replace("mod_version=1.0.0", "mod_version=1.1.0"),
                         (self.root / "gradle.properties").read_text())

    def test_bad_versions_are_refused_without_changes(self):
        before = self.files()
        for bad in ["v1.1.0", " 1.1.0", "1.1", "1.1.0-beta", "1.0.1; rm -rf ~", ""]:
            with self.subTest(bad=bad):
                with self.assertRaisesRegex(release.ReleaseError, r"like 1\.2\.3"):
                    release.prepare(self.root, bad, "2026-11-02")
        self.assertEqual(before, self.files())

    def test_version_must_be_newer_numerically(self):
        self.write(CHANGELOG, PROPERTIES.replace("1.0.0", "1.9.0"))
        before = self.files()
        for old in ["1.9.0", "1.8.5", "0.10.0"]:
            with self.subTest(old=old):
                with self.assertRaisesRegex(release.ReleaseError, "newer than 1.9.0"):
                    release.prepare(self.root, old, "2026-11-02")
        self.assertEqual(before, self.files())
        release.prepare(self.root, "1.10.0", "2026-11-02")
        self.assertIn(b"mod_version=1.10.0", (self.root / "gradle.properties").read_bytes())

    def test_empty_unreleased_is_refused(self):
        self.write(CHANGELOG.replace("**Fixes**\n- `each` runs builtins without red in chat.\n", "\n  \n"), PROPERTIES)
        before = self.files()
        with self.assertRaisesRegex(release.ReleaseError, "Unreleased section is empty"):
            release.prepare(self.root, "1.1.0", "2026-11-02")
        self.assertEqual(before, self.files())

    def test_missing_unreleased_is_refused(self):
        self.write(CHANGELOG.replace("## Unreleased\n", ""), PROPERTIES)
        with self.assertRaisesRegex(release.ReleaseError, "no ## Unreleased"):
            release.prepare(self.root, "1.1.0", "2026-11-02")

    def test_an_existing_version_section_is_refused(self):
        self.write(CHANGELOG.replace("## 1.0.0 — first release", "## 1.1.0 — 2026-10-30"), PROPERTIES)
        before = self.files()
        with self.assertRaisesRegex(release.ReleaseError, "already has a 1.1.0 section"):
            release.prepare(self.root, "1.1.0", "2026-11-02")
        self.assertEqual(before, self.files())

    def test_crlf_changelog_keeps_its_line_endings(self):
        self.write(CHANGELOG.replace("\n", "\r\n"), PROPERTIES.replace("\n", "\r\n"))
        notes = release.prepare(self.root, "1.1.0", "2026-11-02")
        self.assertEqual("**Fixes**\n- `each` runs builtins without red in chat.\n", notes)
        changelog = (self.root / "CHANGELOG.md").read_bytes()
        self.assertIn(b"## Unreleased\r\n\r\n## 1.1.0 \xe2\x80\x94 2026-11-02\r\n", changelog)
        self.assertNotIn(b"\n", changelog.replace(b"\r\n", b""))
        self.assertIn(b"mod_version=1.1.0\r\n", (self.root / "gradle.properties").read_bytes())

    def test_mod_version_with_spaces_is_read_and_replaced(self):
        self.write(CHANGELOG, PROPERTIES.replace("mod_version=1.0.0", "mod_version = 1.0.0"))
        release.prepare(self.root, "1.0.1", "2026-11-02")
        self.assertIn(b"mod_version=1.0.1\n", (self.root / "gradle.properties").read_bytes())

    def test_changelog_without_final_newline(self):
        self.write(CHANGELOG.replace("**Fixes**\n- `each` runs builtins without red in chat.\n\n## 1.0.0 — first release\n\n**Chains**\n- `&&`, `||` and `;`.\n",
                                     "- last line"), PROPERTIES)
        self.assertEqual("- last line\n", release.prepare(self.root, "1.1.0", "2026-11-02"))
        self.assertTrue((self.root / "CHANGELOG.md").read_text().endswith("## 1.1.0 — 2026-11-02\n\n- last line"))

    def test_notes_reads_any_released_section(self):
        self.assertEqual("**Chains**\n- `&&`, `||` and `;`.\n", release.notes(self.root, "1.0.0"))
        with self.assertRaisesRegex(release.ReleaseError, "no 2.0.0 section"):
            release.notes(self.root, "2.0.0")

    def test_notes_of_the_real_first_release(self):
        text = release.notes(REPO, "1.0.0")
        self.assertTrue(text.startswith("**Chains**\n"))
        self.assertIn("**Safety**", text)
        self.assertNotIn("## ", text)

    def test_command_line_reports_refusals_on_stderr(self):
        run = subprocess.run([sys.executable, str(SCRIPT), "prepare", "v1.1.0", "--date", "2026-11-02",
                              "--root", str(self.root)], capture_output=True, text=True)
        self.assertEqual(1, run.returncode)
        self.assertTrue(run.stderr.startswith("release: "), run.stderr)
        ok = subprocess.run([sys.executable, str(SCRIPT), "notes", "1.0.0", "--root", str(self.root)],
                            capture_output=True, text=True)
        self.assertEqual((0, "**Chains**\n- `&&`, `||` and `;`.\n"), (ok.returncode, ok.stdout))


if __name__ == "__main__":
    unittest.main()
