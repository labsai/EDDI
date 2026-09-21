#!/usr/bin/env python3
"""Fold the pending fragments in docs/changelog.d/ into docs/changelog.md.

    python scripts/collate-changelog.py           # collate and delete the fragments
    python scripts/collate-changelog.py --check   # validate only, change nothing

Why fragments exist at all: AGENTS.md §2 rule 8 requires every branch to add a
changelog entry, and the rule said to add it at the top of docs/changelog.md —
the same handful of lines, in every open PR at once. Git has no way to merge
two insertions at the same point, so with a dozen PRs in flight every one of
them conflicted with every other, on a file that had nothing to do with the
code under review. Rebasing to fix it re-ran the same collision on the next
merge.

A fragment is a whole new file with a name no other branch picks, so git takes
both sides without asking. The entries are merged once, afterwards, on main,
by the nightly job in `.github/workflows/changelog-collate.yml` — where there
is no competing branch to conflict with.

The fragment format is exactly what used to be pasted into docs/changelog.md,
so collation is a move rather than a translation: one or more `## ` entries,
each carrying a `(YYYY-MM-DD)` date in its heading. Relative links carry one
extra `../`, because a fragment sits one directory deeper than the live file.
Entries are merged into the live file *by date*, not stacked on top of it: a
PR that sat open for three weeks carries an old date, and pushing it above
newer entries would make the file's newest-first order a lie.

Rows for the two running registers at the bottom of the live file ride along in
fenced blocks, which is the one part that is not a straight copy:

    ```decision-log
    | 2026-09-21 | What was decided | Why it came up | What was rejected |
    ```

    ```regression-note
    | 2026-09-21 | What broke | Cause | Fix | Commit |
    ```

Those tables are appended to by every session too, so they conflict for exactly
the same reason the top of the file does.

Every scan here is fence-aware. A changelog entry routinely quotes the markdown
it describes, so a `## ` or a ```` ```decision-log ```` inside a fenced block is
an example, not structure — reading it as structure splits an entry in half or
files an example row into a real table.
"""
import argparse
import os
import re
import sys

from changelog_common import (
    DATE,
    DATE_SHAPED,
    FENCE_MARK,
    FRAGMENT_DIR,
    FRAGMENT_NAME,
    LIVE,
    ROW_DATE,
    date_of,
    fence_mask,
    heading_indices,
    normalised_size,
    read,
    register_separator,
    require_repo_root,
    split_sections,
    undepth,
    write,
)

# A fenced block carrying rows for one of the running registers.
REGISTER_INFO = re.compile(r"^(`{3,})(decision-log|regression-note)[ \t]*$")
REGISTER_OF = {"decision-log": "## Decision Log", "regression-note": "## Regression Notes"}
# Near-misses: the right intent with the wrong spelling. Left alone these render
# as an ordinary code block and the rows are never filed, which nothing notices.
REGISTER_TYPO = re.compile(r"^(?:decision[-_ ]?logs?|regression[-_ ]?notes?)$", re.IGNORECASE)

TABLE_ROW = re.compile(r"^\|.*\|\s*$")
# A table's separator row: pipes, dashes, colons and spaces, nothing else.
SEPARATOR_ROW = re.compile(r"^\|[\s\-:|]+\|\s*$")
# A row whose every cell is blank — the placeholder that keeps an empty table's
# shape.
BLANK_ROW = re.compile(r"^\|[\s|]*\|\s*$")


def fragments():
    """Every pending fragment, oldest filename first, validated."""
    if not os.path.isdir(FRAGMENT_DIR):
        return []
    found = []
    for name in sorted(os.listdir(FRAGMENT_DIR)):
        if name == "README.md":
            continue  # the directory's own instructions
        path = os.path.join(FRAGMENT_DIR, name)
        if not os.path.isfile(path) or not name.endswith(".md"):
            continue
        if not FRAGMENT_NAME.match(name):
            sys.exit("%s is not a changelog fragment name. Use YYYY-MM-DD-<slug>.md, "
                     "where the slug is lower-case and unique to your branch." % path)
        found.append(path)
    return found


