## 🛡️ fix(security): validate the token audience, and stop calling userinfo on every request (2026-09-20)

**Repo:** EDDI (`feat/oidc-audience-validation`, stacked on `feat/keycloak-mcp-client`)

Increment 2 of [`planning/mcp-oauth-protected-resource-plan.md`](../../planning/mcp-oauth-protected-resource-plan.md),
kept separate because it changes which tokens are accepted — every token, not only MCP ones.

Quarkus verifies `aud` on an **access** token only when `quarkus.oidc.token.audience` is set
(`OidcIdentityProvider` passes `enforceAudienceVerification = idToken`, and with the property
unset `OidcProvider` calls `setSkipDefaultAudienceValidation()`). It was unset, so EDDI accepted
any token the realm issued for any client in it — and roles come from `realm_access/roles`,
which is client-independent, so such a token arrived carrying the user's full rights. The
`eddi-backend-audience` mapper that has been on `eddi-frontend` all along is evidence someone
intended this check and never switched it on. It matters more now that a second client exists.

### What changed

- **`application.properties`** — `quarkus.oidc.token.audience=eddi-backend`.
- **`application.properties`** — `quarkus.oidc.token-cache.{max-size=1000,time-to-live=3M,clean-up-timer-interval=5M}`.
  `user-info-required=true` means one Keycloak round trip per request, which an MCP client makes
  many of. Signature, expiry and audience are still checked per request, before the cache is
  consulted at all. What it defers is the userinfo call, which doubles as a session-revocation
  check (Keycloak refuses userinfo for a logged-out session) — so a killed session keeps working
  for up to the TTL. That, not token expiry, is why the TTL is short.
- **`DeploymentManifestsTest`** — every client that can mint a token (standard, direct-grant,
  implicit or service-account flow) must mint the audience the property requires, in all three realm copies, compared
  against the property rather than a spelling repeated in the test.
- **`ui/manager/e2e/auth/mcp-oauth.spec.ts`** (new) — the middle of the feature, which the
  discovery and 401 cases do not reach: authorization code + PKCE against `eddi-mcp`, the
  token exchange, then `/mcp` `initialize` and a real `list_agents` call. It asserts the
  token carries `aud=eddi-backend` and realm roles, so the two silent failures — a token the
  backend refuses, and one that authenticates and is then refused by every tool — surface as
  themselves rather than as a generic 401.
- **`docs/security.md`**, **`docs/open-webui-integration.md`** — both properties in the table, what
  a hand-built realm has to do, and the `/v1` adapter's 401-under-OIDC entry, which now also means
  "and carrying `aud=eddi-backend`".

### Compatibility

**This rejects tokens that were accepted before.** A deployment whose users authenticate through
a client *without* an audience mapper starts answering 401. The shipped realm is unaffected
(both login clients carry the mapper). The fix for a custom realm is to add the mapper; the
escape hatch is `QUARKUS_OIDC_TOKEN_AUDIENCE=any` — quarkus-oidc's own sentinel for skipping
audience validation (`OidcProvider.ANY_AUDIENCE`), which restores the old behaviour.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/security.md`, `docs/open-webui-integration.md`
