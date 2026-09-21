## 📝 docs(mcp): how to reach an authenticated `/mcp`, and the plan to stop needing this (2026-09-20)

**Repo:** EDDI (`docs/mcp-oauth-plan`)

A local MCP client — Claude Desktop, Claude Code, Cursor, LM Studio — cannot practically
manage an EDDI instance that has OIDC enabled. `/mcp` carries an `authenticated` policy
(its own `quarkus.http.auth.permission.mcp` rule), EDDI is bearer-only (`application-type=service`), and it
advertises no OAuth metadata, so a client that would log in by itself gets a bare 401 with
nothing to discover. The only way in is a hand-pasted token that the shipped realm lets
expire after Keycloak's default five minutes, and there is no long-lived key for `/mcp`
(the only api-key surface is the `/v1` adapter).

The Quick Start in `docs/mcp-server.md` only ever showed the unauthenticated
`localhost:7070` case, so nothing said any of that.

### What changed

- **`docs/mcp-server.md`** — new *Connecting to an authenticated instance* section under
  Authentication & Authorization: get a token from the public `eddi-frontend` client, pass
  it either as a header on a Streamable-HTTP client or through `mcp-remote` (whose argument
  splitting means the value belongs in an env var), and four caveats in the order they
  bite — expiry, no api key, roles decide which tools work, and `/mcp` cannot be opened
  selectively. The Quick Start now points at it.
- **`docs/mcp-server.md`** — the Configuration block documented `quarkus.mcp-server.http.root-path`.
  That hyphenated form is not a key the extension knows; `application.properties`
  already says so. Corrected to `quarkus.mcp.server.http.root-path` with the warning kept.
- **`planning/mcp-oauth-protected-resource-plan.md`** (new) — the fix: advertise `/mcp` as an
  RFC 9728 protected resource so the client runs the OAuth flow and refreshes its own token,
  removing the shared long-lived credential rather than automating its rotation.

### Decisions

- **Rotation is the wrong problem to solve.** The instinct is to reuse **Connections**, which
  already does lazy OAuth refresh with a single-flight claim. It cannot apply: a connection
  resolves to a header on a request *EDDI originates*, and here EDDI is the callee. Connections
  exists because EDDI holds a credential it must refresh; inbound, the client holds it.
- **Serving the metadata is configuration, not code.** Quarkus OIDC 3.39.3 already ships
  `ResourceMetadataHandler` and appends `resource_metadata="…"` to the 401 challenge. The
  plan's Increment 1 is four properties, a permit rule and a Keycloak client.
- **A permit rule is mandatory, not a precaution.** That handler registers as
  `FilterBuildItem(handler, 50)`, and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — it
  runs *after* authorization, so the catch-all at `/*` would 401 the discovery document and
  the flow could never start.
- **Pre-registered client over dynamic registration.** The realm defines no `roles` client
  scope; `eddi-frontend` gets `realm_access.roles` only from its own protocol mapper. A
  dynamically registered client cannot carry mappers, so its tokens authenticate and then
  fail every tool with "requires role" — the worst failure shape available.
- **Review follow-up (2026-09-21).** The §3.2 configuration block quoted a hardcoded `/mcp`
  in both `resource-metadata.resource` and the permit rule's second path. What ships derives
  both from `${quarkus.mcp.server.http.root-path}`, so an operator who moves the MCP root
  moves the metadata document and its permit rule with it; a hardcoded permit path would
  leave the relocated document behind the `authenticated` policy and 401 the discovery
  request before it starts. The snippet now matches `application.properties`.

- **The EDDI → client direction is deliberately out of scope** and recorded as such in the
  plan, so it is not re-derived: it needs Claude Code channels rather than MCP, and two
  design answers first — attribution (nothing reads the token's `azp`, so a model answering
  a HUMAN member's turn is recorded as the person) and keeping HITL decisions out of an
  AI client's reach.

### Files

- `docs/mcp-server.md`
- `planning/mcp-oauth-protected-resource-plan.md` (new)
