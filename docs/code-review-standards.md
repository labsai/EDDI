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
- **HTTP clients** — Must use `SafeHttpClient`. No direct `HttpClient.newBuilder()`. All URLs validated via `UrlValidationUtils`.
- **Input validation** — Template injection, path traversal, SSRF vectors
- **Deserialization** — Jackson `@JsonTypeInfo`, polymorphic type handling
- **Dependencies** — New transitive dependencies reviewed for known vulnerabilities

### Review Turnaround

- **Target**: Reviews completed within 2 business days.
- **Stale PRs**: If no review after 3 days, the author should ping reviewers directly.

## Merge Policy

- **Squash and merge** is the default merge strategy for feature branches.
- **Merge commits** are used only for long-lived branches or release merges.
- Branch protection on `main` requires two status checks: **CodeQL Analysis** and **Build & Test** (both from `ci.yml`). The branch does not have to be up to date with `main` first. The other checks (integration and E2E tests, secret scanning, Trivy, changelog discipline, review bots) are not merge-blocking, but a red one is expected to be fixed or explained before merging.
- Force-push to `main` is prohibited (enforced by branch protection and `.githooks/pre-push`).
- **At least one approval must come from a human.** `.github/workflows/auto-approve-copilot.yml` turns a clean Copilot review plus a green CI run into a bot approval, but that approval is *additional*: it only ever satisfies the second of two required approvals, never the first. Whether `main` requires two approvals is a repository setting (Settings → Branches → `main` → *Require approvals*), not something the workflow can read or enforce, so an admin who lowers it to one must disable the workflow (unset the `AUTO_APPROVE_BOT_LOGIN` variable) in the same change. The workflow also never approves a PR from a `COLLABORATOR` (only `OWNER`/`MEMBER` authors) or one that touches CI, build, deploy or the security-critical code (`engine/security/**`, `secrets/**`, `engine/httpclient/**`, `connections/**`, `application.properties`, any `*Guard*.java`); those always wait for a human.

## AI-Assisted Code

Code authored or modified by AI coding agents (Gemini, Copilot, etc.) follows the **same review process** as human-authored code. AI-generated code is not exempt from review requirements.
