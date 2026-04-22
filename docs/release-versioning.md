# Release & Versioning Strategy

> **Audience:** Maintainers, contributors, and CI/CD operators.

## Version Format

EDDI follows [Semantic Versioning](https://semver.org/):

```
MAJOR.MINOR.PATCH[-PRERELEASE]
```

| Component | Meaning | Example |
|---|---|---|
| `MAJOR` | Breaking API/config changes | `6.0.0` → `7.0.0` |
| `MINOR` | New features, backward-compatible | `6.0.0` → `6.1.0` |
| `PATCH` | Bug fixes only | `6.0.0` → `6.0.1` |
| `PRERELEASE` | Release candidate or beta | `6.0.0-RC1`, `6.0.0-RC2` |

The canonical version lives in `pom.xml` (`<version>6.0.0</version>`) and is used for Maven artifacts and CI build tags.

---

## Branching Model

```
main ─────────────────────────────────────── production
  ↑
  │  merge when ready
  │
feature/version-6.0.0 ───────────────────── active development
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
| Push to `main` | `labsai/eddi:6.0.0-b<N>` | Continuous integration build. `<N>` is the GitHub Actions run number. |
| Git tag `v6.0.0-RC1` | `labsai/eddi:6.0.0-RC1` + `labsai/eddi:latest` | Release candidate |
| Git tag `v6.0.0` | `labsai/eddi:6.0.0` + `labsai/eddi:latest` | General availability release |

> **Key rule:** `latest` is **only** pushed on tag-based releases (RC or GA), never on regular main builds. This ensures `docker pull labsai/eddi` always gives users a deliberately released version.

### Build Tags

Every push to `main` produces a unique, immutable build tag:

```
labsai/eddi:6.0.0-b42
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

# 2. Tag the release candidate
git tag v6.0.0-RC1

# 3. Push the tag — CI pipeline triggers automatically
git push origin v6.0.0-RC1
```

This produces:
- `labsai/eddi:6.0.0-RC1` — the version-pinned tag
- `labsai/eddi:latest` — updated to point to this RC

### Subsequent Release Candidates

If RC1 needs fixes:

```bash
# 1. Fix on feature branch, merge to main
# 2. Tag the new main HEAD
git checkout main
git pull origin main
git tag v6.0.0-RC2
git push origin v6.0.0-RC2
```

### General Availability Release

```bash
git tag v6.0.0
git push origin v6.0.0
```

### Red Hat Certification Release

For Red Hat-certified images, use the separate workflow:

```
GitHub → Actions → "Red Hat Certification Release" → Run workflow
```

This builds, pushes, and submits the image to Red Hat's preflight certification system.

---

## Skipping Docker Builds

For documentation, config, or non-code commits, add `[skip docker]` to the commit message:

```bash
git commit -m "docs: update README [skip docker]"
```

This skips the Docker build and smoke test jobs, but **tests still run**.

| Commit message | Tests | Docker build | Smoke test |
|---|---|---|---|
| `feat: add new API endpoint` | ✅ | ✅ | ✅ |
| `docs: update changelog [skip docker]` | ✅ | ❌ | ❌ |
| Any tag push (`v6.0.0-RC1`) | ✅ | ✅ (always) | ✅ |

> `[skip docker]` is ignored on tag pushes — releases always build Docker images.

---

## CI/CD Pipeline

The entire pipeline lives in a single file: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

```
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
| **build-and-test** | Every push/PR/tag | Always | ~3-5 min |
| **docker** | Push to `main` or tag `v*` | `[skip docker]` to skip | ~3-4 min |
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

```
Development             Release Candidates          General Availability
─────────────────       ──────────────────          ────────────────────
feature/version-6.0.0   v6.0.0-RC1                  v6.0.0
    │                       │                           │
    ├── merge to main       ├── tag → Docker push       ├── tag → Docker push
    │   → 6.0.0-b1          │   → 6.0.0-RC1 + latest    │   → 6.0.0 + latest
    ├── merge to main       │                           │
    │   → 6.0.0-b2         v6.0.0-RC2                  │
    ├── merge to main       │                           └── start v6.1.0 cycle
    │   → 6.0.0-b3          └── 6.0.0-RC2 + latest
    └── ...
```

### After a GA Release

After tagging `v6.0.0`, update `pom.xml` on the feature branch to the next version:

```bash
# On feature/version-6.1.0 (or rename the branch)
# Update pom.xml: <version>6.1.0</version>
# CI builds will now produce 6.1.0-b1, 6.1.0-b2, etc.
```

---

## Release Signing

All Docker images pushed by CI are **cryptographically signed** using [Sigstore cosign](https://github.com/sigstore/cosign) with keyless OIDC signing. This ensures that users can verify any image was built by the official `labsai/EDDI` GitHub Actions pipeline.

For full details on how signing works and how to verify images, see [Release Signing & Verification](release-signing.md).

### Signed Git Tags

When creating release tags, use signed tags:

```bash
# Instead of: git tag v6.0.0
# Use:
git tag -s v6.0.0 -m "Release 6.0.0"
git push origin v6.0.0
```
