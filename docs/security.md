# Security

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

This document describes the security measures applied to EDDI's AI Agent Tooling system, particularly for tools that execute in response to LLM-generated arguments, as well as the Keycloak-based authentication layer.

---

## Authentication — Keycloak OIDC

**Version: ≥6.0.0**

EDDI authenticates requests via [Keycloak](https://www.keycloak.org/) (or another OIDC provider) using the Quarkus OIDC extension. Enforcement is switched by `quarkus.oidc.tenant-enabled`, which defaults to `false` — but EDDI does **not** silently run open in production. In production launch mode (the packaged jar and the Docker image; `quarkus:dev` and tests are exempt) two startup guards refuse to boot an unauthenticated instance:

- **`AuthStartupGuard`** fails startup when OIDC is off unless the operator opts out explicitly with `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`. With that opt-out it boots, logs a `[SECURITY]` ERROR at startup and repeats a WARN every hour.
- **`HighValueSurfaceGuard`** fails startup when `authorization.enabled` is false (it follows `quarkus.oidc.tenant-enabled`) unless each of the two most sensitive surfaces is opted out on its own: `EDDI_MCP_ALLOW_UNAUTHENTICATED=true` for `/mcp` and `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true` for `/secretstore`. The general opt-out above does not cover them.

The shipped `docker-compose.yml` sets all three opt-outs so the laptop quick start works, and binds EDDI to `127.0.0.1` for that reason. For anything reachable from another machine, remove them and set `QUARKUS_OIDC_TENANT_ENABLED=true` instead.

### Architecture

EDDI uses **bearer-only (service) mode** — the backend never redirects to Keycloak. The **Manager SPA** performs the interactive login via `keycloak-js` and sends Bearer tokens to the backend for validation.

The **Chat UI does not run `keycloak-js`** and has no login flow of its own. It is an embeddable widget: it receives a Bearer token from its host — via `setAuthToken`/`ChatConfig`, or a `postMessage` hand-off from its parent page at an allow-listed origin (`?tokenOrigin=`) — and forwards it as `Authorization: Bearer <token>`. The hand-off posts only to exact origins (never `*`), asks the host for a new token before the current one expires and on any `401`, holds the start of the conversation for up to 5 seconds until the first token arrives (then starts without one), and keeps the token in memory only; [chat-ui-embedding.md](chat-ui-embedding.md) is the full contract. A token in the URL (`?token=`) is a login-CSRF vector and is logged by the first request before the widget can strip it, so the widget ignores it unless the operator sets `eddi.chat.url-token.enabled=true` (served to the widget by `/chat/__config__.js`); enabled, it is read once and stripped from the address bar on load. Prefer the config or `postMessage` paths.

```
Browser (EDDI Manager)                  Browser (EDDI Chat UI widget)
    │                                        │
    ├── keycloak-js → login → JWT            ├── token from host (config / postMessage)
    │                                        │
    ├── Authorization: Bearer <token> ───────┴──→ EDDI backend
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

This starts Keycloak alongside EDDI with a pre-configured realm, clients and accounts. **No account ships with a password**, and the installer generates the Keycloak bootstrap admin password rather than using a default:

| User | Password | Role | Notes |
|------|----------|------|-------|
| `eddi` | one-time, printed by the installer | `eddi-admin`, `eddi-editor`, `eddi-viewer` | Full access. Keycloak asks for a new password at the first login |
| `viewer` | *none* (`--demo-users` / `-DemoUsers` sets a one-time one) | `eddi-viewer` | Read-only access |
| `user` | *none* (`--demo-users` / `-DemoUsers` sets a one-time one) | `eddi-user` | Standard user access |

The Keycloak admin console is at `http://localhost:8180/admin` — bound to `127.0.0.1` unless `KEYCLOAK_BIND` says otherwise — with the user `admin` and the `KC_BOOTSTRAP_ADMIN_PASSWORD` in `~/.eddi/.env`: the installers generate one, and the GCP provisioner generates one on the VM. A plain `docker compose up` without it falls back to the `admin`/`admin` dev default, which is acceptable only while Keycloak stays on loopback — set `KC_BOOTSTRAP_ADMIN_PASSWORD` before widening `KEYCLOAK_BIND` (Keycloak reads it only when it first creates the master realm). The auth overlay enforces this: its one-shot `keycloak-guard` service, which Keycloak waits for, refuses to start Keycloak when `KEYCLOAK_BIND` is not loopback and `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin` — the same pattern as `docker-compose.yml`'s `bind-guard` for EDDI. On an existing volume whose console password you changed by hand, put that password in `.env` to pass it. Because Keycloak reads that password only on first boot, a volume first started with `admin`/`admin` keeps that login whatever `.env` says later; the one-shot `keycloak-admin-rotate` service, which runs once Keycloak is healthy and before EDDI starts, therefore logs in with `admin` and, if that still works, sets the account to the configured `KC_BOOTSTRAP_ADMIN_PASSWORD`. The public `eddi-frontend` client does not allow the password grant: the Manager signs in with the authorization-code flow.

> **Upgrading an existing install.** These accounts used to ship as `viewer`/`viewer` and `user`/`user`, the console as `admin`/`admin`, and the password grant was on. Realm import is one-shot, so an existing Keycloak volume keeps all of that until it is changed. Re-run the installer (`install.sh` or `install.ps1`, whether EDDI is running or stopped):
>
> - It moves a console that still accepts `admin`/`admin` to a generated `KC_BOOTSTRAP_ADMIN_PASSWORD` and records that in `.env`. If `.env` has no password and the console no longer accepts `admin`/`admin`, the password is yours: the installer warns and says which line to add to `.env` so later runs can manage Keycloak. The same goes for Grafana and `GRAFANA_ADMIN_PASSWORD` on a running stack; on a fresh or stopped one, a generated Grafana password the existing volume refuses stops the install before the success banner, with the `.env` line to fix.
> - It turns the password grant off on `eddi-frontend`, and gives `eddi` a one-time password if it has none.
> - It does **not** touch `viewer` and `user` (without `--demo-users` / `-DemoUsers`), but it tells you when either still has a password — on a legacy realm that is `viewer`/`viewer` and `user`/`user`. Reset or delete their credentials in the console.

### Configuration Properties

| Property                       | Type           | Default                             | Description                                     |
| ------------------------------ | -------------- | ----------------------------------- | ----------------------------------------------- |
| `quarkus.oidc.enabled`         | **Build-time** | `true`                              | Extension active — must be `true` at build time |
| `quarkus.oidc.tenant-enabled`  | **Runtime**    | `false`                             | Enables/disables auth enforcement               |
| `quarkus.oidc.auth-server-url` | Runtime        | `http://localhost:8180/realms/eddi` | Keycloak realm URL                              |
| `quarkus.oidc.client-id`       | Runtime        | `eddi-backend`                      | OIDC client ID (bearer-only)                    |
| `quarkus.oidc.application-type` | Runtime       | `service`                           | Bearer-only mode (no login redirects)           |
| `authorization.enabled`        | Runtime        | `${quarkus.oidc.tenant-enabled}`    | Fine-grained `@RolesAllowed` authorization      |
| `quarkus.oidc.token.audience`  | Runtime        | `eddi-backend`                      | The `aud` an access token must carry            |
| `quarkus.oidc.token-cache.max-size` | Runtime   | `1000`                              | Caches the per-request userinfo lookup (`0` disables) |
| `quarkus.oidc.token-cache.time-to-live` | Runtime | `3M`                              | How long a cached entry stays valid |
| `quarkus.oidc.token-cache.clean-up-timer-interval` | Runtime | `5M`                    | How often stale entries are swept |

> **The token cache defers a revocation check, not a validation.** Signature, expiry and
> audience are verified on every request, before the cache is consulted. What it caches is the
> userinfo lookup — which doubles as the session-revocation check, since Keycloak refuses
> userinfo for a logged-out session. So a session killed in Keycloak keeps working here for up
> to `time-to-live` (3 minutes). Set `max-size=0` to disable the cache and pay a Keycloak
> round trip per request instead.

> **Audience validation.** Quarkus verifies `aud` on an *access* token only when
> `quarkus.oidc.token.audience` is set. Without it EDDI accepts any token the realm
> issued, for any client in it — and since roles come from `realm_access/roles`, which
> is client-independent, such a token arrives with the user's full rights. The shipped
> realm's `eddi-frontend` and `eddi-mcp` clients both carry an `eddi-backend-audience`
> protocol mapper, so their tokens satisfy it. **If you provision your own realm, any
> client whose tokens EDDI should accept needs that mapper** (`oidc-audience-mapper`,
> `included.client.audience=eddi-backend`) — otherwise every request answers `401`.
> Setting `QUARKUS_OIDC_TOKEN_AUDIENCE=any` returns to accepting every token in the realm:
> `any` is quarkus-oidc's sentinel for skipping audience validation, and is the supported
> way to opt out.
>
> This also applies to the `/v1` adapter when it runs in `authenticated` mode, and to any
> deployment pointed at its own identity provider rather than the shipped realm.

> **Important:** `quarkus.oidc.enabled` is a **build-time** property — it cannot be changed at container start. The OIDC extension must always be active in the binary. Use `quarkus.oidc.tenant-enabled` (runtime) to toggle auth on/off via environment variables.

### Enabling Auth at Container Start

```bash
docker run -e QUARKUS_OIDC_TENANT_ENABLED=true \
           -e QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/eddi \
           -e QUARKUS_OIDC_CLIENT_ID=eddi-backend \
           -e QUARKUS_OIDC_APPLICATION_TYPE=service \
           labsai/eddi:latest
```

> **A misconfigured start is refused first.** With OIDC off in production and no explicit
> opt-out (`EDDI_SECURITY_ALLOW_UNAUTHENTICATED`, and for `/mcp` and `/secretstore` their own
> `EDDI_MCP_ALLOW_UNAUTHENTICATED` / `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED`), EDDI stops with
> the guard's message before any startup step touches the database. Earlier releases ran the
> vault and index setup first, so a start that also had no reachable database spent its
> server-selection timeout on MongoDB and exited with a MongoDB error instead of the reason.

### Running without authentication

With OIDC off (`authorization.enabled=false`, the quick start's
`EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`) every endpoint, `/mcp` and
`/secretstore` answer anyone who reaches the port. The compose stacks therefore
publish on loopback only — but loopback stops the network, not the browser on the
same machine. A page on `evil.example` whose DNS name is re-pointed at `127.0.0.1`
(DNS rebinding) becomes same-origin with EDDI and can read and write the whole API;
without rebinding, a page can still send CORS "simple" requests (a form-encoded or
body-less `POST`) that execute although it cannot read the answer.

So while authentication is off, `UnauthenticatedRequestGuard` (a Vert.x route
filter, so it also covers `/mcp`, `/q/*` and static assets):

- answers **421** to a request whose `Host` is not `localhost` (or a
  `*.localhost` name), an IP address, one of the container-host names
  `host.docker.internal`, `gateway.docker.internal` and `host.containers.internal`
  (Podman), or an entry of `eddi.security.unauthenticated.allowed-hosts`
  (`EDDI_SECURITY_UNAUTHENTICATED_ALLOWED_HOSTS`; `host` or `host:port`,
  comma-separated, a bare host matching any port). IP addresses pass because a
  rebinding attack needs a DNS name the attacker controls — and kubelet probes,
  Prometheus scrapes and `eddi.self.base-url` address EDDI that way. The
  container-host names are how a container (Open WebUI, an MCP client) reaches an
  EDDI running on the host; Docker's and Podman's own resolvers answer them, and
  `.internal` is a reserved top-level domain that public DNS never delegates. The raw
  `Host` header is read, never a forwarded one: a rebound page is same-origin and
  may set `X-Forwarded-Host` itself. `*` turns the check off;
- answers **403** to a `POST`, `PUT`, `PATCH` or `DELETE` a browser marks as
  cross-site: an `Origin` that is neither the request's own origin (host and port;
  the scheme may differ behind a TLS-terminating proxy) nor in
  `quarkus.http.cors.origins` — `Origin: null` included — or, with no `Origin`,
  `Sec-Fetch-Site: cross-site` or `same-site`. A request with neither header
  comes from a non-browser client (curl, an SDK) and passes.

