#!/usr/bin/env python3
"""Rotate the oldest entries out of docs/changelog.md into docs/changelog/<YYYY-MM>.md.

Run this when ChangelogRotationTest fails, or to make room before adding a long
entry:

    python scripts/rotate-changelog.py            # rotate down to the target
    python scripts/rotate-changelog.py --check    # report only, change nothing

Why a script rather than a paragraph of instructions: the mechanical part is
easy to get wrong in a way nothing catches. Archived text moves one directory
deeper, so every relative link in it needs one more `../` — except the ones
inside code spans and fenced blocks, which are documentation *of* link syntax
(the `![alt](uri)` rows in the output-format tables) and must be left alone. A
hand-rotation that misses either half produces broken links or corrupted
examples, and both survive review easily.

The Archive table in docs/changelog.md is regenerated from what is on disk, so
it cannot drift from the files it points at.
"""
import argparse
import collections
import datetime
import io
import os
import re
import sys

LIVE = os.path.join("docs", "changelog.md")
ARCHIVE_DIR = os.path.join("docs", "changelog")

# Must match ChangelogRotationTest.LIVE_CAP_BYTES.
CAP_BYTES = 250 * 1024
# Rotate down to this, so the next few entries fit without another rotation.
TARGET_BYTES = 200 * 1024

REGISTERS = ("## Decision Log", "## Regression Notes")
DATE = re.compile(r"\((\d{4})-(\d{2})-\d{2}")
ARCHIVE_ROW = re.compile(r"^\| \[.*?\]\(changelog/\d{4}-\d{2}\.md\) \|.*\|$", re.MULTILINE)

LINK = re.compile(r"\]\((?!https?://|#|mailto:|<http)([^)]+)\)")
FENCE = re.compile("(?ms)^```.*?^```")
SPAN = re.compile("`[^`\n]*`")
NUL = chr(0)
TOKEN = re.compile(NUL + r"(\d+)" + NUL)


def redepth(body):
    """Add one '../' to each relative link, leaving code spans and fences alone."""
    vault = []

    def stash(m):
        vault.append(m.group(0))
        return NUL + str(len(vault) - 1) + NUL

    masked = SPAN.sub(stash, FENCE.sub(stash, body))

    def fix(m):
        target = m.group(1)
        if target.startswith("/"):
            return m.group(0)  # repo-root-relative: depth does not affect it
        return "](../" + target + ")"

    return TOKEN.sub(lambda m: vault[int(m.group(1))], LINK.sub(fix, masked))


def read(path):
    return io.open(path, encoding="utf-8").read()


def write(path, text):
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)


def normalised_size(path):
    """Byte length with CRLF normalised to LF — what git stores, and what
    ChangelogRotationTest measures.

    Markdown has no `eol` setting in .gitattributes, so a Windows checkout is
    CRLF and Linux is LF: about 5% apart on a file this size, for identical
    content. Measuring the working copy would make the cap and the Archive
    table's sizes differ per platform, so the table would churn on every
    rotation depending on who ran it.
    """
    return len(read(path).replace("\r\n", "\n").encode("utf-8"))


def split_sections(text):
    """(header, [(heading, body)], [(register_heading, body)])."""
    lines = text.split("\n")
    starts = [i for i, l in enumerate(lines) if l.startswith("## ")]
    if not starts:
        sys.exit("docs/changelog.md has no '## ' sections — nothing to rotate.")

    # The header runs to the '---' that closes it, which is where entries begin.
    # Sections above that rule are header prose, not entries.
    first_entry = next(
        (i for i in starts if DATE.search(lines[i]) or lines[i].startswith(REGISTERS)),
        None,
    )
    if first_entry is None:
        sys.exit("docs/changelog.md has no dated entries — nothing to rotate.")

    header = "\n".join(lines[:first_entry]).rstrip() + "\n"

    blocks = []
    tail = [i for i in starts if i >= first_entry]
    for n, start in enumerate(tail):
        end = tail[n + 1] if n + 1 < len(tail) else len(lines)
        blocks.append((lines[start], "\n".join(lines[start:end]).rstrip() + "\n"))

    entries = [b for b in blocks if not b[0].startswith(REGISTERS)]
    registers = [b for b in blocks if b[0].startswith(REGISTERS)]
    return header, entries, registers


