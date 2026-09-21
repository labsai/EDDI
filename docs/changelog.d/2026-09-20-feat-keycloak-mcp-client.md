## 🔑 feat(keycloak): ship an `eddi-mcp` client for MCP clients to log in through (2026-09-20)

**Repo:** EDDI (`feat/keycloak-mcp-client`, stacked on `feat/mcp-oauth-discovery`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../../planning/mcp-oauth-protected-resource-plan.md),
second half. The previous entry made EDDI tell a client *where* to authenticate; this gives
it something to authenticate as.

### What changed

- **All three realm copies** (`keycloak/`, `helm/eddi/files/`, `k8s/overlays/auth/`) gain
  `eddi-mcp`: public, authorization code + PKCE `S256` required, direct access grant / implicit
  / service accounts all off, redirect URIs `http://localhost:*` and `http://127.0.0.1:*`, no
  web origins, and the `realm-roles`, `eddi-backend-audience` and `groups` protocol mappers
  copied from `eddi-frontend`.
- **`DeploymentManifestsTest`** — a case per realm copy asserting the flow settings, the PKCE
  requirement, the mappers (by claim name and by *access* token, not just id token), that no
  redirect is `*` or a remote http URL, that `webOrigins` is empty, and that neither `name` nor
  `description` exceeds 255 characters: Keycloak stores them in `VARCHAR(255)` and an over-long
  value does not truncate — **the realm import fails and Keycloak exits 1**, which is how the
  first draft of this client took down every stack that imports the realm. Found by running the
  import, not by reading the file.
- **`helm/eddi/templates/NOTES.txt`**, **`k8s/overlays/auth/kustomization.yaml`**,
  **`docs/security.md`** — every place that told an operator to grant an account "those two
  roles" now names all three. Following the old instruction built an administrator that logs in
  and is refused every MCP read tool, which is the trap the realm change exists to close.
- **`.github/workflows/ci.yml`** — `keycloak/**` added to the `code` and `backend` path
  filters. `k8s/` and `helm/` were already there, so the compose realm was the one copy whose
  change ran no CI — including the audience mapper every accepted token depends on.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the client, how to point a client at it,
  why dynamic registration is not an option here, and what to do on an **existing** realm:
  `--import-realm` never re-imports into a realm that already exists and both auth stacks keep
  Keycloak's database in a named volume, so an upgrade leaves the client absent and the flow
  ends in `invalid_client`. The manual steps are listed, `realm-roles` first.

- **All three realm copies** — the seeded `eddi` administrator gains `eddi-viewer` alongside
  `eddi-admin`/`eddi-editor`. There is no role hierarchy, so without it the account an operator
  points their first MCP client at completes the login and is then refused all 27 viewer-gated tools.
  A test pins it. `scripts/make-test-realm.mjs` guards that fixture set against the realm and
  fails the auth E2E run when the two drift, so `ROLE_FIXTURES` and `e2e/auth/auth-helpers.ts`
  move with it — which is how CI caught this change the first time it ran.

### Decisions

- **Pre-registered client, not dynamic registration.** Not a preference: this realm supplies its
  own `clientScopes` and defines no `roles` scope, so `realm_access.roles` comes only from a
  client's own protocol mapper. RFC 7591 registration carries no mappers, so a self-registered
  client would mint tokens that authenticate and then fail every tool with "requires role" —
  login succeeded, everything forbidden. Keycloak's default registration policies would also
  have to be loosened in at least three places to get there.
- **Loopback redirects only; `https://claude.ai/api/mcp/auth_callback` is not shipped.** Claude
  Desktop connectors redirect to that remote callback, so the authorization response for a
  self-hosted EDDI would pass through a third party. That is an operator's decision, documented
  in `docs/mcp-server.md`, rather than a default inherited from us.
- **No `webOrigins`, not even `+`.** These clients are native processes; `eddi-frontend` needs
  browser origins and this one never makes a browser request.
- **The redirect list is the one `[ext]` assumption in the plan.** Which loopback path each
  client uses is documented client behaviour rather than something verified here, so the entries
  are the broad `localhost` / `127.0.0.1` wildcards the realm already uses for the SPA, and the
  docs say to add anything else in the admin console.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`, `k8s/overlays/auth/eddi-realm.json`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/mcp-server.md`, `docs/security.md`