`/q/health` is exempt from both. EDDI logs one `[SECURITY]` WARN at startup
describing the policy, and a WARN the first time it refuses each host (at most 32
distinct hosts). With authentication on nothing is registered: every endpoint then
needs a token, which a cross-site page does not have. The compose stacks set the
allow-list to `eddi`, their service name on the compose network; add the name you
browse to when it is not `localhost`. The MCP endpoint's own `Origin` validation
still applies on top.

> **Dev mode answers 400 for more hosts — that is Quarkus.** Under
> `./mvnw quarkus:dev` (and in tests) the server binds `localhost`, which switches on
> Quarkus' own `HostValidationFilter`: it accepts only `localhost`, `127.0.0.1` and
> `[::1]` and ends every other `Host` — `eddi.localhost`, `localhost.`, any name on
> `/q/health` — with an empty `400`, before or after this filter. Widen it with
> `quarkus.http.host-validation.allowed-hosts`. A container binds `0.0.0.0`, does not
> enable it, and answers those requests as described above.

### Shipped realm hardening

`keycloak/eddi-realm.json` (and its copies under `helm/eddi/files/` and
`k8s/overlays/auth/`) sets a password policy (`length(12) and maxLength(128) and
notUsername and notEmail and passwordHistory(3)`), records login and admin events
(30-day retention, admin event bodies off), requires consent on `eddi-mcp` and limits
that client's tokens to the `eddi-*` realm roles (`fullScopeAllowed: false` plus a scope
mapping), and seeds its three accounts with `emailVerified: false` — their placeholder
addresses were never verified, and the user directory resolves shares by verified e-mail.
Realm import is one-shot: a realm imported before these settings keeps its old ones until
you change them in the admin console or re-provision it.

### The Token Must Name the User

EDDI files conversations, memories and approvals under the principal name.
Quarkus reads it from `quarkus.oidc.token.principal-claim` or, when that is unset,
from the first of `upn`, `preferred_username` and `sub` the token carries. A
token that carries none of them still validates, but its principal has no name,
and nothing can be owned by nobody.

EDDI therefore **rejects such a token with `401`** (`NamelessPrincipalAugmentor`)
and logs a `[SECURITY]` WARN, at most once every five minutes, naming the claims
it looked for and the token's issuer and client (`azp`). The ownership checks deny
a nameless caller with `403` as a second line of defence. Background work
(schedule fires, group members, sub-agents) never authenticates a request and is
unaffected.

