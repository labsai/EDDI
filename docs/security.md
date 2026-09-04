# Security

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

This document describes the security measures applied to EDDI's AI Agent Tooling system, particularly for tools that execute in response to LLM-generated arguments, as well as the Keycloak-based authentication layer.

---

## Authentication — Keycloak OIDC

**Version: ≥6.0.0**

EDDI supports optional authentication via [Keycloak](https://www.keycloak.org/) using the Quarkus OIDC extension. Authentication is **disabled by default** — the system runs open (no login required) unless explicitly enabled.

### Architecture

EDDI uses **bearer-only (service) mode** — the backend never redirects to Keycloak. The Manager SPA and Chat UI handle login via `keycloak-js`, then send Bearer tokens to the backend for validation.

```
Browser (EDDI Manager / Chat UI)
    │
    ├── keycloak-js → Keycloak login → JWT access token
    │
    ├── Authorization: Bearer <token> → EDDI backend
    │                                      │
    │                                      ├── Quarkus OIDC validates token via JWKS
    │                                      ├── SecurityIdentity populated
    │                                      └── RestAgentManagement checks identity
    │
    └── Token refresh (automatic, every 30s before expiry)
```

> **Note:** The backend runs with `application-type=service` (bearer-only). It does not handle authorization code flows or login redirects. All login UI is handled client-side.

### Quick Setup with Installer

The easiest way to enable auth is to use the installer:

```bash
# Linux / macOS
bash install.sh --with-auth

# PowerShell
.\install.ps1 -WithAuth
```

This starts Keycloak alongside EDDI with pre-configured realm, clients, and test users:

| User | Password | Role | Notes |
|------|----------|------|-------|
| `eddi` | `eddi` | `eddi-admin`, `eddi-editor` | Full access, forced password change on first login |
| `viewer` | `viewer` | `eddi-viewer` | Read-only access, forced password change on first login |
| `user` | `user` | `eddi-user` | Standard user access, forced password change on first login |

### Configuration Properties

| Property                       | Type           | Default                             | Description                                     |
| ------------------------------ | -------------- | ----------------------------------- | ----------------------------------------------- |
| `quarkus.oidc.enabled`         | **Build-time** | `true`                              | Extension active — must be `true` at build time |
| `quarkus.oidc.tenant-enabled`  | **Runtime**    | `false`                             | Enables/disables auth enforcement               |
| `quarkus.oidc.auth-server-url` | Runtime        | `http://localhost:8180/realms/eddi` | Keycloak realm URL                              |
| `quarkus.oidc.client-id`       | Runtime        | `eddi-backend`                      | OIDC client ID (bearer-only)                    |
| `quarkus.oidc.application-type` | Runtime       | `service`                           | Bearer-only mode (no login redirects)           |
| `authorization.enabled`        | Runtime        | `${quarkus.oidc.tenant-enabled}`    | Fine-grained `@RolesAllowed` authorization      |

> **Important:** `quarkus.oidc.enabled` is a **build-time** property — it cannot be changed at container start. The OIDC extension must always be active in the binary. Use `quarkus.oidc.tenant-enabled` (runtime) to toggle auth on/off via environment variables.

### Enabling Auth at Container Start

```bash
docker run -e QUARKUS_OIDC_TENANT_ENABLED=true \
           -e QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/eddi \
           -e QUARKUS_OIDC_CLIENT_ID=eddi-backend \
           -e QUARKUS_OIDC_APPLICATION_TYPE=service \
           labsai/eddi:latest
```

> **Roles are deployment-wide.** `eddi-editor` grants authoring rights over
> *every* configuration in the deployment. To scope agents, workflows and the
> rest to the user or team that created them — and to share them deliberately —
> see [Workspaces](workspaces.md).

### Auth Permissions

When OIDC is enabled, the following permission rules apply (see `application.properties`):

| Path Pattern | Policy |
| --- | --- |
| `/q/health/*` | **Permit** (GET only) — required for k8s probes |
| `/q/metrics/*` | **Authenticated** — deliberately not permitted (metrics leak deployment shape); a Prometheus scraper must present a Bearer token |
| `/`, `/manage`, `/manage/*`, `/chat`, `/chat/*` | **Permit** — SPA entry points (the SPA loads and handles Keycloak login via keycloak-js) |
| `/scripts/*`, `/fonts/*`, `/css/*`, `/js/*`, `/img/*` | **Permit** — Static assets for Manager SPA |
| `/*` (catch-all) | **Authenticated** — All other API endpoints require a valid Bearer token |

> **Note:** there is no HTTP-layer permit for conversation endpoints — `/agents/production/*` is caught by the catch-all `authenticated` policy and answers `401` before any resource method runs. The production exemption in `RestAgentManagement.checkUserAuthIfApplicable` (below) is an inner check only; to expose a production conversation surface anonymously you must add your own `quarkus.http.auth.permission.*` permit rule.

### RestAgentManagement Gate

`RestAgentManagement.checkUserAuthIfApplicable()` enforces per-request auth:

```java
if (checkForUserAuthentication &&
        !production.equals(userConversation.getEnvironment()) &&
        identity.isAnonymous()) {
    throw new UnauthorizedException();
}
```

- When `quarkus.oidc.tenant-enabled=false` → `checkForUserAuthentication=false` → all requests pass
- When `quarkus.oidc.tenant-enabled=true` → a request against a non-production environment (`unrestricted`, `test`) must be authenticated; `production` conversations are exempt from this particular gate
- Requests to `/production/` environments always pass regardless of auth status

### Local Development Keycloak

The EDDI-Manager repo provides a docker-compose for local Keycloak:

```bash
docker compose -f docker-compose.keycloak.yml up
```

This starts Keycloak 26 on port 8180 with:

- **Realm**: `eddi`
- **Clients**: `eddi-frontend` (SPA, public), `eddi-backend` (bearer-only)
- **Roles**: `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer` (plus `eddi-approver`, used by the HITL approval endpoints)
- **Test users**: `eddi`/`eddi` (`eddi-admin` + `eddi-editor`), `viewer`/`viewer` (`eddi-viewer`), `user`/`user` (`eddi-user`)

> `keycloak/eddi-realm.json` is the source of truth for client ids. Provisioning a
> realm by hand from a doc that names a different one gets you `invalid_client`
> at login: `RestManagerResource` hardcodes `eddi-frontend` as the id the Manager
> SPA requests tokens for, so that is the client that has to exist.

---

## Threat Model

When an LLM is given access to tools, every argument it supplies must be treated as **untrusted input**. An attacker can craft prompts that cause the LLM to pass malicious arguments to tools — a class of attacks known as **prompt injection**. EDDI mitigates these risks at the tool-execution layer so that individual tools do not need to implement their own defences.

---

## Caller Identity Forwarding — `CallerIdentityResolver`

**Applies to:** apicall headers, and an MCP server's `apiKey`, that reference `${caller:token}` / `${caller:userId}`.

An agent that calls an API needs a credential. Baking a static one into the
config is the wrong shape when the API is EDDI's own: an OIDC token expires
within the hour, cannot be least-privilege, and attributes every action to a
single synthetic principal. Instead, a header may reference the authenticated
caller, and EDDI substitutes that user's own token while building the request.

```json
"headers": { "Authorization": "Bearer ${caller:token}" }
```

Forwarding a user's token is only safe under strict conditions, so resolution
fails the call loudly rather than degrading quietly:

| Control | Behaviour |
| ------- | --------- |
| **Same-origin only** | The token is released only when the outbound call targets the exact `scheme://host:port` the caller addressed. That origin comes from the inbound request, never from configuration — so an agent config naming a third-party host cannot exfiltrate the token, and no allow-list is required for this to hold by default. |
| **Headers only** | A token reference in a query parameter, request body or request path is rejected — only a header is ever substituted. `${caller:userId}` is permitted in headers and query parameters. |
| **Authenticated turns only** | The identity is captured from the request driving the turn. Scheduled jobs and triggers have no caller and cannot satisfy the reference. |
| **Fails closed** | An unsatisfiable reference throws rather than resolving to an empty string, which would send `Bearer ` and surface downstream as a confusing `401`. |
| **Never persisted** | Resolution happens while building the request; `RequestRedactor.redactRequestMap` redacts the URI, headers, query parameters and body before the request is written to conversation memory. |

**Thread safety.** A conversation turn is built on the request thread but
executed on pool threads, where request-scoped beans no longer resolve. The
identity is captured while the request context is live and bound to the
executing thread by `CallerIdentityContext`, always cleared in a `finally` —
those threads are reused across conversations, so a leaked binding would be
readable by the next caller's turn.

Set `eddi.caller-identity.enabled=false` to forbid the feature outright.

---

## SSRF Protection — `UrlValidationUtils`

**Applies to:** PDF Reader, Web Scraper, and any future tool that fetches remote
resources — **always**, with no configuration.

Server-Side Request Forgery (SSRF) occurs when an attacker tricks a server-side application into making requests to internal services. EDDI prevents this with `UrlValidationUtils.validateUrl(url)`:

> ### ⚠️ Configured outbound calls are a separate, opt-in case
>
> The validation described in this section is unconditional for **tool** URLs,
> because those are chosen by the LLM and therefore attacker-influenceable. It is
> **not** applied by default to outbound calls whose target comes from *your own
> configuration* — httpCalls, MCP servers and A2A peers — because those routinely
> and legitimately address internal hosts (`http://billing.internal/api`), which
> the validator would reject.
>
> Turn it on with:
>
> ```properties
> eddi.security.ssrf-protection.enabled=true   # default: false
> ```
>
> When enabled, `ApiCallExecutor`, `McpToolProviderManager` and
> `A2AToolProviderManager` validate the **fully resolved** target — after
> templating, global variables and vault references have been substituted — and
> stop following redirects, so a `3xx` cannot bounce a permitted request onto an
> internal host.
>
> **Enable it whenever any part of an httpCall URL can be influenced by
> conversation input** (`{properties.x}`, `{memory.current.input}`, a context
> variable). Leave it off only if every outbound target is a fixed literal and you
> genuinely need to reach private addresses.

### Scheme Allowlist

Only `http` and `https` URLs are accepted. All other schemes are rejected:

| Blocked     | Example                       |
| ----------- | ----------------------------- |
| `file://`   | `file:///etc/passwd`          |
| `ftp://`    | `ftp://internal-server/data`  |
| `jar://`    | `jar:file:///app.jar!/secret` |
| `gopher://` | `gopher://127.0.0.1:25/...`   |

### Private / Internal IP Blocking

DNS resolution is performed and the resolved address is checked before any connection is made:

| Range            | Description                   |
| ---------------- | ----------------------------- |
| `127.0.0.0/8`    | Loopback addresses            |
| `10.0.0.0/8`     | Private network (Class A)     |
| `172.16.0.0/12`  | Private network (Class B)     |
| `192.168.0.0/16` | Private network (Class C)     |
| `169.254.0.0/16` | Link-local (AWS/GCP metadata) |
| `100.64.0.0/10`  | CGNAT (RFC 6598)              |
| `224.0.0.0/4`    | IPv4 multicast                |
| `0.0.0.0/8`      | Unspecified / "this network"  |
| `fc00::/7`       | IPv6 unique-local (RFC 4193 — covers `fc00::/8` and `fd00::/8`) |
| `fe80::/10`      | IPv6 link-local               |
| `::1`            | IPv6 loopback                 |

IPv4-mapped IPv6 addresses (`::ffff:x.x.x.x`) are unwrapped and re-checked against every IPv4 rule above.

### Cloud Metadata Endpoint Blocking

Cloud provider metadata services are explicitly blocked by IP and hostname:

- `169.254.169.254` (AWS, GCP, Azure metadata)
- `metadata.google.internal` (GCP)

### Internal Hostname Blocking

Hostnames that indicate internal services are rejected:

- `localhost`
- Any hostname ending in `.local`
- Any hostname ending in `.internal`

### Usage

```java
import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

// In any tool method that accepts a URL:
validateUrl(url); // throws IllegalArgumentException if blocked
```

---

## Sandboxed Math Evaluation — `SafeMathParser`

**Applies to:** Calculator tool.

### Problem

The original implementation used Java's `ScriptEngine` (Nashorn/Rhino) to evaluate math expressions. A malicious expression could execute arbitrary JavaScript:

```
// DANGEROUS — would execute arbitrary code in old implementation:
java.lang.Runtime.getRuntime().exec('rm -rf /')
```

### Solution

The Calculator tool now uses `SafeMathParser`, a **recursive-descent parser** written in pure Java. It:

- Recognises only numeric literals, arithmetic operators (`+`, `-`, `*`, `/`, `%`, `^`), and parentheses
- Supports a fixed allowlist of math functions (`sqrt`, `pow`, `abs`, `sin`, `cos`, `log`, `exp`, etc.)
- Supports only two constants (`PI`, `E`)
- Has **no code execution capability** — unrecognised tokens cause an immediate parse error
- Requires no external dependencies (no Rhino/Nashorn/GraalJS)

### Allowed Grammar

```
expression → term (('+' | '-') term)*
term       → power (('*' | '/' | '%') power)*
power      → unary ('^' unary)*
unary      → ('-' | '+')? primary
primary    → NUMBER | FUNCTION '(' args ')' | '(' expression ')' | CONSTANT
```

### Supported Functions

`sqrt`, `pow`, `abs`, `ceil`, `floor`, `round`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `log`, `log10`, `exp`, `signum`/`sign`, `toRadians`, `toDegrees`, `cbrt`, `min`, `max`

---

## Tool Execution Pipeline

All tool invocations — both built-in and HTTP-call-based — are routed through `ToolExecutionService.executeToolWrapped()`. This ensures consistent security and operational controls:

```
Tool Call ──▶ Rate Limiter ──▶ Cache Check ──▶ Execute Tool ──▶ Cost Tracker ──▶ Result
```

### Rate Limiting

- **Algorithm:** Token-bucket per **dispatch name** — the `@Tool` method the model called
- **Configuration:** `enableRateLimiting` (default `true`), `defaultRateLimit` (default `100`), `toolRateLimits` (per-tool overrides)
- **Key resolution:** a `toolRateLimits` entry may be keyed on the dispatch name (`searchWeb`) or on the built-in slug (`websearch`, the same token as `builtInToolsWhitelist`). The dispatch name is checked first, then the slug, then `defaultRateLimit`
- **Bucket granularity:** a slug-keyed limit sets the value for every operation of that tool but each operation keeps its **own** bucket. `{"websearch": 30}` grants `searchWeb`, `searchNews` and `searchWikipedia` 30 calls/minute *each*. To bound a single operation, key it by dispatch name
- **Behaviour:** Requests exceeding the limit receive a "Rate limit exceeded" error message returned to the LLM, which can then retry or use a different approach

### Smart Caching

- **Key:** `scopeTag|toolName:arguments`. Arguments longer than 2048 characters are replaced by their SHA-256 hex digest to keep keys bounded; shorter arguments are inlined verbatim
- **Scope tag — this is a data-isolation boundary:**
  - `u:<first 32 hex chars of SHA-256(userId)>` for `user` scope (the default). The raw user id never appears in a key
  - `c:<conversationId>` for `conversation` scope
  - `g` for `global` scope
  - When `user` scope is in effect but no user id is available, the entry falls back to the narrower `c:` partition. If neither a user id nor a conversation id is available, **no tag can be derived and the cache is bypassed entirely** — nothing is read and nothing is stored. A placeholder is deliberately never substituted, because that would put every unattributable request back into one shared partition
- **Configuration:** `enableToolCaching` (default `true`), `toolCacheScopes` (per-tool overrides, keyed on the dispatch name or the built-in slug — dispatch name wins, same vocabulary as `toolRateLimits` and `toolPricing`), `defaultToolCacheScope` (task-level default, effectively `user`)
- **Unparseable tokens fail safe:** a `toolCacheScopes` value that does not parse resolves to `user` and is logged at WARN — never to `defaultToolCacheScope`, so a typo in an override that was written to *narrow* one tool cannot promote it onto a `global` partition
- **Behaviour:** A cached result is only ever served back inside its own partition. With the default `user` scope, one authenticated user's tool result is never returned to another. Set a tool to `global` only when its result depends purely on its arguments and never on who is asking — that is an explicit, per-tool opt-in to cross-user reuse
- **Expiry:** Each entry expires on its own per-tool TTL, measured from the write (`weather` 300s, `websearch` 1800s, `news` 600s, `calculator` 7 days, 300s for tools with no table entry — see `GET /llm/tools/cache/ttl/{toolName}`). The TTL is matched against the dispatch name first and the slug second, so `searchNews` gets the `news` entry rather than its tool's `websearch` entry. Size-based eviction (`tool-results` holds 10 000 entries) is the secondary bound. A stale or poisoned result cannot outlive its TTL

### Cost Tracking

- **Configuration:** `enableCostTracking` (default `true`), `toolPricing` (per-call price overrides), `maxBudgetPerConversation` (no default — unlimited), `enforceBudget` (default `false`, deployment fallback `eddi.tools.budget.enforce-by-default`)
- **Scope:** `maxBudgetPerConversation` bounds **tool** cost only. LLM token spend is governed separately and per run by the model cascade's `maxCostPerRun`; the two are not summed
- **Pricing:** default per-call prices are keyed on the built-in slug (`webscraper` $0.002, `websearch` $0.001, `pdfreader` $0.001, `weather` $0.0005; `calculator`/`datetime`/`dataformatter`/`textsummarizer` free). Everything else — http, mcp, a2a, dynamic — is $0.00 until priced via `toolPricing`, which accepts a slug or a dispatch name (dispatch name wins). Operator-supplied prices are clamped at 0.0, so a negative value cannot credit a conversation and make a ceiling unreachable
- **Eviction:** To prevent unbounded memory growth, the tracker caps per-conversation entries at 10 000 and evicts the oldest ~10% when the limit is reached
- **Behaviour:** Enforcement is **opt-in**. A configured `maxBudgetPerConversation` records cost but refuses nothing until `enforceBudget: true` (per task) or `eddi.tools.budget.enforce-by-default=true` (per deployment). Once enforced, the budget is checked *before* each call using `<=` — the call that crosses the ceiling completes and the next one returns `Error: Budget exceeded for conversation <id>` to the LLM. Enforcing by default was rejected because built-ins priced at $0.00 until the canonical-slug fix, so it would make those ceilings bind for the first time and abort tool calls on upgrade. The converse cost is real — http/MCP/A2A/dynamic tools dispatch under their configured name, so an agent with a tool called `websearch`/`webscraper`/`pdfreader` *was* being refused before this release — so every task carrying a ceiling without the flag is named once in a startup WARN rather than lapsing silently

### Configuration Example

```json
{
  "tasks": [
    {
      "actions": ["help"],
      "type": "openai",
      "enableBuiltInTools": true,
      "enableRateLimiting": true,
      "defaultRateLimit": 100,
      "toolRateLimits": { "websearch": 30, "weather": 50 },
      "enableToolCaching": true,
      "enableCostTracking": true,
      "toolPricing": { "websearch": 0.005 },
      "maxBudgetPerConversation": 5.0,
      "enforceBudget": true,
      "parameters": {
        "apiKey": "...",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant."
      }
    }
  ]
}
```

---

## Conversation Coordinator — Sequential Processing

The `ConversationCoordinator` ensures that messages for the same conversation are processed **sequentially**, preventing race conditions in conversation state. The `isEmpty()` → `offer()` → `submit()` sequence is wrapped in a `synchronized` block to prevent two concurrent requests from both being submitted to the thread pool simultaneously.

Different conversations are processed **concurrently** — only same-conversation messages are serialised.

---

## HTTP Call Content-Type Handling

The `ApiCallExecutor` uses strict equality (`equals`) rather than prefix matching (`startsWith`) when checking the `Content-Type` header against `application/json`. This prevents content types like `application/json-patch+json` from being incorrectly deserialised as standard JSON.

---

## Recommendations for New Tools

When adding a new tool to EDDI:

1. **Validate all URLs** with `UrlValidationUtils.validateUrl()` before making any outbound request
2. **Never use `ScriptEngine`** or any form of dynamic code evaluation
3. **Add `@Tool` annotations** with clear descriptions so the LLM understands the tool's purpose and constraints
4. **Write unit tests** that specifically verify rejection of malicious inputs (SSRF URLs, injection strings)
5. **Route execution through `ToolExecutionService`** to inherit rate limiting, caching, and cost tracking

---

## TLS Requirements

EDDI does not enforce TLS directly — it is designed to run behind a reverse
proxy (nginx, Traefik, Caddy, cloud load balancer) that handles TLS
termination.

**For regulated deployments (HIPAA, EU AI Act)**, all traffic to and from
EDDI must be encrypted in transit. A compliance startup warning is logged
if no TLS certificate is detected.

### Option 1: TLS at Reverse Proxy (Recommended)

Configure your reverse proxy to terminate TLS and forward traffic to EDDI
on `localhost:7070`. This is the standard production pattern.

### Option 2: TLS Directly in Quarkus

```properties
quarkus.http.ssl.certificate.file=/path/to/cert.pem
quarkus.http.ssl.certificate.key-file=/path/to/key.pem
quarkus.http.ssl-port=8443
```

### Internal Traffic

If EDDI and its database run on the same host or within a private network,
internal traffic may be unencrypted. However, HIPAA deployments should
evaluate whether this meets their security requirements.

---

## Supply Chain & CI/CD Security

EDDI's CI/CD pipeline enforces multiple automated security gates before any code reaches production. All GitHub Actions are **SHA-pinned** to immutable commit hashes to prevent supply-chain attacks via tag hijacking.

### Security Scanning Pipeline

| Tool | Type | Scope | Mode | Override |
|------|------|-------|------|----------|
| **CodeQL** | SAST | Java source code | Blocking (PR) + weekly deep scan | N/A |
| **Trivy** | CVE scanning | Filesystem deps + Docker image | Blocking (CRITICAL/HIGH) | `.trivyignore` |
| **Gitleaks** | Secret scanning | Full git history | Blocking | `.gitleaksignore` |
| **CycloneDX** | SBOM | Maven dependency tree | Artifact generation | N/A |
| **Jazzer** | Fuzz testing | PathNavigator, MatchingUtilities | JUnit integration | N/A |

> **DAST is intentionally absent.** A ZAP API scan used to live in `ci.yml` and was removed rather than kept as decorative coverage: it ran *after* the image was pushed, it scanned an instance started with `EDDI_SECURITY_ALLOW_UNAUTHENTICATED` (so the authorization layer under test was switched off), and it ran passive-only with `fail_action: false`. Re-adding it means fixing all three at once — build the image in a job that does not push, stand up Keycloak, drive an authenticated OpenAPI scan against it, and gate the publish job on the result.

### Override Files

For audited false positives, EDDI provides override files at the repository root:

- **`.trivyignore`** — Suppress specific CVEs with mandatory justification comments
- **`.gitleaksignore`** — Suppress specific Gitleaks fingerprints with justification

Both files should be reviewed periodically to ensure suppressions remain valid.

### Fuzz Testing

Security-critical input parsers are tested with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) coverage-guided fuzzing:

