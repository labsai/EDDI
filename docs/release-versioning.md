# Release & Versioning Strategy

> **Audience:** Maintainers, contributors, and CI/CD operators.

## Version Format

EDDI follows [Semantic Versioning](https://semver.org/):

```text
MAJOR.MINOR.PATCH[-PRERELEASE]
```

| Component | Meaning | Example |
|---|---|---|
| `MAJOR` | Breaking API/config changes | `6.0.0` → `7.0.0` |
| `MINOR` | New features, backward-compatible | `6.0.0` → `6.1.0` |
| `PATCH` | Bug fixes only | `6.0.0` → `6.0.1` |
| `PRERELEASE` | Release candidate or beta | `6.0.0-RC1`, `6.0.0-RC2` |

The canonical version lives in `pom.xml` — the top-level `<version>` element, which CI reads with
`grep -m1 '<version>' pom.xml` — and is used for Maven artifacts and CI build tags. (Deliberately
not quoted with a concrete number here: this page is not a place that should need editing on every
release.)

> ### ⚠️ Release tags are **not** `v`-prefixed
>
> `ci.yml` triggers on `tags: ["[0-9]*"]`, so a release tag must **start with a digit**: `6.3.0`,
> never `v6.3.0`. This is not a style preference — a `v`-prefixed tag matches nothing, so pushing
> one runs **no workflow at all**: no build, no image, no `latest`, no cosign signature, no SLSA
> attestation and no GitHub release. Nothing fails, because nothing starts. The tag name is also
> used *verbatim* as the Docker tag (`PRIMARY_TAG="${GITHUB_REF#refs/tags/}"`), so the `v` would
> not be stripped even if it did fire.

---

## Branching Model

```text
main ─────────────────────────────────────── production
  ↑
  │  merge when ready
  │
feature/version-6.3.0 ───────────────────── active development
```

| Branch | Purpose | Docker push? |
|---|---|---|
| `main` | Production-ready code | ✅ Build tags on every push |
| `feature/version-X.Y.Z` | Active development branch | ❌ No Docker push |
| Pull requests → `main` | Code review, CI validation | ❌ Tests + preflight only |

---

## Docker Tag Strategy

All images are pushed to [Docker Hub: `labsai/eddi`](https://hub.docker.com/r/labsai/eddi).

| Trigger | Docker Tags | Purpose |
|---|---|---|
| Push to `main` | `labsai/eddi:6.3.0-b<N>` | Continuous integration build. `<N>` is the GitHub Actions run number. |
| Git tag `6.3.0-RC1` | `labsai/eddi:6.3.0-RC1` + `labsai/eddi:latest` | Release candidate |
| Git tag `6.3.0` | `labsai/eddi:6.3.0` + `labsai/eddi:6.3` + `labsai/eddi:6` + `labsai/eddi:latest` | General availability release |

> **Key rule:** `latest` is **only** pushed on tag-based releases (RC or GA), never on regular main builds. This ensures `docker pull labsai/eddi` always gives users a deliberately released version.
>
> **The `6.3` and `6` aliases are moving tags**, and only a *stable* release publishes them — CI
> gates them on `^([0-9]+)\.([0-9]+)\.([0-9]+)$`, so an RC never claims them. They are a
> convenience for "track the latest 6.x", not something to deploy from: pin the immutable
> `6.3.0` (better, `6.3.0@sha256:<digest>`) in manifests, which is why `helm/eddi/values.yaml`
> and the k8s manifests use the full patch version.

### Build Tags

Every push to `main` produces a unique, immutable build tag:

```text
labsai/eddi:6.3.0-b42
                  │  │
                  │  └── GitHub Actions run number (auto-incrementing)
                  └───── Version from pom.xml
```

These are useful for:
- Pinning deployments to a specific build
- Debugging issues ("which exact build is running?")
- Rolling back to a known-good build

---

## How to Release

### Release Candidate

```bash
# 1. Ensure feature branch is merged to main
git checkout main
git pull origin main

# 2. Tag the release candidate — no "v" prefix, or nothing triggers
git tag 6.3.0-RC1

# 3. Push the tag — CI pipeline triggers automatically
git push origin 6.3.0-RC1
```

This produces:
- `labsai/eddi:6.3.0-RC1` — the version-pinned tag
- `labsai/eddi:latest` — updated to point to this RC

An RC does **not** move the `6.3` or `6` aliases; only a stable release does.

### Subsequent Release Candidates

If RC1 needs fixes:

```bash
# 1. Fix on feature branch, merge to main
# 2. Tag the new main HEAD
git checkout main
git pull origin main
git tag 6.3.0-RC2
git push origin 6.3.0-RC2
```

### General Availability Release

```bash
git tag 6.3.0
git push origin 6.3.0
```

This is the only trigger that publishes the moving `6.3` and `6` aliases alongside `6.3.0` and
`latest`.

`pom.xml` must already say `6.3.0` — CI refuses a tag that disagrees with it. It normally does: the
previous release's post-release PR set it (see [After a GA Release](#after-a-ga-release)). Two
cases where it does not:

- **The next release is a major** (the PR assumed `6.4.0`, you are shipping `7.0.0`): run
  `python scripts/bump-version.py next 7.0.0` on a branch and merge it before tagging. `next`
  refuses to go backwards, so this only works upwards.
- **A patch release** (`6.3.1` while main already says `6.4.0`): do not pull main's pom back. Cut
  a release branch from the `6.3.0` tag, fix there, run `python scripts/bump-version.py next
  6.3.1` on that branch and tag it. After it is published, the post-release PR on main moves the
  release pointers to `6.3.1` and leaves main's `6.4.0` alone.

> **If nothing happens after pushing a tag, check the prefix first.** A `v`-prefixed tag does not
> match the `[0-9]*` trigger, and GitHub reports no error for a tag that matches no workflow — the
> push simply succeeds and nothing runs. Delete it (`git push origin :refs/tags/v6.3.0`) and re-tag
> without the `v`.

### Red Hat Certification Release

For Red Hat-certified images, use the separate workflow:

```text
GitHub → Actions → "Red Hat Certification Release" → Run workflow
```

This builds, pushes, and submits the image to Red Hat's preflight certification system.

---

## Skipping Docker Builds

For documentation, config, or non-code commits, add `[skip docker]` to the commit message:

```bash
git commit -m "docs: update README [skip docker]"
```

This skips the Docker build and smoke test jobs, but **tests still run** — unless the commit touches no path in the `code` filter, in which case `build-and-test` is skipped as well.

| Commit message | Tests | Docker build | Smoke test |
|---|---|---|---|
| `feat: add new API endpoint` | ✅ | ✅ | ✅ |
| `docs: update changelog [skip docker]` | ❌ (docs-only paths skip `build-and-test` too) | ❌ | ❌ |
| Any tag push (`6.3.0-RC1`) | ✅ | ✅ (always) | ✅ |

> `[skip docker]` is ignored on tag pushes — releases always build Docker images.

---

## CI/CD Pipeline

The entire pipeline lives in a single file: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

```text
┌──────────────────┐
│  build-and-test  │  ← Always runs (push, PR, tag)
│  mvnw clean test │     Unit tests only
└────────┬─────────┘
         │
         ├──────────────┬─────────────┬────────────┐
         ▼              ▼             ▼            ▼
┌──────────────────┐ ┌────────────┐ ┌────────┐ ┌──────────┐
│ integration-test │ │ trivy-scan │ │ codeql │ │ gitleaks │
│ mvnw verify      │ └─────┬──────┘ └───┬────┘ └────┬─────┘
│ -DskipITs=false  │       │            │           │
│ ITs + the JaCoCo │       │            │           │
│ 90/80 gate       │       │            │           │
└────────┬─────────┘       │            │           │
         └─────────────────┴────────────┴───────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
         ┌────────┐          ┌──────────────────┐
         │ docker │          │ preflight-check  │  ← PRs only
         │ build  │          │ Red Hat dry-run  │
         │ + push │          └──────────────────┘
         └───┬────┘
             ▼
       ┌────────────┐
       │ smoke-test │  ← Starts image + MongoDB, checks /q/health/ready
       └────────────┘
```

Nothing is published until every gate above `docker` has passed. Its `needs` list is
`[detect-changes, build-and-test, integration-test, trivy-scan, codeql, gitleaks]`, so a failing
CVE scan, a secret leak, a CodeQL finding or a coverage shortfall each block the push on their
own. **`build-and-test` runs `mvnw clean test`, not `verify`** — `integration-test` is the only
job that runs the JaCoCo 90/80 coverage gate.

### Job Details

| Job | Runs on | Condition | Duration |
|---|---|---|---|
| **build-and-test** | Every push/PR/tag | When changed paths match the `code` filter (`src/**`, `pom.xml`, `.github/workflows/**`, `Dockerfile*`, `docker-compose*.yml`, `.dockerignore`, `k8s/**`, `helm/**`, `mvnw*`, `.mvn/**`); always on tags | ~3-5 min |
| **integration-test** | Same as build-and-test | Runs `mvnw verify -DskipITs=false`; the only job that enforces the JaCoCo 90/80 gate | ~10-15 min |
| **codeql** | Every push/PR/tag on the `code` filter | SAST build + analysis; blocks `docker` | ~8-12 min |
| **trivy-scan** | Same | Filesystem CVE scan, `exit-code 1`; blocks `docker` | ~2-3 min |
| **gitleaks** | Same | Secret scanning; blocks `docker` | ~1 min |
| **sbom** | Same | CycloneDX SBOM; does not block `docker` | ~2 min |
| **docker** | Push to `main` or a tag matching `[0-9]*` | `[skip docker]` to skip (ignored on tags). Needs detect-changes, build-and-test, integration-test, trivy-scan, codeql and gitleaks | ~3-4 min |
| **smoke-test** | After `docker` succeeds | Same as docker | ~1-2 min |
| **preflight-check** | Pull requests only | Always on PRs | ~5-7 min |

### Secrets Required

Configure these in GitHub → Settings → Secrets → Actions:

| Secret | Purpose |
|---|---|
| `DOCKER_USERNAME` | Docker Hub login |
| `DOCKER_PASSWORD` | Docker Hub access token |
| `REDHAT_API_TOKEN` | Red Hat certification (only for `redhat-certify.yml`) |
| `REDHAT_CERT_PROJECT_ID` | Red Hat project ID (only for `redhat-certify.yml`) |

---

## Local Preflight Check (Windows)

Run Red Hat certification checks locally without needing Linux:

```powershell
# Full build + label check + preflight
.\scripts\preflight-local.ps1

# Skip Maven/Docker build, use existing image
.\scripts\preflight-local.ps1 -SkipBuild

# Just verify Red Hat labels are present
.\scripts\preflight-local.ps1 -LabelsOnly
```

Requires Docker Desktop for Windows. The `preflight` tool runs inside a Docker container — no WSL needed.

---

## Version Lifecycle

```text
Development                 Release Candidates          General Availability
─────────────────           ──────────────────          ────────────────────
feature/version-6.3.0       tag 6.3.0-RC1               tag 6.3.0
    │                       │                           │
    ├── merge to main       ├── tag → Docker push       ├── tag → Docker push
    │   → 6.3.0-b1          │   → 6.3.0-RC1 + latest    │   → 6.3.0 + latest
    ├── merge to main       │                           │      + 6.3 + 6
    │   → 6.3.0-b2          tag 6.3.0-RC2               │
    ├── merge to main       │                           └── start 6.4.0 cycle
    │   → 6.3.0-b3          └── 6.3.0-RC2 + latest
    └── ...
```

Every tag in this diagram is written exactly as it must be pushed — bare, with no `v`.

### After a GA Release

**Nothing to do by hand.** Once a stable tag's image is published and smoke-tested, `ci.yml`'s
`post-release` job runs [`post-release.yml`](../.github/workflows/post-release.yml), which opens a
PR titled `chore(release): after 6.3.0`. Review and merge it. It does two things, and they belong
to two different versions:

| Version | Where it lives | Moves |
|---|---|---|
| **Build version** — what main is building | `pom.xml` only. `application.properties`, the OpenAPI document, the image label and the Manager's sidebar all derive from it | To the next minor (`6.3.0` → `6.4.0`) **right after** the release, so every snapshot from then on is `6.4.0-b<N>` |
| **Published release** — what a reader should deploy | The release pointers: Helm `appVersion`, the k8s base and quickstart, the docs' copy-pasteable commands — everything [`scripts/release-pointers.json`](../scripts/release-pointers.json) matches | To the new release, but **only once its image exists** — which is why this happens after the tag, not before |

Without the first, main publishes `6.3.0-b<N>` for a whole cycle after `6.3.0` shipped, and a
pre-release suffix sorts *before* the release — so every snapshot looks older than the version it
came after. Without the second, the quickstart keeps deploying the previous release.

Both are done by one script, which you can also run yourself:

```bash
python scripts/bump-version.py show                   # both versions, and every pointer
python scripts/bump-version.py post-release 6.3.0     # what the PR contains
python scripts/bump-version.py next 7.0.0             # e.g. the next release is a major
python scripts/bump-version.py release 6.3.0          # pointers only (the chart version follows)
```

`release` also bumps the Helm chart's own `version` — a minor, or a patch for a patch release —
along with the constant `DeploymentManifestsTest` pins it to. A breaking chart change is a human
decision: pass `--chart-bump major`. `post-release` never moves `pom.xml` backwards, so a patch
release cut from an older line moves only the pointers.

`ReleaseVersionSourceTest` enforces the result: every release pointer must name the chart's
`appVersion`, which may never be ahead of `pom.xml`. The `Release Pointers` job in `ci.yml` runs the
same check (`bump-version.py check`) on any PR that touches `docs/`, `k8s/`, `helm/` or `README.md`,
including a docs-only one that skips Build & Test. A new doc that writes `labsai/eddi:<x.y.z>` is
covered without anyone listing it, and a stale one fails the build instead of shipping. History is
excluded (the changelog, `docs/archive/`, `docs/release-notes-*`), and so is this page, whose
version numbers are worked examples.

**Tokens.** The PR is opened with `RELEASE_BOT_TOKEN`, or `CHANGELOG_BOT_TOKEN`, a bot account's
fine-grained PAT. With neither set, it falls back to the default `GITHUB_TOKEN`, and GitHub then
does not start the PR's required checks: close and reopen the PR to start them. That fallback also
needs the repository setting *Actions → General → Allow GitHub Actions to create and approve pull
requests*, which the nightly changelog collation relies on as well.

**Redoing a run.** The workflow can be dispatched by hand with the release version. It refuses a
version that has no tag on `origin` or no image on Docker Hub, and does nothing if main already
reflects it.

---

## Release Signing

All Docker images pushed by CI are **cryptographically signed** using [Sigstore cosign](https://github.com/sigstore/cosign) with keyless OIDC signing. This ensures that users can verify any image was built by the official `labsai/EDDI` GitHub Actions pipeline.

For full details on how signing works and how to verify images, see [Release Signing & Verification](release-signing.md).

### Signed Git Tags

When creating release tags, use signed tags:

```bash
# Instead of: git tag 6.3.0
# Use:
git tag -s 6.3.0 -m "Release 6.3.0"
git push origin 6.3.0
```

Signing changes how the tag is created, not what it is called — it is still bare, with no `v`.
