#!/usr/bin/env python3
"""Shared pieces of the changelog pipeline, used by both scripts that move
entries between its three depths.

An entry lives at one of three places, and moves between them in one direction:

    docs/changelog.d/<date>-<slug>.md   a pending fragment, written by a PR
              |  collate-changelog.py
              v
    docs/changelog.md                   the live file, newest first
              |  rotate-changelog.py
              v
    docs/changelog/<YYYY-MM>.md         a monthly archive

A fragment and an archive are both one directory below the live file, so text
moving *up* loses one `../` from each relative link and text moving *down*
gains one. Getting that wrong produces broken links that render fine in the
diff and are only noticed by DocumentationLinksTest, so both transforms live
here rather than being written twice.

Fenced code blocks and code spans are excluded from every scan in this module —
the link transforms, the heading scan and the register-block scan alike. A
changelog entry frequently *quotes* the markdown it is describing, and text
inside a fence is an example rather than structure: re-depthing it corrupts the
example, and reading a `## ` inside it as a heading splits an entry in half.
"""
import datetime
import io
import os
import re
import sys

LIVE = os.path.join("docs", "changelog.md")
ARCHIVE_DIR = os.path.join("docs", "changelog")
FRAGMENT_DIR = os.path.join("docs", "changelog.d")

# Must match ChangelogRotationTest.LIVE_CAP_BYTES.
CAP_BYTES = 250 * 1024
# Rotation trims back to this, leaving headroom below the cap.
TARGET_BYTES = 200 * 1024

# The two running registers at the bottom of the live file. Anchored to the end
# of the line rather than tested as a prefix: "## Decision Log (2026-09-22)" is
# a plausible entry title, and a prefix test classified it as the register
# itself — so the entry collated normally and was then shunted below the bottom
# rule on the NEXT run, long after the commit that could explain it.
# ChangelogFragmentTest rejects that heading in a fragment, so it should never
# reach here; this is the half of the guard that does not depend on the test.
REGISTER_HEADINGS = ("## Decision Log", "## Regression Notes")
REGISTER = re.compile(r"^## (Decision Log|Regression Notes)\s*$")

# The date an entry carries, in its own heading: "... (2026-09-21)".
DATE = re.compile(r"\((\d{4})-(\d{2})-\d{2}")

# A fragment filename. The date prefix is a sort hint and a hint only — the
# heading inside is authoritative, and ChangelogFragmentTest requires the two
# to agree.
FRAGMENT_NAME = re.compile(r"^(\d{4}-\d{2}-\d{2})-([a-z0-9][a-z0-9._-]*)\.md$")

LINK = re.compile(r"\]\((?!https?://|#|mailto:|<http)([^)]+)\)")
# A reference-style link definition: "[label]: ../../src/Foo.java".
REF_DEF = re.compile(r"^\[[^\]]+\]:\s*\S")
# A code span, honouring the backtick-run rule: ``a `b` c`` is one span.
SPAN = re.compile(r"(`+)(?:(?!\1).)*?\1")
# A fence marker, allowing the indentation a fence inside a list item carries.
FENCE_MARK = re.compile(r"^(`{3,})(.*)$")


def fence_mask(lines):
    """(one bool per line — True inside a fenced block, unterminated?).

    A fence is closed by a run of at least as many backticks with no info
    string, so a ```` ```decision-log ```` inside a ` ````markdown ` block that
    *documents* the fragment format stays part of the example.
    """
    inside = [False] * len(lines)
    open_run = None
    for i, line in enumerate(lines):
        mark = FENCE_MARK.match(line.strip())
        if open_run is None:
            if mark:
                open_run = len(mark.group(1))
                inside[i] = True
        else:
            inside[i] = True
            if mark and len(mark.group(1)) >= open_run and not mark.group(2).strip():
                open_run = None
    return inside, open_run is not None


def heading_indices(lines, level="## "):
    """Line numbers of the headings that are structure rather than example."""
    inside, _ = fence_mask(lines)
    return [i for i, line in enumerate(lines) if line.startswith(level) and not inside[i]]


def _map_links(body, fix):
    """Apply `fix` to every relative link target outside fences and code spans."""
    lines = body.split("\n")
    inside, _ = fence_mask(lines)
    out = []
    for i, line in enumerate(lines):
        if inside[i]:
            out.append(line)
            continue
        # Mask code spans, rewrite what is left, then restore them.
        spans = []

        def stash(m):
            spans.append(m.group(0))
            return "\0%d\0" % (len(spans) - 1)

        masked = SPAN.sub(stash, line)
        masked = LINK.sub(fix, masked)
        out.append(re.sub(r"\0(\d+)\0", lambda m: spans[int(m.group(1))], masked))
    return "\n".join(out)


