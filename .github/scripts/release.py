#!/usr/bin/env python3
"""Changelog and version edits for a BashCraft release (spec E §3). Standard library only.

    release.py prepare VERSION --date YYYY-MM-DD [--root DIR]
        CHANGELOG.md's "## Unreleased" becomes "## VERSION — DATE" under a fresh, empty Unreleased section;
        gradle.properties' mod_version becomes VERSION; the section's text goes to build/release-notes.md.
    release.py notes VERSION [--root DIR]
        Prints the text of a released version's section.

Either exits 1 with "release: <reason>" on stderr, having changed nothing.
"""
import argparse
import pathlib
import re
import sys

VERSION = re.compile(r"\d+\.\d+\.\d+")
MOD_VERSION = re.compile(r"^mod_version\s*=\s*(\S+)\s*$")


class ReleaseError(Exception):
    pass


def _lines(path):
    """The file's lines without their endings, and the ending it uses ("\\r\\n" or "\\n")."""
    text = path.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in text else "\n"
    return text.split(newline), newline


def _parse(version):
    if not VERSION.fullmatch(version):
        raise ReleaseError(f"{version!r} is not a version like 1.2.3 (digits only, no v)")
    return tuple(int(part) for part in version.split("."))


def _headings(lines):
    return [i for i, line in enumerate(lines) if line.startswith("## ")]


def _section(lines, start):
    """The body of the section whose heading is lines[start], without leading or trailing blank lines."""
    end = next((i for i in _headings(lines) if i > start), len(lines))
    body = lines[start + 1:end]
    while body and not body[0].strip():
        body.pop(0)
    while body and not body[-1].strip():
        body.pop()
    return body


def _find(lines, version):
    for i in _headings(lines):
        if lines[i] == f"## {version}" or lines[i].startswith(f"## {version} "):
            return i
    return None


def notes(root, version):
    lines, _ = _lines(pathlib.Path(root) / "CHANGELOG.md")
    start = _find(lines, version)
    if start is None:
        raise ReleaseError(f"CHANGELOG.md has no {version} section")
    return "\n".join(_section(lines, start)) + "\n"


def prepare(root, version, date):
    root = pathlib.Path(root)
    new = _parse(version)
    props_path, changelog_path = root / "gradle.properties", root / "CHANGELOG.md"

    props, props_newline = _lines(props_path)
    current = next((m.group(1) for m in map(MOD_VERSION.match, props) if m), None)
    if current is None:
        raise ReleaseError("gradle.properties has no mod_version")
    if new <= _parse(current):
        raise ReleaseError(f"{version} is not newer than {current}")

    lines, newline = _lines(changelog_path)
    unreleased = next((i for i in _headings(lines) if lines[i].strip() == "## Unreleased"), None)
    if unreleased is None:
        raise ReleaseError("CHANGELOG.md has no ## Unreleased section")
    if _find(lines, version) is not None:
        raise ReleaseError(f"CHANGELOG.md already has a {version} section")
    body = _section(lines, unreleased)
    if not body:
        raise ReleaseError("the changelog's Unreleased section is empty: add what changed first")

    lines[unreleased:unreleased + 1] = ["## Unreleased", "", f"## {version} — {date}"]
    props = [f"mod_version={version}" if MOD_VERSION.match(line) else line for line in props]
    text = "\n".join(body) + "\n"

    changelog_path.write_bytes(newline.join(lines).encode("utf-8"))
    props_path.write_bytes(props_newline.join(props).encode("utf-8"))
    (root / "build").mkdir(exist_ok=True)
    (root / "build" / "release-notes.md").write_bytes(text.encode("utf-8"))
    return text


def main(argv):
    parser = argparse.ArgumentParser(prog="release.py")
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("prepare")
    p.add_argument("version")
    p.add_argument("--date", required=True)
    p.add_argument("--root", default=".")
    n = commands.add_parser("notes")
    n.add_argument("version")
    n.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            prepare(args.root, args.version, args.date)
        else:
            sys.stdout.write(notes(args.root, args.version))
    except ReleaseError as e:
        print(f"release: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
