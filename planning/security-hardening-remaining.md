# Security Hardening — Remaining Work (v6.0.3+)

> **Context:** This plan follows the v6.0.2 security hardening (Sprint 1-2) on branch `fix/security-hardening-6.0.2`. All P0/P1 items are done. These are remaining P2/P3 deferred items plus test gaps discovered during code review.

## Prerequisite Reading

1. [`docs/changelog.md`](../changelog.md) — Sprint 1 and Sprint 2 entries describe what was already done
2. [`AGENTS.md`](../../AGENTS.md) — §4.4 "Tool Security" for URL validation patterns
3. Key files to understand:
   - [`SafeHttpClient.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java) — centralized SSRF-safe HTTP
   - [`UrlValidationUtils.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java) — SSRF IP validation
   - [`VaultSaltManager.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java) — per-deployment KEK salt
   - [`AuthStartupGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/AuthStartupGuard.java) — production auth enforcement

---

## 1. SafeHttpClient Unit Tests ⚠️ HIGH

**Why:** SafeHttpClient is security-critical infrastructure (all tool HTTP flows through it) but has zero direct unit tests. It's only indirectly exercised by tool SSRF tests.

**What to test:**

```
SafeHttpClientTest.java (use com.sun.net.httpserver.HttpServer)

1. send() follows a safe redirect (public IP → public IP)
2. send() blocks redirect to 127.0.0.1 (SSRF via 302)
3. send() blocks redirect to 169.254.169.254 (cloud metadata)
4. send() throws IOException on >5 redirect hops
5. send() throws IOException on redirect without Location header
6. send() returns non-redirect response directly (200, 404, etc.)
7. sendValidated() validates the initial URL before sending
8. sendValidated() rejects file:// scheme on initial URL
9. Redirect 307/308 behavior (currently converts to GET — document this)
```

**Where:** `src/test/java/ai/labs/eddi/engine/httpclient/SafeHttpClientTest.java`

