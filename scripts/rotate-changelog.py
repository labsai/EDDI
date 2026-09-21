#!/usr/bin/env python3
"""Rotate the oldest entries out of docs/changelog.md into docs/changelog/<YYYY-MM>.md.

The nightly collation job (`.github/workflows/changelog-collate.yml`) runs this
straight after `collate-changelog.py`, so in normal operation nobody has to.
Run it by hand when ChangelogRotationTest fails between collations, or to make
room before adding a long entry:

    python scripts/rotate-changelog.py            # rotate down to the target
    python scripts/rotate-changelog.py --check    # report only, change nothing

Two different thresholds, deliberately: rotation trims the live file back to
TARGET_BYTES (200 KB) whenever it is over that, while ChangelogRotationTest —
and `--check` — fail only at CAP_BYTES (250 KB). Rotating at the cap would mean
the session that wrote the entry pushing the file over it is the session told to
rotate, so the gap is the headroom that keeps that chore off the critical path.
The consequence is that a nightly run rotates a file that is under the cap, and
is supposed to.

Why a script rather than a paragraph of instructions: the mechanical part is
easy to get wrong in a way nothing catches. Archived text moves one directory
deeper, so every relative link in it needs one more `../` — except the ones
inside code spans and fenced blocks, which are documentation *of* link syntax
(the `![alt](uri)` rows in the output-format tables) and must be left alone. A
hand-rotation that misses either half produces broken links or corrupted
examples, and both survive review easily. That transform lives in
`changelog_common.py`, beside its inverse.

The Archive table in docs/changelog.md is regenerated from what is on disk, so
it cannot drift from the files it points at.
"""
import argparse
import collections
import os
import re
import sys

from changelog_common import (
    ARCHIVE_DIR,
    CAP_BYTES,
    LIVE,
    TARGET_BYTES,
    month_of,
    normalised_size,
    pretty_month,
    read,
    redepth,
    register_separator,
    require_repo_root,
    split_sections,
    write,
)

ARCHIVE_ROW = re.compile(r"^\| \[.*?\]\(changelog/\d{4}-\d{2}\.md\) \|.*\|$", re.MULTILINE)

SUMMARY = os.path.join("docs", "SUMMARY.md")
SUMMARY_ANCHOR = "- [Changelog](changelog.md)"
SUMMARY_ENTRY = re.compile(r"^\s+- \[.*?\]\(changelog/\d{4}-\d{2}\.md\)\s*$")
# Any indented child of the anchor, month archive or not — the "Pending entries"
# link is one of these, and must survive being regenerated around.
SUMMARY_CHILD = re.compile(r"^\s+- \[")


def archives_on_disk():
    """Every archive file, newest month first."""
    if not os.path.isdir(ARCHIVE_DIR):
        return []
    found = []
    for name in sorted(os.listdir(ARCHIVE_DIR), reverse=True):
        m = re.match(r"^(\d{4}-\d{2})\.md$", name)
        if m:
            found.append((m.group(1), name))
    return found


def update_summary():
    """Regenerate SUMMARY.md's changelog sub-list from the files on disk.

    A rotation that creates a new archive and does not list it here fails
    DocumentationLinksTest — the page exists under docs/ and nothing navigates
    to it. That used to be a printed reminder, which is a step a tired human
    skips and an automated rotation cannot perform at all.
    """
    if not os.path.isfile(SUMMARY):
        return
    lines = read(SUMMARY).split("\n")
    try:
        anchor = lines.index(SUMMARY_ANCHOR)
    except ValueError:
        sys.exit("docs/SUMMARY.md has no '%s' line to hang the archives from." % SUMMARY_ANCHOR)

    # Consume EVERY indented child, not just the month rows. Stopping at the
    # first non-month line meant that moving the "Pending entries" link above
    # the months — a perfectly natural place for it — had the regenerated list
    # inserted above it while the old month rows stayed below it, doubling
    # them. Nothing would have caught that: every duplicated link resolves.
    end = anchor + 1
    while end < len(lines) and SUMMARY_CHILD.match(lines[end]):
        end += 1
    children = lines[anchor + 1:end]

    months = ["  - [%s](changelog/%s)" % (pretty_month(mo), name)
              for mo, name in archives_on_disk()]
    listed = months + [c for c in children if not SUMMARY_ENTRY.match(c)]
    if children == listed:
        return
    write(SUMMARY, "\n".join(lines[:anchor + 1] + listed + lines[end:]))
    print("  %-28s <- %d archives listed" % (SUMMARY, len(months)))


def rebuild_archive_table(header):
    """Regenerate the Archive table from the files actually on disk."""
    rows = []
    for mo, name in archives_on_disk():
        path = os.path.join(ARCHIVE_DIR, name)
        count = sum(1 for l in read(path).split("\n") if l.startswith("## "))
        rows.append("| [%s](changelog/%s) | %d | %.0f KB |"
                    % (pretty_month(mo), name, count, normalised_size(path) / 1024))
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

    require_repo_root()

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
            body = ("# Changelog — " + pretty_month(mo) + "\n\n"
                    "> Archived entries for " + pretty_month(mo) + ", newest first. "
                    "For recent work see [the live changelog](../changelog.md).\n\n"
                    "---\n\n" + block)
        write(path, body)
        print("  %-28s <- %d entries" % (path, len(items)))

    header = rebuild_archive_table(header)
    kept_bodies = [b for _, b in keep]
    write(LIVE, header + "\n"
          + "\n".join(kept_bodies)
          + register_separator(kept_bodies)
          + "\n".join(b for _, b in registers))

    update_summary()

    print(f"moved {len(move)} entries; docs/changelog.md is now {normalised_size(LIVE):,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
