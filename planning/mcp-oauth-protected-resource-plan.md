# MCP over an authenticated EDDI — make `/mcp` an OAuth protected resource

> **In one line:** a local MCP client (Claude Desktop, Claude Code, Cursor, LM Studio) cannot practically manage an authenticated EDDI instance today, because the only way in is a hand-pasted Keycloak access token that dies in five minutes. The fix is not to automate rotating that token — it is to stop issuing one, by advertising EDDI as an OAuth 2.0 protected resource so the client runs the authorization flow and refreshes its own credential.

**Status:** Increment 1 ships in **#803** (discovery config) and **#804** (the `eddi-mcp` Keycloak client); Increment 2 in **#805** (audience validation and the OIDC token cache). Each lands on its own branch and is present in every branch stacked above it, so what a checkout does depends on which of them it contains: without #803 EDDI advertises no OAuth metadata, and without #805 it does not validate the token audience. The optional DCR phase is not started, and the open questions below still stand.
**Audience:** a coding agent or engineer with no prior context. Every fact below is cited by file and symbol.
**Repo:** `EDDI` (Java 25 / Quarkus backend). The surprise is how little Java this needs: the work is configuration, three Keycloak realm files, and tests.
**Confidence:** facts marked **[repo]** were read in this checkout or in the Maven jars it resolves. Facts marked **[ext]** come from the MCP specification, client behaviour or Keycloak knowledge that cannot be verified here, with a confidence level — **verify those before depending on them.**
**Related:** [`operator-mcp-guardrails-plan.md`](operator-mcp-guardrails-plan.md) (what an MCP caller may *do* once in), [`security-hardening-remaining.md`](security-hardening-remaining.md), [`docs/connections.md`](../docs/connections.md) (the outbound mirror image of this problem).

---

## 1. Why this exists

### The user-visible problem

An operator runs EDDI with OIDC on. They want a local AI assistant to administer it over MCP — list agents, read logs, apply config changes, resolve HITL approvals. All 84 tools are already there ([`docs/mcp-server.md`](../docs/mcp-server.md)).

What they have to do today:

1. Obtain an access token by hand, e.g. a password grant against the public `eddi-frontend` client.
2. Paste it into the MCP client config as a static `Authorization: Bearer …` header (natively, or via `mcp-remote --header`).
3. Repeat every time it expires. The shipped realm does not override Keycloak's default `accessTokenLifespan`, so that is **every five minutes**.

There is no long-lived key alternative: the only api-key surface in the codebase is the `/v1` OpenAI-compatible adapter (`eddi.openai-compat.api-key`), which is not MCP.

### Why the obvious fix is the wrong fix

The instinctive answer is "make the rotation automatic" — reuse the **Connections** feature, which already does lazy OAuth refresh with a single-flight claim and DEK-sealed grants ([`docs/connections.md` §Refresh](../docs/connections.md)).

**It cannot apply, and the reason matters.** A connection resolves to *an HTTP header EDDI attaches to a request EDDI originates*. Every call site — httpcalls, mcpcalls, A2A, the model factories, Slack — is egress. In this scenario EDDI is the **callee**: the socket is opened by a process on the user's laptop, and there is no seam in EDDI at which to attach anything.

Stated generally: **Connections exists because EDDI holds a credential it must refresh. Inbound, the client holds the credential — so the problem Connections solves does not exist, provided we tell the client how to get one.** That is what this plan does.

### The asymmetry we are closing

EDDI already implements the *reading* half of the exact handshake proposed here, for the case where EDDI is the MCP client talking to somebody else's protected server:

- [`McpAuthChallengeParser`](../src/main/java/ai/labs/eddi/connections/McpAuthChallengeParser.java) parses `WWW-Authenticate: Bearer resource_metadata="…"`, including same-origin validation (`describesResource`).
- [`CredentialEndpointAllowlist`](../src/main/java/ai/labs/eddi/connections/oauth/CredentialEndpointAllowlist.java) says so in its own javadoc: *"RFC 9728 resource-metadata discovery … is not implemented: `McpAuthChallengeParser` can read such a challenge, but nothing fetches the document or selects a server from it. If that ever lands, this allowlist is where the selected server has to be checked."*

