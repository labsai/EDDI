## 🔐 fix(auth): MCP logins last 30 days, and Keycloak behind TLS advertises https token endpoints (2026-10-26)

**Repo:** EDDI (`fix/mcp-long-lived-sessions`)

### What changed and why

Connecting Claude Code to a TLS-proxied EDDI deployment surfaced two gaps, both
configuration only — no Java change.

**1. An MCP login died after 30 idle minutes.** An MCP client keeps a refresh
token, but it was tied to the realm's SSO session (Keycloak default: 30 minutes
idle, 10 hours max), so the user re-ran the browser flow after every break.
`eddi-mcp` now issues **offline tokens** (30 days idle, renewed on use, revocable
per user). In all three realm copies (`keycloak/`, `helm/eddi/files/`,
`k8s/overlays/auth/`): `offline_access` is a Default client scope of `eddi-mcp`,
the role is in its scope mapping, `default-roles-eddi` includes it, and each seed
user lists it. The scope, the role and the scope's role mapping are now defined in
the file itself: the realm supplies its own `clientScopes`, and
`everyReferencedClientScopeIsDefined` rightly refuses a reference to a scope the
file does not define, even though Keycloak 26.8 happens to create this one anyway.
`default-roles-eddi` is stated twice in the file (`defaultRole` and the
`roles.realm` entry) and the roles-list entry is the one an import keeps — with the
role added only under `defaultRole`, a newly created user was refused, so both
carry it and the test checks both. `eddi-frontend` is unchanged — no 30-day tokens
in browser storage.

Verified against Keycloak 26.8.0 with the edited realm imported: all seed users
and a newly created user receive `typ=Offline` refresh tokens (2 592 000 s) with
their roles and `aud=eddi-backend`, and the refresh grant works. The same test
disproved both recipes `docs/mcp-server.md` previously gave:

- `default-roles-eddi` did **not** grant `offline_access` (the realm defines its
  composites explicitly) — without the role: *Offline tokens not allowed for the
  user or client*.
- Advertising `offline_access` in `quarkus.oidc.resource-metadata.scopes` makes
  Keycloak fail the **whole login** with `invalid_scope` on any realm whose client
  lacks the scope — i.e. every existing deployment. Left at `openid`.

**2. Keycloak behind a TLS proxy advertised `http://` token endpoints.** With
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, Keycloak derives the token, JWKS and
userinfo URLs from the request, which is plain http behind the proxy; the issuer
and authorization URLs come from `KC_HOSTNAME` and stayed https. Claude Code
refuses to send credentials to an http token endpoint, so login failed after the
browser step. Fix: `KC_PROXY_HEADERS=xforwarded`.
- `gcp/provision-vm.sh --https` now sets it, and its nginx Keycloak location
  now **sets** every forwarded header instead of passing client values through:
  `X-Forwarded-Host $host`, `X-Forwarded-Port 443`, `X-Forwarded-For
  $remote_addr` (was appended). Once Keycloak trusts these headers, a
  client-supplied `X-Forwarded-Host` would choose the host in that caller's
  discovery response (review finding, CWE-346).
- Helm: new `keycloak.proxyHeaders` — empty means `xforwarded` when
  `eddi.oidc.publicUrl` is https, nothing otherwise; `none` opts out. Rendered and
  checked for all four cases. Chart `2.11.0` → `2.12.0`.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`,
  `k8s/overlays/auth/eddi-realm.json` — offline-token wiring.
- `helm/eddi/values.yaml`, `helm/eddi/templates/keycloak.yaml`,
  `helm/eddi/Chart.yaml` — `keycloak.proxyHeaders`.
- `gcp/provision-vm.sh` — `KC_PROXY_HEADERS` in the HTTPS override.
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java` — the
  `eddi-mcp` scope-mapping assertion allows `offline_access`; new
  `mcpClientIssuesOfflineTokens` pins the three conditions in every realm copy and
  that `eddi-frontend` stays without it.
- `ui/manager/scripts/make-test-realm.mjs`, `ui/manager/e2e/auth/auth-helpers.ts`
  — the Auth E2E fixtures pin each seed user's exact roles; they now include
  `offline_access`. `ui/manager/e2e/auth/mcp-oauth.spec.ts` — the real
  authorization-code + PKCE + consent flow against `eddi-mcp` now asserts the
  refresh token is `typ: Offline`, the one path the container check could not drive.
- `docs/mcp-server.md` — corrected offline-token section, upgrade steps for an
  existing realm, proxy-header deployment note. `docs/kubernetes.md` — settings row.

### Upgrade note

`--import-realm` does not touch an existing realm. Existing deployments keep
working with ordinary 30-minute MCP logins; for long-lived ones they add the three
pieces by hand (steps in `docs/mcp-server.md`, "Upgrading an existing realm"),
the Default scope **last** — with the scope in place but the role missing from
the user or the client's scope mapping, Keycloak refuses the login with *Offline
tokens not allowed* rather than falling back to an ordinary token. Revoke a
single offline token under *Users* → the user → *Consents*. The nginx Keycloak
location also clears `X-Forwarded-Prefix` (review finding).
The `quarkus.oidc.roles.role-claim-path=realm_access/roles` fix for "requires
role" on every tool is already in the image since 6.6.0; deployments on older
images should pin it as `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`.
