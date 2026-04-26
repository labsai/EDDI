# Security Review Summary

This document provides a formal summary of security reviews performed on the EDDI project, as required by the [OpenSSF Best Practices](https://www.bestpractices.dev/) Gold criteria.

## Review Scope

EDDI is a multi-agent orchestration middleware. Its security boundary includes:

- **REST API endpoints** — agent management, conversation handling, resource CRUD
- **LLM tool execution** — HTTP calls, MCP calls, built-in tools invoked by AI models
- **Secrets management** — API keys, vault master key, encrypted property storage
- **Template engine** — Qute-based templating with user-controlled inputs
- **Container runtime** — Docker image, base image supply chain, Kubernetes deployment

## Automated Security Tooling (Continuous)

The following tools run **on every push and PR** via [GitHub Actions CI](https://github.com/labsai/EDDI/actions/workflows/ci.yml):

| Tool | Type | Scope | Frequency |
|------|------|-------|-----------|
| **CodeQL** | SAST | Java source code — injection, XSS, hardcoded creds, unsafe deserialization | Every push + PR |
| **Trivy** | Container + FS scanning | Filesystem scan (every push + PR), Docker image scan (every push to `main`) | Every CI run |
| **Gitleaks** | Secret scanning | Git history + staged files for leaked credentials | Every push + PR |
| **Dependency Review** | SCA | New dependency license + vulnerability check on PRs | Every PR |
| **OWASP ZAP** | DAST | API scan against live EDDI instance in CI | Every push to `main` |
| **ClusterFuzzLite** | Fuzz testing | `PathNavigator`, `MatchingUtilities` via Jazzer | PRs touching `src/` + weekly batch |
| **Cosign** | Supply chain | Keyless OIDC signing of Docker images | Every push to `main` + tags |
| **CycloneDX** | SBOM | Software Bill of Materials generation | Every push to `main` |
| **SLSA Provenance** | Attestation | Build provenance attestation (Level 1) | Every push to `main` + tags |

## Manual Security Reviews

### Security Hardening Sprint — April 2026

**Scope:** Full codebase security audit  
**Reviewers:** Project maintainers + AI-assisted code review (CodeRabbit, CodeQL)  
**Duration:** April 2–24, 2026

#### Phase 1: SAST Remediation (April 2)
- CodeQL scanner findings remediation — 9 findings across 6 files
- Fixed: regex injection (ReDoS), path traversal, error message exposure
- CVE-2026-25526 (Jinjava template injection) — mitigated by upgrading dependency

#### Phase 2: SSRF & Input Validation (April 13)
- `SafeHttpClient` introduced — all HTTP calls must go through SSRF-validated client
- `UrlValidationUtils` — blocks private/loopback/link-local IPs
- `PathNavigator` replaced OGNL (eliminated arbitrary code execution risk)
- Regex injection hardening in user-facing pattern inputs

#### Phase 3: Secrets & Auth (March–April 2026)
- `VaultSecretProvider` — encrypted at-rest secret storage with salt rotation
- `AuthStartupGuard` — validates OIDC configuration at boot
- API key auto-vaulting — keys stored as vault references, never in plaintext configs
- Property `scope: secret` — automatic encryption for sensitive conversation properties

#### Phase 4: Container & Supply Chain (April 22–23)
- Base image pinned by digest (immutable reference)
- Trivy vulnerability remediation — CVE-2026-4424 (libarchive)
- Cosign keyless signing of all published images
- CycloneDX SBOM attached to every release

### Code Review Security Passes

| Date | Scope | Key Findings |
|------|-------|-------------|
| 2026-04-02 | CodeQL findings — 2 passes | Resolved all HIGH/CRITICAL findings |
| 2026-04-13 | SSRF & regex injection | Introduced `SafeHttpClient`, `UrlValidationUtils` |
| 2026-04-16 | Security hardening sprints 1 & 2 | Template injection guards, auth hardening |
| 2026-04-17 | Code review remediation | Post-review fixes from security audit |
| 2026-04-22 | CI security scanning hardening | Trivy, Gitleaks, dependency review pipeline |
| 2026-04-23 | Fuzzing + SLSA provenance | ClusterFuzzLite, Jazzer targets, build attestation |

## Threat Model

EDDI's security architecture is documented in [docs/security.md](security.md). Key threats and mitigations:

- **T1: Prompt injection** — mitigated by template sandboxing, PathNavigator (no OGNL)
- **T2: SSRF via tool execution** — mitigated by `SafeHttpClient`, URL validation
- **T3: Secret exfiltration** — mitigated by vault encryption, property visibility scoping
- **T4: Supply chain compromise** — mitigated by digest-pinned images, Cosign, SBOM, Dependabot
- **T5: Unauthorized access** — mitigated by OIDC auth, `AuthStartupGuard`, role checks

## Compliance

- **EU AI Act** — Audit ledger for AI decision traceability
- **HIPAA** — Documentation of PHI handling controls
- **GDPR/CCPA** — Cascading erasure, data portability, per-category retention policies
