# EDDI 6.0.2 Release Notes

**Release Date:** April 2026  
**Platform:** Java 25 · Quarkus 3.34.5 · langchain4j 1.13.0

> EDDI 6.0.2 is a **security hardening and quality assurance** release. It delivers comprehensive security fixes, a 2.5× increase in test coverage, a fully rewritten Admin UI, and a hardened CI/CD pipeline — bringing the platform to OpenSSF Silver compliance readiness.

---

## 🔐 Security Hardening

### SSRF Prevention
- **SafeHttpClient** — New centralized, SSRF-safe HTTP client with per-hop redirect validation. All LLM tools (WebScraper, PdfReader, WebSearch, Weather) migrated from inline `HttpClient` to `@Inject SafeHttpClient`
- **UrlValidationUtils** — Extended to block IPv6 ULA, CGNAT, IPv4-mapped IPv6, multicast, and unspecified address ranges
- **Redirect hardening** — Manual redirect loop (max 5 hops) with same-origin/cross-origin header policies. Overall wall-clock timeout across redirect chains

### Vault & Encryption
- **Per-deployment random KEK salt** — Each EDDI instance now generates a unique 16-byte salt for key derivation (was hardcoded). Backward-compatible: existing deployments auto-detect and use legacy salt
- **KEK rotation salt migration** — `rotateKek()` now properly migrates from legacy to new salt during key rotation
- **API key auto-vaulting** — Agent setup automatically stores API keys in the Secrets Vault, persisting only vault references (`${eddivault:...}`) in MongoDB

### Authentication & Authorization
- **AuthStartupGuard** — Fail-loud production auth check: EDDI refuses to start if OIDC is disabled in production (escape hatch: `eddi.security.allow-unauthenticated=true`)
- **RBAC on 7 REST endpoints** — Added `@RolesAllowed` annotations to previously unprotected admin/user endpoints
- **Fine-grained permit rules** — Static assets GET/HEAD only, health endpoint GET only, Slack webhook POST only (was blanket permit for all methods)

### HTTP & Protocol
- **Security response headers** — `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, `Content-Security-Policy`
- **Qute strict rendering** — Templates fail loudly on missing variables in production
- **Jackson 3.x ban** — `maven-enforcer-plugin` rule prevents accidental introduction via transitive dependencies
- **Log injection prevention** — `sanitizeForLog()` applied to all user-controlled identifiers in log statements

### Container & Docker
- **MongoDB 6.0 → 7.0.14** (pinned), with authentication enabled
- **MongoDB port binding** — `127.0.0.1:27017` (was all interfaces)
- **Docker healthchecks** added for both EDDI and MongoDB
- **Base image** — Pinned to immutable digest for supply-chain integrity
- **CVE-2026-4424** remediated via digest pin

---

## 🧪 Testing & Quality (2,400 → 5,100+ tests · >80% coverage)

### Test Suite Expansion
- **5,100+ total tests** (up from ~2,400 in 6.0.1) — 4,500+ unit tests, 550+ integration tests
- **>80% combined test coverage** (unit + integration tests merged) — instruction, method, and class coverage all exceed 80%
- **Coverage-guided fuzz testing** — Jazzer v0.30.0 with harnesses for `PathNavigator` and `MatchingUtilities` (security-critical parsers)

### Test Infrastructure Improvements
- **Testcontainers ITs for all datastore adapters** — MongoDB (75 tests) and PostgreSQL (57 tests) covering every persistence adapter
- **WireMock-based API tests** — Config-driven agent ITs for LLM, HTTP calls, PropertySetter, and complex rules
- **JaCoCo two-tier gates** — Merged UT+IT coverage gate enforced in CI

---

## 📊 Observability & Monitoring (improved)

### OpenTelemetry Distributed Tracing
- Added per-task pipeline spans (`eddi.pipeline.task`) with `task.id`, `task.type`, `conversation.id`, `agent.id` attributes
- Backend-agnostic via OTLP protocol (Jaeger, Tempo, Datadog, Honeycomb)
- Auto-instrumented: REST endpoints, Vert.x HTTP, MongoDB
- Disabled by default — zero overhead in production without a collector

### ConversationCoordinator Hardening
- **Max-size limit** — Configurable `eddi.coordinator.max-active-conversations` (default 10,000) with backpressure rejection
- **Eager cleanup** — Empty queues removed on drain, preventing memory leaks from abandoned conversations
- **CAS loop race fix** — `submitInOrder` / `submitNext` race condition resolved with compare-and-swap loop

### Metrics & Dashboard
- Added **3 Micrometer gauges** — `active_conversations`, `queue_depth`, `total_processed`
- Added **pipeline task metrics** — `eddi.pipeline.task.duration` Timer + `eddi.pipeline.task.errors` Counter (tagged by `task.id/type`)
- Added **pre-built Grafana dashboard** — 14 panels across 5 rows (Coordinator, Tools, Vault, NATS, HTTP/JVM)
- Added **one-command monitoring stack** — `docker-compose.monitoring.yml` (Prometheus v3.4 + Grafana 11.6 + Jaeger 2.7)

---

## ⚡ Multi-Tenancy & Quota Enforcement (improved)

- **Atomic quota enforcement** — Fixed TOCTOU race condition in `TenantQuotaService`. Check+record merged into single `synchronized` block per tenant
- **Monthly cost reset** — Fixed bug where `monthlyCostUsd` accumulated indefinitely (now resets on UTC calendar month boundary)
- **Quota ordering fix** — Quota is now acquired AFTER all validation checks, preventing quota exhaustion from invalid requests
- **Metrics hygiene** — Quota counters no longer inflate when quotas are disabled

---

## 🔄 CI/CD Pipeline (improved)

### Security Scanning (6 new tools added)
| Tool | Purpose | Gating |
|------|---------|--------|
| **Trivy image scan** | OS-level CVEs in base image | Blocks Docker push |
| **CodeQL SAST** | Static analysis (security-extended) | SARIF → GitHub Security |
| **Gitleaks** | Leaked secrets in git history | Blocks build |
| **CycloneDX SBOM** | Software Bill of Materials (EU AI Act) | Artifact upload |
| **ZAP API scan** | Runtime misconfig, auth bypass | Report-only |
| **Security headers check** | Missing security headers | Warning |

### Build & Release Hardening
- **Sigstore cosign** — Keyless OIDC container image signing on Docker Hub push
- **Red Hat preflight** — Certification check on push events (not just PRs)
- **Failsafe timeout** — 15-minute cap prevents CI hangs from Docker build failures
- **Unified CI pipeline** — GitHub Actions (compile → test → Docker build → Trivy → smoke test → ZAP → push → sign)

---

## 🖥️ EDDI Manager (improved)

The EDDI Manager was completely rewritten in 6.0.1 (React 19 + Vite + Tailwind CSS). Version 6.0.2 focuses on **stability, bug fixes, and UX polish**.

### 6.0.2 Improvements
- 🔧 **Studio editors fix** — Normalized `eddi://` prefix handling for pipeline extension types
- 📐 **Resizable panels** — Side panels in Studio are now drag-resizable
- 📝 **Snippet creation fix** — Name validation (`[a-z0-9_]+`) with auto-sanitization and real error messages
- 🔄 **Resilient version resolution** — Resource detail pages handle undefined versions gracefully
- 🐛 **9 bugs fixed** from comprehensive codebase audit (auth headers, version staleness, URI schemes, UX)
- 🏗️ **`pkg` → `wf/workflow` rename** — All variables and IDs aligned with backend terminology
- 🔒 **Cascade save hardening** — Prevented stale version context causing save failures
- 🧪 **16 new tests** — Cascade-save and resource-usage coverage

