# Security

**Version: 6.0.0**

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
| `eddi` | `eddi` | admin | Full access, forced password change on first login |
| `viewer` | `viewer` | viewer | Read-only access, forced password change on first login |

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

### Auth Permissions

When OIDC is enabled, the following permission rules apply (see `application.properties`):

| Path Pattern | Policy |
| --- | --- |
| `/q/metrics/*`, `/q/health/*` | **Permit** — Infrastructure endpoints |
| `/`, `/manage`, `/manage/*`, `/chat`, `/chat/*` | **Permit** — SPA entry points (the SPA loads and handles Keycloak login via keycloak-js) |
| `/agents/production/*` | **Permit** — Production conversation endpoints (public-facing) |
| `/scripts/*`, `/fonts/*`, `/css/*`, `/js/*`, `/img/*` | **Permit** — Static assets for Manager SPA |
| `/*` (catch-all) | **Authenticated** — All other API endpoints require a valid Bearer token |

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
- When `quarkus.oidc.tenant-enabled=true` → only authenticated users can access production endpoints
- Requests to `/production/` environments always pass regardless of auth status

### Local Development Keycloak

The EDDI-Manager repo provides a docker-compose for local Keycloak:

```bash
docker compose -f docker-compose.keycloak.yml up
```

This starts Keycloak 26 on port 8180 with:

- **Realm**: `eddi`
- **Clients**: `eddi-manager` (SPA, public), `eddi-backend` (bearer-only)
- **Roles**: `admin`, `editor`, `viewer`
- **Test users**: `eddi`/`eddi` (admin), `viewer`/`viewer` (read-only)

---

## Threat Model

When an LLM is given access to tools, every argument it supplies must be treated as **untrusted input**. An attacker can craft prompts that cause the LLM to pass malicious arguments to tools — a class of attacks known as **prompt injection**. EDDI mitigates these risks at the tool-execution layer so that individual tools do not need to implement their own defences.

---

## SSRF Protection — `UrlValidationUtils`

**Applies to:** PDF Reader, Web Scraper, and any future tool that fetches remote resources.

Server-Side Request Forgery (SSRF) occurs when an attacker tricks a server-side application into making requests to internal services. EDDI prevents this with `UrlValidationUtils.validateUrl(url)`:

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
| `fd00::/8`       | IPv6 unique-local             |
| `fe80::/10`      | IPv6 link-local               |
| `::1`            | IPv6 loopback                 |

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
import static ai.labs.eddi.modules.langchain.tools.UrlValidationUtils.validateUrl;

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

- **Algorithm:** Token-bucket per tool name
- **Configuration:** `enableRateLimiting` (default `true`), `defaultRateLimit` (default `100`), `toolRateLimits` (per-tool overrides)
- **Behaviour:** Requests exceeding the limit receive a "Rate limit exceeded" error message returned to the LLM, which can then retry or use a different approach

### Smart Caching

- **Key:** SHA-256 hash of `toolName + arguments`
- **Configuration:** `enableToolCaching` (default `true`)
- **Behaviour:** Identical tool calls within the same conversation return cached results, reducing redundant API calls and cost

### Cost Tracking

- **Configuration:** `enableCostTracking` (default `true`), `maxBudgetPerConversation` (no default — unlimited)
- **Eviction:** To prevent unbounded memory growth, the tracker caps per-conversation entries at 10 000 and evicts the oldest ~10% when the limit is reached
- **Behaviour:** When the budget is exceeded, tools return a "Budget exceeded" message to the LLM

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
      "maxBudgetPerConversation": 5.0,
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

The `HttpCallExecutor` uses strict equality (`equals`) rather than prefix matching (`startsWith`) when checking the `Content-Type` header against `application/json`. This prevents content types like `application/json-patch+json` from being incorrectly deserialised as standard JSON.

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

## See Also

- [LangChain Integration](langchain.md) — Full agent configuration reference
- [Agent Father LangChain Tools Guide](agent-father-langchain-tools-guide.md) — Guided tool setup
- [Architecture](architecture.md) — EDDI's lifecycle pipeline and concurrency model
- [Metrics](metrics.md) — Monitoring tool execution performance
- [HIPAA Compliance](hipaa-compliance.md) — HIPAA deployment guide
- [EU AI Act Compliance](eu-ai-act-compliance.md) — EU AI Act compliance
- [Compliance Data Flow](compliance-data-flow.md) — Data flow diagram for auditors
