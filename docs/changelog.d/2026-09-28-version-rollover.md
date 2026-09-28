## 🔖 chore(release): one command for version bumps, run automatically after every release (2026-09-28)

**Repo:** EDDI (`chore/version-rollover`)

### Why

A release meant hand-editing about a dozen lines outside `pom.xml`: the Helm `appVersion` and
image tag, three places in the k8s base Deployment, three in the quickstart, four doc pages, the
Manager's `package.json` + lockfile, and a design-sync literal. Whatever was missed stayed wrong.
`docs/kubernetes.md` named `6.3.0` through the whole 6.4.0 cycle, and nothing noticed.

The timing was wrong too. The pom was bumped just before a release instead of right after one, so
for a whole cycle main published `6.4.0-b<N>` snapshots *after* 6.4.0 had shipped. A pre-release
suffix sorts before the release, so every snapshot looked older than the version it followed.

### What changed

**Fewer copies.**
- Helm: `values.yaml`'s `eddi.image.tag` is now empty and falls back to `appVersion` through a new
  `eddi.imageTag` helper ([`_helpers.tpl`](../../helm/eddi/templates/_helpers.tpl), used by
  `deployment.yaml` and `NOTES.txt`). Chart `2.1.0 → 2.2.0`, a non-breaking change: an explicit
  tag still wins.
- k8s: the base Deployment no longer names a tag. [`kustomization.yaml`](../../k8s/base/kustomization.yaml)
  pins it through `images:` and sets `app.kubernetes.io/version` through `labels:` (selectors
  untouched). The Deployment's placeholder, `labsai/eddi:pinned-by-kustomization`, is a tag that
  does not exist, so applying the file without kustomize fails with `ErrImagePull` rather than
  pulling `latest`. Every overlay and example was re-rendered with `kubectl kustomize` to check it
  gets the real tag.
- Manager: `package.json` has no `version` any more. [`vite.config.ts`](../../ui/manager/vite.config.ts)
  reads the first `<version>` from `pom.xml` (the rule `ci.yml` uses) when Maven's `EDDI_VERSION`
  is not set, so `npm run dev` and the CI UI builds show the right version too. `ds-entry.tsx`'s
  cosmetic footer string is now `"preview"` instead of a version literal.

**One definition, one command, one check.**
- [`scripts/release-pointers.json`](../../scripts/release-pointers.json) says where the
  *published-release* pointers live and what they look like: roots, extensions, exclusions (the
  worked tagging examples and the changelog) and six patterns.
- [`scripts/bump-version.py`](../../scripts/bump-version.py) supports `show`, `check`, `next`
  (the pom), `release` (every pointer, plus the chart version: a minor, or a patch for a patch
  release, together with `EXPECTED_CHART_VERSION`) and `post-release <tag>`, which combines both
  and never moves the pom backwards.
- `ReleaseVersionSourceTest` reads the same JSON. Every pointer must name the chart's
  `appVersion`, which may never be ahead of the pom. The test also requires that the Helm tag
  default to it, that the k8s Deployment carry no tag, and that the Manager's `package.json` carry
  no version. It found the stale `kubernetes.md` line on its first run; the test was
  mutation-checked against it.
- `BumpVersionScriptTest` runs the script against a synthetic repository in a temp dir and covers
  seven transitions and refusals. The test is skipped where there is no Python 3; CI runners have
  one.

**Automation.** New [`post-release.yml`](../../.github/workflows/post-release.yml), called by the new
`post-release` job in `ci.yml` after `docker`, `smoke-test` and `release` on a stable tag. It can
also be dispatched manually. It runs `post-release <tag> --changelog` and opens `chore(release):
after <tag>` from `chore/post-release-<tag>`. The PR is opened with `RELEASE_BOT_TOKEN`, falling
back to `CHANGELOG_BOT_TOKEN` and then `GITHUB_TOKEN`. With `GITHUB_TOKEN` the PR's checks do not
start, and the PR body says so.

Docs: [`release-versioning.md`](../release-versioning.md) "After a GA Release" rewritten around the
two versions; `build-reproducibility.md` now says where to pin the k8s digest; AGENTS.md §1 points
at the script.

### Decisions

- **Two versions, bumped at different times.** The build version moves right after a release; the
  release pointers move only once the image exists. Moving them together, before the tag (the old
  practice), sends quickstart readers on main to a tag that is not published yet.
- **The JSON is shared, not duplicated.** The script and the test read the same scope and patterns,
  so neither can quietly cover less than the other.
- **The chart's major is never automatic.** Whether a values file still renders is a judgement
  call, so a breaking chart change takes `--chart-bump major`.

### Next

- The open `chore/release-6.5.0` branch (hand-made, unpushed) is superseded: once this merges,
  `python scripts/bump-version.py next 6.5.0` then, after tagging, the automatic post-release PR.
- Create a `RELEASE_BOT_TOKEN` secret, or rely on `CHANGELOG_BOT_TOKEN`, so the post-release PR's
  checks start on their own.
