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

## AI-Assisted Code

Code authored or modified by AI coding agents (Gemini, Copilot, etc.) follows the **same review process** as human-authored code. AI-generated code is not exempt from review requirements.