The principal is also the id a share is stored under and the `subject` the
[user directory](workspaces.md#finding-people-the-user-directory) returns beside a
person's name, so whatever claim it comes from is visible to anyone who can share
with that person. Where `preferred_username` is an email address, set
`quarkus.oidc.token.principal-claim` to a stable, non-address claim such as `sub`
if colleagues' addresses are not everyone's business; the directory hides
addresses it holds as *email* or *username*, but cannot hide the principal itself.

If you see that WARN, either add one of those claims to the access token — for the
shipped realm, [Identity claims, and realms imported from EDDI
6.1.0–6.4.0](#identity-claims-and-realms-imported-from-eddi-610640) below is the
repair — or set `QUARKUS_OIDC_TOKEN_PRINCIPAL_CLAIM` to a claim your provider does
emit. Pick a claim that is stable and unique per user: ownership is keyed on its
value, so a changed name orphans everything filed under the old one.

**Some names are EDDI's own.** EDDI also files work under principals it makes up
for callers that bring no token: `slack:<team>:<user>` for a Slack user,
`openwebui:…` for a header-named Open WebUI user, `group:<id>` for a group's
shared memory, `system:…` for the scheduler and other engine work, `a2a:…`,
`mcp:…`, `webhook:…`, `agent-test:…`, `anonymous-…` and `anonymous`, and the
`gdpr-erased:…` pseudonym. Because ownership is a string comparison, a token
whose principal were `slack:T1:U2` would own that Slack user's conversations.
EDDI therefore **rejects a token whose principal starts with any of these
prefixes (case-insensitively) with `401`**, and logs a `[SECURITY]` WARN naming
the prefix and the issuer, throttled like the one above. Only an identity
provider that allows `:` in usernames can issue such a name; if you see the WARN,
rename the account or point `QUARKUS_OIDC_TOKEN_PRINCIPAL_CLAIM` at a claim that
cannot take that value. The list lives in `ReservedPrincipals`, and a unit test
fails when the code starts minting a new prefix without adding it there.

> **Roles are deployment-wide.** `eddi-editor` grants authoring rights over
> *every* configuration in the deployment. To scope agents, workflows and the
> rest to the user or team that created them — and to share them deliberately —
> see [Workspaces](workspaces.md).

### Role Tiers

Roles are checked literally (`hasRole`), never inherited, so every endpoint and every MCP tool enumerates the roles it admits. The REST API and the MCP server share one matrix — a tier admits the same roles on both doors, except where the table says otherwise:

| Tier | Roles | Covers |
| ---- | ----- | ------ |
| Docs | `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver`, `eddi-viewer` | Published documentation |
| Converse | `eddi-admin`, `eddi-editor`, `eddi-user` (+ `eddi-viewer` over MCP) | Starting, continuing and reading one's **own** conversations, group discussions and memories (the REST memory store admits only `eddi-admin` and `eddi-user`; over MCP an editor may read its own memories too) |
| Author | `eddi-admin`, `eddi-editor` | Configuration stores, deployment, triggers, channels, groups, schedules (REST) |
| Admin | `eddi-admin` | Agent setup (writes API keys to the vault), GDPR, logs, audit, secrets, tenant quotas; over MCP also schedules and memory writes for arbitrary users |
| Observe (MCP only) | `eddi-admin`, `eddi-viewer` | The audit trail and pipeline logs of a conversation the caller owns (`read_audit_trail`, conversation-scoped `read_agent_logs`). Over REST these are Admin |
| HITL | owner, `eddi-admin`, `eddi-approver` | Approval decisions, per conversation |

`eddi-viewer` is the MCP read-and-converse role: over REST it reaches only the docs, usage and workspace listings. The per-tool assignment is in [MCP Server → Role Mapping](mcp-server.md#role-mapping), and `McpRoleTierParityTest` fails the build if an MCP tier and the REST endpoints it mirrors disagree beyond these documented differences.

### Auth Permissions

When OIDC is enabled, the following permission rules apply (see `application.properties`):

| Path Pattern | Policy |
| --- | --- |
| `/q/health/*` | **Permit** (GET only) — required for k8s probes |
| `/q/metrics/*` | **Role `eddi-admin` or `eddi-metrics`** (policy `eddi.metrics.http-policy`, default `eddi-metrics-reader`; roles from `eddi.metrics.roles-allowed`) — metrics leak deployment shape, so a scraper presents a Bearer token **carrying one of those roles**. `authenticated` (any token, the 6.5 default) and `permit` remain selectable; see [Scraping with authentication on](monitoring/monitoring-guide.md#scraping-with-authentication-on) |
| `/openapi`, `/q/openapi`, `/q/swagger-ui` (and below) | **Any EDDI role** (policy `eddi.api-docs.http-policy`, default `eddi-api-docs-reader`; roles from `eddi.api-docs.roles-allowed`) — the API description names every endpoint and model; a token with no EDDI role gets `403` |
| `/`, `/manage`, `/manage/*`, `/chat`, `/chat/*` | **Permit** — SPA entry points. The Manager loads and handles Keycloak login via keycloak-js; the Chat UI widget has no login flow and takes its Bearer token from its host (config / `postMessage`) |
| `/scripts/*`, `/fonts/*`, `/css/*`, `/js/*`, `/img/*` | **Permit** — Static assets for Manager SPA |
| `/.well-known/oauth-protected-resource`, `/.well-known/oauth-protected-resource${quarkus.mcp.server.http.root-path}` | **Permit** (GET/HEAD) — the RFC 9728 document that tells an MCP client where to authenticate. Two exact paths, never a `/*` under the prefix, and the second interpolates the MCP root path so the rule follows the endpoint rather than stranding the document behind the catch-all if that path moves. It must be anonymously readable or discovery cannot start, and it discloses only the public Keycloak URL, which `/manage/__auth_config__.js` already serves unauthenticated. See [MCP Server](mcp-server.md#connecting-to-an-authenticated-instance) |
| `/.well-known/agent-card.json`, `/.well-known/agent.json`, `/a2a/agents/*/agent.json`, `/a2a/agents/*/.well-known/agent-card.json` | **Permit** (GET) — A2A Agent Card discovery: a peer agent fetches these before it has any credential |
| `/.well-known/capabilities`, `/.well-known/capabilities/skills` | **Permit** (GET) — A2A capability discovery, and additionally gated by `eddi.a2a.capabilities.public` (default `false`, which answers 404) |
| `/mcp`, `/mcp/*`, `/secretstore`, `/secretstore/*` | **Authenticated** — named explicitly rather than inheriting the catch-all, so a future permit rule cannot open them by accident; `HighValueSurfaceGuard` refuses a production boot if either is reachable unauthenticated |
| `/*` (catch-all) | **Authenticated** — All other API endpoints require a valid Bearer token |

> **`@PermitAll` does not make a path public.** Quarkus evaluates these path policies *before* declarative RBAC, so an endpoint annotated `@PermitAll` but not named in a `permit` rule still answers `401`. That ordering is also why the discovery document above needs its own rule: quarkus-oidc serves it from a Vert.x filter at priority 50, and authorization runs at 100 — higher first.

> **Note:** there is no HTTP-layer permit for conversation endpoints — `/agents/production/*` is caught by the catch-all `authenticated` policy and answers `401` before any resource method runs. The production exemption in `RestAgentManagement.checkUserAuthIfApplicable` (below) is an inner check only; to expose a production conversation surface anonymously you must add your own `quarkus.http.auth.permission.*` permit rule.

### Deny by default, and what a role-less token reaches

A valid token from your realm is not, by itself, a reason to answer. Two layers make sure of that:

- **`quarkus.security.jaxrs.deny-unannotated-endpoints=true`.** A JAX-RS resource method with no security annotation — `@RolesAllowed`, `@PermitAll`, `@Authenticated` or `@DenyAll`, on the method, its class, or the resource interface — is refused to everyone. Every deliberately public endpoint says so with `@PermitAll`: the SPA shells (`/manage`, `/chat`, `/welcome`, `/workforce`), the OAuth callback `/connections/callback`, the Slack webhook (signature-verified in the handler) and A2A discovery. `EndpointSecurityAnnotationsTest` walks every compiled resource and fails the build on a method that says nothing, so the safe default cannot turn into an outage. Like every rule here it applies only while authorization is on.
- **Roles on the surfaces that used to ask only for a token:** `/q/metrics` and the API description (table above), `GET /a2a/agents` (`eddi-admin`, `eddi-editor`, `eddi-user` — the tier the A2A JSON-RPC endpoint requires), the per-conversation `/conversationstore/conversations/**` operations (any EDDI role, on top of the owner check), and `/v1` in `authenticated` mode — see below.

**`/v1` in OIDC mode.** With `eddi.openai-compat.http-policy=authenticated`, `OpenAiAuthFilter` now requires `eddi-admin`, `eddi-editor` or `eddi-user` — the roles `POST /agents/{id}/start` and A2A require — and answers `403` (`insufficient_permissions`) to any other token. It used to accept any token the realm issued. The default `permit` mode is unchanged: its credential is the shared API key, and the caller is Open WebUI acting for its own users, who hold no EDDI identity.

### Conversations are not configuration

Conversation descriptors share the `descriptors` collection with configuration descriptors, and the generic descriptor API used to take either. Since this release:

- `GET /descriptorstore/descriptors` requires `type` to name a configuration type (one of the types the configuration stores declare, e.g. `ai.labs.agent`); anything else — `ai.labs.conversation`, a blank type, a prefix — is a `400`.
- `GET`/`PATCH /descriptorstore/descriptors/{id}` and every `/descriptorstore/descriptors/{id}/shares/**` operation (including access requests) answer `404` for an id whose descriptor is not configuration. The configuration descriptor store also refuses to write a conversation descriptor at all, because its whole-document write would drop the fields only a conversation descriptor has — its owner among them.
- **Conversation ownership fails closed.** When a conversation's descriptor records no owner, the owner recorded in the conversation memory is used (the pre-v5.1.6 fallback the listing always had); when neither records one, only an administrator may read, continue, list or delete it. It used to be open to every authenticated caller.

### Editors never read a plaintext credential back

Vault values belong to the administrator, so a configuration store does not hand a literal credential to anyone else. `GET` on an LLM, MCP-calls, httpcalls, RAG or channel configuration — including `?version=` reads of old versions, and MCP `read_resource` / `read_channel_integration`, which go through the same REST stores — returns the document as stored to `eddi-admin` and a redacted copy to every other caller (`ConfigSecretRedactor`):

- a string under a credential-named field (`apiKey`, `botToken`, `signingSecret`, `password`, `clientSecret`, `connectionString`, …) anywhere in the document — LLM task parameters, `a2aAgents[].apiKey`, `mcpServers[].apiKey`, `modelCascade` step and judge parameters, `platformConfig`, `embeddingParameters`, `storeParameters` — is replaced by a `<redacted: …>` marker;
- an httpcall's credential headers (`Authorization`, `X-Api-Key`, `*-Key`, `Cookie`) are replaced whole; credential-named query values in `request.path`, a userinfo password in any URL, and credential-shaped values in `request.body` are replaced in place with `<redacted: credential>`.

A reference — `${vault:…}`, `${vars:…}`, `${connection:…}`, `${caller:…}` — or a template (`{properties.apiKey}`) is a pointer and is returned unchanged, also as `Bearer ${vault:k}`.

**An administrator's read does not carry its credentials to a model.** Every LLM tool result passes through the same `SecretRedactionFilter` scan as the tool trace before it enters the model's context (and before the `summarize` truncation strategy hands it to a second model): credential-shaped values (`sk-…`, `Bearer …`, JWTs, provider keys, connection-string passwords) and values under credential-named keys (`apiKey`, `token`, `secret`, `password`, `authorization`) arrive as `<REDACTED>`. So an agent acting with an administrator's authority — the Platform Operator reading a configuration — sees what its trace shows, and the model provider never receives the key. The name rule matches the key's ending, so a field such as `nextPageToken` is redacted too; a model cannot page an API by a token it is never shown.

**A document carrying the marker is never stored.** Every write to these five stores — create, update, JSON Patch, duplicate — and every import or upgrade that creates one directly answers `400` with `{"error": "bad_request", "message": …, "fields": [<paths>]}` when any string in the document contains `<redacted: ` (the whole-value marker and the in-string `<redacted: credential>` both begin with it), for administrators too. Re-enter the credential or replace it with a `${vault:…}` reference. Without this, an editor who opened a configuration in the Manager and saved it would silently replace the stored key with the marker. The store deliberately does not merge the stored literal back in either, because that would let an editor move the marker next to a host of their choosing. Move such literals into the vault.

Server-side work started by an editor reads the unredacted document, with the same access check: RAG ingestion (so a knowledge base whose key is a literal still ingests) and agent upgrade and sync (`StructuralMatcher` compares against, and `UpgradeExecutor` writes back, the target's stored values — an export's `${vault:REDACTED}` placeholder is replaced by the target's own key, never by the marker). A sync **source** read from a peer through a non-admin credential does carry the marker, so its affected resources are refused rather than stored. The upgrade and sync **previews** those reads feed are redacted again at the REST boundary for anyone who is not `eddi-admin` (`PreviewRedaction`), so an editor's preview shows the marker where the stored document holds a literal.

### RestAgentManagement Gate

`RestAgentManagement.checkUserAuthIfApplicable()` enforces per-request auth on the managed-conversation endpoints (`/agents/managed/*`):

```java
if (checkForUserAuthentication &&
        !production.equals(environment) &&
        identity.isAnonymous()) {
    throw new UnauthorizedException();
}
```

- When `quarkus.oidc.tenant-enabled=false` → `checkForUserAuthentication=false` → all requests pass
- When `quarkus.oidc.tenant-enabled=true` → a request against a non-production environment (`test`) must be authenticated; `production` conversations are exempt from this particular gate (the legacy `unrestricted`/`restricted` names are read as `production`)
- Requests to `/production/` environments always pass regardless of auth status

**The check runs before any side effect.** The managed `GET` and `POST /agents/managed/{intent}/{userId}` create the user's conversation when none exists and replace it when it has ended. The environment checked is the one the request acts on: a live conversation's stored environment, or — for a conversation about to be created or to replace an ended one — the environment of the trigger deployment it will be started with, which is picked *before* the check and then used for the start. So a `401` from these endpoints means nothing was created, deleted or replaced. The `POST` hands the `UnauthorizedException` to the JAX-RS exception mappers, so it answers `401` like the `GET` does (it used to answer an opaque `500`).

### Local Development Keycloak

The Manager (`ui/manager` in this repository) provides a docker-compose for local Keycloak — run it from that directory:

```bash
docker compose -f docker-compose.keycloak.yml up
```

This starts Keycloak 26 on port 8180 with:

- **Realm**: `eddi`
- **Clients**: `eddi-frontend` (SPA, public), `eddi-backend` (bearer-only), `eddi-mcp` (public, code+PKCE only — what an MCP client logs in through; see [MCP Server](mcp-server.md#connecting-to-an-authenticated-instance))
- **Roles**: `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer` (plus `eddi-approver`, used by the HITL approval endpoints)
- **Test users**: `viewer`/`viewer` (`eddi-viewer`), `user`/`user` (`eddi-user`), and `eddi` (`eddi-admin` + `eddi-editor` + `eddi-viewer`), which ships **without a password** — set one in the admin console at http://localhost:8180 (`admin`/`admin`)

> `keycloak/eddi-realm.json` is the source of truth for client ids. Provisioning a
> realm by hand from a doc that names a different one gets you `invalid_client`
> at login: `RestManagerResource` hardcodes `eddi-frontend` as the id the Manager
> SPA requests tokens for, so that is the client that has to exist.

### Identity claims, and realms imported from EDDI 6.1.0–6.4.0

EDDI identifies a caller by the principal Quarkus OIDC reads from the access
token: `upn`, then `preferred_username`, then `sub`. Conversation ownership,
long-term user memory, HITL attribution and workspaces all key on that name. So
the `eddi-frontend` client has to put identity claims in its tokens, which takes
three client scopes: `basic` (`sub`), `profile` (`preferred_username`, `name`)
and `email`. The `openid` scope the realm also defines is there for a different
reason: it puts `openid` in the token's `scope` claim even when a client did not
ask for it, and Keycloak's userinfo endpoint — which the backend calls on every
request — refuses a token without it.

**The realm shipped with EDDI 6.1.0 through 6.4.0 had none of the three** (for
Helm and Kustomize, which first shipped a realm in 6.4.0, that release only). It
defined a single client scope, and a realm file that defines any client scopes
gets only those: Keycloak creates its built-in ones only for realms that define
none. The import logs `Referenced client scope 'profile' doesn't exist. Ignoring`
and carries on. Tokens still authenticated and still carried their roles, so
logins worked and role checks passed, but every caller's principal had no name.

**From this release on, such a token is refused outright:** every request answers
`401` (see [The Token Must Name the User](#the-token-must-name-the-user) above),
so an install carrying an unrepaired realm cannot be used until the repair below
has been run. Up to and including 6.4.0 the same token was let through, and the
damage was quieter:

- Every conversation was stamped with a random `anonymous-<hex>` owner instead of
  the user, and long-term user memories were filed under that same per-conversation
  id, so nothing a user saved was recalled in their next conversation.
- A user without `eddi-admin` who opened or continued their own conversation got
  **HTTP 500**.
- `GET /workspaces` reported no principal, so workspaces had nothing to scope to.
- The Manager showed "?" in place of the user's initials and no name in the user
  menu.

Conversations and memories written while the realm was broken keep their
`anonymous-<hex>` owner, as described at the end of this section — the `401` stops
new ones from being created that way.

Realm import only runs on first boot, so a fixed realm file does not reach a
Keycloak that already has the `eddi` realm:

- **`install.sh`** repairs it whenever it runs, including when EDDI is already up:
  run the installer again while EDDI is running — with EDDI running, it only
  refreshes the `eddi` command and checks Keycloak. If you installed on a port
  other than 7070, pass the same one (`EDDI_PORT=7071 bash install.sh`): the
  installer recognises a running EDDI only on the port it is given, and would
  otherwise set up a new installation. Look for
  `Checking Keycloak identity scopes ✅ (repaired: …)`, then sign out and in again.
  `eddi update` does **not** run this check.
- **`install.ps1`, Helm, Kustomize, or a realm provisioned by hand**: run the
  repair below once, against the Keycloak admin API — on Windows from Git Bash or
  WSL. It creates only the scopes that are missing, using the definitions in the
  realm file, attaches them to `eddi-frontend`, and removes nothing, so running it
  twice is harmless. It needs `curl` and `jq`, and a checkout of this repository
  for the realm file (the three copies define the same scopes); run it from the
  checkout's root. Set `KC`, `KC_ADMIN_USER` and `KC_ADMIN_PASSWORD` for your
  Keycloak: `http://localhost:8180`, `admin` and `admin` for the docker-compose
  setup the installers create, or the port-forward, `keycloak.adminUsername` and
  the admin Secret on Kubernetes. It runs in a subshell, so pasting it into a
  terminal cannot close that terminal when a step fails.

  ```bash
  ( set -eu
  KC=${KC:-http://localhost:8180}   # kubectl -n eddi port-forward svc/keycloak 8080:8080 → http://localhost:8080
  : "${KC_ADMIN_PASSWORD:?set KC_ADMIN_PASSWORD to the Keycloak admin password}"
  REALM_FILE=keycloak/eddi-realm.json
  # tr -d '\r': a native Windows jq.exe ends its output with CRLF under Git Bash
  j() { jq "$@" | tr -d '\r'; }
  TOKEN=$(curl -sSf -d client_id=admin-cli -d grant_type=password -d "username=${KC_ADMIN_USER:-admin}" \
    --data-urlencode "password=$KC_ADMIN_PASSWORD" \
    "$KC/realms/master/protocol/openid-connect/token" | j -r .access_token)
  [ -n "$TOKEN" ] || { echo "could not log in to $KC as ${KC_ADMIN_USER:-admin}" >&2; exit 1; }
  AUTH="Authorization: Bearer $TOKEN"
  SPA=$(curl -sSf -H "$AUTH" "$KC/admin/realms/eddi/clients?clientId=eddi-frontend" | j -r '.[0].id // empty')
  [ -n "$SPA" ] || { echo "no eddi-frontend client in realm eddi" >&2; exit 1; }
  scope_id() {
    curl -sSf -H "$AUTH" "$KC/admin/realms/eddi/client-scopes" \
      | j -r --arg n "$1" '.[] | select(.name == $n) | .id'
  }
  for scope in basic profile email web-origins acr; do
    if [ -z "$(scope_id "$scope")" ]; then
      j -c --arg n "$scope" '.clientScopes[] | select(.name == $n)' "$REALM_FILE" \
        | curl -sSf -X POST -H "$AUTH" -H "Content-Type: application/json" -d @- \
            "$KC/admin/realms/eddi/client-scopes"
      echo "created $scope"
    fi
    id=$(scope_id "$scope")
    [ -n "$id" ] || { echo "client scope $scope is still missing" >&2; exit 1; }
    curl -sSf -X PUT -H "$AUTH" "$KC/admin/realms/eddi/clients/$SPA/default-client-scopes/$id"
    echo "attached $scope"
  done )
  ```

Users pick the claims up at their next sign-in. The principal then becomes each
user's username, so conversations and memories created **after** the repair
belong to the user who created them. Anything written before it keeps its
`anonymous-<hex>` owner: an `eddi-admin` can still open those conversations, but
neither they nor the memories filed with them can be attributed to a person, so
a data-subject request by username will not find them.

---

## Threat Model

When an LLM is given access to tools, every argument it supplies must be treated as **untrusted input**. An attacker can craft prompts that cause the LLM to pass malicious arguments to tools — a class of attacks known as **prompt injection**. EDDI mitigates these risks at the tool-execution layer so that individual tools do not need to implement their own defences.

### Input and output screening

Tool *arguments* are governed at the tool-execution layer, tool *results* by the tool-result guardrail. The user's message and the model's final answer are screened by **input and output guardrails** — opt-in per LLM task (`inputGuardrails`, `outputGuardrails`), see [Input and Output Guardrails](langchain.md#input-and-output-guardrails). The built-ins are deterministic and cheap: `prompt-injection` (instruction-override phrasings), `pii-redaction` (e-mail, SSN, payment card, phone), `regex-filter` (author patterns), `max-length` and `json-object`. Design points that matter for a security review:

- **A blocked input never reaches the model** — and no `preRequest` property mutation runs for it. Earlier messages of the history window are screened again on every turn, so a message blocked or redacted on its own turn is not replayed to the model later.
- **Redaction changes what the model sees and what is delivered, not what is stored as the user's message.** Conversation memory keeps the user's input verbatim (it is the record); the ledger records the guardrail outcome and the delivered answer, never the guardrail's view of the screened text.
- **Fail-safe vocabulary.** An unknown action is `block`; an unknown type is skipped with a WARN (configuration must keep loading on older versions). A guardrail that throws or returns no verdict **fails closed** when its action is `block` or `reprompt` (the turn is blocked, reason `guardrail_error`) and open when it is `redact` or `warn`; the per-guardrail `failMode` parameter overrides either way.
- **No template injection.** Guardrail parameters, block messages and reprompt messages are author text used verbatim; the screened text is only ever data. The guardrails that render a template, `classifier` and `judge` (their `promptTemplate`), receive the screened text as template *variables* (`{input}`, `{output}`, `{context}`), never as template source. A `judge` reprompt is **closed-vocabulary**: the judge may only name issue codes the author declared (any other answer is undecided), and the corrective message is built from the author's fix texts for those codes alone. The corrective message therefore carries nothing the judge, the user or the model wrote, and nothing the judge wrote reaches the producing model except the codes it chose, as the author's text. Like every reprompt, the re-ask still sends the original request and the rejected answer (as the assistant's turn), so this narrows what the *judge* can inject; it does not isolate the producer from the conversation it is answering.
- **Streaming.** An output guardrail that can change the answer forces the task to buffer it — streamed tokens cannot be retracted.
- **A quality check does not override the safety checks around it.** `onExhausted: "deliver-best"` lets a `reprompt` guardrail whose retries are spent deliver its best attempt instead of blocking. That attempt is first re-screened by every output guardrail **after** it in the list (the ones before it already screened it: redactions applied, a blocking finding blocks). The exhausted guardrail itself does not re-screen what it delivers, so on a `classifier` it delivers the very attempt it flagged: the deploy warns, and a safety classifier should keep `onExhausted: "block"`.
- **Model-backed screening, and its availability trade-off.** The `classifier` type asks a safety-classifier model the agent designer configures (EDDI ships and endorses none). It reaches the model through the LLM provider client, not `SafeHttpClient`: classifier endpoints are typically in-cluster, and a configured classifier `baseUrl` is the same trust class as a task's own model `baseUrl` — only configuration authors can set either. A classifier that is unreachable, times out (`timeoutMs`, default 3 s, bounded by the turn deadline) or answers something that is not a label has not checked the text, and `failMode` decides: **`closed`** (the default for `block`/`reprompt`) refuses the turn, so a classifier outage becomes an outage of every guarded agent — the safe choice where letting unscreened text through is the worse failure; **`open`** records a `warn` (reason `classifier_unavailable` / `classifier_unparseable`) and lets the text through, keeping the agent available while the screen is down. Pick per guardrail, watch `eddi.guardrail.classifier.duration{outcome}` and the `classifier_unavailable` audit reasons, and size the classifier for the input path: it runs before the main model on every turn.
- **Limits.** Pattern guardrails catch verbatim attacks and well-formed identifiers, not paraphrase, encoding or semantics. Input guardrails screen the user's message before retrieval, so a blocked message issues no RAG query and the query is the redacted text; an `httpCallRag` template that reads `memory.current.input` directly still sees the stored, unredacted text.

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
>
> **A templated host is refused while it is off.** An httpCall whose scheme, host
> or port contains a template expression (`https://{properties.apiHost}`,
> `http://127.0.0.1:{properties.port}`) fails to deploy, and a turn that still
> reaches one fails, with a message naming this flag. A property is routinely
> captured from user input, so without validation of the rendered host the
> chatting user could pick loopback — EDDI's own admin API included. A
> `${vars:…}` reference in the host is still accepted: its value is set by an
> operator. Templating the *path* or query is unaffected.
>
> **Keep internal APIs with protection on** by listing them:
>
> ```properties
> eddi.security.ssrf-protection.enabled=true
> eddi.security.ssrf-protection.allowed-hosts=billing.internal,10.0.0.5:9200
> ```
>
> Entries are exact `host` or `host:port` values, compared case-insensitively,
> with no wildcards; a bare host matches any port. An httpCall to a listed host
> skips the private-address check but never the cloud-metadata check, and
> redirects stay off. A templated host can then only resolve to a listed host or
> a public one. The allow-list applies to httpCalls; MCP servers and A2A peers
> are validated as before.
>
> **Production warns when it is off.** `AuthStartupGuard` logs a `[SECURITY]`
> WARN at startup in production while `eddi.security.ssrf-protection.enabled` is
> `false`. The default stays `false` because configured targets legitimately reach
> internal hosts (the rule-based reference agent calls `http://localhost:7070`).
>
> **The cloud instance-metadata service is blocked either way.** With protection
> off, httpCalls, MCP servers and A2A peers still refuse
> `169.254.169.254`, `fd00:ec2::254`, `100.100.100.200`,
> `metadata.google.internal` and the whole link-local range (`169.254.0.0/16`,
> `fe80::/10`) — including a hostname that resolves there
> (`UrlValidationUtils.rejectCloudMetadataTarget`). The metadata service hands out
> the instance's cloud credentials; nobody configures it as an API. A URL whose
> host the check cannot read is refused too: an `http(s)` URL with an underscore
> in its host (`x_y.example`), a percent-encoded host, or an IP literal with a
> trailing dot (`169.254.169.254.`) has no host to `java.net.URI`, while the
> httpCalls client parses it with `java.net.URL`, resolves it and connects — so a
> registry-style name pointed at `169.254.169.254` used to pass unchecked
> (security review 2026-10-17, H2). Use a standard host name; a Docker service
> name with an underscore needs a hyphenated alias. With protection
> off redirects are still followed, so the httpCalls client checks every redirect
> hop too: a public URL answering `302 Location: http://169.254.169.254/…` fails
> instead of being followed. The MCP client and the A2A client never follow a
> redirect at all, protection on or off: the transport is built with
> `followRedirects(false)` (pinned in `McpToolProviderManager`, held to its word
> by `McpTransportRedirectTest` against an embedded redirecting server), so a
> `3xx` from a configured MCP URL fails the handshake instead of carrying the
> session — and its `Authorization` header — to a host nobody validated. (Until
> 6.6.0 the MCP transport followed redirects: security review 2026-10-15, M2.)
>
> **Configured endpoints beyond httpCalls are covered by the same switch.** LLM
> provider `baseUrl`/`endpoint`/`apiEndpoint` in every builder, embedding
> endpoints, vector-store addresses (Elasticsearch, Chroma as URLs; pgvector,
> Qdrant and every host in a MongoDB connection string as bare hosts, opened as
> raw connections) and the OpenAPI spec location of endpoint discovery and the
> setup wizard all go through `OutboundTargetPolicy`: the metadata service is
> refused always, and with protection on any private, loopback or link-local
> address is too — with two exemptions: a host on
> `eddi.security.ssrf-protection.allowed-hosts` (the same list httpCalls honour),
> and a URL on this deployment's own origin (`eddi.self.base-url`), so an agent
> may still call EDDI's own `/v1` API. The spec document is fetched by EDDI
> itself (`OpenApiSpecFetcher`: every redirect hop checked, body capped at 16 MB)
> rather than by swagger-parser. Residual:
> `$ref`s inside a fetched spec are resolved by swagger-parser's own resolver,
> which checks each reference's URL but follows that URL's redirects itself.

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
| `255.255.255.255` | Limited broadcast (the rest of `240.0.0.0/4` stays reachable) |
| `192.0.0.0/24`   | IETF protocol assignments (RFC 6890) |
| `198.18.0.0/15`  | Benchmarking (RFC 2544)       |
| `fc00::/7`       | IPv6 unique-local (RFC 4193 — covers `fc00::/8` and `fd00::/8`) |
| `fe80::/10`      | IPv6 link-local               |
| `::1`            | IPv6 loopback                 |
| `64:ff9b:1::/48` | NAT64 local-use prefix (RFC 8215) — blocked whole, since where the IPv4 address sits inside it depends on the prefix length the network chose |

IPv6 addresses that carry an IPv4 address are unwrapped and the IPv4 address is re-checked against every IPv4 rule above: IPv4-mapped (`::ffff:x.x.x.x`), IPv4-compatible (`::x.x.x.x`), NAT64 well-known prefix (`64:ff9b::x.x.x.x`, RFC 6052), 6to4 (`2002:xxxx:xxxx::/48`, RFC 3056) and Teredo (`2001::/32`, server and client address). On a NAT64 network `64:ff9b::7f00:1` *is* 127.0.0.1 once the gateway translates it, so checking the IPv6 address alone let it through. The same unwrapping applies to the always-on cloud-metadata refusal.

### Cloud Metadata Endpoint Blocking

Cloud provider metadata services are explicitly blocked by IP and hostname:

- `169.254.169.254` (AWS, GCP, Azure metadata)
- `metadata.google.internal` (GCP)

### Internal Hostname Blocking

Hostnames that indicate internal services are rejected:

- `localhost`
- Any hostname ending in `.local`
- Any hostname ending in `.internal`

### DNS rebinding

The checks above resolve the host; the HTTP client then resolves it **again** to connect, and none of the clients EDDI uses can be handed the validated addresses (the JDK `HttpClient` has no resolver hook, and an IP-literal URL would break TLS SNI and certificate checks). Both lookups normally read the JVM's positive DNS cache — 30 seconds by default, and Vert.x resolves through the JDK because `quarkus.vertx.use-async-dns` is off — so they agree. A rebinding name aims for the instant that cache entry expires between the two, answering with a public address for the check and a private one for the connection. What each path does about it:

| Path | Guard | What is left |
| --- | --- | --- |
| httpcalls (Vert.x client, `HttpClientModule`) | Before a byte of the request is written, the address the connection **actually reached** is checked — first request, pooled connection and every redirect hop alike. A metadata or link-local peer is refused always; with `eddi.security.ssrf-protection.enabled=true`, a loopback, private or internal peer is refused unless the request's host is on `eddi.security.ssrf-protection.allowed-hosts`. | Nothing for the address check. With protection off, the per-hop "no step into a more private class" rule still compares by name |
| `OutboundHttp` (LLM tools, crawler, attachments, A2A, OpenAPI specs) | The hop's check runs a second time once the response headers are in. The connection's own lookup is the one the cache now holds, so a target that has turned private, or into the metadata service, is refused and its response discarded before it reaches the caller or a redirect. | The request itself was sent (a blind request, no response). An exchange slower than the cache lifetime, or `networkaddress.cache.ttl=0`, defeats the second look; EDDI logs a warning at startup when the cache is disabled. A pooled connection opened under a laxer policy for the same host is not re-checked |
| MCP servers (langchain4j transport, `McpToolProviderManager`) | The server URL is validated on every discovery, a cache hit included, and again inside the transport's per-request header hook, before each request — handshake, `tools/list`, every tool call — is sent. | The same cache-expiry window as any pre-connect check; the transport exposes no connected address to check after the fact |

Keep `networkaddress.cache.ttl` at its default or above 0: with the positive cache off every lookup is fresh and the two answers can differ at will.

### Usage

```java
import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

// In any tool method that accepts a URL:
validateUrl(url); // throws IllegalArgumentException if blocked
```

---

## Mail Sockets — `SafeSocketConnector`

IMAP and SMTP are raw TCP, which the HTTP client's SSRF policy does not cover. A mail host that a channel or
knowledge-base author types in would otherwise be an internal port scanner ("does `10.0.0.5:993` answer?").
`SafeSocketConnector` (`engine/net`) is the only way EDDI opens such a socket, and it applies the same address rules
as `UrlValidationUtils` — it calls `isPrivateAddress` and `isBlockedHostname` rather than keeping a copy:

| Rule | Detail |
|---|---|
| Host is a DNS name | IP literals are refused in every spelling resolvers accept (`127.0.0.1`, `2130706433`, `0x7f.0.0.1`, `[::1]`), as are single-label names. A mail server has a name, and TLS validates that name |
| Internal names refused before lookup | `localhost`, `*.local`, `*.internal`, cloud metadata host names |
| Allowed ports only | IMAP `993` (TLS) or `143` (STARTTLS, required); SMTP `465` (TLS) or `587` (STARTTLS, required). No port 25, no plaintext session |
| Deployment allowlist | When `eddi.email.allowed-hosts` is set, the host must match an entry (`host` or `*.domain`; the wildcard does not match the domain itself). Empty allows any public host |
| Resolved once, every address checked | One DNS answer with a private, loopback, link-local, CGNAT, ULA, metadata, multicast or unspecified address — or an IPv6 form embedding one — refuses the host |
| Pinned | The socket connects to the first address of that one resolution. A mail library re-resolving the name (DNS rebinding) is ignored: the socket accepts only the vetted host and port and connects to the pinned address |
| TLS always verified | Certificate chain against the JVM trust store and host name against the certificate. There is no setting that turns either off |
| Timeouts | 10 s connect, 60 s per read |

The allowlist does **not** exempt private addresses: an on-premises mail server named by a channel or knowledge-base
author is refused.

### The operator policy — the system mailer's relay

The rules above are the **author** policy (`resolve`). A host the deployment's **operator** set in
`application.properties` — today only the [system mailer](configuration-reference.md#email)'s
`eddi.mail.smtp.host` — goes through `resolveOperator` instead. The operator already controls the network the process
runs in, so the rules that stop an author probing it are lifted, and nothing an author or an agent writes can reach
this path:

| Rule | Author (`resolve`) | Operator (`resolveOperator`) |
|---|---|---|
| Private, loopback, CGNAT, ULA addresses | refused | allowed (an on-premises relay, a Postfix sidecar) |
| IP literals, single-label names (`localhost`, `relay`) | refused | allowed |
| Ports | 993/143, 465/587 | any (25 included) |
| `eddi.email.allowed-hosts` | applied | not consulted — it confines authors |
| Link-local and cloud-metadata addresses | refused | **refused** |
| Resolved once and pinned | yes | **yes** |
| TLS certificate and host name verified | always | **always**, when TLS is used |
| Plaintext session | never | only `eddi.mail.smtp.tls=none` with `eddi.mail.smtp.allow-plaintext=true`, and never with a credential |

`TlsMode.PLAINTEXT` cannot exist on an author endpoint: the `Endpoint` constructor refuses it. STARTTLS on the system
mailer is required, not opportunistic — a relay that does not offer it gets no message and no credential.

For Angus Mail, `angusProperties(endpoint)` installs the pinned factory for both plain and TLS connections, turns
`socketFactory.fallback` off (otherwise a refusal inside the factory makes Angus retry with an unguarded socket of
its own), requires STARTTLS where it is used, and keeps `ssl.checkserveridentity` on. Callers add only their
authentication settings, never `*.ssl.trust`, socket-factory, proxy or SOCKS properties.

The mail core it serves (`engine/mail`) treats every message as untrusted: size, header, nesting, part and
attachment limits are enforced before the work they bound, executables never carry content, archives are never
expanded, HTML is converted with hidden content removed (`display:none`, `hidden`, zero opacity, zero-size or
transparent text, text the colour of its background, off-screen positioning, `<style>` hiding rules, comments —
`HiddenContentFilter` in mail mode; web and file ingestion use its conservative page mode, which keeps
script-revealed content and drops only comments, 1px text, transparent text and explicitly same-coloured text),
a `multipart/alternative` whose plain and HTML parts say different things is flagged `ALTERNATIVES_DIVERGE`, and
the plain-text renderer refuses any header value containing a line break. Every one of these passes is linear in
the input — style blocks are read by a single forward scan, never by a backtracking regular expression.

---

## Mail Sender Authentication — DKIM and DMARC

A `From:` header is a string the sender types. Before the email channel (plan Phase 2) lets a mail reach an agent
under its sender's identity, EDDI checks the DKIM signatures on the **raw bytes** itself and then DMARC alignment —
provider verdicts are inconsistent, mostly unsigned, and absent for IMAP. One call, `SenderAuthenticator.authenticate`
(`engine/mail`), returns `MailAuthentication`: `ALIGNED_PASS`, `PASS_UNALIGNED`, `FAIL` or `NONE`, the signing domain,
a reason, a `temporary` flag and the outcome of every evaluated signature. **Only `ALIGNED_PASS` counts as
authenticated.**

| Rule | Detail |
|---|---|
| Algorithms | `rsa-sha256` (keys of 1024–8192 bits, public exponent at most 64 bits) and `ed25519-sha256` (RFC 8463, the JDK's EdDSA). `rsa-sha1` fails (RFC 8301), as does any other algorithm |
| Canonicalisation | `simple` and `relaxed` for header and body, in every combination (RFC 6376 §3.4). Bare-LF input (a message stored on Unix) is converted to CRLF first |
| `l=` refused | A body-length limit lets anyone append unsigned text to a signed mail and keep the signature valid; a signature carrying `l=` fails |
| Header selection | Bottom-up per RFC 6376 §5.4.2; a name listed in `h=` more often than the header occurs binds its absence (over-signing), so a header added later breaks the signature. A signature must cover `From` |
| One `From`, one mailbox | Exactly one `From` field with one mailbox, counted on the raw bytes as well as by the parser. A second `From` added above a once-signed one is not hashed — the signature still verifies — and many clients display it, so such a message is `FAIL` whatever its signatures say. The same holds for a duplicated `Subject`, `Date`, `Message-ID`, `In-Reply-To` or `References` |
| Key record | `v=DKIM1` (first, if present), `k=` matching the algorithm, `h=` allowing sha256, `s=` allowing email, one record per selector. Empty `p=` = revoked = `FAIL`. `t=y` (testing) is not a pass: a message with only such signatures is `NONE`. `t=s` requires `i=` to equal `d=` |
| Identity and time | `i=` must lie within `d=`. An expired `x=`, an `x=` before `t=`, or a `t=` more than 5 minutes in the future fails. Both are judged at the delivery instant when the transport passes the receiving server's own timestamp (IMAP `INTERNALDATE`, never a sender header), clamped to `[now − eddi.email.dkim.max-delivery-age, now]` (default 7 days); otherwise at the current time |
| DMARC alignment | The `From` domain and a passing signature's `d=` must share an organisational domain (relaxed), or be equal when the `_dmarc` record says `adkim=s` (strict). The record is read from the `From` domain, else from its organisational domain (RFC 7489 §6.6.3); none = relaxed. A DMARC lookup that fails temporarily assumes strict and sets `temporary` |
| Public Suffix List | Organisational domains come from the bundled Public Suffix List (ICANN and private sections, wildcard and exception rules), never fetched at runtime — see below |
| DNS | TXT lookups through dnsjava (`MailDnsResolver`): `eddi.email.dns.servers` or the system's resolvers, a timeout, a cache that honours each record's TTL capped at `eddi.email.dns.cache-max-ttl` and bounded in size, at most `eddi.email.dns.max-lookups-per-message` network lookups per message, records over 4096 characters or answers of more than 10 records refused. DNSSEC is not required: run a validating resolver to add it |
| Bounds | At most 5 signatures evaluated (the rest are counted), a 256 KB header block, 100 names per `h=`, one body hash per canonicalisation however many signatures there are, and the body is checked before the key is fetched so a tampered mail spends no lookup. Never throws: broken or hostile input is `FAIL` or `NONE` with a reason |
| Provider fallback | `SignedProviderVerdict`, for a transport that receives a provider verdict under a signature it verified (Amazon SES over SNS). Used only when the local check could not run (no raw bytes, or every key lookup failed temporarily); never in place of a local `FAIL`, `NONE` or pass, and alignment is still EDDI's own. No provider implements it yet |

**What an aligned pass proves, and what it does not.**

- It authenticates the **domain**, not the person: anyone who can send through `example.com`'s signing servers can
  write as `ceo@example.com`. Treat it as "this came from that organisation's mail system".
- **SPF is not evaluated.** EDDI never sees the IP address that delivered the mail, so DMARC can only be satisfied by
  aligned DKIM here. A domain that publishes SPF but does not sign with DKIM is never authenticated (most large
  senders sign).
- **Forwarded and mailing-list mail** usually breaks or replaces the original signature: a list that rewrites the
  subject or adds a footer gives `FAIL`, one that re-signs as itself gives `PASS_UNALIGNED`. **ARC is not
  evaluated**, so such mail is not authenticated.
- `temporary=true` means a DNS failure or the lookup budget prevented a check; trying the same message again later
  may authenticate it.

**The Public Suffix List** is `src/main/resources/mail/public_suffix_list.dat`, the Public Suffix List project's file
taken unmodified (MPL-2.0, `licenses/MPL-2.0.txt`). `public_suffix_list.properties` next to it records the upstream
commit (currently `8437e526`, 2026-10-22) and the file's SHA-256; `PublicSuffixListTest` fails if the two disagree or
the file is missing. Refresh both with `python scripts/update-public-suffix-list.py` (latest upstream commit, or one
given as an argument) and review the diff: a newly listed suffix changes which domains align.

**dnsjava and the JVM resolver.** The dnsjava jar registers a `java.net.spi.InetAddressResolverProvider`, which the
JDK would use for *all* host-name resolution — including `SafeSocketConnector`'s and the SSRF checks'. Its provider
returns the JDK's own resolver unless the system property `org.dnsjava.spi.enable=true` is set. Never set it.

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

## Runtime Template Engine

**Applies to:** every Qute template EDDI renders at runtime — system prompts, output texts and quick replies, httpcall URLs/headers/bodies, property instructions, MCP tool arguments, group-discussion prompts and the template preview endpoint.

### Templates are author text; data is never a template

A template string comes from agent configuration. Everything that reaches a template at render time — the user's message, conversation properties, context, API and MCP responses, model output — is **data**: it is substituted into the template and written out literally, whatever braces it contains. EDDI keeps that boundary in three places where it used to be crossed:

- **Property instructions.** A value read through `fromObjectPath` (in `property.json`, and in the `postResponse.propertyInstructions` of httpcalls, LLM and MCP tasks) is stored exactly as found. Only `valueString` and the property `name` are rendered as templates. A configuration that relied on a navigated value being rendered a second time no longer gets that; write the template in `valueString` instead.
- **Output supplied as data.** Output and quick replies sent as `context` on a turn, and those a `postResponse` builds (`outputBuildInstructions`, `qrBuildInstructions` — rendered once, the author's `outputValue` with the response substituted), are stored verbatim (`IData#isVerbatim`, persisted with the step so it survives a HITL resume). The templating task (`eddi://ai.labs.templating`) leaves them alone and marks everything it renders verbatim, so a second templating pass cannot re-evaluate substituted values; output authored in an output configuration is rendered once, as before.
- **Generated agents.** The system prompt a model chooses for a sub-agent (`create_sub_agent`) is stored inside a Qute unparsed block, so the new agent receives exactly the text the model wrote. Parameter names from an OpenAPI specification (MCP API tools, the setup wizard) are reduced to plain identifiers (`[A-Za-z_][A-Za-z0-9_]*`, e.g. `pet-id` → `pet_id`) before they become `{...}` placeholders.

### A restricted engine

Runtime templates are **not** rendered by the Qute engine Quarkus injects — that engine is built for an application's own, build-time-checked templates. `RuntimeTemplateEngineFactory` builds a separate engine from it, through allow-lists:

| Feature | Runtime templates |
|---|---|
| Namespaces | `vault`, `eddivault`, `connection`, `vars`, `caller` (pass-through references), `uuidUtils`, `json`, `encoder` (EDDI extensions), `str`, `time` (Qute helpers). **`config:`, `inject:` and `cdi:` resolve to nothing**, so they cannot read configuration, environment variables or beans |
| Sections | `{#if}`, `{#for}`/`{#each}`, `{#let}`/`{#set}`, `{#with}`, `{#when}`/`{#switch}`. No `{#include}`, `{#insert}`, `{#eval}`, `{#fragment}`, `{#cache}` or user tags; `str:eval` is removed |
| Object access | Maps, lists and EDDI's string methods as before. On other objects only properties are read — record components, public no-argument getters (`getX`/`isX`/`hasX`) and public fields. Methods with arguments and non-getter methods are never invoked, and reflection-sensitive types (`Class`, class loaders, threads, `java.lang.reflect`, …) are never reachable |
| Missing values | Always render as an empty string (never `NOT_FOUND`), independent of `quarkus.qute.property-not-found-strategy` |

### Render limits

| Property | Default | Bounds |
|---|---|---|
| `eddi.templating.max-output-chars` | `2000000` | Characters one render may produce, and the length of any single string an expression evaluates to while rendering |
| `eddi.templating.max-iterations` | `100000` | Loop iterations per render, summed over all (nested) loops; an iterable larger than this is rejected before the loop starts |

A render that exceeds a limit fails like any other malformed template: the output path substitutes an empty string and logs it, and every other caller handles it exactly as it handles a template syntax error. `0` disables a limit — only do that for a deployment whose agent configurations are fully trusted.

`str.replace` and `str.concat` apply the output bound *before* they allocate: the size the call would produce is computed first and refused with the same `TemplateLimitExceededException` a too-long rendered value raises. `str:fmt`/`str:format` and the `String.fmt`/`format` instance forms do the same: an upper bound for the formatted length is computed from the format specifiers (literal text, and per specifier the larger of its width and its content — the argument's length for `%s`, a numeric bound plus the precision for numbers) and refused before `String.format` runs, so `{str:fmt('%2000000000d', 1)}` fails in milliseconds instead of allocating 2 GB. A render that still runs out of memory is caught by `processTemplate` and reported the same way, naming the bound, instead of escaping as an `Error`; so is a `StackOverflowError` from a template that nests thousands of sections.

`quarkus.qute.timeout` (default 10 s) bounds the wall-clock time of one render. EDDI's resolvers render synchronously, so the timeout is enforced from inside the render: every value resolution and every loop iteration checks the render's deadline and fails it once passed, which stops a CPU-bound template that stays under every size and iteration cap.

---

## Parser and Matcher Bounds

Four small parsers and matchers run on text an agent designer or a chatting user controls. Each one has a bound that makes its worst case cheap, and each bound fails closed in a way the caller can see:

| Parser | Reached by | Bound | On exceeding it |
|---|---|---|---|
| Expression parser (`ExpressionProvider`) | Any client — a `context` entry of type `expressions` is parsed by the property-setter and by every `contextmatcher` rule; also dictionaries, `inputmatcher` text | `eddi.expressions.max-length` (32,768 characters) and `eddi.expressions.max-depth` (32 nested parentheses), checked in one linear scan before parsing; the parser itself works on index ranges of the one string | A client-sent context entry: `413` `input_too_large` before the turn starts. Inside a turn: the task fails, counted and audited like any other task failure |
| Dictionary `regEx` (`RegEx`) | Every token of every user input, against every pattern of the deployed dictionaries | 200 ms per match, enforced through a deadline-checking `CharSequence` (`RegexDeadline`); the pattern must also compile when the dictionary is **saved** | A match past its budget is a miss (logged at WARN); an invalid pattern is refused with `400` at save time, naming the syntax error |
| Guardrail `patterns` (`regex-filter`, `prompt-injection`) and the classifier's `labelPattern` | Every user message (input guardrails, including attachment text and the history window) and every answer (output guardrails) of a guarded LLM task | 250 ms per text for all of one guardrail's patterns together (one shared `RegexDeadline`); `labelPattern` 250 ms over the last 4 KB of the classifier's answer; every pattern must compile when the task is **deployed** | *Could not decide* (`pattern_timeout`; the classifier's answer is `classifier_unparseable`): the guardrail's `failMode` applies: a `block` guardrail failing closed refuses the text; failing open, what was already found is kept (a match still blocks, completed redactions still apply) and otherwise the text passes with a warning. An invalid pattern fails the deploy, naming its line |
| Path expressions (`PathNavigator`) | `fromObjectPath` and the other property-instruction paths in `httpcalls`/`mcpcalls`/`property` configs | 64 `+`/`-` operands per expression (the longest-prefix search is quadratic in that count) | The expression resolves to nothing |

A task that nevertheless overflows the stack — an `Error`, which the pipeline's failure handling did not catch before — is now treated as a task failure: counted under `error.type=stack_overflow`, announced as `task_failed`, audited, and rolled back under strict write.

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
- **Side-effecting sources are opt-in:** HTTP-call, MCP and A2A tools are cached only when named in `toolCacheScopes` — a cache hit skips the call, and for a write that means the write never happens. Keys also carry the tool's source and the agent id, so same-named tools of different agents never share an entry
- **Unparseable tokens fail safe:** a `toolCacheScopes` value that does not parse resolves to `user` and is logged at WARN — never to `defaultToolCacheScope`, so a typo in an override that was written to *narrow* one tool cannot promote it onto a `global` partition
- **Behaviour:** A cached result is only ever served back inside its own partition. With the default `user` scope, one authenticated user's tool result is never returned to another. Set a tool to `global` only when its result depends purely on its arguments and never on who is asking — that is an explicit, per-tool opt-in to cross-user reuse
- **Expiry:** Each entry expires on its own per-tool TTL, measured from the write (`weather` 300s, `websearch` 1800s, `news` 600s, `calculator` 7 days, 300s for tools with no table entry — see `GET /llm/tools/cache/ttl/{toolName}`). The TTL is matched against the dispatch name first and the slug second, so `searchNews` gets the `news` entry rather than its tool's `websearch` entry. Size-based eviction (`tool-results` holds 10 000 entries) is the secondary bound. A stale or poisoned result cannot outlive its TTL

### Cost Tracking

- **Configuration:** `enableCostTracking` (default `true`), `toolPricing` (per-call price overrides), `maxBudgetPerConversation` (no default — unlimited), `enforceBudget` (default `true`, deployment fallback `eddi.tools.budget.enforce-by-default`)
- **Scope:** `maxBudgetPerConversation` bounds **tool** cost only. LLM token spend is governed separately and per run by the model cascade's `maxCostPerRun`; the two are not summed
- **Pricing:** default per-call prices are keyed on the built-in slug (`webscraper` $0.002, `websearch` $0.001, `pdfreader` $0.001, `weather` $0.0005; `calculator`/`datetime`/`dataformatter`/`textsummarizer` free). Everything else — http, mcp, a2a, dynamic — is $0.00 until priced via `toolPricing`, which accepts a slug or a dispatch name (dispatch name wins). Operator-supplied prices are clamped at 0.0, so a negative value cannot credit a conversation and make a ceiling unreachable
- **Eviction:** To prevent unbounded memory growth, the tracker caps per-conversation entries at 10 000 and evicts the oldest ~10% when the limit is reached
- **Behaviour:** Enforcement is the **default** (6.6.0, security review finding M8). A configured `maxBudgetPerConversation` is checked *before* each call using `<=` — the call that crosses the ceiling completes and the next one returns `Error: Budget exceeded for conversation <id>` to the LLM. Opting out — `enforceBudget: false` per task, or `eddi.tools.budget.enforce-by-default=false` per deployment — keeps cost tracking and reporting and turns the refusal off; every task whose ceiling is opted out is named once in a WARN rather than lapsing silently, and the MCP `get_agent`/`deploy_agent` tools report the effective default as `toolBudgetEnforcedByDefault`. Until 6.6.0 this was an opt-in whose deployment key was not declared, so every stored ceiling was advisory

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

**HSTS.** Once the public origin is https only, set
`eddi.security.hsts.enabled=true` (`EDDI_SECURITY_HSTS_ENABLED`): every response then
carries `Strict-Transport-Security: max-age=31536000` (`eddi.security.hsts.max-age`), so
a browser that has seen the origin once refuses plain http to it afterwards. It is off by
default because EDDI usually serves plain http behind the proxy; the Helm chart turns it
on when `ingress.tls` is set, and the GCP provisioner's nginx sends it itself. Add
`includeSubDomains`/`preload` at the proxy if the whole domain is https-only.

### Option 2: TLS Directly in Quarkus

```properties
quarkus.http.ssl.certificate.files=/path/to/cert.pem
quarkus.http.ssl.certificate.key-files=/path/to/key.pem
quarkus.http.ssl-port=8443
# Required. Configuring TLS does not switch plaintext off: quarkus.http.insecure-requests
# defaults to `enabled`, so port 7070 keeps serving cleartext alongside 8443.
quarkus.http.insecure-requests=disabled
```

For post-quantum key exchange (`X25519MLKEM768`) and what the JDK supports today, see [Cryptography → TLS key exchange](cryptography.md#tls-key-exchange).

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
| **CodeQL** | SAST | Java source code; the shipped UI sources (`.github/codeql/codeql-ui.yml`) | Blocking (PR) + weekly deep scan | N/A |
| **Trivy** | CVE scanning | Filesystem deps + Docker image | Blocking (CRITICAL/HIGH) | `.trivyignore` |
| **Gitleaks** | Secret scanning | Full git history | Blocking | `.gitleaksignore` |
| **CycloneDX** | SBOM | Maven dependency tree + both UIs’ npm production dependencies | Artifact generation | N/A |
| **Jazzer** | Fuzz testing | PathNavigator, MatchingUtilities | JUnit integration | N/A |

> **DAST is intentionally absent.** A ZAP API scan used to live in `ci.yml` and was removed rather than kept as decorative coverage: it ran *after* the image was pushed, it scanned an instance started with `EDDI_SECURITY_ALLOW_UNAUTHENTICATED` (so the authorization layer under test was switched off), and it ran passive-only with `fail_action: false`. Re-adding it means fixing all three at once — build the image in a job that does not push, stand up Keycloak, drive an authenticated OpenAPI scan against it, and gate the publish job on the result.

### Override Files

For audited false positives, EDDI provides override files at the repository root:

- **`.trivyignore`** — Suppress specific CVEs with mandatory justification comments
- **`.gitleaksignore`** — Suppress specific Gitleaks fingerprints with justification

Both files should be reviewed periodically to ensure suppressions remain valid.

### Installer Downloads

`install.sh` and `install.ps1` fetch the compose files, the Keycloak realm and login
theme, and the monitoring configuration from `raw.githubusercontent.com` at a git ref
(`EDDI_BRANCH`; by default the newest release tag). Each release now publishes a
`SHA256SUMS` asset — the release job in `ci.yml` hashes those files at the tag — and when
the ref is a release tag the installers check every downloaded file against it and refuse
one whose hash differs. A ref without the asset (`main`, a branch, a release published
before it existed) still installs, with a warning that only TLS vouches for the files; an
unlisted file is warned about, not refused. The installers do not verify themselves —
fetch `install.sh` from a release tag rather than `main` for a pinned install. The CLI's
`eddi update` re-downloads compose files without this check. The GCP provisioner
(`gcp/provision-vm.sh`) now defaults `--eddi-branch` to `--eddi-version`'s tag or the
newest stable release instead of `main`, falling back to `main` with a warning when the
lookup fails.

The checksum list is signed too. A `SHA256SUMS` on its own proves only that the files
match whatever list the release carries; whoever could replace one release asset could
replace both. The release job therefore signs it with cosign **keyless**, the same way the
Docker image is signed: GitHub Actions' OIDC token gets a short-lived Fulcio certificate
naming the workflow that ran, and the signature is logged in the public Rekor transparency
log. The Sigstore bundle is attached as `SHA256SUMS.sigstore.json`, after the job has
verified it against the identity below. When `cosign` is on `PATH`, both installers verify
the list before they trust any hash in it and refuse to install from a list that does not
verify. The certificate must name `https://github.com/labsai/EDDI/.github/workflows/ci.yml`
at `refs/tags/<the release tag>`, issued by `https://token.actions.githubusercontent.com`.
Without `cosign` they print one warning line and go on with the unsigned list. A release
before 6.6.0, the first one signed, has no bundle and is used unsigned, with a warning. For
6.6.0 and later (pre-releases included) a `SHA256SUMS` that cannot be fetched always stops the
install, and so does a missing bundle **when `cosign` is installed** — a missing signature on a
signed release is indistinguishable from a removed one. Without `cosign` the bundle is not
fetched at all, whatever the version. To
check a release by hand:

```bash
cosign verify-blob --bundle SHA256SUMS.sigstore.json \
  --certificate-identity https://github.com/labsai/EDDI/.github/workflows/ci.yml@refs/tags/<tag> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS
```

### Fuzz Testing

Security-critical input parsers are tested with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) coverage-guided fuzzing:

- **`PathNavigator`** — Safe path navigation (replaced OGNL). Fuzz targets: `getValue`, `setValue`, arithmetic paths
- **`MatchingUtilities`** — Condition evaluation for DynamicValueMatcher

In CI, fuzz tests run as standard JUnit regression tests. For deep coverage-guided fuzzing locally:

```bash
./mvnw test -Dtest=PathNavigatorFuzzTest \
  -Djazzer.instrument=ai.labs.eddi.utils.PathNavigator
```

### Secrets in the Container Environment

The image's start script (`run-java.sh`) logs the complete Java command line at startup, built from
`JAVA_OPTS` and `JAVA_OPTS_APPEND`. It masks only keys whose name contains `password`, and no
setting disables the line. Never put a credential in a `-D...` system property there. In particular,
`-Dmongodb.connectionString=...` writes the database user and password into the container log, and
from there into any log aggregator.

Provide the connection string through the `MONGODB_CONNECTIONSTRING` environment variable, injected
from a secret store (ECS `secrets`, a Kubernetes `Secret`, Docker secrets). Environment variables
are not printed by the script. See [Kubernetes](kubernetes.md) for the secret-based layout and
[Upgrading from 5.x](upgrading-from-5x.md#keep-the-connection-string-out-of-the-java-command-line).

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
- [Cryptography](cryptography.md) — Algorithm inventory and post-quantum readiness