**Pattern:** See [`WebScraperToolSsrfTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperToolSsrfTest.java) for the embedded-server pattern.

**Implementation note:** The tests need 127.0.0.1 for the embedded server, but `validateUrl()` blocks loopback. Use `send()` (not `sendValidated()`) for the embedded server tests and test the redirect target validation separately.

---

## 2. SafeHttpClient 307/308 Method Preservation 🟡 MEDIUM

**Why:** RFC 7538 requires 307/308 to preserve the original HTTP method (POST→POST). Current implementation always converts to GET. All current callers are GET-only, so this is not a live bug yet.

**What to do:**

In `SafeHttpClient.sendWithRedirects()`, line ~127:

```java
// Current (wrong for 307/308):
HttpRequest redirectRequest = HttpRequest.newBuilder()
    .uri(resolvedUri)
    .GET()
    .build();

// Fixed:
HttpRequest.Builder builder = HttpRequest.newBuilder()
    .uri(resolvedUri)
    .timeout(request.timeout().orElse(Duration.ofSeconds(15)))
    .header("User-Agent", request.headers().firstValue("User-Agent").orElse("EDDI-Agent/1.0"));

if ((statusCode == 307 || statusCode == 308) && !"GET".equals(request.method())) {
    // Preserve method and body for 307/308
    builder.method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
} else {
    builder.GET(); // 301/302/303 always downgrade to GET per RFC 7231
}

HttpRequest redirectRequest = builder.build();
```

**Test:** Add `shouldPreserveMethodOn307Redirect` to `SafeHttpClientTest`.

---

## 3. DNS Rebinding (TOCTOU) Fix 🟡 MEDIUM

**Why:** `validateUrl()` resolves the hostname and checks the IP, but `HttpClient.send()` re-resolves independently. An attacker with a fast-TTL DNS server can return a safe IP at validation time and `127.0.0.1` at connect time.

**Current state:** `validateUrl(String, HostResolver)` returns `InetAddress[]` but no caller uses the return value to pin the connection.

**Options:**

1. **Option A: IP literal in URI** — After validation, replace the hostname with the resolved IP and add `Host` header. Simple but breaks HTTPS SNI for virtual hosts.

2. **Option B: Custom `java.net.ProxySelector`** — Return a `DIRECT` proxy with the resolved IP. Complex but transparent.

3. **Option C: Accept the risk** — With `Redirect.NEVER` + per-hop validation, the attack window is small (attacker must control a DNS server with microsecond-TTL and time it between validation and connect). Document as accepted risk.

**Recommendation:** Option C for now. The defense-in-depth layers (hostname blocklist + IP check + redirect validation) make successful exploitation very difficult. Revisit when Java's HttpClient supports connection-level IP pinning.

---

## 4. SafeHttpClient Migration — Remaining Services 🟢 LOW

**Why:** Four LLM tools were migrated to SafeHttpClient. Several other services still create their own `HttpClient`:

| Service | File | Risk | Notes |
|---------|------|------|-------|
| `A2AToolProviderManager` | `modules/llm/impl/A2AToolProviderManager.java` | LOW | URLs come from agent config (admin-controlled) |
| `SlackWebApiClient` | `integrations/slack/SlackWebApiClient.java` | LOW | URLs are Slack API endpoints (hardcoded) |
| `RemoteApiResourceSource` | `backup/impl/RemoteApiResourceSource.java` | LOW | URLs from admin-configured sync targets |
| `HttpCallsTask` | Not yet using SafeHttpClient | MEDIUM | URLs from httpcalls.json (admin-controlled but template-expanded) |

**What to do:**
- Search for `HttpClient.newBuilder()` in `src/main/java` — each occurrence is a candidate
- Replace with `@Inject SafeHttpClient` or use `SafeHttpClient.unwrap()` for cases needing raw access
- `HttpCallsTask` is the most important — it template-expands URLs which could contain user-controlled data

---

## 5. AuthStartupGuard Unit Test 🟢 LOW

**Why:** The guard's behavior is critical (blocks production startup) but has no tests.

**What to test:**
```
AuthStartupGuardTest.java (plain unit test, not @QuarkusTest)

1. Dev mode + OIDC disabled → no exception, just log
2. Prod mode + OIDC disabled + no escape hatch → throws IllegalStateException
3. Prod mode + OIDC disabled + escape hatch → logs ERROR, doesn't throw
4. Prod mode + OIDC enabled → no exception, no warning
```

**Implementation:** Use reflection to set the `@ConfigProperty` fields, and mock `LaunchMode.current()` (this is static, so may need a wrapper).

---

## 6. Documentation Updates 📝

### AGENTS.md

Add to the "Reusable Infrastructure" table (line ~250):

```markdown
| **`SafeHttpClient`**                                     | SSRF-safe HTTP wrapper — `Redirect.NEVER` + per-hop validated redirects, configurable timeout | ALL outbound HTTP from LLM tools. Never create `HttpClient.newBuilder()` in tool code. |
| **`UrlValidationUtils.validateUrl()`**                   | Blocks private IPs, loopback, link-local, cloud metadata, non-HTTP schemes                    | Always call before fetching user-controlled URLs. See Tool Security section §4.4.       |
| **`AuthStartupGuard`**                                   | Fails startup if OIDC disabled in prod without explicit opt-out                               | No action needed — runs automatically. Operators need only set QUARKUS_OIDC_TENANT_ENABLED=true. |
```

Add to "Completed" table:

```markdown
| —     | Security Hardening v6.0.2 | SSRF prevention, SafeHttpClient, auth guard, vault salt, security headers, CI scanning |
```

### docs/architecture.md

Add a new "## Security Architecture" section covering:
- SSRF prevention model (3-layer: hostname → IP → redirect)
- Vault encryption model (master key → KEK via PBKDF2 with per-deployment salt → DEK per secret)
- Auth model (OIDC required in prod, AuthStartupGuard, @RolesAllowed)
- CI scanning (CodeQL, Trivy)

---

## Verification Checklist

After completing items above:

- [ ] `./mvnw test` — 4,100+ tests, 0 failures
- [ ] `./mvnw verify -DskipITs=false` — full integration test suite
- [ ] Review CodeQL results after first CI run
- [ ] Review Trivy results after first CI run
- [ ] Smoke test with docker-compose (verify security headers in browser DevTools)
