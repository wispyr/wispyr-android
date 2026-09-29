#!/usr/bin/env python3
"""Telegram -> Wispyr rebranding (contents + file/directory names).

Usage: python3 scripts/rebrand.py [--dry-run]

Submodules (third-party code) and server/protocol identifiers are left untouched.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SELF = os.path.relpath(os.path.abspath(__file__), ROOT)
DRY = "--dry-run" in sys.argv

OLD = "tele" + "gram"
OLD_KO = "텔레" + "그램"

WORD = re.compile(OLD, re.IGNORECASE)

# Lines kept verbatim (GPL copyright / attribution notices).
KEEP_LINE = re.compile(r"source code of " + OLD + r"|copyright.*" + OLD, re.IGNORECASE)

# Spans kept verbatim: they must match what Telegram servers, Mini Apps or shared
# theme files expect.
PROTECT = re.compile("|".join([
    r"(?:[a-z0-9-]+\.)*" + OLD + r"\.(?:org|me|dog)\b",          # domains, e-mails
    r"github\.com/[^\s)\"'>]*",                                  # upstream repo links
    r"window\.Telegram\b", r"Telegram\.Web(?:View|App)\b",        # Mini App JS API
    r"TelegramWebview\w*",                                       # JS bridge names
    r"(?-i:[\"']" + OLD + r"_\w*[\"'])",                          # webpage types, product ids, theme keys
    r"(?-i:[\"']" + OLD + r"(?:passport)?[\"'])",                 # server usernames / config values
    OLD + r"_?antispam\w*",                                      # app config keys
]), re.IGNORECASE)

SKIP_EXT = {".attheme"}

KO_RULES = [
    (re.compile(OLD_KO + r"(\*\*)?이라"), r"위스퍼\1라"),
    (re.compile(OLD_KO + r"(\*\*)?으로"), r"위스퍼\1로"),
    (re.compile(OLD_KO + r"(\*\*)?을"), r"위스퍼\1를"),
    (re.compile(OLD_KO + r"(\*\*)?은"), r"위스퍼\1는"),
    (re.compile(OLD_KO + r"(\*\*)?과"), r"위스퍼\1와"),
    (re.compile(OLD_KO + r"(\*\*)?이(?=[\s<.,!?])"), r"위스퍼\1가"),
    (re.compile(OLD_KO), "위스퍼"),
]


def case_map(m):
    s = m.group(0)
    if s.islower():
        return "wispyr"
    if s.isupper():
        return "WISPYR"
    return "Wispyr"


def replace_line(line):
    if KEEP_LINE.search(line):
        return line
    parts, last = [], 0
    for m in PROTECT.finditer(line):
        parts.append(WORD.sub(case_map, line[last:m.start()]))
        parts.append(m.group(0))
        last = m.end()
    parts.append(WORD.sub(case_map, line[last:]))
    out = "".join(parts)
    for rx, rep in KO_RULES:
        out = rx.sub(rep, out)
    return out


def tracked_files():
    out = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    submodules = set(subprocess.check_output(
        ["git", "config", "--file", ".gitmodules", "--get-regexp", r"\.path$"], cwd=ROOT
    ).decode().split()[1::2])
    for f in out.decode().split("\0"):
        if f and f not in submodules and f != SELF:
            yield f


def main():
    files = list(tracked_files())
    changed = 0
    for f in files:
        path = os.path.join(ROOT, f)
        if os.path.splitext(f)[1].lower() in SKIP_EXT or not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8", newline="") as fh:
                text = fh.read()
        except UnicodeDecodeError:
            continue
        if not WORD.search(text) and OLD_KO not in text:
            continue
        new = "".join(replace_line(l) for l in text.splitlines(keepends=True))
        if new != text:
            changed += 1
            if not DRY:
                with open(path, "w", encoding="utf-8", newline="") as fh:
                    fh.write(new)
    print(f"contents changed: {changed} files")

    # Rename deepest paths first so parent renames don't invalidate children.
    paths = set()
    for f in files:
        parts = f.split("/")
        for i in range(1, len(parts) + 1):
            if WORD.search(parts[i - 1]):
                paths.add("/".join(parts[:i]))
    renamed = 0
    for p in sorted(paths, key=lambda x: x.count("/"), reverse=True):
        head, name = os.path.split(p)
        src = os.path.join(ROOT, p)
        dst = os.path.join(ROOT, head, WORD.sub(case_map, name))
        if os.path.exists(src):
            renamed += 1
            if not DRY:
                os.rename(src, dst)
    print(f"paths renamed: {renamed}")


if __name__ == "__main__":
    main()