- **`PathNavigator`** — Safe path navigation (replaced OGNL). Fuzz targets: `getValue`, `setValue`, arithmetic paths
- **`MatchingUtilities`** — Condition evaluation for DynamicValueMatcher

In CI, fuzz tests run as standard JUnit regression tests. For deep coverage-guided fuzzing locally:

```bash
./mvnw test -Dtest=PathNavigatorFuzzTest \
  -Djazzer.instrument=ai.labs.eddi.utils.PathNavigator
```

### Docker Image Security

- Trivy scans the built Docker image for CRITICAL/HIGH CVEs **before** pushing to Docker Hub
- Red Hat Preflight checks verify container certification compliance (labels, licenses)
- Security headers are validated against the running container in the smoke test

---

## See Also

- [LangChain Integration](langchain.md) — Full agent configuration reference
- [Human-in-the-Loop](hitl.md) — Gating tool calls on human approval
- [Architecture](architecture.md) — EDDI's lifecycle pipeline and concurrency model
- [Metrics](metrics.md) — Monitoring tool execution performance
- [HIPAA Compliance](hipaa-compliance.md) — HIPAA deployment guide
- [EU AI Act Compliance](eu-ai-act-compliance.md) — EU AI Act compliance
- [Compliance Data Flow](compliance-data-flow.md) — Data flow diagram for auditors