def month_of(heading, fallback):
    m = DATE.search(heading)
    return (m.group(1) + "-" + m.group(2)) if m else fallback


def rebuild_archive_table(header):
    """Regenerate the Archive table from the files actually on disk."""
    rows = []
    if os.path.isdir(ARCHIVE_DIR):
        for name in sorted(os.listdir(ARCHIVE_DIR), reverse=True):
            m = re.match(r"^(\d{4}-\d{2})\.md$", name)
            if not m:
                continue
            path = os.path.join(ARCHIVE_DIR, name)
            count = sum(1 for l in read(path).split("\n") if l.startswith("## "))
            pretty = datetime.date.fromisoformat(m.group(1) + "-01").strftime("%B %Y")
            rows.append("| [%s](changelog/%s) | %d | %.0f KB |"
                        % (pretty, name, count, normalised_size(path) / 1024))
    if not rows:
        return header
    if not ARCHIVE_ROW.search(header):
        sys.exit("Could not find the Archive table in docs/changelog.md — fix the header by hand.")
    first = ARCHIVE_ROW.search(header)
    end = list(ARCHIVE_ROW.finditer(header))[-1].end()
    return header[:first.start()] + "\n".join(rows) + header[end:]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="report only, change nothing")
    args = ap.parse_args()

    if not os.path.isfile("pom.xml"):
        sys.exit("Run this from the repository root.")

    size = normalised_size(LIVE)
    print(f"docs/changelog.md is {size:,} bytes "
          f"(cap {CAP_BYTES:,}, rotate target {TARGET_BYTES:,})")

    if args.check:
        print("OVER CAP — rotation needed" if size > CAP_BYTES else "under cap — no rotation needed")
        return 1 if size > CAP_BYTES else 0

    if size <= TARGET_BYTES:
        print("Already at or below the target; nothing to do.")
        return 0

    text = read(LIVE)
    header, entries, registers = split_sections(text)

    fixed = len(header.encode("utf-8")) + sum(len(b.encode("utf-8")) for _, b in registers)
    budget = TARGET_BYTES - fixed

    keep, move, used = [], [], 0
    for heading, body in entries:
        n = len(body.encode("utf-8"))
        if not move and used + n <= budget:
            keep.append((heading, body))
            used += n
        else:
            move.append((heading, body))

    if not move:
        print("Nothing to move.")
        return 0
    if not keep:
        sys.exit("The newest entry alone exceeds the target — split that entry, or raise TARGET_BYTES.")

    # Assign months, inheriting from the newest kept entry so an undated
    # continuation lands with the entry it continues.
    fallback = month_of(keep[-1][0], None)
    by_month = collections.OrderedDict()
    for heading, body in move:
        fallback = month_of(heading, fallback)
        if fallback is None:
            sys.exit("Cannot date entry: " + heading[:80])
        by_month.setdefault(fallback, []).append((heading, body))

    os.makedirs(ARCHIVE_DIR, exist_ok=True)
    for mo, items in by_month.items():
        path = os.path.join(ARCHIVE_DIR, mo + ".md")
        block = "\n".join(redepth(b) for _, b in items)
        if os.path.exists(path):
            # Newest-first ordering holds inside an archive too, so rotated
            # entries go above what is already there.
            existing = read(path)
            marker = "\n---\n\n"
            head, sep, rest = existing.partition(marker)
            body = head + sep + block + "\n" + rest if sep else existing + "\n" + block
        else:
            pretty = datetime.date.fromisoformat(mo + "-01").strftime("%B %Y")
            body = ("# Changelog — " + pretty + "\n\n"
                    "> Archived entries for " + pretty + ", newest first. "
                    "For recent work see [the live changelog](../changelog.md).\n\n"
                    "---\n\n" + block)
        write(path, body)
        print("  %-28s <- %d entries" % (path, len(items)))

    header = rebuild_archive_table(header)
    write(LIVE, header + "\n"
          + "\n".join(b for _, b in keep)
          + "\n---\n\n"
          + "\n".join(b for _, b in registers))

    print(f"moved {len(move)} entries; docs/changelog.md is now {normalised_size(LIVE):,} bytes")
    print("Remember to add docs/changelog/<new files> to docs/SUMMARY.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
