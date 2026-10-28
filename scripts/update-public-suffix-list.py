#!/usr/bin/env python3
"""Refresh the Public Suffix List bundled for DMARC alignment.

EDDI never fetches the list at runtime: DMARC alignment
(src/main/java/ai/labs/eddi/engine/mail/PublicSuffixList.java) reads the copy in
src/main/resources/mail/public_suffix_list.dat, and the provenance next to it
(public_suffix_list.properties) records which upstream commit it is and its
SHA-256. PublicSuffixListTest fails when the two disagree.

Usage (from the repository root, with network access):

  python scripts/update-public-suffix-list.py            latest commit on main
  python scripts/update-public-suffix-list.py <sha>      a specific commit

The file is taken unmodified from the publicsuffix/list repository at that
commit (MPL-2.0, see licenses/MPL-2.0.txt). Review the diff before committing:
a newly added public suffix changes which domains align.
"""

import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = "publicsuffix/list"
DATA = Path("src/main/resources/mail/public_suffix_list.dat")
PROVENANCE = Path("src/main/resources/mail/public_suffix_list.properties")

HEADER = """# Provenance of public_suffix_list.dat, the Public Suffix List bundled for DMARC
# alignment (engine/mail PublicSuffixList). Written by
# scripts/update-public-suffix-list.py; PublicSuffixListTest checks that the
# SHA-256 below matches the bundled file, so a hand edit of either fails the build.
#
# The list is published by the Public Suffix List project under the Mozilla
# Public License 2.0 (licenses/MPL-2.0.txt); it is redistributed unmodified.
"""


ALLOWED_PREFIXES = ("https://api.github.com/", "https://raw.githubusercontent.com/")
REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")


def fetch(url):
    # Only the two fixed GitHub https origins: no file:, no custom scheme, no other host.
    if not url.startswith(ALLOWED_PREFIXES):
        sys.exit(f"Refusing to fetch {url}: not an allowed https GitHub URL.")
    request = urllib.request.Request(url, headers={"User-Agent": "eddi-psl-refresh"})
    with urllib.request.urlopen(request, timeout=60) as response:  # nosec B310 - scheme and host checked above
        return response.read()


def main(argv):
    if not DATA.parent.is_dir():
        sys.exit("Run from the repository root.")
    ref = argv[1] if len(argv) > 1 else "main"
    if not REF.fullmatch(ref):
        sys.exit(f"Not a branch name or commit sha: {ref!r}")
    commit = json.loads(fetch(f"https://api.github.com/repos/{REPO}/commits/{ref}"))
    sha = commit["sha"]
    date = commit["commit"]["committer"]["date"]
    data = fetch(f"https://raw.githubusercontent.com/{REPO}/{sha}/public_suffix_list.dat")
    if b"===BEGIN ICANN DOMAINS===" not in data or b"===END PRIVATE DOMAINS===" not in data:
        sys.exit("The downloaded file does not look like the Public Suffix List; nothing written.")
    DATA.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    PROVENANCE.write_bytes((HEADER
                            + f"source=https://github.com/{REPO}/blob/{sha}/public_suffix_list.dat\n"
                            + f"commit={sha}\n"
                            + f"commit-date={date}\n"
                            + f"sha256={digest}\n"
                            + "license=MPL-2.0\n").encode("utf-8"))
    print(f"public_suffix_list.dat <- {sha} ({date}), {len(data)} bytes, sha256 {digest}")


if __name__ == "__main__":
    main(sys.argv)
