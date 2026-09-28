## 🔖 chore(release): one command for version bumps, run automatically after every release (2026-09-28)

**Repo:** EDDI (`chore/version-rollover`)

### Why

A release meant hand-editing about a dozen lines outside `pom.xml`: the Helm `appVersion` and
image tag, three places in the k8s base Deployment, three in the quickstart, four doc pages, the
Manager's `package.json` + lockfile, and a design-sync literal. Whatever was missed stayed wrong.
`docs/kubernetes.md` named `6.3.0` through the whole 6.4.0 cycle, and `release-signing.md`'s
verification commands still named `6.0.0`, and nothing noticed.

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
  pulling `latest`. Every overlay and example was re-rendered with `kubectl kustomize`; the only
  difference from before is the version label on the base's other resources.
- Manager: `package.json` has no `version` any more. [`vite.config.ts`](../../ui/manager/vite.config.ts)
  reads the first `<version>` from `pom.xml` (the rule `ci.yml` uses) when Maven's `EDDI_VERSION`
  is not set, so `npm run dev` and the CI UI builds show the right version too. `ds-entry.tsx`'s
  cosmetic footer string is now `"preview"` instead of a version literal.

**One definition, one command, two checks.**
- [`scripts/release-pointers.json`](../../scripts/release-pointers.json) says where the
  *published-release* pointers live and what they look like: roots, extensions, prefix exclusions
  (history and the worked tagging examples) and six line-level patterns. The patterns accept a
  version that ends a sentence and unquoted or single-quoted YAML, and still refuse `-RC1`, `-b42`
  and `x.y.z.w`.
- [`scripts/bump-version.py`](../../scripts/bump-version.py) supports:
  - `show` and `check`.
  - `next`, which sets the pom and compares a suffixed pom such as `7.0.0-SNAPSHOT` by its
    numbers.
  - `release`, which moves every pointer plus the chart version: a minor, or a patch for a patch
    release, together with `EXPECTED_CHART_VERSION`. `release <current> --force` repairs strays
    without bumping the chart.
  - `post-release <tag>`, which moves the pom first (so a tag main's pom has not reached still
    works), then the pointers, and never moves the pom backwards.
- `ReleaseVersionSourceTest` reads the same JSON. Every pointer must name the chart's
  `appVersion`, which may never be ahead of the pom. The test also requires:
  - the Helm tag defaults to `appVersion`;
  - the k8s Deployment carries no tag;
  - the Manager's `package.json` carries no version;
  - the pom's first `<version>` is the project's own, with no `<parent>`;
  - CI's path filters trigger the check for every root the JSON sweeps.

  It found the stale `kubernetes.md` line on its first run, and was mutation-checked against it.
- `BumpVersionScriptTest` runs the script against a synthetic repository in eleven tests covering transitions
  and refusals. It feeds the generated changelog fragment through the collator. It also checks
  that the Python and Java sweeps find exactly the same pointers in the real repository, since the
  walk and matching are implemented twice. On CI a missing Python fails it rather than skipping it.
- New `Release Pointers` job in `ci.yml` runs `bump-version.py check` on any change under
  `docs/`, `k8s/`, `helm/` or `README.md`. Build & Test skips a docs-only PR, so without this job
  such a PR could merge a stale pointer. The script and the JSON joined the `operator_docs` filter
  so their Java tests run.

**Automation.** New [`post-release.yml`](../../.github/workflows/post-release.yml), called by the new
`post-release` job in `ci.yml` after `docker`, `smoke-test` and `release` on a stable tag. It can
also be dispatched manually, and refuses a version with no tag on `origin` or no image on Docker
Hub. It runs `post-release <tag> --changelog` and opens `chore(release): after <tag>` from
`chore/post-release-<tag>`. The PR is opened with `RELEASE_BOT_TOKEN`, falling back to
`CHANGELOG_BOT_TOKEN` and then `GITHUB_TOKEN`. With `GITHUB_TOKEN` the PR's checks do not start,
and the PR body says so. The Slack notification now reports this job's result.

Docs: [`release-versioning.md`](../release-versioning.md) "After a GA Release" rewritten around the
two versions, with the major and patch release paths; `build-reproducibility.md` now says where to
pin the k8s digest; `release-signing.md`'s commands are release pointers now; AGENTS.md §1 points at
the script.

### Decisions

- **Two versions, bumped at different times.** The build version moves right after a release; the
  release pointers move only once the image exists. Moving them together, before the tag (the old
  practice), sends quickstart readers on main to a tag that is not published yet.
- **The definition is shared; the implementations are cross-checked.** The script and the test read
  one JSON for scope and patterns, and a test requires the two implementations to find the same
  pointers in the real repository, so neither can quietly cover less than the other.
- **A cheap job for docs, not a Java build.** Adding `docs/**` to `operator_docs` would run the
  whole unit suite on every docs PR to check a handful of version strings.
- **The chart's major is never automatic.** Whether a values file still renders is a judgement
  call, so a breaking chart change takes `--chart-bump major`.
- **The version label covers the whole base** (Namespace, ConfigMap and Service too), as the
  recommended-labels scheme intends. It is metadata only, and selectors are excluded.

### Next

- Set `RELEASE_BOT_TOKEN` (or rely on `CHANGELOG_BOT_TOKEN`) so the post-release PR's checks start
  on their own.
- The next release needs one manual step: `python scripts/bump-version.py next <version>`, because
  the post-release job did not exist when 6.4.0 shipped.