def redepth(body):
    """Add one '../' to each relative link — text moving one directory deeper."""

    def fix(m):
        target = m.group(1)
        if target.startswith("/"):
            return m.group(0)  # repo-root-relative: depth does not affect it
        return "](../" + target + ")"

    return _map_links(body, fix)


def undepth(body, where):
    """Remove one '../' from each relative link — text moving one directory up.

    The inverse of :func:`redepth`, and not merely its mirror image: a link can
    always gain a `../`, but one that does not start with `../` cannot lose
    one. That case is a fragment linking to a sibling inside
    ``docs/changelog.d/`` — correct where it is written, and broken the moment
    the entry is folded into ``docs/changelog.md``. DocumentationLinksTest
    checks the fragment where it lives, so it passes; the breakage would first
    appear in the collation commit, attributed to the nightly job rather than
    to the PR that wrote it. Refusing here puts the failure back on the author.
    """
    bad = []

    def fix(m):
        target = m.group(1)
        if target.startswith("/"):
            return m.group(0)  # repo-root-relative: depth does not affect it
        if target == "../":
            # Would become the empty target "]()", which redepth can no longer
            # match — the one input where the two transforms are not inverses.
            bad.append(target + " (a bare '../' has nothing left to point at)")
            return m.group(0)
        if not target.startswith("../"):
            bad.append(target)
            return m.group(0)
        return "](" + target[3:] + ")"

    out = _map_links(body, fix)

    # Reference-style definitions carry a path that LINK cannot see, so they
    # would move up a directory without being re-depthed — and
    # DocumentationLinksTest only follows inline links, so nothing would catch
    # the break either.
    lines = body.split("\n")
    inside, _ = fence_mask(lines)
    for i, line in enumerate(lines):
        if not inside[i] and REF_DEF.match(line.strip()):
            bad.append(line.strip()[:60] + " (reference-style link definition)")

    if bad:
        sys.exit(
            "%s cannot be collated because of %s.\nA fragment sits one directory below "
            "docs/changelog.md, so every relative link in it needs a leading '../' — write "
            "'../../src/...' for a source file and '../architecture.md' for a neighbouring "
            "doc, and spell links inline rather than by reference."
            % (where, ", ".join(sorted(set(bad))))
        )
    return out


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
    starts = heading_indices(lines)
    if not starts:
        sys.exit("docs/changelog.md has no '## ' sections — nothing to do.")

    # The header runs to the '---' that closes it, which is where entries begin.
    # Sections above that rule are header prose, not entries.
    first_entry = next(
        (i for i in starts if DATE.search(lines[i]) or REGISTER.match(lines[i])),
        None,
    )
    if first_entry is None:
        sys.exit("docs/changelog.md has no dated entries — nothing to do.")

    header = "\n".join(lines[:first_entry]).rstrip() + "\n"

    blocks = []
    tail = [i for i in starts if i >= first_entry]
    for n, start in enumerate(tail):
        end = tail[n + 1] if n + 1 < len(tail) else len(lines)
        blocks.append((lines[start], "\n".join(lines[start:end]).rstrip() + "\n"))

    entries = [b for b in blocks if not REGISTER.match(b[0])]
    registers = [b for b in blocks if REGISTER.match(b[0])]
    return header, entries, registers


def register_separator(entry_bodies):
    """The text that closes the entry list and opens the running registers.

    Entries already end with their own `---` rule, so emitting another one
    unconditionally added a rule per run — the live file gained one every night
    and never lost it, because rotation only resets the count on the runs that
    happen to move the last entry.
    """
    last = entry_bodies[-1].rstrip() if entry_bodies else ""
    return "\n" if last.endswith("---") else "\n---\n\n"


def month_of(heading, fallback):
    m = DATE.search(heading)
    return (m.group(1) + "-" + m.group(2)) if m else fallback


def date_of(heading, fallback):
    """The full YYYY-MM-DD an entry is dated, for ordering."""
    m = DATE.search(heading)
    return m.group(0)[1:] if m else fallback


def pretty_month(mo):
    return datetime.date.fromisoformat(mo + "-01").strftime("%B %Y")


def require_repo_root():
    if not os.path.isfile("pom.xml"):
        sys.exit("Run this from the repository root.")