def take_register_rows(text, path):
    """(text without the register blocks, {register heading: [rows]}).

    Walks the fences rather than pattern-matching the whole file, so a
    ```` ```decision-log ```` nested inside a ` ````markdown ` block that
    documents this very format stays part of the example.
    """
    lines = text.split("\n")
    kept, rows = [], {}
    i = 0
    while i < len(lines):
        mark = FENCE_MARK.match(lines[i].strip())
        if not mark:
            kept.append(lines[i])
            i += 1
            continue

        run, info = len(mark.group(1)), mark.group(2).strip()
        close = i + 1
        while close < len(lines):
            end = FENCE_MARK.match(lines[close].strip())
            if end and len(end.group(1)) >= run and not end.group(2).strip():
                break
            close += 1
        if close >= len(lines):
            sys.exit("%s has an unterminated ```%s block — add the closing fence."
                     % (path, info or "```"))

        register = REGISTER_INFO.match(lines[i].strip())
        if register:
            kind = register.group(2)
            for line in lines[i + 1:close]:
                line = line.strip()
                if not line or SEPARATOR_ROW.match(line):
                    continue  # a copied header separator, not a row
                if not TABLE_ROW.match(line):
                    sys.exit("%s has a line in its ```%s block that is not a table row: %s"
                             % (path, kind, line[:80]))
                # Both registers lead with a Date column, and that date is what
                # orders the rows once several fragments' worth arrive at once.
                # A row without one cannot be placed, so it is refused here
                # rather than silently landing wherever it happened to be read.
                if not ROW_DATE.match(line):
                    sys.exit("%s has a ```%s row whose first cell is not a date: %s\n"
                             "Start the row with the day it applies to, e.g. "
                             "'| 2026-09-21 | …'." % (path, kind, line[:80]))
                rows.setdefault(REGISTER_OF[kind], []).append(line)
        elif REGISTER_TYPO.match(info):
            sys.exit("%s opens a ```%s block. The collator files rows from ```decision-log and "
                     "```regression-note only — spelt any other way the rows stay in the entry "
                     "as a code block and are never added to the table." % (path, info))
        else:
            kept.extend(lines[i:close + 1])
        i = close + 1

    return "\n".join(kept), rows


def entries_of(path):
    """The `## ` entries in one fragment, re-depthed for the live file."""
    text, registers = take_register_rows(read(path), path)

    lines = text.split("\n")
    starts = heading_indices(lines)
    if not starts:
        sys.exit("%s has no '## ' heading — a fragment is one or more changelog entries, "
                 "each headed '## <title> (YYYY-MM-DD)'.\n"
                 "(A '## ' inside a fenced block is an example, and is not counted.)" % path)

    # Anything above the first heading would be dropped on the floor, which is
    # the one failure this script can produce that leaves no trace at all.
    inside, _ = fence_mask(lines)
    preamble = [l for n, l in enumerate(lines[:starts[0]]) if l.strip() and not inside[n]]
    if preamble:
        sys.exit("%s has text above its first '## ' heading, which collation would discard: %s\n"
                 "Put it inside the entry." % (path, preamble[0][:80]))

    found = []
    for n, start in enumerate(starts):
        heading = lines[start]
        if not DATE.search(heading):
            if DATE_SHAPED.search(heading):
                sys.exit("%s has a heading dated with something that is not a calendar date: %s\n"
                         "The month must be 01-12 and the day 01-31 — an out-of-range month "
                         "passes every check here and then crashes rotation, weeks later, in "
                         "the nightly job." % (path, heading[:80]))
            sys.exit("%s has an entry with no date in its heading: %s\n"
                     "Every entry ends with its date in brackets, e.g. '(2026-09-21)'."
                     % (path, heading[:80]))
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        body = "\n".join(lines[start:end]).rstrip() + "\n"
        found.append((date_of(heading, None), os.path.basename(path), n, undepth(body, path)))
    return found, registers


