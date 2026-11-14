# Code Review Standards

This document defines the code review requirements for the EDDI project.

## Review Requirements

### All Pull Requests

Every pull request must receive at least **one approval** from a core maintainer who is **not the author** before merging to `main`.

### What Reviewers Check

| Category | What to look for |
|----------|-----------------|
| **Correctness** | Does the code do what it claims? Are edge cases handled? |
| **Thread safety** | Lifecycle tasks are singletons — no state in instance variables. All state in `IConversationMemory`. |
| **Action-based orchestration** | Tasks must not call other tasks directly. Communication is through string-based actions. |
| **Configuration vs. code** | Agent behavior belongs in JSON configs, not hardcoded in Java. |
| **Error handling** | External calls wrapped in try-catch. Errors logged with context (conversationId, agentId). No silent swallowing. |
| **Test coverage** | New code must have unit tests. Bug fixes must have regression tests. |
| **Security** | See [Security-Sensitive Review](#security-sensitive-review) below. |
| **API compatibility** | REST API changes must not break existing clients. New fields should have defaults. |

### Security-Sensitive Review

Changes touching the following areas require **extra scrutiny** and explicit security sign-off:

- **Authentication / authorization** — `AuthStartupGuard`, OIDC configuration, role checks
- **Secrets handling** — `VaultSecretProvider`, `VaultSaltManager`, `SecretResolver`, property `scope: secret`
- **HTTP clients** — Must use `OutboundHttp`. No direct `HttpClient.newBuilder()`. A user- or model-chosen URL keeps `SsrfPolicy.STRICT`; every body is read with a capped `BodyMode`.
- **Input validation** — Template injection, path traversal, SSRF vectors
- **Deserialization** — Jackson `@JsonTypeInfo`, polymorphic type handling
- **Dependencies** — New transitive dependencies reviewed for known vulnerabilities

### Review Turnaround

- **Target**: Reviews completed within 2 business days.
- **Stale PRs**: If no review after 3 days, the author should ping reviewers directly.

## Merge Policy

- **Squash and merge** is the default merge strategy for feature branches.
- **Merge commits** are used only for long-lived branches or release merges.
- Branch protection on `main` requires two status checks: **CodeQL Analysis** and **Build & Test** (both from `ci.yml`). The branch does not have to be up to date with `main` first; the [merge queue](#merge-queue) is what tests a PR against the `main` it will actually land on. The other checks (integration and E2E tests, secret scanning, Trivy, changelog discipline, review bots) are not merge-blocking, but a red one is expected to be fixed or explained before merging.
- Force-push to `main` is prohibited (enforced by branch protection and `.githooks/pre-push`).
- **At least one approval must come from a human.** `.github/workflows/auto-approve-copilot.yml` turns a clean Copilot review plus a green CI run into a bot approval, but that approval is *additional*: it only ever satisfies the second of two required approvals, never the first. Whether `main` requires two approvals is a repository setting (Settings → Branches → `main` → *Require approvals*), not something the workflow can read or enforce, so an admin who lowers it to one must disable the workflow (unset the `AUTO_APPROVE_BOT_LOGIN` variable) in the same change. The workflow also never approves a PR from a `COLLABORATOR` (only `OWNER`/`MEMBER` authors) or one that touches CI, build, deploy or the security-critical code (`engine/security/**`, `secrets/**`, `engine/httpclient/**`, `connections/**`, `application.properties`, any `*Guard*.java`); those always wait for a human.

### Merge queue

Two PRs can each be green and still break `main` together. On 2026-11-12 #1352 renamed `ResourceAccessGuard.callerIsAdmin()` to `isAdmin()` while #1345 added a test that called `callerIsAdmin()`. Both passed their checks against an older `main`, they were merged minutes apart, and `main`'s test sources stopped compiling. Requiring branches to be up to date would catch that too, but it makes every author rebase and rerun the whole pipeline each time anything else lands. A merge queue does that work for them.

With the queue on, **Merge when ready** adds the PR to the queue instead of merging it. GitHub builds a temporary branch, `gh-readonly-queue/main/pr-<n>-<sha>`, holding `main` plus every PR ahead in the queue plus this one, and fires the `merge_group` event at it. `ci.yml` runs on that event, and the PR merges once the two required checks pass there. If they fail, the PR leaves the queue and the entries behind it are rebuilt without it.

What `ci.yml` does on `merge_group`:

| Job | On a queue entry |
| --- | --- |
| Detect Changes | Runs, but skips the path filter and forces every filter true, exactly as for a release tag. A queue entry runs every test path; it does not trust a diff to say what could have broken. |
| Maven Cache, **Build & Test**, **CodeQL Analysis** | Run and report. These are the checks the queue waits for. |
| Integration Tests, Deployment Manifests, Shell Lint, Release Pointers, Trivy, CodeQL (UI), the UI jobs and UI Gate, Build Image, OpenAPI Snapshot, Auth E2E, E2E Gate | Run as on a push to `main`. They are not required, so the queue does not wait for them, but a red one is visible on the run. |
| Backend E2E | MongoDB leg only, as on a pull request. The push to `main` that follows still runs MongoDB and PostgreSQL. |
| Secret Scanning | Scans `merge_group.base_sha..merge_group.head_sha`, every commit the queue stacked on `main`, with `.gitleaksignore` and `.gitleaks.toml` taken from the base, as for a pull request. |
| Changelog Discipline, Preflight Dry-Run (PR) | Do not run: they are pull-request checks and already ran on the PR. |
| Generate SBOM, Docker Build & Push, Smoke Test, Preflight Verify, GitHub Release, Red Hat Catalog Publish, Post-Release Version Bump | Do not run. Nothing is published from a queue entry; publishing stays on `push` and tags. |
| Slack Notification | Does not run. A failing entry is reported on its PR, and the push to `main` that a passing entry produces notifies as before. |

Queue runs use `cancel-in-progress`, like pull requests. Each entry has a ref of its own, so entries building side by side never cancel each other. `BuildQualityGatesTest` checks this contract: the trigger is present, the required checks and everything they `need` can run on `merge_group`, and every publishing job is unreachable from it.

**Turning it on (repository admin).** A workflow cannot switch this on itself. Settings → Branches → the `main` rule (or, if `main` moves to rulesets, Settings → Rules → Rulesets → the `main` ruleset) → **Require merge queue**, then:

- **Merge method**: Squash, the project's default merge strategy.
- **Build concurrency**: 2 to 3. Every entry runs the full pipeline (about 25 jobs; Build & Test alone takes about 15 minutes), and that competes for the same runners as PR and `main` runs. A higher value just lengthens the runner queue.
- **Minimum pull requests to merge**: 1, with a **Wait time** of about 5 minutes, so a single PR does not wait for company.
- **Maximum pull requests to merge**: about 5. Larger groups use fewer runs, but a failure in a large group costs a rebuild of every entry behind it.
- **Only merge non-failing pull requests**: on. Each PR then needs its own entry to pass, not just a later group that contains it.
- **Status check timeout**: about 90 minutes. A run takes 30 to 70 minutes in total when runners are busy.
- **Require status checks to pass**: keep **Build & Test** and **CodeQL Analysis** as the required checks. "Require branches to be up to date" can stay off: the queue does that job.

The names above are from GitHub's [merge queue documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue), which describes the branch-protection path. The ruleset path is assumed to offer the same settings and has not been checked. A required check that a workflow skips with an `if:` counts as passing, so a queue only protects `main` while `ci.yml` keeps running both required checks on `merge_group`.

## AI-Assisted Code

Code authored or modified by AI coding agents (Gemini, Copilot, etc.) follows the **same review process** as human-authored code. AI-generated code is not exempt from review requirements.
