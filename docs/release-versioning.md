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
│  mvnw verify     │     Tests + JaCoCo coverage
└────────┬─────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐  ┌──────────────────┐
│ docker │  │ preflight-check  │  ← PRs only
│ build  │  │ Red Hat dry-run  │
│ + push │  └──────────────────┘
└────┬───┘
     │
     ▼
┌────────────┐
│ smoke-test │  ← Starts image + MongoDB, checks /q/health/ready
└────────────┘
```

### Job Details

| Job | Runs on | Condition | Duration |
|---|---|---|---|
| **build-and-test** | Every push/PR/tag | When changed paths match the `code` filter (`src/**`, `pom.xml`, `.github/workflows/**`, `Dockerfile*`, `docker-compose*.yml`, `.dockerignore`, `k8s/**`, `helm/**`, `mvnw*`, `.mvn/**`); always on tags | ~3-5 min |
| **docker** | Push to `main` or a tag matching `[0-9]*` | `[skip docker]` to skip (ignored on tags) | ~3-4 min |
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

After tagging `6.3.0`, update `pom.xml` on the feature branch to the next version:

```bash
# On feature/version-6.4.0 (or rename the branch)
# Update pom.xml: <version>6.4.0</version>
# CI builds will now produce 6.4.0-b1, 6.4.0-b2, etc.
```

`pom.xml` is not the only artefact carrying the release number — the Helm chart, the k8s manifests,
the Dockerfile label, `application.properties` and the bundled agent filename all do too. See the
version-bump entries in [`changelog.md`](changelog.md) for the full file set.

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
