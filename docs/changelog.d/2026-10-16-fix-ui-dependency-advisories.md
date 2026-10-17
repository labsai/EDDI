## 🔒 fix(ui): clear three npm advisories reported by Scorecard (2026-10-16)

**Repo:** EDDI (`fix/ui-dependency-advisories`)

### What changed and why

OpenSSF Scorecard's Vulnerabilities check flagged three advisories. All three
came from the UI lockfiles, not from the Maven build.

- **GHSA-238p-pmpm-9mq7, KaTeX prototype-pollution trust bypass (`katex` < 0.18.2).**
  The Chat UI's own `katex` was already 0.19.0. `rehype-katex@7.0.1` and
  `micromark-extension-math@3.1.0` each ask for `^0.16`, which gave them a nested
  0.16.47 copy that ships in the chat bundle. Both are on their latest release,
  so an npm `overrides` entry now points both at the root version (`$katex`).
  Build, lint and all 480 chat tests pass, including the `MessageBubble`
  math-rendering tests.
- **GHSA-rj75-hqrm-r3gf, quadratic selector parsing in `postcss-selector-parser`
  (< 7.1.6).** `@tailwindcss/typography@0.5.20`, also on its latest release,
  pins exactly 6.0.10. The Manager now overrides it to `^7.1.6`. This is a
  build-time dev dependency. The Manager's generated CSS is byte-for-byte
  identical under 6.0.10 and 7.1.6, across all three stylesheets and 141
  `.prose` rules.
- **GHSA-vfj7-8cjw-p6xm, `braces` stack exhaustion (all versions through 3.0.3,
  with no fixed release).** It reached the repo only through
  `ui/manager/.ds-sync/package-lock.json`, via `@tailwindcss/cli` →
  `@parcel/watcher` → `micromatch`. `.ds-sync` holds local design-sync tooling
  that is never shipped and is not inventoried in the SBOM. Its lockfile is
  removed and gitignored, and the Dependabot entry for that directory stays to
  keep the manifest's direct dependencies current.

Both lockfiles were regenerated with the pinned Node 24.21.0 (npm 11.19.0), so
the diff holds only the dependency change, without `peer`-flag churn from a
different npm. Each nested entry was deleted by hand before `npm install`,
because npm does not apply a new override to an entry the lockfile has already
resolved.

**Files:** [`ui/chat/package.json`](../../ui/chat/package.json),
[`ui/manager/package.json`](../../ui/manager/package.json), both
`package-lock.json` files, [`ui/manager/.gitignore`](../../ui/manager/.gitignore),
[`.github/dependabot.yml`](../../.github/dependabot.yml)

### Next

Drop the two overrides once `rehype-katex`/`micromark-extension-math` accept
KaTeX ≥ 0.18.2 and `@tailwindcss/typography` stops pinning
`postcss-selector-parser` 6.
