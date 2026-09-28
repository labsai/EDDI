#!/usr/bin/env python3
"""Move EDDI's version numbers. The only tool a release needs.

EDDI's version lives in two kinds of place, and they move at different times.

  THE BUILD VERSION: pom.xml's <version>. Everything the build reports derives
  from it: application.properties, the OpenAPI document, the image label, and
  the Manager's sidebar. Set it to the NEXT release as soon as the previous one
  ships, so every snapshot on main (labsai/eddi:<pom>-b<N>) carries the version
  it is heading towards, not one that is already out.

  THE PUBLISHED RELEASE: the version a reader should deploy. It appears in the
  Helm chart's appVersion, the k8s manifests and the copy-pasteable commands in
  the docs, all listed by scripts/release-pointers.json. It may only move once
  that image exists, or a quickstart followed from main pulls a tag that is not
  there.

Commands:

  show                   print both versions and every release pointer
  check                  exit 1 if the pointers disagree, or run ahead of the pom
  next <x.y.z>           set the build version (pom.xml)
  release <x.y.z>        point every release pointer at a published release,
                         and bump the Helm chart's own version to match
  post-release <x.y.z>   what CI runs after publishing release <x.y.z>:
                         `release` if the pointers are older, then `next` to
                         the following minor if the pom has not moved past it

The usual cycle, if CI does not do it for you:

  python scripts/bump-version.py post-release 6.5.0    # right after tagging 6.5.0

ReleaseVersionSourceTest enforces the result, so a file this script misses
fails the build rather than shipping stale.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
POM_VERSION = re.compile(r"<version>([^<]+)</version>")
CHART_VERSION = re.compile(r"^version:\s*(\S+)\s*$", re.MULTILINE)
EXPECTED_CHART_CONSTANT = re.compile(r'(EXPECTED_CHART_VERSION = ")([^"]+)(")')

CHART = Path("helm/eddi/Chart.yaml")
POM = Path("pom.xml")
POINTERS = Path("scripts/release-pointers.json")
CHART_TEST = Path("src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java")
CHANGELOG_DIR = Path("docs/changelog.d")


class BumpError(Exception):
    pass


def parse(version: str) -> tuple[int, int, int]:
    m = SEMVER.match(version)
    if not m:
        raise BumpError(f"'{version}' is not MAJOR.MINOR.PATCH (release tags carry no 'v' and no suffix)")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def fmt(v: tuple[int, int, int]) -> str:
    return ".".join(str(p) for p in v)


def read(path: Path) -> str:
    # newline="" keeps CRLF as CRLF, so a rewrite changes only the version.
    with path.open(encoding="utf-8", newline="") as f:
        return f.read()


def write(path: Path, text: str) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write(text)


class Repo:
    def __init__(self, root: Path):
        self.root = root
        cfg = json.loads(read(root / POINTERS))
        self.roots = cfg["roots"]
        self.extensions = tuple(cfg["extensions"])
        self.exclude = cfg["exclude"]
        self.patterns = [re.compile(p) for p in cfg["patterns"]]
        self.changed: list[str] = []

    # ── discovery ────────────────────────────────────────────────────────────
    def pointer_files(self) -> list[Path]:
        files: list[Path] = []
        for entry in self.roots:
            base = self.root / entry
            candidates = [base] if base.is_file() else sorted(p for p in base.rglob("*") if p.is_file())
            for path in candidates:
                rel = path.relative_to(self.root).as_posix()
                if not rel.endswith(self.extensions):
                    continue
                if any(rel == ex or (ex.endswith("/") and rel.startswith(ex)) for ex in self.exclude):
                    continue
                files.append(path)
        return files

    def pointers(self) -> list[tuple[str, int, str]]:
        """(file, line number, version) for every match of every pattern."""
        found = []
        for path in self.pointer_files():
            rel = path.relative_to(self.root).as_posix()
            for lineno, line in enumerate(read(path).splitlines(), start=1):
                for pattern in self.patterns:
                    for m in pattern.finditer(line):
                        found.append((rel, lineno, m.group(1)))
        return found

    def pom_version(self) -> str:
        m = POM_VERSION.search(read(self.root / POM))
        if not m:
            raise BumpError("no <version> element in pom.xml")
        return m.group(1).strip()

    def app_version(self) -> str:
        m = re.search(r'^appVersion:\s*"([^"]+)"', read(self.root / CHART), re.MULTILINE)
        if not m:
            raise BumpError(f'no appVersion: "<x.y.z>" line in {CHART}')
        return m.group(1)

    def chart_version(self) -> str:
        m = CHART_VERSION.search(read(self.root / CHART))
        if not m:
            raise BumpError(f"no top-level version: line in {CHART}")
        return m.group(1)

    # ── edits ────────────────────────────────────────────────────────────────
    def _rewrite(self, path: Path, text: str) -> None:
        if text != read(path):
            write(path, text)
            self.changed.append(path.relative_to(self.root).as_posix())

    def set_pom(self, version: str) -> None:
        path = self.root / POM
        text = read(path)
        m = POM_VERSION.search(text)
        # Only the FIRST <version> — the project's own. Every later one is a
        # dependency or plugin, and ci.yml reads the version with the same rule.
        self._rewrite(path, text[: m.start(1)] + version + text[m.end(1):])

    def set_pointers(self, version: str) -> None:
        for path in self.pointer_files():
            text = read(path)
            for pattern in self.patterns:
                text = pattern.sub(lambda m: m.group(0)[: m.start(1) - m.start(0)] + version
                                   + m.group(0)[m.end(1) - m.start(0):], text)
            self._rewrite(path, text)

    def set_chart_version(self, version: str) -> None:
        path = self.root / CHART
        text = read(path)
        m = CHART_VERSION.search(text)
        self._rewrite(path, text[: m.start(1)] + version + text[m.end(1):])
        # DeploymentManifestsTest pins the chart version exactly, on purpose (see
        # its chartVersionRecordsTheBreakingChange), so it moves in the same edit.
        test = self.root / CHART_TEST
        if test.is_file():
            text = read(test)
            if not EXPECTED_CHART_CONSTANT.search(text):
                raise BumpError(f"{CHART_TEST} no longer declares EXPECTED_CHART_VERSION = \"...\"")
            self._rewrite(test, EXPECTED_CHART_CONSTANT.sub(lambda m: m.group(1) + version + m.group(3), text))


# ── commands ─────────────────────────────────────────────────────────────────
def problems(repo: Repo) -> list[str]:
    found = []
    app = repo.app_version()
    stray = [p for p in repo.pointers() if p[2] != app]
    for rel, lineno, version in stray:
        found.append(f"{rel}:{lineno} names {version}, but helm/eddi/Chart.yaml's appVersion is {app}")
    pom = repo.pom_version()
    if SEMVER.match(pom) and parse(app) > parse(pom):
        found.append(f"the release pointers name {app}, which is AHEAD of pom.xml's {pom}")
    return found


def cmd_show(repo: Repo, _args) -> int:
    pointers = repo.pointers()
    print(f"build version (pom.xml):         {repo.pom_version()}")
    print(f"published release (appVersion):  {repo.app_version()}")
    print(f"helm chart version:              {repo.chart_version()}")
    print(f"release pointers:                {len(pointers)}")
    for rel, lineno, version in pointers:
        print(f"  {version:<10} {rel}:{lineno}")
    return 0


def cmd_check(repo: Repo, _args) -> int:
    found = problems(repo)
    for p in found:
        print(f"error: {p}", file=sys.stderr)
    if not found:
        print(f"ok: {len(repo.pointers())} release pointers all name {repo.app_version()}")
    return 1 if found else 0


def do_next(repo: Repo, version: str, force: bool) -> None:
    current = repo.pom_version()
    if not force and SEMVER.match(current) and parse(version) <= parse(current):
        raise BumpError(f"pom.xml is already {current}; refusing to move the build version to {version} "
                        "(pass --force to go backwards)")
    repo.set_pom(version)


def do_release(repo: Repo, version: str, chart_bump: str | None, force: bool) -> None:
    current = repo.app_version()
    if not force and parse(version) <= parse(current):
        raise BumpError(f"the release pointers already name {current}; refusing to point them at {version} "
                        "(pass --force to go backwards)")
    pom = repo.pom_version()
    if not force and SEMVER.match(pom) and parse(version) > parse(pom):
        raise BumpError(f"{version} is ahead of pom.xml's {pom}, so it cannot have been released yet. "
                        f"Run `next {version}` first, or pass --force")
    # A patch release moves the chart by a patch, anything else by a minor:
    # the chart's templates did not change, only the image it defaults to.
    # Breaking chart changes are a human decision and bump the major by hand.
    if chart_bump is None:
        chart_bump = "patch" if parse(version)[2] > 0 else "minor"
    major, minor, patch = parse(repo.chart_version())
    new_chart = {
        "major": (major + 1, 0, 0),
        "minor": (major, minor + 1, 0),
        "patch": (major, minor, patch + 1),
    }[chart_bump]
    repo.set_pointers(version)
    repo.set_chart_version(fmt(new_chart))


def cmd_next(repo: Repo, args) -> int:
    parse(args.version)
    do_next(repo, args.version, args.force)
    return report(repo)


def cmd_release(repo: Repo, args) -> int:
    do_release(repo, args.version, args.chart_bump, args.force)
    return report(repo)


def cmd_post_release(repo: Repo, args) -> int:
    tag = args.version
    parse(tag)
    notes = []
    before_app, before_pom, before_chart = repo.app_version(), repo.pom_version(), repo.chart_version()

    if parse(tag) > parse(before_app):
        do_release(repo, tag, None, force=False)
        notes.append(f"Release pointers: {before_app} → {tag} (Helm chart {before_chart} → {repo.chart_version()})")
    else:
        notes.append(f"Release pointers already name {before_app}; {tag} does not move them.")

    if not SEMVER.match(before_pom) or parse(before_pom) <= parse(tag):
        major, minor, _ = parse(tag)
        following = fmt((major, minor + 1, 0))
        repo.set_pom(following)
        notes.append(f"Build version (pom.xml): {before_pom} → {following}")
    else:
        notes.append(f"Build version (pom.xml) is already {before_pom}, past {tag}; left alone.")

    if repo.changed and args.changelog:
        write_fragment(repo, tag, notes)

    for n in notes:
        print(n)
    return report(repo, quiet=True)


def write_fragment(repo: Repo, tag: str, notes: list[str]) -> None:
    today = datetime.date.today().isoformat()
    path = repo.root / CHANGELOG_DIR / f"{today}-post-release-{tag.replace('.', '-')}.md"
    files = [f for f in repo.changed]
    body = [f"## 🔖 chore(release): after {tag} ({today})", "",
            "**Repo:** EDDI (opened by `post-release.yml` after the release pipeline published the image)", "",
            "### What", ""]
    body += [f"- {n}" for n in notes]
    body += ["", "Generated by `python scripts/bump-version.py post-release " + tag + "`. Files:", ""]
    body += [f"- `{f}`" for f in files]
    path.parent.mkdir(parents=True, exist_ok=True)
    write(path, "\n".join(body) + "\n")
    repo.changed.append(path.relative_to(repo.root).as_posix())


def report(repo: Repo, quiet: bool = False) -> int:
    if not quiet:
        print(f"build version: {repo.pom_version()}   published release: {repo.app_version()}   "
              f"chart: {repo.chart_version()}")
    if repo.changed:
        print("changed:")
        for f in repo.changed:
            print(f"  {f}")
    else:
        print("nothing to change")
    found = problems(repo)
    for p in found:
        print(f"error: {p}", file=sys.stderr)
    return 1 if found else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: the checkout this script lives in)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("show").set_defaults(func=cmd_show)
    sub.add_parser("check").set_defaults(func=cmd_check)
    p = sub.add_parser("next")
    p.add_argument("version")
    p.add_argument("--force", action="store_true")
    p.set_defaults(func=cmd_next)
    p = sub.add_parser("release")
    p.add_argument("version")
    p.add_argument("--chart-bump", choices=["major", "minor", "patch"])
    p.add_argument("--force", action="store_true")
    p.set_defaults(func=cmd_release)
    p = sub.add_parser("post-release")
    p.add_argument("version")
    p.add_argument("--changelog", action="store_true", help="also write a docs/changelog.d fragment")
    p.set_defaults(func=cmd_post_release)

    args = parser.parse_args(argv)
    # The notes use "→"; a Windows console defaults to a code page without it.
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        return args.func(Repo(args.root.resolve()), args)
    except BumpError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
