# Documentation & AGENTS.md Updates Plan

> **Context:** After the v6.0.2 security hardening, several documentation artifacts need updating to reflect new infrastructure, patterns, and operational requirements. This is non-code work that can be done in a single focused session.

## Prerequisite Reading

1. [`docs/changelog.md`](../changelog.md) — Sprint 1 and 2 entries
2. [`AGENTS.md`](../../AGENTS.md) — The full file, especially §3 Roadmap, §4.4 Tool Security, and the Reusable Infrastructure table

---

## 1. AGENTS.md Updates ⚠️ HIGH

These updates ensure future AI agents know about the new security infrastructure.

### 1a. Reusable Infrastructure Table (line ~250)

Add these rows to the existing table:

| Infrastructure | What it does | Use it for |
|---|---|---|
| **`SafeHttpClient`** | SSRF-safe HTTP wrapper — `Redirect.NEVER` + per-hop validated redirects | ALL outbound HTTP from LLM tools and integrations. Never create `HttpClient.newBuilder()` in tool code. |
| **`UrlValidationUtils`** | Blocks private IPs, loopback, link-local, cloud metadata, non-HTTP schemes | Always call before fetching user-controlled URLs |
| **`AuthStartupGuard`** | Fails startup if OIDC disabled in prod without explicit opt-out | Automatic — operators only need `QUARKUS_OIDC_TENANT_ENABLED=true` |
| **`VaultSaltManager`** | Per-deployment PBKDF2 salt for KEK derivation | Managed by `VaultSecretProvider` — no direct usage needed |

### 1b. Development Roadmap — Completed Table (line ~73)

Add a new row:

```markdown
| —     | Security Hardening v6.0.2 | SSRF prevention, SafeHttpClient, auth guard, vault salt, security headers, CodeQL + Trivy CI |
```

### 1c. Tool Security Section (line ~368)

Update to reference SafeHttpClient:

```markdown
#### Tool Security

- **Use `SafeHttpClient`** (`@Inject SafeHttpClient`) for ALL outbound HTTP — never create `HttpClient.newBuilder()` directly
- **Always validate URLs** with `UrlValidationUtils.validateUrl(url)` before fetching user-controlled input
- **Only allow `http`/`https`** — never `file://`, `ftp://`, etc.
- **Block private/internal addresses** — handled automatically by `SafeHttpClient.sendValidated()`
- **Never use `ScriptEngine`** — use `SafeMathParser` (recursive-descent)
```

### 1d. Key Files Table (line ~536)

Add:

```markdown
| `src/main/java/.../httpclient/SafeHttpClient.java` | Centralized SSRF-safe HTTP client wrapper |
| `src/main/java/.../security/AuthStartupGuard.java`  | Production auth enforcement guard          |
| `.env.example`                                       | Required environment variables             |
```

---

## 2. docs/architecture.md — Security Section 🟡 MEDIUM

Add a new `## Security Architecture` section covering:

### SSRF Prevention Model
```
Layer 1: UrlValidationUtils — hostname blocklist (localhost, .internal, .local)
Layer 2: UrlValidationUtils — DNS resolution + IP classification
         (loopback, RFC 1918, CGNAT, ULA, link-local, cloud metadata)
Layer 3: SafeHttpClient — per-hop redirect validation
         (every 3xx target re-validated through Layers 1-2)
```

### Vault Encryption Model
```
Master Key (env: EDDI_VAULT_MASTER_KEY)
  └─→ PBKDF2 (600k iterations, per-deployment salt via VaultSaltManager)
      └─→ KEK (Key Encryption Key)
          └─→ AES-256-GCM encrypt/decrypt DEKs
              └─→ DEK per secret (random, AES-256-GCM)
                  └─→ Secret plaintext
```

### Authentication Model
```
Production: OIDC required (AuthStartupGuard enforces at startup)
            @RolesAllowed on sensitive REST APIs
            eddi-user role for authenticated operations
Development: OIDC optional (info log only)
Escape hatch: EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true (periodic ERROR log)
```

### CI Security Scanning
- CodeQL SAST with `security-extended` queries on every push/PR
- Trivy filesystem scan for CRITICAL/HIGH vulnerabilities
- Jackson 3.x banned via `maven-enforcer-plugin`

---

## 3. README.md Security Section 🟢 LOW

Add a brief "## Security" section near the deployment instructions:

```markdown
## Security

EDDI enforces security-by-default in production:

- **OIDC authentication** is required in production mode. Set `QUARKUS_OIDC_TENANT_ENABLED=true` and configure your Keycloak realm.
- **Secrets vault** encrypts API keys and credentials at rest using AES-256-GCM with per-deployment salt.
- **SSRF protection** blocks requests to internal networks, cloud metadata endpoints, and non-HTTP schemes.
- **Security headers** (CSP, X-Frame-Options, X-Content-Type-Options) are set on all responses.
- **CI scanning** via CodeQL (SAST) and Trivy (vulnerability scan) on every push.

See [`.env.example`](.env.example) for required environment variables.
```

---

## 4. SecurityUtilities — Deprecation Decision 🟢 LOW

`SecurityUtilities` has zero callers in the codebase. Options:

1. **Mark `@Deprecated(forRemoval = true, since = "6.0.2")`** — keeps it but signals intent to remove
2. **Delete it** — it's dead code with no external consumers (EDDI is a self-contained platform per AGENTS.md §1)
3. **Keep as-is** — it's now fixed and might be useful later

**Recommendation:** Option 1 (deprecate). It preserves the fixed code for potential future use while making it clear it's not currently part of the active codebase.

---

## Verification

After completing all items:

- [ ] `./mvnw compile` — verify no broken references after doc changes
- [ ] Read through `AGENTS.md` end-to-end to verify consistency
- [ ] Verify no dead links in all modified markdown files
- [ ] Grep for `HttpClient.newBuilder()` in `src/main/java/` to verify no new instances crept in