We expect this of other servers and do not offer it ourselves. This plan ships the serving half.

---

## 2. Verified facts — do not re-derive

### EDDI's HTTP and auth posture

| # | Fact | Where |
| --- | --- | --- |
| F1 | `/mcp` and `/mcp/*` carry an explicit `authenticated` HTTP policy, deliberately independent of the catch-all so a future broad permit rule cannot open them | `quarkus.http.auth.permission.mcp.*` in `application.properties` |
| F2 | EDDI is bearer-only: `quarkus.oidc.application-type=service`, so an unauthenticated request gets a bare 401, never a redirect | `quarkus.oidc.application-type` in `application.properties`; [`docs/security.md`](../docs/security.md) |
| F3 | The catch-all `authenticated` policy covers `/` and `/*` for every method | `quarkus.http.auth.permission.authenticated.*` in `application.properties` |
| F4 | **HTTP permission policies run before declarative RBAC.** `@PermitAll` on a JAX-RS method does *not* make a path reachable — it must also have its own `permit` entry | the `quarkus.http.auth.permission.*` block in `application.properties` vs. `RestA2AEndpoint`'s `@PermitAll` methods |
| F5 | Roles come from the realm, not the client: `quarkus.oidc.roles.role-claim-path=realm_access/roles` | `quarkus.oidc.roles.role-claim-path` in `application.properties` |
| F6 | **No `quarkus.oidc.token.audience` is configured.** `OidcIdentityProvider` passes `enforceAudienceVerification = idToken`, i.e. **false for bearer access tokens**, and with `token.audience` unset `OidcProvider` calls `setSkipDefaultAudienceValidation()`. A token minted for *any* client in the realm is accepted at `/mcp` and every REST endpoint. **Closed by Increment 2 (#805).** | absence in `application.properties`; `quarkus-oidc-3.39.3-sources.jar`: `OidcIdentityProvider` ~:218, `OidcProvider` ~:265-274 |
| F7 | Per-tool authorization is in-code, a literal `hasRole` with no hierarchy, and a **no-op** when `authorization.enabled=false` | `McpToolUtils.requireRole:41` |
| F8 | `authorization.enabled` tracks `quarkus.oidc.tenant-enabled`, and when false, `DisabledAuthController` switches HTTP permission checks off too | `authorization.enabled` in `application.properties`; `DisabledAuthController` |
| F9 | `HighValueSurfaceGuard` refuses a production boot where `/mcp` or `/secretstore` would be unauthenticated, unless narrowly opted out | `HighValueSurfaceGuard.java` |
| F10 | `quarkus.oidc.authentication.user-info-required=true`, and no `quarkus.oidc.token-cache.*` was configured → one Keycloak userinfo round-trip per request. **Closed by Increment 2 (#805)**, which also notes that this call doubles as the session-revocation check | `quarkus.oidc.authentication.user-info-required` in `application.properties` |

### The Keycloak realms

| # | Fact | Where |
| --- | --- | --- |
| F11 | **Three** realm copies, not two: `helm/eddi/files/eddi-realm.json` and `k8s/overlays/auth/eddi-realm.json` are byte-identical; `keycloak/eddi-realm.json` (compose) differs — it adds `loginTheme: eddi` and lacks the `eddi.example.com` URIs. `DeploymentManifestsTest.realmCopiesDoNotDrift` asserts all three agree on client ids, realm roles and seed users, so adding a client to one fails the unit run until all three have it. The auth E2E realm is generated from the helm copy by `ui/manager/scripts/make-test-realm.mjs`, which also pins its `ROLE_FIXTURES` against the realm's seed users | verified by parsing all three |
| F12 | Clients: `eddi-backend` (confidential, no direct grant, no service accounts, one `groups` mapper) and `eddi-frontend` (public, direct grants, redirects `http://localhost:*` / `https://localhost:*` / `https://eddi.example.com/*`, `webOrigins` including `+`). Realm roles: `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer`, `eddi-approver` | same |
| F13 | **The realm defines no `roles` client scope.** `defaultDefaultClientScopes` is `openid, basic, profile, email, web-origins, acr`, and `eddi-frontend` gets `realm_access.roles` *only* from its own explicit `realm-roles` protocol mapper. It also carries `eddi-backend-audience` and `groups` | same |
| F14 | Every shipped deployment points `auth-server-url` at an **internal** address — `http://keycloak:8080/realms/eddi` (`docker-compose.auth.yml:37`), `http://<release>-keycloak:8080/realms/eddi` (`helm/eddi/templates/configmap.yaml:215`) — and separately sets `QUARKUS_OIDC_TOKEN_ISSUER` to the public one (`docker-compose.auth.yml:53`, `configmap.yaml:262`) | as cited |

### The extensions

| # | Fact | Where |
| --- | --- | --- |
| F15 | **quarkus-mcp-server 1.13.2 contains no auth/OIDC classes at all** — the MCP extension will not help here | `unzip -l` on all three 1.13.2 jars: zero matches |
| F16 | **Quarkus OIDC 3.39.3 already implements RFC 9728 serving.** `ResourceMetadataHandler` serves `/.well-known/oauth-protected-resource`, and — when `resource` is a relative path such as `/mcp` — the **path-inserted** form `/.well-known/oauth-protected-resource/mcp`. `BearerAuthenticationMechanism.getChallenge` appends `resource_metadata="<absolute url>"` to `WWW-Authenticate: Bearer` on 401 (401 only; a 403 carries no challenge). Config: `quarkus.oidc.resource-metadata.{enabled,resource,scopes,authorization-server,force-https-scheme}`; `enabled` defaults **false**, `authorization-server` defaults to `auth-server-url`, `force-https-scheme` defaults **true** | `quarkus-oidc-3.39.3-sources.jar`: `ResourceMetadataHandler.java`, `OidcTenantConfig$ResourceMetadata` |
| F17 | The handler installs only for a tenant that is **both** `tenantEnabled` and `resourceMetadata.enabled`, so an auth-disabled instance serves nothing and needs no extra guard | `ResourceMetadataHandler.createOrUpdatePathMatcher` |
| F18 | The document carries `resource`, `authorization_servers` and — only if configured — `scopes_supported`. Nothing else | `ResourceMetadataHandler.prepareMetadata` |
| F19 | **The metadata handler is registered as `new FilterBuildItem(handler, 50)`**, while `SecurityHandlerPriorities.AUTHENTICATION = 200` and `AUTHORIZATION = 100`. Higher runs first, so the handler runs **after** authorization — the catch-all (F3) answers 401 before it is ever reached. **A permit rule is mandatory, not a precaution** | `javap -c io/quarkus/oidc/deployment/OidcBuildStep.class` → `registerResourceMetadataHandler` → `bipush 50`; `quarkus-vertx-http-3.39.3-sources.jar`: `SecurityHandlerPriorities:7-9` |
| F20 | The resource identifier is built from `context.request().absoluteURI()` unless `resource` is an absolute URL — i.e. from the `Host` header and the perceived scheme. `application.properties` sets no `quarkus.http.proxy.*`, so behind a TLS-terminating ingress it would render `http://…` | `ResourceMetadataHandler.buildResourceIdentifierUrl`; absence in `application.properties` |
| F21 | The MCP extension's failure route does **not** strip the challenge. `HttpMcpServerRecorder.createAuthFailureHandler` is an `asFailureRoute` on `/mcp`. With no token, `AbstractHttpAuthorizer.doDeny` → `sendChallenge` → `response.end()` and the failure route never runs; with an expired token, `DefaultAuthFailureHandler` calls `sendChallenge` (header set) before `event.fail(…)`, so the route overwrites status and body but the header survives | quarkus-mcp-server-http 1.13.2 `HttpMcpServerProcessor`; quarkus-vertx-http 3.39.3 sources |
| F22 | That failure body carries `failure.toString()` — exception class and message reach the client | same |

---

## 3. The design

### 3.1 What a compliant MCP client does

Per the MCP authorization specification (revision 2025-06-18 — **[ext: high]**, re-verify against the current revision), a client that receives a 401 from an MCP endpoint:

1. Reads `WWW-Authenticate`, extracting `resource_metadata`.
2. Fetches that RFC 9728 **protected resource metadata** document, learning the `resource` identifier and the `authorization_servers` list.
3. Fetches the authorization server's RFC 8414 metadata. Clients try `https://kc/.well-known/oauth-authorization-server/realms/eddi` then `https://kc/realms/eddi/.well-known/openid-configuration`; Keycloak serves both **[ext: medium-high]**. The `issuer` must equal the advertised AS URL.
4. Registers itself (RFC 7591 DCR) or uses a pre-configured client id.
5. Runs authorization code + PKCE in the user's browser, passing the RFC 8707 `resource` parameter.
6. Stores access + refresh tokens locally and **refreshes them itself**, with no operator involvement.

Steps 1 and 2 are EDDI's. Step 3 is Keycloak's, already working. Steps 4–6 are the client's.

> **Older clients.** Clients on the 2025-03-26 revision skip the resource metadata and probe `https://<eddi>/.well-known/oauth-authorization-server`, then fall back to `/authorize` on EDDI's own origin **[ext: high]** — both 401 under the catch-all. **Do not proxy authorization-server metadata from EDDI**; state a minimum client spec version in the docs instead.

### 3.2 EDDI configuration — the whole of the backend change

```properties
# Advertise /mcp as an OAuth 2.0 protected resource (RFC 9728). Serving is built
# into quarkus-oidc; tracking tenant-enabled means an auth-less instance
# advertises nothing.
quarkus.oidc.resource-metadata.enabled=${quarkus.oidc.tenant-enabled}
quarkus.oidc.resource-metadata.resource=${quarkus.mcp.server.http.root-path}
# MUST be the PUBLIC Keycloak URL. The default reports auth-server-url, which in
# every shipped deployment is the cluster-internal address (F14) — a laptop
# client would be told to authorize at a host it cannot resolve. token.issuer is
# already the public one, and RFC 8414 requires AS URL == issuer anyway.
quarkus.oidc.resource-metadata.authorization-server=${quarkus.oidc.token.issuer:${quarkus.oidc.auth-server-url}}
# Clients copy scopes_supported into the authorize request. 'openid' is required
# because user-info-required=true (F10) and Keycloak >= 25 refuses userinfo for a
# token issued without it.
quarkus.oidc.resource-metadata.scopes=openid

# The metadata document must be anonymously readable — the handler runs at filter
# priority 50, i.e. AFTER authorization at 100 (F19), so the catch-all would 401
# it and the discovery loop could never start. Exact paths, GET/HEAD only: a
# wildcard such as /.well-known/* would also pre-permit whatever lands there next.
quarkus.http.auth.permission.oauth-resource-metadata.paths=/.well-known/oauth-protected-resource,/.well-known/oauth-protected-resource${quarkus.mcp.server.http.root-path}
quarkus.http.auth.permission.oauth-resource-metadata.policy=permit
quarkus.http.auth.permission.oauth-resource-metadata.methods=GET,HEAD
```

`force-https-scheme` ships as `true`, because the scheme is otherwise read from a request a TLS-terminating ingress has already downgraded (F20). The deployments that need action are therefore the **plain-http** ones running with authentication on — both compose auth stacks, and the `kubectl port-forward` flows the helm chart and the k8s auth overlay document — each of which must set it to `false` or advertise a URL nothing is serving. Pinning an absolute `resource` is preferable again in production, since the authority otherwise follows the `Host` header.

`scopes_supported` stays at `openid` until [Q2](#10-open-questions) decides whether MCP sessions have scopes at all. Advertising scopes we do not enforce is worse than advertising none.

### 3.3 The Keycloak client — where the real work is

**Ship a pre-registered public client `eddi-mcp`. Treat dynamic client registration as a documented escape hatch, never the default.** The decisive reason is not policy preference, it is F13:

> A client without an explicit `realm-roles` protocol mapper issues tokens that **authenticate** — valid JWT, userinfo succeeds — but carry no `realm_access.roles`. Every `requireRole` then throws. The failure mode is *"OAuth worked, and every single tool says forbidden"*, which is the same shape as the group-claim bug already documented beside `quarkus.oidc.roles.role-claim-path` in `application.properties`, and just as invisible to a smoke test run by an admin who happens to hold every role.

A dynamically registered client cannot carry protocol mappers — RFC 7591 registration has no field for them **[ext: high]** — and Keycloak's default anonymous-registration policies (`Trusted Hosts` empty → refuse, `Full Scope Disabled`, `Allowed Client Scopes`, `Allowed Protocol Mapper Types`) would each have to be loosened, plus `roles` promoted to a realm default scope **[ext: medium-high]**. That is a lot of realm surgery to reach a worse security posture than one shipped client.

`eddi-mcp` must have:

| Setting | Value | Why |
| --- | --- | --- |
| `publicClient` | `true` | native/desktop clients cannot hold a secret |
| `standardFlowEnabled` | `true`; `directAccessGrantsEnabled` `false` | code+PKCE only — no password grant |
| `pkce.code.challenge.method` | `S256` | Keycloak then *requires* PKCE |
| protocolMappers | `realm-roles`, `eddi-backend-audience`, `groups` — copied verbatim from `eddi-frontend` | F13, and the audience mapper is what makes §6 Increment 2 possible |
| `redirectUris` | every client shape in the matrix below | F12's list is insufficient |
| `webOrigins` | **not** `+` | no browser origin needs it; do not copy `eddi-frontend`'s |

**Redirect URIs are per-client and one entry does not cover them all [ext: medium-high]:** `mcp-remote` uses `http://127.0.0.1:<port>/oauth/callback` (or `localhost:3334`); Claude Code `http://localhost:<port>/callback`; VS Code `http://127.0.0.1:<port>/`; Cursor a custom scheme; **Claude Desktop custom connectors redirect to `https://claude.ai/api/mcp/auth_callback`** — a remote HTTPS callback, not loopback at all. Keycloak does not treat RFC 8252 loopback-any-port as automatic, and `localhost` and `127.0.0.1` are separate entries. **Verify each of these against a live client before shipping the list** and record a "verified on <date>" line in the docs.

Static-client support also varies: `mcp-remote --static-oauth-client-info '{"client_id":"eddi-mcp"}'` **[ext: medium-high]**; Claude Desktop accepts a client id in the connector's advanced settings **[ext: medium]**; Claude Code and Cursor unconfirmed. A client that supports *only* DCR is the one case that forces the escape hatch.

### 3.4 What actually ends the session

Not the 5-minute access token — the client refreshes that. It is Keycloak's default **`ssoSessionIdleTimeout` of 30 minutes** **[ext: high]**: a laptop idle longer than that must re-run the browser flow. Either raise the idle timeout on the realm or grant `offline_access` to `eddi-mcp`. Decide this deliberately; it is the difference between "set up once" and "log in after every coffee".

---

## 4. Non-goals — do not build these here

- **No EDDI-issued personal access tokens for `/mcp`** in this plan. It is a real option with real merit (§9-A1) and it is *not* a substitute for this one.
- **No relaxation of F1.** `/mcp` keeps its explicit `authenticated` policy. Only the metadata path becomes public, at exact paths, `GET,HEAD`.
- **No proxying of authorization-server metadata** from EDDI's origin (§3.1).
- **No new role semantics.** Whatever roles the user holds is what their MCP session gets (F5, F7). Scoping below that is Q2.
- **No RFC 9728 discovery on the outbound side.** `CredentialEndpointAllowlist`'s javadoc names it; that is a separate change to the Connections path with its own allowlist check.
- **Nothing in the EDDI → client direction. Considered and deferred — recorded here so it is not re-raised from scratch.** The wish is for a local Claude to be reachable *by* EDDI: an agent or group pushing a turn to it, as Slack or email would receive one. MCP cannot do it (client→server only, and a laptop is not addressable), but Claude Code **channels** can — a local stdio MCP server that Claude Code spawns, which connects outbound and pushes `notifications/claude/channel` events into the running session, two-way via a reply tool. The server-side machinery largely exists too: HUMAN members (I6) already pause a discussion, render a prompt, accept a submission and apply a timeout policy, and `/groups/pending-approvals` is already owner-scoped and pollable. **Two things must be designed before any of it is built, and neither is about transport:**
>   1. **Attribution.** A Claude answering a HUMAN member's turn under the user's token is recorded as the user. `group-conversations.md` calls speaking as another human impersonation, and the audit ledger would attribute a model's words to a person. EDDI cannot currently tell the difference — nothing in `src/main` reads the token's `azp` claim, though a token minted for `eddi-mcp` would carry it. A distinct member kind reusing the HUMAN pause machinery is likely better than letting a model occupy a HUMAN seat.
>   2. **HITL must not be answerable by the AI.** Push pending approvals to a client that can call `resume_conversation` and human-in-the-loop becomes model-in-the-loop. Same `azp` hook: refuse HITL decisions from AI-client tokens by default. This is [Q2](#10-open-questions) made concrete.
>
>   Also note channels are Claude Code only (not Claude Desktop), in research preview behind `--dangerously-load-development-channels` or an org `allowedChannelPlugins` entry, and deliver only while a session is open.

---

## 5. Traps and security review

**T1 — The permit rule is mandatory (F19).** Filter priority 50 runs after authorization at 100. Without the rule the metadata document sits behind a 401 and nothing can discover anything. This is the one thing that silently turns the whole feature into a no-op.

> **Latent bug of the same shape, filed separately — do not fix here.** `RestA2AEndpoint` annotates five paths `@PermitAll` — `/.well-known/agent.json` (:79), `/a2a/agents/{agentId}/agent.json` (:98), `/a2a/agents` (:117), `/.well-known/capabilities` (:136), `/.well-known/capabilities/skills` (:165) — with no permission entry naming any of them, so on an authenticated instance all five are unreachable to the anonymous callers they were written for. `A2aEndpointIT` runs with authorization off (`BaseStandaloneIT`), which is why it is invisible. The fix is a decision about which are genuinely public, not a blanket permit — and in particular **not** a `/.well-known/*` wildcard, which would pre-permit this plan's own metadata path.

**T2 — A role-less token is the likeliest failure (F13, §3.3).** Login succeeds, every tool 403s. Any smoke test must assert a *tool call* succeeds, not that the flow completed.

**T3 — The advertised authorization server would be the internal Keycloak URL out of the box (F14).** Works in CI, fails on every laptop.

**T4 — `http://` advertised behind TLS ingress (F20).** No `quarkus.http.proxy.*` is configured today.

**S1 — A hostile metadata document is a redirect to a hostile authorization server.** `authorization_servers` is config-only and a request cannot steer it — keep it that way. The `resource` authority does follow `Host`: pin an absolute `resource` in production. Validating the pointer is the *client's* job, and EDDI's own outbound client already does it (`McpAuthChallengeParser.resourceMetadataUrl`).

**S2 — Audience confusion is real today (F6).** Any realm-issued token is accepted with the user's full realm roles. Fixing it (`quarkus.oidc.token.audience=eddi-backend`) changes acceptance of *every* existing token, so it ships as its own change (§6 Increment 2). Note it does not distinguish two EDDI instances sharing one realm — both carry the same audience; per-instance audiences need per-instance client ids and mappers.

**S3 — Consent is the only thing between a user and full admin.** Realm roles ride in the token, so any client the user authorizes gets everything the user has. Standard OAuth, but it belongs in the docs and is the strongest argument for Q2.

**S4 — DCR's real risk is impersonation, not escalation.** Roles come from the user and `Full Scope Disabled` strips them anyway; the danger is anyone registering a client called "EDDI" with an attacker-controlled redirect and phishing consent. Pre-registration closes it.

**S5 — Metadata discloses nothing new.** It reveals only the public Keycloak URL, which `RestManagerResource.java:46` already injects into the unauthenticated `/manage/__auth_config__.js`. With auth off the route does not exist at all (F17).

**S6 — `HighValueSurfaceGuard` is unaffected** — it reads `authorization.enabled` and its two opt-outs, not the permit table. Add a config test asserting the new permit rule can never match `/mcp`; that guard's own comment exists precisely to prevent "a future broad permit rule".

**S7 — The 401 body leaks `failure.toString()` (F22).** Low severity, worth a line and a follow-up.

**S8 — CORS is out of scope but should be stated.** Native clients do not preflight. Browser-based ones would: `quarkus.http.cors.headers` (`:303`) omits `mcp-session-id` / `mcp-protocol-version`, `exposed-headers` is `Location` only, and the origins are three localhost ports. The MCP extension's DNS-rebinding `Origin` check is only active when `quarkus.http.host` is a localhost name.

---

## 6. Phases

**Phase 0 — document the status quo.** Add an "Authenticated instance" subsection to [`docs/mcp-server.md`](../docs/mcp-server.md) §Authentication (~line 620) with the token-in-header recipe, the five-minute caveat stated plainly, and a pointer to this plan. **Worth doing even if nothing else here is built** — the Quick Start currently shows only unauthenticated `localhost:7070`.

**Increment 1 — discovery (config + realms + docs; essentially no Java).** The `application.properties` block of §3.2; `eddi-mcp` in all three realm copies (F11); docs. After this, a client that supports the flow with a known client id connects and refreshes by itself.

**Increment 2 — audience validation (separate PR).** `quarkus.oidc.token.audience=eddi-backend` (F6/S2) plus `quarkus.oidc.token-cache.*` sizing (F10 — an MCP client is chatty, and today every request costs a Keycloak userinfo round-trip). Depends on Increment 1 having given `eddi-mcp` the audience mapper; the auth E2E tier keeps working because it mints via `eddi-frontend`, which already has one (`tokenFor` in `ui/manager/e2e/auth/auth-helpers.ts`).

**Optional, later — DCR.** Behind a flag, default off, with a registration policy, only if a client that matters supports nothing else (§3.3).

---

## 7. Tests

| Level | What |
| --- | --- |
| Unit (properties-file style, as `ComposeStackTest` / `DeploymentManifestsTest` already do) | The permit rule names exactly the two paths and `GET,HEAD`; `/mcp` is still `authenticated`; the permit rule cannot match `/mcp` (S6); `resource-metadata.enabled` tracks `tenant-enabled` |
| Unit — `DeploymentManifestsTest` | `eddi-mcp` present in **all three** realms (F11), public, PKCE `S256`, no direct grants, carries `realm-roles` + `eddi-backend-audience` mappers, no bare `*` redirect, no `+` web origin |
| Unit | The challenge we emit parses back through `McpAuthChallengeParser.resourceMetadataUrl`, and `describesResource` validates our advertised resource against our own `/mcp` URL — **the parser is already in the repo; use it as the oracle, it is literally what a client does** |
| IT (`tenant-enabled=true`, Keycloak Testcontainer or dev-services) | Metadata path → 200 **unauthenticated**, `resource` ending `/mcp`, `authorization_servers[0]` = the configured public issuer; `POST /mcp` with no token → 401 whose `WWW-Authenticate` names the path-inserted URL; with `tenant-enabled=false` the path 404s. **This is the assertion that catches T1** |
| E2E (`ui/manager/e2e/auth`, realm generated from the helm copy by `make-test-realm.mjs`) | Drive code+PKCE against `eddi-mcp` with Playwright, then call `/mcp` `initialize` **and a real tool** with the resulting token. The only test that catches T2 and validates Increment 2 |

`AGENTS.md` sandbox caveat: ITs need Docker and usually cannot run in agent environments. CI is the gate.

---

## 8. Documentation to change

- [`docs/mcp-server.md`](../docs/mcp-server.md) — §Authentication & Authorization (~620) and Client Configuration (215-283): the new flow, the per-client matrix with its "verified on" date, the `mcp-remote --static-oauth-client-info` form, and the T2 troubleshooting line ("login worked, every tool forbidden → check realm roles and mappers").
- [`docs/security.md`](../docs/security.md) — bearer-only description (:17) and the Auth Permissions table (~:86) gain the new permit row.
- [`docs/configuration-reference.md`](../docs/configuration-reference.md) — only if an `eddi.*` key is introduced; `ConfigurationReferenceCoverageTest` checks both directions.
- [`docs/changelog.md`](../docs/changelog.md) — same commit as the work, per `AGENTS.md` §2 rule 8.

**Pre-existing drift found while reviewing, worth sweeping in the same PR:** `docs/mcp-server.md`'s Configuration block documents `quarkus.mcp-server.http.root-path`, which `application.properties` explicitly says is *not* a recognised key; and the MCP security banner in that file still claims 33 tools and a viewer/admin-only role model.

---

## 9. Alternatives considered

**A1 — Personal access tokens for `/mcp`,** mirroring `/v1`'s `OpenAiAuthFilter`. Works with *everything*, including clients with no OAuth support and CI. Cost: a custom `HttpAuthenticationMechanism` producing a `SecurityIdentity` with roles (tools inject `SecurityIdentity` — `McpConversationTools.java:91-107`); the OIDC bearer mechanism rejects a non-JWT `Bearer` before a second mechanism gets a turn, so it needs its own scheme or a JWT-shaped token; `/mcp` grows a third policy mode and `HighValueSurfaceGuard` must learn it; plus issuance, listing, rotation and revocation. It is the long-lived credential this plan removes — but *per-user and role-scoped*, which is a different animal from a shared key. **A genuine phase 2, not a substitute.**

**A2 — Keycloak offline tokens.** `offline_access` on `eddi-mcp`: refresh never idles out, revocable from the account console, still requires the browser flow once. Strictly complementary to this plan (§3.4), not an alternative.

**A3 — An EDDI-shipped stdio bridge doing device-code flow (RFC 8628).** No redirect URIs, no DCR, works for every stdio client. Cost: an artifact to package and maintain per platform, when `mcp-remote` already covers most of it once discovery exists.

**A4 — A service account per automation.** Right for CI and headless agents, wrong for an assistant acting *as a person* — one synthetic principal, no per-human least privilege, flattened audit trail. Needs a realm change too (F12 has no service-account client).

**A5 — Do nothing but raise the access-token lifespan** on a dedicated client (per-client `access.token.lifespan` avoids touching the SPA). Cheap, and it trades the whole security story for convenience.

**A6 — EDDI manages EDDI.** Already possible with no code: an agent with an `mcpcalls` config pointing at another instance's `/mcp` and `apiKey: "${connection:…}"` against an `OAUTH2_CLIENT_CREDENTIALS` connection — rotation genuinely solved. It does not address this plan's case (the local assistant reaches it only by talking *to* an agent, so there is an LLM in the middle) but belongs in the docs as the machine-to-machine story. Requires the Keycloak origin in `credentialEndpointAllowlist`, which is fail-closed when empty.

---

## 10. Open questions

**Q1 — ~~Does anything emit the challenge natively?~~ Answered:** quarkus-oidc does (F16), quarkus-mcp-server does not (F15), and the ordering question is answered too (F19) — the permit rule is required.

**Q2 — Should an MCP session be scopeable below the user's own rights?** Today it cannot be (F5, F7, S3). A read-only session for an assistant one does not fully trust is an obvious want; it needs a scope-to-role mapping that `requireRole` consults, and it interacts with [`operator-mcp-guardrails-plan.md`](operator-mcp-guardrails-plan.md). Decide separately — do not smuggle it into Increment 1.

**Q3 — `offline_access` or a longer `ssoSessionIdleTimeout`?** (§3.4.) This is a product decision about how often an operator re-authenticates, not a technical one.

**Q4 — What is the real client matrix?** Which of Claude Desktop, Claude Code, Cursor, VS Code and LM Studio implement full discovery, which accept a static client id, which are DCR-only, and what each one's redirect URI actually is. Every `[ext]` marker in §3.3 lives here. This determines whether Increment 1 suffices in practice or whether Phase 0's manual recipe has to stay in the docs indefinitely — **and it is cheap to answer empirically: point one client at a test instance and read the Keycloak login events.**