def merge_register(body, heading, rows):
    """Insert rows at the top of a register's table, below its separator."""
    lines = body.split("\n")
    sep = next((i for i, l in enumerate(lines) if SEPARATOR_ROW.match(l)), None)
    if sep is None:
        sys.exit("The '%s' table in docs/changelog.md has no |---|---| separator row, so there "
                 "is nowhere to insert below and nothing renders as a table. Add one under its "
                 "header row." % heading)

    # The table runs to the last contiguous row; a trailing `---` rule and the
    # blank lines around it belong to the section, not the table, and have to
    # survive untouched.
    end = sep
    while end + 1 < len(lines) and TABLE_ROW.match(lines[end + 1]):
        end += 1

    # A trailing all-blank row is the placeholder that keeps an empty table's
    # shape. Drop it once there is real data to hold the shape.
    while end > sep and BLANK_ROW.match(lines[end]):
        lines.pop(end)
        end -= 1

    lines[sep + 1:sep + 1] = rows
    return "\n".join(lines)


def merge_entries(collected, existing):
    """Weave the new entries into the existing ones, newest first.

    Ties put the new entry above the old one, so a run of same-day entries
    reads with this collation's work on top.
    """
    dated, fallback = [], "9999-99-99"
    for heading, body in existing:
        fallback = date_of(heading, fallback)
        dated.append((fallback, body))

    merged, i = [], 0
    for date, body in dated:
        while i < len(collected) and collected[i][0] >= date:
            merged.append(collected[i][3])
            i += 1
        merged.append(body)
    merged.extend(entry[3] for entry in collected[i:])
    return merged


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true",
                    help="validate the fragments and report, change nothing")
    args = ap.parse_args()

    require_repo_root()

    pending = fragments()
    if not pending:
        print("No fragments in %s — nothing to collate." % FRAGMENT_DIR)
        return 0

    collected, register_rows = [], {}
    for path in pending:
        found, registers = entries_of(path)
        collected.extend(found)
        for heading, rows in registers.items():
            register_rows.setdefault(heading, []).extend(rows)
        print("  %-52s %d entr%s" % (path, len(found), "y" if len(found) == 1 else "ies"))

    # Newest first. Ties break on filename and then on position within the file,
    # so the same fragments always collate the same way — a nightly job that
    # produced a different diff on a re-run would be unreviewable. Two passes
    # rather than one compound key, because the date sorts descending and the
    # tie-breakers ascending, and Python's sort is stable.
    collected.sort(key=lambda e: (e[1], e[2]))
    collected.sort(key=lambda e: e[0], reverse=True)

    if args.check:
        print("\n%d entr%s and %d register row(s) ready to collate."
              % (len(collected), "y" if len(collected) == 1 else "ies",
                 sum(len(r) for r in register_rows.values())))
        return 0

    header, existing, registers = split_sections(read(LIVE))

    unplaced = set(register_rows) - {h for h, _ in registers}
    if unplaced:
        sys.exit("docs/changelog.md has no %s section to append to."
                 % " or ".join(sorted(unplaced)))
    # Newest first, matching both tables and the entry list. The rows arrive in
    # fragment-filename order — oldest first — and were inserted as one block at
    # the top, so a night that collated several days' fragments put 09-20 above
    # 09-21 inside a table whose whole ordering is newest-first. Stable, so rows
    # sharing a date keep the order their fragment wrote them in.
    for rows in register_rows.values():
        rows.sort(key=lambda row: ROW_DATE.match(row).group(1), reverse=True)

    registers = [(h, merge_register(b, h, register_rows[h]) if h in register_rows else b)
                 for h, b in registers]

    bodies = merge_entries(collected, existing)
    write(LIVE, header + "\n"
          + "\n".join(bodies)
          + register_separator(bodies)
          + "\n".join(b for _, b in registers))

    for path in pending:
        os.remove(path)

    print("\ncollated %d entries from %d fragment(s); docs/changelog.md is now %s bytes"
          % (len(collected), len(pending), format(normalised_size(LIVE), ",")))
    print("Run scripts/rotate-changelog.py next to trim it back under the rotation target.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
