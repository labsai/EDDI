## 🔐 feat(mcp): advertise `/mcp` as an OAuth protected resource, so clients sign themselves in (2026-09-20)

**Repo:** EDDI (`feat/mcp-oauth-discovery`, stacked on `docs/mcp-oauth-plan`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../../planning/mcp-oauth-protected-resource-plan.md).
An MCP client now discovers where to authenticate and holds its own token, instead of an
operator pasting a bearer that expires in five minutes.

Quarkus OIDC 3.39.3 already serves the RFC 9728 document and appends `resource_metadata="…"`
to the 401 challenge, so there is no new EDDI code — five properties and one permit rule.

### What changed

- **`application.properties`** — `quarkus.oidc.resource-metadata.*`: `enabled` tracks
  `tenant-enabled` (an instance with auth off has no authorization server to name, and the
  handler is not installed for a disabled tenant), `resource=/mcp`, `force-https-scheme=true`,
  `scopes=openid`, and `authorization-server` preferring `token.issuer` over `auth-server-url`.
- **`application.properties`** — a `permit` rule for the two exact metadata paths (the bare
  form and the path-inserted document), `GET,HEAD` only. Exact rather than a `/*` under the
  prefix, which would anonymously expose any future handler beneath it.
- **`helm/eddi`** — `eddi.oidc.resourceMetadata.{forceHttpsScheme,authorizationServer}`, because
  neither is safely inferable: `publicUrl` describes Keycloak, not EDDI, so an https IdP in
  front of a plain-http port-forward would advertise a resource nothing serves. The scheme now
  follows EDDI's own `ingress.tls` unless set. Chart version bumped per Chart.yaml's rule.
- **`application.properties`** — the MCP security banner said 33 tools (there are 84) and
  described a two-role model (there are four, with no hierarchy).
- **`McpOAuthDiscoveryConfigTest`** (new, 8 cases) — the config *is* the feature, so it is what
  gets asserted: the enabled expression, the resource matching the MCP root path, the issuer
  preference, `openid` while `user-info-required` is on, the permit rule's policy/methods/paths,
  what those paths match and do not match, and `/mcp` still being `authenticated`.
- **`ui/manager/e2e/auth/auth.spec.ts`** — two cases in the Keycloak tier: the document is
  readable with no token and names the issuer a real token carries; an unauthenticated `/mcp`
  POST answers 401 with a challenge pointing at it.
- **Every shipped stack that serves plain http with authentication on** overrides
  `force-https-scheme`, because that is the one shape a forced https identifier is wrong for:
  `docker-compose.auth.yml`, the auth E2E tier, `k8s/overlays/auth` (its documented flow is
  `kubectl port-forward`), and the helm chart whenever the Keycloak URL it is given is itself
  plain http. Without it those deployments advertise `https://…/mcp` with nothing serving TLS,
  and discovery dies before it starts.
- **`ui/manager/docker-compose.integration-keycloak.yml`** — the tier now pins the
  browser-reachable issuer the way `docker-compose.auth.yml` does, so the discovery document it
  publishes is the one a real deployment publishes. The E2E case can therefore assert the
  advertised authorization server is reachable **from outside the compose network** — with the
  old in-cluster hostname that assertion could not have failed.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the discovery path as the preferred way in,
  with the hand-pasted token demoted to a fallback; the new permit row, and why `@PermitAll`
  alone does not make a path public.

### Decisions

- **The permit rule is mandatory, not defence in depth.** quarkus-oidc registers its handler as
  `FilterBuildItem(handler, 50)` and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — higher
  runs first, so authorization would answer 401 before the document could be read, and
  discovery could never start. Verified by `javap` on `OidcBuildStep`, and asserted over HTTP
  in the auth E2E tier because no properties file can prove an ordering.
- **Exact paths, never `/.well-known/*`.** A wildcard there would pre-permit whatever lands
  under that prefix later. A test asserts what the patterns match *and* what they must not.
- **`authorization-server` defaults to `token.issuer`.** `auth-server-url` is the
  cluster-internal Keycloak address in both shipped deployments, so the default would have
  advertised a host no client outside the cluster can resolve. RFC 8414 wants the advertised
  server to equal the issuer regardless, and the E2E assertion compares it against the `iss`
  claim of an accepted token rather than a hardcoded URL.
- **`openid` is advertised deliberately.** `user-info-required=true` makes EDDI call userinfo
  on every request, and Keycloak refuses userinfo for a token minted without that scope;
  clients copy `scopes_supported` into the authorize request. A test pins the pair together so
  removing one surfaces the other.
- **https is forced by default.** `quarkus.http.proxy.*` is unset, so behind a TLS-terminating
  ingress the identifier would be advertised as `http://`.
- **The permit path interpolates the MCP root path (review round 4).** It read
  `/.well-known/oauth-protected-resource/mcp`, a literal, while the advertised resource was
  already derived from `${quarkus.mcp.server.http.root-path}`. An operator who moved the MCP
  root would have moved the document with it and left the permit rule behind: the metadata
  request then meets the catch-all `authenticated` policy and answers 401, and the 401
  challenge that is supposed to bootstrap discovery points at a path that also answers 401.
  Both halves now interpolate the same property. `McpOAuthDiscoveryConfigTest` asserts the
  raw expression — asserting the resolved form would pass either way — and resolves it
  against the root path for its match checks; reverting the property to the literal fails
  that test. `A2aEndpointPermissionsTest` builds its matcher from this file, so it grew a
  small expander for `${…}` inside a permission path and now probes the path-inserted
  document through it; unexpanded, that path entered the matcher as a literal and the model
  reported `authenticated` for a document Quarkus permits.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/configs/McpOAuthDiscoveryConfigTest.java` (new)
- `ui/manager/e2e/auth/auth.spec.ts`, `ui/manager/docker-compose.integration-keycloak.yml`
- `docs/mcp-server.md`, `docs/security.md`
- `src/test/java/ai/labs/eddi/engine/a2a/A2aEndpointPermissionsTest.java`