---

## 💬 Chat UI (improved)

### 6.0.2 Improvements
- 📎 **Attachment upload** — Added file upload support with multipart form submission
- 🔄 **SSE stream fix** — Fixed hang on `[DONE]` event that left connections open
- 🔐 **Security hardening** — Improved input sanitization, CSP compliance, HTTPS-only cookies
- ♿ **Accessibility** — Added ARIA labels, keyboard navigation, focus management
- 📖 **README overhaul** — Aligned with EDDI ecosystem documentation styling

---

## 🔧 Platform & Dependencies

| Component | 6.0.1 | 6.0.2 |
|-----------|-------|-------|
| Java | 25 | 25 |
| Quarkus | 3.34.1 | 3.34.5 |
| langchain4j | 1.13.0 | 1.13.0 |
| MongoDB (Docker) | 6.0 | 7.0.14 |
| WireMock | — | 3.13.2 |
| Jazzer (fuzz) | — | 0.30.0 |
| Testcontainers | — | 1.21.4 |

### Other Improvements
- **JDK 25 compatibility** — `--enable-native-access=ALL-UNNAMED` for JNA
- **WhiteSource/Mend false positive** — Removed 25 bloated HTML license files that triggered Bootstrap CVE flags; replaced with 13 clean SPDX plain-text licenses
- **Slack integration hardening** — Improved per-agent credentials, fixed dead retry logic, enterprise-grade error handling, Jackson JSON migration, TTL caches, graceful shutdown
- **Group conversation safety** — Added `maxTurns` safety cap preventing runaway multi-agent discussions

---

## 📚 Documentation (improved)

- **Architecture doc** — Added sections: Multi-Agent Orchestration, MCP Integration, Persistent Memory, Agent Sync
- **Project Philosophy** — Expanded from 7 → 9 Pillars (added Persistent Memory, Agent Portability)
- **Security Architecture** — Added SSRF 3-layer model, vault encryption model, auth decision matrix
- **Monitoring Guide** — Added metrics reference, tracing setup, 6 alerting rules, production checklist
- **Slack Integration Guide** — Updated with per-agent config, troubleshooting, building custom channels
- **~6 MB stale docs purged** — Removed legacy GitBook assets, research dumps, implemented plans

---

## ⬆️ Upgrade Notes

### Breaking Changes
- **Production auth enforcement** — EDDI now refuses to start without OIDC in production mode. Set `eddi.security.allow-unauthenticated=true` to opt out
- **Docker Compose** — MongoDB now requires authentication. Copy `.env.example` → `.env` and set credentials
- **Security headers** — `X-Frame-Options: DENY` may break iframing EDDI's UI. Configure CSP if embedding is needed

### Migration
- **Vault salt** — Fully automatic. Existing deployments continue using legacy salt; new deployments get random salt. No operator action required
- **API keys** — Existing plaintext API keys in MongoDB continue to work. New keys created via Agent Father are auto-vaulted

### Recommended Actions
1. Set `EDDI_VAULT_MASTER_KEY` environment variable for production deployments
2. Configure OIDC/Keycloak for production auth
3. Review `.env.example` for new Docker Compose variables
4. Enable OpenTelemetry tracing with `docker-compose.monitoring.yml` for observability
