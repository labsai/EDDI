# Implementation Plan — SaaS Connectors & Outbound Integration Hardening

**Status:** Planning
**Created:** 2026-08-11
**Scope:** Backend (EDDI repo), plus EDDI-Manager surfaces in Phases 0.2 and 5
**Verified:** 2026-08-11 against `main` @ `00420daa5` (post-#668) — §2 claims spot-checked against source
(auth defaults, `DisabledAuthController`, both credential-in-query endpoints and
the `apiAuth` echo, verbatim tool-result append, single `governDescription` call
site, per-request `customHeaders` supplier, `SecretScrubber` whole-string and
array-element holes, `LogCaptureFilter` pass-through, empty resolver maps in
`McpToolsProvider`/`A2AToolsProvider`, transport sets, zero OAuth machinery)

> **Read §3 before §5.** This plan is greenfield — no existing planning doc covers
> outbound OAuth, a per-user credential store, a connection resource type, or MCP
> stdio — but it lands in a repo with strong existing opinions about outbound HTTP
> (`SafeHttpClient`), credential storage (`SecretsVault` + deploy-time grant
> enforcement) and new auth surfaces (path-scoped policy + fail-closed startup
> guard, never a change to the global `authorization.enabled` default). §3 lists
> those constraints with citations; several of them invalidate the obvious design.

> **Why hardening and connectors are one plan, not two.** Phases 0 and 1 are not
> adjacent nice-to-haves: they are preconditions. Connectors multiply the blast
> radius of three defects that already exist (unauthenticated `/mcp`, unsanitized
> tool results, credentials in query strings). Storing per-user Google refresh
> tokens behind an admin API that ships unauthenticated is the bad outcome this
> sequencing exists to prevent. Phases 0-1 are independently valuable and can ship
> alone; Phases 2+ must not ship without them.

---

## Table of Contents

1. [Goal](#1-goal)
2. [Current state — honest inventory](#2-current-state--honest-inventory)
3. [Constraints inherited from existing decisions](#3-constraints-inherited-from-existing-decisions)
4. [Design options for the connection model](#4-design-options-for-the-connection-model)
5. [The `ConnectionConfiguration` model](#5-the-connectionconfiguration-model)
6. [OAuth design](#6-oauth-design)
7. [MCP transport work](#7-mcp-transport-work)
8. [Phases](#8-phases)
9. [Files that would change](#9-files-that-would-change)
10. [Metrics](#10-metrics)
11. [Tests and verification](#11-tests-and-verification)
12. [What I would NOT do](#12-what-i-would-not-do)
13. [Open questions](#13-open-questions)

---

## 1. Goal

Let an agent designer connect an EDDI agent to a third-party SaaS platform —
Jira, Amplitude, Google Drive, Notion, Linear — over REST **or** MCP **or** A2A,
with the platform's real auth model, including per-end-user OAuth where the
platform requires it, without hardcoding any provider in Java.

Three concrete acceptance scenarios, in ascending difficulty:

| Scenario | Auth shape | Today |
|---|---|---|
| **Amplitude analytics** — one org-wide API key + secret, shared by all users of the agent | Static, Basic | ⚠️ Works mechanically; unsafe defaults around it |
| **Jira via Atlassian's hosted MCP server** | OAuth 2.1, service account or per-user | ❌ Impossible — static Bearer only |
| **Google Drive, each end user seeing only their own files** | OAuth 2.0 authorization code + refresh, per end user | ❌ Impossible — no per-user credential dimension exists |

The unifying requirement is **one credential model across all outbound paths**,
because there are currently five independent ones (§2.2) and adding OAuth to each
separately is a five-times problem with five sets of bugs.

### Non-goals

Stated here so the phase list can be read without ambiguity; expanded in §12.

- Not a multi-tenancy implementation. This plan consumes
  [`multi-tenancy-plan.md`](multi-tenancy-plan.md) Phase 1 when it lands and
  defaults to `"default"` until then.
- Not a guardrails implementation. Phase 1 adds the **tool-result** hook that
  [`langchain4j-recommendations.md:250`](langchain4j-recommendations.md#L250)
  identifies as the acknowledged gap in R0, and stops there.
- Not 250 provider integrations. §4 Option D keeps the long tail delegable.

---

## 2. Current state — honest inventory

### 2.1 What already works

- **`SecretsVault`** — AES-256-GCM envelope encryption, per-tenant DEK wrapped by
  a PBKDF2-derived KEK (`crypto/EnvelopeCrypto.java`, `crypto/VaultSaltManager.java`,
  `impl/VaultSecretProvider.java:137`, `:178`), Mongo and Postgres persistence keyed
  `(tenantId, keyName)`, write-only REST under `@RolesAllowed("eddi-admin")`
  (`rest/IRestSecretStore.java:27-29`). `${vault:key}` / `${vault:tenant/key}`
  parsing in `model/SecretReference.java:42`, `:54`; Caffeine-cached resolution in
  `SecretResolver.java:145-207`. **This is the right foundation and this plan builds on
  it rather than beside it.**
- **`VaultGrantChecker`** — `allowedAgents` enforced at deployment by serializing
  the agent + workflow-extension configs and scanning for `${vault:...}`
  (`:122-155`, `:166-196`, mcpcalls at `:222-224`), defaulting to `enforce` as of
  2026-08-11.
- **`${caller:token}`** — `engine/security/CallerIdentityResolver.java`, headers only,
  same-origin only (`:263-279`), fails closed on unauthenticated turns.
- **`SafeHttpClient`** — `Redirect.NEVER` (`:65`), per-hop `validateUrl` (`:237`),
  `Authorization`/`Cookie` stripped on cross-origin redirect (`:55`, `:197`).
- **HITL tool approvals** — gate covers all seven tool sources including `mcp` and
  `a2a`; fail-closed refusal for rule-triggered MCP calls (`McpCallsTask.java:237-246`,
  `:399-409`).
- **OpenAPI → tool generation** — `engine/mcp/McpApiToolBuilder.java` turns a spec
  URL into httpcall configs. Under-advertised; it is the shortest path to a working
  Jira or Amplitude integration today.

### 2.2 The eleven outbound paths

Every way EDDI currently reaches something external, with its auth model. The
column that matters is **Vault** and **Per-user** — the fragmentation this plan exists
to collapse.

| # | Path | Primary class | Auth | Vault | Per-user | `SafeHttpClient` |
|---|---|---|---|---|---|---|
| 1 | httpcalls (REST) | `modules/apicalls/impl/ApiCallExecutor.java:603-682` | any header / param | ✅ | ✅ `${caller:}` same-origin | n/a (Vert.x) |
| 2 | OpenAPI auto-discovery | `engine/mcp/McpApiToolBuilder.java:250-251` | `apiAuth` header string | ✅ | ❌ | ❌ |
| 3 | MCP client — tools, plus **resources** via `exposeResources` (#668) | `modules/llm/impl/McpToolProviderManager.java:617-653`, `:475` | single `apiKey` → Bearer | ✅ | ✅ same-origin | ❌ (lc4j transport) |
| 4 | A2A client | `modules/llm/impl/A2AToolProviderManager.java:245-331` | static Bearer | ✅ | ❌ | ❌ |
| 5 | Vector stores (5 types) | `modules/llm/impl/EmbeddingStoreFactory.java:181` | per-store | ✅ | ❌ | n/a |
| 6 | Embedding models (8) | `modules/llm/impl/EmbeddingModelFactory.java:97` | provider key | ✅ | ❌ | n/a |
| 7 | LLM providers (11) | `modules/llm/impl/ChatModelRegistry.java:217-218` | provider key | ✅ | ❌ | n/a |
| 8 | Built-in tools (4 networked) | `modules/llm/tools/impl/` | MicroProfile config | ❌ | ❌ | ✅ (2 of 4 also `validateUrl`) |
| 9 | Slack outbound | `integrations/slack/SlackWebApiClient.java:172` | workspace bot token | ✅ | ❌ | ❌ |
| 10 | Agent Sync (EDDI↔EDDI) | `backup/impl/RemoteApiResourceSource.java:415-417` | `X-Source-Authorization` relay | n/a | relay only | ❌ |
| 11 | NATS event bus | `engine/runtime/internal/NatsConversationCoordinator.java:165-183` | **none** | ❌ | ❌ | n/a |

**Five distinct credential-resolution implementations** (rows 1, 3, 4, 5-7, 9),
each with its own `apiKey`-shaped field and its own
`globalVariableResolver → secretResolver` snippet. Row 8 is the only outbound
credential class in EDDI that never touches the vault.

> [!NOTE]
> Row 3 gained a second *content* surface in #668: `exposeResources` synthesizes
> `<server>_list_resources` / `<server>_read_resource` tools, so an MCP server's
> **resources** — not just its tools — reach the agent. Same connection, same
> credential, so it is not a twelfth path and needs no separate connector work.
> It does widen G5: resource text is returned **verbatim** (capped at
> `RESOURCE_CONTENT_MAX_CHARS`), and the server-authored resource *names and
> descriptions* rendered by `renderResourceList` are ungoverned third-party text
> on the same channel. Phase 1.1's hook covers both automatically (§8.1).

Inbound, for completeness: EDDI's own MCP server (33 tools), the OpenAI-compatible
`/v1` adapter, and the A2A server endpoint. For some platforms the cheaper
integration is inverting the direction — letting the vendor's agent call EDDI —
and that needs no work here beyond Phase 0.

### 2.3 What does NOT exist

| Gap | Detail |
|---|---|
| **G1 — No OAuth client, anywhere** | Repo-wide search for `authorization_code`, `grant_type`, `refresh_token`, `redirect_uri`, `token_endpoint` across `.java`/`.md`/`.properties` returns three hits: two `grant_type=password` calls against Keycloak in `install.sh:847` / `gcp/provision-vm.sh:562`, and a sample URL in `keycloak-eddi-theme.md:414`. In `src/main/java` the only hit is the redaction denylist `SecretScrubber.java:66`. No callback resource, no PKCE, no token exchange, no refresh storage, no `state` store. |
| **G2 — No per-user credential dimension** | `SecretReference` is `(tenantId, keyName)`. No `userId` in `SecretMetadata` or either persistence impl. The closest thing, `PropertySetterTask.autoVaultSecret()` (`:446-449`), keys on `agentId + "." + keyName` — two end users of the same agent **overwrite each other**. |
| **G3 — MCP 401 is not an auth challenge** | `McpToolProviderManager.java:362-367` treats it as a discovery failure and trips the circuit breaker (3 failures / 60s, `:112-114`). No `WWW-Authenticate` parsing, no `/.well-known/oauth-protected-resource` discovery, no dynamic client registration. This is the exact hook OAuth needs. |
| **G4 — MCP transport is HTTP-only** | `stdio` hard-rejected at `:555-565`; `sse` survives as a deprecated alias (`:487`, `:576-582`) but is served over StreamableHTTP anyway. `McpCallsConfiguration.SUPPORTED_TRANSPORTS` (`:80`) omits `sse`, so the REST write path 400s a config the runtime would accept — a live inconsistency. Most vendor-shipped MCP servers are stdio binaries. |
| **G5 — Tool results enter the LLM verbatim** | `ToolLoopRunner.java:460-465`, comment: *"append the raw result verbatim"*. `governDescription` / `DIRECTIVE_PATTERN` (`McpToolProviderManager.java:590-612`, `:101-108`) is applied at exactly one call site (`:331`) and only to MCP tool **descriptions**. A2A descriptions, which come from a remote agent card, are ungoverned (`A2AToolProviderManager.java:152-175`), as is MCP **resource** content and its listing metadata (`:475+`, #668). |
| **G6 — Credentials in query strings** | Two endpoints: `GET /mcpcallsstore/mcpcalls/discover-tools?apiKey=` (`IRestMcpCallsStore.java:91-94`) and `GET /apicallsstore/apicalls/discover-endpoints?apiAuth=` (`IRestApiCallsStore.java:91-94`). The second takes a full `Authorization` header value **and echoes it back** in every generated ApiCall's headers (`RestApiCallsStore.java:97`). `IRestImportService.java:65` already does this correctly with `@HeaderParam` — the in-repo pattern to copy. |
| **G7 — Authorization is off by default** | `engine/security/DisabledAuthController.java:17-24` is an `@Alternative` `AuthorizationController` returning `authorization.enabled`, which defaults to `${quarkus.oidc.tenant-enabled}` = `false` (`application.properties:318`, `:335`). When false, Quarkus disables path policies **and** `@RolesAllowed`. `/mcp` has no permit entry, so it falls to the catch-all and is wide open — stated outright in the banner at `application.properties:429-446`. `AuthStartupGuard` exempts dev/test (`:54`) and every shipped `docker-compose*.yml` / k8s manifest sets `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`, so **the guard effectively never fires**. |
| **G8 — SSRF protection defaults off** | `application.properties:344` and `defaultValue="false"` at `ApiCallExecutor.java:96`, `A2AToolProviderManager.java:70`, `McpToolProviderManager.java:143`. With it off, MCP URL validation degrades to a scheme check (`:533-547`), and `discover-tools` echoes the response — a read primitive against `169.254.169.254`, described in that class's own Javadoc at `:496-507`. |
| **G9 — Console logs unredacted** | `LogCaptureFilter.java:65-76` always returns `true`; redaction happens on a **copy** inside `BoundedLogStore.capture()` (`:149`). Ring buffer, DB and SSE are clean; container stdout is not. Unredacted throwables at `McpToolProviderManager.java:363`, `HttpClientWrapper.java:303`, `:316`; raw exception text returned to the HTTP caller at `RestMcpCallsStore.java:161`. |
| **G10 — MCP/A2A approvals cannot be pinned** | `ToolApprovalGateSupport.pinResolvedRequest` (`:284`, `:315`) fires only for tools with a `ToolRequestResolver`. `HttpCallToolsProvider.java:205` supplies resolvers; `McpToolsProvider.java:80` passes `Map.of()` and `A2AToolsProvider.java:51` passes none. A gated MCP call shows the approver a tool name and `argumentsRedacted` — no URL, no request fingerprint. |
| **G11 — Export scrubbing has holes** | `SecretScrubber` catches `apikey` / `authorization` by field name, so mcpcalls and httpcalls Authorization headers are scrubbed. Not caught: a header named `X-Api-Token` (normalizes to `xapitoken`, falls to the entropy heuristic, and `KEY_LIKE_PATTERN` at `:53` requires a whole-string match so `Bearer abc…` with its space never matches); URL-embedded credentials (`?api_key=`, `https://user:pass@host` — the `?`/`=`/`:` defeat the same pattern); and **any string inside an array** (`scrubNode:134` recurses with the parent field name into a node that is neither object nor array, and does nothing). Separately, `ChannelIntegrationConfiguration` is **not exported at all**, so `botToken` never reaches a ZIP — but channel integrations are also silently missing from backups. |
| **G12 — Stale credential caches** | `ChatModelRegistry` (`:152-167`), `EmbeddingModelFactory` (`:74-77`) and `EmbeddingStoreFactory` (`:146-147`) register `SecretResolver` invalidation listeners. `McpToolProviderManager` does not — its apiKey is resolved once per cached client (`:626-628`), so a rotated vault secret is not picked up until eviction. `ChannelTargetRouter` polls at 60s (`:55`). `A2AToolProviderManager` re-resolves per call but caches agent cards by URL for 5 min. |

---

## 3. Constraints inherited from existing decisions

Load-bearing. Several invalidate the obvious design; each is cited.

**C1 — Never change the global `authorization.enabled` default.** The established
pattern for a new or under-protected surface is a path-scoped
`quarkus.http.auth.permission.*` policy plus a startup guard that fails closed,
per [`openai-api-adapter-plan.md:465-471`](openai-api-adapter-plan.md#L465) and
`:592-595` (*"`enabled=false` by default, and startup fails on the
unauthenticated-and-keyless combination"*). `mcp-hitl-surface-plan.md:15` sets the
matching expectation for MCP: *"Mirror REST auth exactly… When
`authorization.enabled=false` (dev default), `OwnershipValidator` no-ops —
identical to REST today."* Flipping the global would break every shipped compose
file and k8s manifest at once.

**C2 — All outbound HTTP goes through `SafeHttpClient`.**
[`security-hardening-remaining.md:141`](security-hardening-remaining.md#L141):
*"ALL outbound HTTP from LLM tools. Never create `HttpClient.newBuilder()` in tool
code."* `:104-107` already lists four services that bypass it
(`A2AToolProviderManager`, `SlackWebApiClient`, `RemoteApiResourceSource`,
`HttpCallsTask`). A connector adding a fifth is a regression, not neutral. DNS
rebinding remains an accepted documented risk (`:94`).

**C3 — Connection resolution must not live inside `SecretResolver`.** The 2026-08-10
changelog decision placed `allowedAgents` enforcement at deployment *specifically
because* `SecretResolver` sees only a string with no agent identity, and
`ChatModelRegistry` caches built models keyed on **unresolved** parameters. A
per-user, per-request, network-calling resolver inside that class would break both
properties.

**C4 — Only a vault *reference* is ever inherited, never a plaintext key**
(changelog, `fix/sub-agent-setup-hardening`). Applies verbatim to connections:
configs carry `${connection:name}`, never a token.

**C5 — Approve the fingerprint, not the tool name.**
[`operator-write-scope-plan.md:120`](operator-write-scope-plan.md#L120):
*"Closing that properly needs a server-side redacted preview plus an immutable
fingerprint of the resolved request, re-checked immediately before execution."*
This is why G10 is a Phase 1 item and not a nicety.

**C6 — `/secretstore` writes are excluded from operator write scope**
([`operator-write-scope-plan.md:190`](operator-write-scope-plan.md#L190)).
Connection writes are the same class of capability — an egress channel plus a
credential — and must be excluded identically. Compare `:97`, which excludes
`PUT /llmstore/llms` because *"an LLM config carries a provider `baseUrl`… an
update is an egress channel plus a prompt rewrite."*

**C7 — Guardrails: never throw out of `GuardrailService`**
([`langchain4j-recommendations.md:2417`](langchain4j-recommendations.md#L2417)); a
terminal `BLOCK` is a returned value, because a thrown message quoting user text
collides with `AgentExecutionHelper.isRetryableError`. And the fix location for
`tool_call`/`tool_result` redaction is **the producer** — the `AgentOrchestrator`
maps — per `:1082`.

**C8 — Guardrail design ownership.** `langchain4j-recommendations.md:2290`
supersedes parts of `guardrails-architecture.md`: adopt lc4j core types, do **not**
create `IGuardrail` or a `Verdict` enum with a `PASS` member; persisted actions stay
EDDI-owned and neutral (`block | redact | reprompt | warn`, `:261`).

**C9 — Tenant scoping follows the existing plan, not a bespoke one.**
[`multi-tenancy-plan.md`](multi-tenancy-plan.md) is Planning status with only
Phase 5 landed. Its Phase 1 design is a `@RequestScoped TenantContext` +
`TenantResolverFilter` that **fails closed with 403** on a missing claim (`:213`),
consumed by `@ApplicationScoped` stores via `Provider<TenantContext>` (`:306-311`),
and inside the pipeline read from a `ConversationProperty` rather than the request
scope (`:381`). New stores add a `tenantId` field defaulting to `"default"` and a
compound `(tenantId, _id)` index (`:257`).

**C10 — Golden Rule 1.** Provider behavior is JSON configuration; Java is the
engine (`AGENTS.md:181`). No provider may be hardcoded. Conversely,
`mcp-hitl-surface-plan.md:40` establishes that a full store/descriptor/REST stack
needs explicit justification — §4 provides it.

**C11 — The gate fails open on naming.** `ToolApprovalGate.classify` returns the
whole batch as allowed when `requireApproval` is null or empty
(`ToolApprovalGate.java:57-59`), and unmatched names fall through
(`operator-write-scope-plan.md:23`). Any connector-related approval default
must be expressed as a wildcard plus an explicit exempt list, never an enumerated
write list. Still true after #668 — that change closed the *adjacent* bypass where
a task-level `toolApprovals` replaced the agent gate wholesale, and its merge
semantics reinforce this rule: `requireApproval` unions (so `[]` can no longer
weaken anything) while a task's `exempt` additions are ignored outright
(`TaskToolApprovalsResolver`, `eddi.hitl.tool.task-approvals.mode=strict`).

---

## 4. Design options for the connection model

### Option A — Add OAuth fields to each existing config type

`McpCallsConfiguration`, `Request` headers, `A2AAgentConfig`, the embedding/vector
param maps, and `ChannelIntegrationConfiguration.platformConfig` each grow
`oauth` blocks and each resolution site grows a token-refresh call.

> **Not recommended.** Five implementations, five caches, five refresh
> concurrency bugs. Directly contradicts `AGENTS.md §4.7` ("unification over
> duplication… prefer unified systems with legacy compat methods over dual
> storage"). It also puts an HTTP-calling refresh path inside
> `ChatModelRegistry`'s build-time resolution, which violates C3's caching
> assumption.

### Option B — A `ConnectionConfiguration` resource plus a `${connection:}` reference ✅ RECOMMENDED

One new resource type describing *how to authenticate to a system*. Every existing
config references it by name; a new `ConnectionResolver` sits alongside
`SecretResolver` in each of the five resolution chains and returns a **per-request**
credential.

> **Recommended.** It is one implementation of OAuth, one grant store, one refresh
> path, one audit surface. It preserves Golden Rule 1 (the connection is JSON
> config, the engine is generic). It reuses `EnvelopeCrypto` and the existing
> tenant DEK rather than inventing storage. The cost is a full store/REST/descriptor
> stack — justified by C10 because a connection is genuinely a new first-class
> config document with its own lifecycle, not a field on an existing one.

### Option C — Extend `SecretsVault` with a self-refreshing "dynamic secret" type

`${vault:google-drive}` transparently returns a fresh access token.

> **Not recommended, and blocked by C3.** `SecretResolver` deliberately has no
> agent or user identity, and `ChatModelRegistry` caches on unresolved parameters —
> both are load-bearing properties of the 2026-08-10 grant-enforcement design.
> Smuggling an HTTP client and a per-user dimension into the vault would break the
> reasoning that made deploy-time enforcement sound. The vault stays a static
> secret store; connections are a layer above it that *uses* it for client secrets.

### Option D — Delegate to an external managed-auth layer

Point EDDI's existing MCP client at a managed integration platform (Nango,
Composio, Zapier MCP) that holds per-end-user tokens for a few hundred providers.

> **Not rejected — complementary, and the escape hatch for the long tail.** It
> requires zero EDDI code beyond Phase 0-1 and Phase 4 (§7), and delivers per-user
> Google Drive sooner than Phase 5 will. It costs a dependency and a third party in
> the credential path, which is disqualifying for some deployments and fine for
> others. **Decision: build Option B for first-party providers and EDDI's own
> control, and document Option D as the supported path for everything else.** This
> is an open question only in how many providers Phase 5 ships natively (§13.2).

---

## 5. The `ConnectionConfiguration` model

### 5.1 Resource

New store `connectionstore/connections`, following the standard stack
(`AGENTS.md §4.3`): configuration record, `IResourceStore`, Mongo + Postgres
stores, JAX-RS interface + impl, `ExtensionDescriptor`, unit tests. Versioned like
every other resource.

```java
public record ConnectionConfiguration(
        String name,                       // referenced as ${connection:name}
        String tenantId,                   // defaults "default" until multi-tenancy Phase 1 (C9)
        String description,
        AuthType authType,                 // STATIC | BASIC | OAUTH2_CLIENT_CREDENTIALS | OAUTH2_AUTHORIZATION_CODE
        Binding binding,                   // SERVICE | PER_USER
        StaticAuth staticAuth,             // authType STATIC/BASIC
        OAuthConfig oauth,                 // authType OAUTH2_*
        List<String> baseUrlAllowlist,     // origins this connection's credential may be sent to
        Integer timeoutMs) { }
```

`binding` is the field that makes Amplitude and Google Drive the same system:
`SERVICE` resolves one grant shared by every user of the agent; `PER_USER`
resolves the calling user's grant and fails closed if absent. It is a config
choice, never a global mode.

```java
public record OAuthConfig(
        String authorizationUrl,           // null for CLIENT_CREDENTIALS
        String tokenUrl,
        String clientId,
        String clientSecret,               // MUST be a ${vault:...} reference (C4)
        List<String> scopes,
        Map<String, String> extraAuthParams,
        boolean usePkce,                   // forced true for AUTHORIZATION_CODE
        String discoveryUrl) { }           // optional RFC 8414 / 9728 metadata URL
```

`validate()` rejects: blank `name`; a `clientSecret` that is not a `${vault:`
reference; `AUTHORIZATION_CODE` without `authorizationUrl`; `PER_USER` binding on
any `authType` other than `AUTHORIZATION_CODE`; an allowlist entry that is not a
bare origin. The allowlist is a **list** because one provider's credential
legitimately spans hosts (Google: `www.googleapis.com`, `drive.googleapis.com`, …)
and the token endpoint's origin routinely differs from the API's (Atlassian:
`auth.atlassian.com` vs `api.atlassian.com` — the token URL does not need to be in
the allowlist at all; only API targets do).

### 5.2 The grant store

Separate collection/table from the config, because grants are runtime state with a
different lifecycle, different access control, and must never appear in an export.

```
connection_grants
  tenantId          string   ─┐
  connectionId      string    ├─ unique compound index
  principal         string   ─┘   userId, or the sentinel "__service__"
  accessToken       bytes        EnvelopeCrypto, tenant DEK
  refreshToken      bytes        EnvelopeCrypto, tenant DEK
  expiresAt         instant
  scopes            list<string>
  status            enum         ACTIVE | EXPIRED | REVOKED | REFRESH_FAILED
  createdAt / updatedAt / lastRefreshAt
  version           long         optimistic lock — see 5.4
```

Reuses `EnvelopeCrypto` and the per-tenant DEK; no new crypto. Never exported
(§9), never returned in plaintext by any REST endpoint, revocable by deleting the
row.

### 5.3 The `${connection:name}` reference

Parsed by a new `ConnectionReference` mirroring `SecretReference`'s shape.
**Critically, it is not a string substitution like `${vault:}`** — it resolves to a
credential bound to the *current caller*, per request.

That distinction drives one integration decision per path:

| Path | How it resolves per request | Work |
|---|---|---|
| httpcalls | `ApiCallExecutor.buildRequest` already resolves per request (`:603-682`); add a fourth resolution step after `secretResolver`, headers-only, same restriction set as `${caller:}` | Small |
| MCP client | The client is cached, but `customHeaders` is already a **per-request supplier lambda** (`McpToolProviderManager.java:649`) — the exact mechanism `${caller:token}` uses. Reuse it verbatim. | Small |
| A2A client | Re-resolves per call at `:203` / `:272` | Small |
| Vector / embedding / LLM | Cached on unresolved params by design (C3). **`SERVICE` binding only** — reject `PER_USER` at validate time. Register a `SecretResolver`-style invalidation listener. | Small |
| Slack | 60s poll refresh; `SERVICE` only | Small |

`ConnectionResolver` is `@ApplicationScoped`, injects `Provider<TenantContext>`
(C9) and reads the principal from `CallerIdentityContext` where available. In the
pipeline, where request scope may be absent, the principal comes from conversation
memory — the same rule `multi-tenancy-plan.md:381` sets for `tenantId`.

**Fail-closed rules**, mirroring `CallerIdentityResolver`:

- On the HTTP-templating paths (httpcalls, MCP, A2A), `${connection:}` may appear
  **only in headers** — in a URL, body, or query param it is rejected at validate
  time, same restriction set as `${caller:}`. On the non-templating paths
  (LLM / embedding / vector-store parameter maps) there are no headers; there the
  reference may appear only in the field the builder designates as the credential,
  and only with `SERVICE` binding.
- **`PER_USER` requires a *verified* principal, not merely a present one.** The
  principal must come from an authenticated OIDC identity
  (`CallerIdentityContext` backed by a validated token) — never from a
  client-supplied header. This is not hypothetical: `OpenAiAuthFilter.java:35-39`
  documents that `X-OpenWebUI-User-Id` is believed verbatim, and with
  `authorization.enabled=false` (the shipped default) there is no verified
  identity anywhere in the system. Without this rule, anyone claiming
  `userId=alice` resolves Alice's Google token. Enforced twice: at grant-store
  read time in `ConnectionResolver`, and at startup by the guard (§6.2
  Decision 4).
- `PER_USER` with no resolvable principal (scheduled turn, trigger, retry on a
  callback thread) → `ConnectionException`, never a silent fallback to the service
  grant. This matches `CallerIdentityResolver.java:713-721`'s existing choice to
  send nothing rather than the wrong authority.
- Target origin not in `baseUrlAllowlist` → rejected. This is the analogue of
  the same-origin rule that makes `${caller:token}` safe, generalised: a connection
  names the origins its credential may reach, so a config edit cannot redirect a
  Google token to an attacker's host.

### 5.4 Refresh concurrency

The production-scale concern `AGENTS.md §4.7` demands be answered up front. Two
conversations hitting an expired grant simultaneously both call the token endpoint;
with rotating refresh tokens (Google, Atlassian) the second call invalidates the
first, and the user is silently logged out.

**Design: single-flight per grant, with optimistic locking.** A refresh acquires
the grant row by `version`; the losing writer re-reads and uses the winner's token
rather than refreshing again. In-process, a `ConcurrentHashMap<GrantKey,
CompletableFuture<Token>>` collapses concurrent refreshes; across the cluster the
`version` CAS is the backstop. Failure semantics distinguish the OAuth error from
the transport error: `invalid_grant` (revoked/expired refresh token) is terminal →
`REFRESH_FAILED`, surfaced as "reconnect required"; a network timeout or 5xx from
the token endpoint is **not** terminal — the grant stays `ACTIVE`/`EXPIRED` and the
next request retries. Conflating the two logs users out on every provider blip.

A proactive sweeper is explicitly **not** in Phase 5 — lazy refresh with
single-flight is correct and simpler, and `ScheduleFireExecutor` is available later
if telemetry shows cold-start latency matters.

---

## 6. OAuth design

### 6.1 Service account — `client_credentials` (Phase 4)

Straight-line: POST `tokenUrl` with `client_id` + `client_secret` (vault-resolved)
+ `scope`, cache the access token against `expiresAt` in the grant store under
principal `__service__`, refresh lazily. No callback, no browser, no user
interaction. This is what Atlassian and Amplitude service integrations need, and it
is the phase that proves the whole `ConnectionConfiguration` machinery end-to-end
with a fraction of Phase 5's surface.

### 6.2 Per-user — authorization code + PKCE (Phase 5)

The flow, with the three decisions that matter:

```
1. User clicks "Connect" in Manager
   → POST /connectionstore/connections/{id}/authorize     (authenticated, user's own token)
   → server generates code_verifier + state, persists {tenantId, connectionId,
     principal, nonce, codeVerifier, expiresAt} single-use, TTL 10 min —
     persisted in the DB, NOT in-memory: behind a load balancer the callback
     routinely lands on a different replica than the one that issued the state
   → returns the provider authorization URL

2. Browser → provider → user consents → provider redirects to
   GET /connections/callback?code=…&state=…                (PERMIT path — see below)

3. Server validates state (exists, unexpired, unconsumed), exchanges code +
   code_verifier at tokenUrl, encrypts and stores the grant under `principal`,
   consumes the state, redirects to a Manager success page

4. Agent turn → ConnectionResolver → grant for (tenant, connection, callerUserId)
   → access token, refreshed if expired
```

**Decision 1 — the callback must be a `permit` path, and `state` is its only
guard.** The redirect arrives from the provider through the user's browser as a
top-level GET carrying no bearer token (`quarkus.oidc.application-type=service`,
`application.properties:314-326` — the comment at `:328` states the same
constraint for Keycloak: *"service mode returns 401 (no redirect)"*). So the
callback cannot be `@Authenticated`. It is secured by a single-use, server-stored,
HMAC-signed, short-TTL `state` that binds the tenant, connection and principal —
the callback never trusts a request parameter for identity. New entry in
`application.properties`:

```properties
quarkus.http.auth.permission.oauth-callback.paths=/connections/callback
quarkus.http.auth.permission.oauth-callback.policy=permit
quarkus.http.auth.permission.oauth-callback.methods=GET
```

**Decision 2 — PKCE is mandatory, not configurable.** `usePkce` is forced true for
`AUTHORIZATION_CODE` at validate time. A public redirect endpoint without PKCE is
an authorization-code interception vector, and there is no legacy provider in scope
that lacks S256 support.

**Decision 3 — a fail-closed startup guard, per C1.** `redirect_uri` must be an
exact match at the provider, so EDDI must know its own public base URL. Following
the `OpenAiStartupGuard` precedent:

```java
// ConnectionStartupGuard
if (connectionsEnabled && publicBaseUrl.isBlank()) {
    throw new IllegalStateException(
        "eddi.connections.enabled=true requires eddi.connections.public-base-url");
}
if (connectionsEnabled && !publicBaseUrl.startsWith("https://") && !launchMode.isDevOrTest()) {
    throw new IllegalStateException("OAuth redirect URI must be HTTPS outside dev");
}
```

`eddi.connections.enabled` defaults to **false**, matching the
`openai-compat` precedent (`openai-api-adapter-plan.md:592-595`).

**Decision 4 — the guard also refuses the two configurations that would make
per-user grants meaningless:**

```java
if (perUserConnectionsConfigured && !authorizationEnabled) {
    throw new IllegalStateException(
        "PER_USER connections require authorization.enabled=true — without a "
      + "verified identity, any caller can claim any userId and resolve that "
      + "user's tokens (see OpenAiAuthFilter's trust-user-headers caveat)");
}
if (oauthConnectionsConfigured && !vaultActive) {
    throw new IllegalStateException(
        "OAuth connections require an active SecretsVault (EDDI_VAULT_MASTER_KEY) "
      + "— grants are envelope-encrypted with the tenant DEK and there is no "
      + "plaintext fallback");
}
```

The first closes the identity hole described in §5.3: with
`authorization.enabled=false`, `@Authenticated` on the authorize endpoint is a
no-op via `DisabledAuthController`, so the flow would happily mint grants for
self-asserted principals. The second refuses the `autoVaultSecret` antipattern —
that path falls back to storing plaintext with an ERROR log when the vault is
inert (`AGENTS.md §5.4`); a refresh-token store must fail to deploy instead.
`SERVICE`-bound static/basic connections remain usable without either
precondition, so dev-mode Jira/Amplitude work is unaffected.

### 6.3 MCP OAuth discovery (Phase 4b)

Turn G3 from a circuit-breaker trip into an auth challenge. On a 401 from an MCP
server, parse `WWW-Authenticate` for a `resource_metadata` URL, fetch
`/.well-known/oauth-protected-resource` (RFC 9728) then the authorization server
metadata (RFC 8414), and either use a matching `ConnectionConfiguration` or surface
a precise "this server needs connection X" error to the Manager. Dynamic client
registration (RFC 7591) is **out of scope** — an admin registers the client once and
stores the ID/secret in a connection.

The circuit breaker must learn to distinguish `401 → needs auth` from
`connection refused → server down`; today both increment the same counter
(`:362-367`).

---

## 7. MCP transport work

`StdioMcpTransport` is already present in the pinned `langchain4j-mcp`
1.18.1-beta28 jar (`dev/langchain4j/mcp/client/transport/stdio/StdioMcpTransport`),
alongside an unused `WebSocketMcpTransport`. The transport is free; the cost is
everything around it.

### 7.1 Phase 3a — sidecar bridge (recommended first, XS)

Document and ship a compose example running `mcp-proxy` / `supergateway` as a
sidecar that bridges stdio → streamable HTTP. EDDI talks to it over the transport
it already has. The child process stays in its own container with its own limits
and its own image; EDDI gains no RCE surface, no interpreter in the runtime image,
and no process lifecycle code. **This unlocks the majority of vendor MCP servers
for a docs page.**

Also in 3a, cheaply: fix the `sse` inconsistency by adding it to
`SUPPORTED_TRANSPORTS` (`McpCallsConfiguration.java:80`) as a deprecated alias, so
the REST write path stops 400-ing a value the runtime accepts.

### 7.2 Phase 6 — native stdio, gated (M, only if 3a proves insufficient)

Deferred deliberately, because a config-editable `command` array is arbitrary code
execution as the EDDI uid — and given G7, that reads as unauthenticated RCE until
Phase 0 lands.

If built, the shape is constrained:

- **Not a free-form command.** An admin-defined catalog of server templates
  (`{name → command, allowedEnv}`) in `application.properties` or a dedicated
  admin-only store; agent configs reference a template **by name** only.
- `eddi.mcp.stdio.enabled` defaults false; startup guard refuses `enabled=true`
  when `authorization.enabled=false`.
- Config model becomes polymorphic on transport: `mcpServerUrl` is currently
  required and must be http(s) (`McpCallsConfiguration.java:105-117`); stdio needs
  `templateName` + `env` instead, and the client cache key (`:437-450`) must digest
  those rather than url+apiKey.
- SSRF validation and `${caller:token}` are meaningless for stdio — reject both at
  validate time rather than silently ignoring them.
- Process lifecycle: spawn, reap, restart-on-crash, memory/CPU caps, drain on
  shutdown. Note every replica spawns its own copy — a 5-pod deploy runs 5 servers
  with 5 independent token states.

---

## 8. Phases

Each phase must build cleanly, compile, and pass tests before the next begins
(`multi-tenancy-plan.md:176`). Phases 0-2 are independently shippable and worth
doing even if the connector work never starts.

### Phase 0 — Close the exposure gap (~2-3 days) · P0

Preconditions for everything else. Nothing here is connector work.

| Item | Change |
|---|---|
| **0.1** | `McpStartupGuard`, mirroring `OpenAiStartupGuard.isUnprotected()` (`:74-76`): refuse `NORMAL` launch when `/mcp` is reachable with `authorization.enabled=false` unless an explicit `eddi.mcp.allow-unauthenticated=true` opt-out is set. Add the matching path-policy entry. **Do not touch the global default** (C1). |
| **0.2** | `discover-tools` and `discover-endpoints` → `POST` with a request body; credential moves to a `@HeaderParam` following `IRestImportService.java:65`. Stop returning `apiAuth` inside generated ApiCall headers (`RestApiCallsStore.java:97` puts `configsByGroup` — each containing the pasted credential via `McpApiToolBuilder.java:250-251` — straight into the response body). **Manager lockstep required:** the endpoint's own Javadoc says "Used by the Manager UI for selective API call import", so the Manager's discovery calls must switch to POST in the same release. Keep the GET forms for one release, deprecated and rejecting a non-empty credential param. |
| **0.3** | `LogCaptureFilter` redacts in place, or a console formatter wraps `SecretRedactionFilter`, so stdout matches the ring buffer. |
| **0.4** | Stop returning raw `e.getMessage()` to HTTP callers (`RestMcpCallsStore.java:161`); log the exception class only on the outbound-failure paths, following the discipline already used at `HttpCallToolsProvider.java:252-262`. |
| **0.5** | `SecretScrubber`: recurse into array elements (`:134`), add an `x-*`/`*-token`/`*-key` header-name rule, and scrub URL-embedded credentials by reusing `RequestRedactor.redactUri`'s logic rather than the whole-string `KEY_LIKE_PATTERN`. |
| **0.6** | Apply `governDescription` to A2A agent-card and skill descriptions (`A2AToolProviderManager.java:152-175`). One call site, closes an asymmetry with MCP. |
| **0.7** | SSRF protection: **needs an explicit product decision, not a silent flip.** The `false` default is documented intent, not an oversight — the comment at `application.properties:341-343` says *"Default OFF to preserve calls to internal/private APIs in self-hosted deployments — enable for multi-tenant / internet-facing."* Flipping `%prod` to `true` breaks every self-hosted agent that calls an internal API. Proposed resolution: keep the global default, but have `eddi.connections.enabled=true` force SSRF protection on (connections by definition target third parties, and Phase 5 stores tokens worth stealing via `discover-tools`'s echo primitive), plus a release-notes migration note. Sign-off required either way. |

### Phase 1 — Govern what comes back (~4-5 days) · P0

| Item | Change |
|---|---|
| **1.1** | Provenance-mark tool results: delimit and label third-party content untrusted. Hook point is **`ToolLoopRunner.executeSingleToolCallResult`** — its own doc comment names it "the single shared copy" serving both the live loop (which appends verbatim at `:464`) and the resume path, so one change covers all seven tool sources, both execution paths, and (because the bridge's executors return ordinary tool results) MCP resource content and listings for free. (The `AgentOrchestrator` `tool_result` map fix from `langchain4j-recommendations.md:1082` is the *redaction* producer for SSE/audit — a related but separate change; do both, conflate neither.) |
| **1.2** | Extend the R0 `GuardrailService` contract to tool results, closing the gap `langchain4j-recommendations.md:250-252` names. Reuses R0's action vocabulary (`block \| redact \| reprompt \| warn`, C8). Never throws (C7). |
| **1.3** | `ToolRequestResolver` implementations for MCP and A2A, so `McpToolsProvider.java:80` and `A2AToolsProvider.java:51` stop passing empty maps and gated calls get a redacted preview plus a re-checked fingerprint (C5). The fingerprint is computed over the **redacted** request — credential header values excluded — otherwise a token refresh between approval and execution changes the hash and every approval of a connection-backed call spuriously fails the re-check. |
| **1.4** | `SecretResolver` invalidation listeners for `McpToolProviderManager` and `ChannelTargetRouter`, matching `ChatModelRegistry.java:152-167`. |

### Phase 2 — Unify (~5-7 days) · P1 — **the gate**

`ConnectionConfiguration` + store + REST + descriptor + `ConnectionResolver`, with
`STATIC` and `BASIC` auth types only. No OAuth yet. Wire the resolver into all five
resolution chains (§5.3). `BASIC` finally gives Jira and Amplitude first-class
support — today you must vault a pre-base64-encoded blob because nothing encodes
for you.

Also: register connection writes in the operator write-scope exclusion list (C6),
and add the store to `VaultGrantChecker`'s traversal so a connection's
`${vault:}` client secret participates in deploy-time grant enforcement.

Migration is additive — every existing `apiKey` field keeps working. Connections
are the recommended path, not a forced one.

### Phase 3 — Transports (~1 day) · P1

3a from §7.1: sidecar docs, compose example, `sse` alias fix. Phase 6 (native
stdio) stays deferred.

### Phase 4 — OAuth service account (~4-5 days) · P2

`OAUTH2_CLIENT_CREDENTIALS`, the grant store, `EnvelopeCrypto` reuse, lazy refresh
with single-flight (§5.4). Then 4b: MCP `WWW-Authenticate` / RFC 9728 discovery
(§6.3) and splitting the circuit breaker's auth-vs-down counters.

### Phase 5 — OAuth per-user (~8-12 days) · P3

`OAUTH2_AUTHORIZATION_CODE` + PKCE, the state store, the permit-path callback, the
`ConnectionStartupGuard`, and the Manager surfaces: an admin connection list, and a
per-user "your linked accounts" page with connect/disconnect. Plus a deploy-time
check that an agent referencing a `PER_USER` connection surfaces "users must
connect" rather than failing at turn time.

### Phase 6 — Deferred / opportunistic · P4

Native stdio (§7.2), A2A peer store extraction from `LlmConfiguration`, built-in
tool credentials → vault, NATS auth, channel export.

---

## 9. Files that would change

#### CREATE

| File | Purpose |
|---|---|
| `engine/security/McpStartupGuard.java` | 0.1 |
| `modules/llm/guardrails/ToolResultGuardrail.java` | 1.2 |
| `modules/llm/impl/McpToolRequestResolver.java`, `A2AToolRequestResolver.java` | 1.3 |
| `configs/connections/model/ConnectionConfiguration.java`, `OAuthConfig.java`, `StaticAuth.java` | 2 |
| `configs/connections/IConnectionStore.java`, `mongo/ConnectionStore.java`, `postgres/ConnectionStore.java` | 2 |
| `configs/connections/IRestConnectionStore.java`, `rest/RestConnectionStore.java` | 2 |
| `connections/ConnectionResolver.java`, `model/ConnectionReference.java`, `ConnectionException.java` | 2 |
| `connections/grants/IConnectionGrantStore.java` + Mongo/Postgres impls, `model/ConnectionGrant.java` | 4 |
| `connections/oauth/OAuthTokenService.java`, `OAuthStateStore.java`, `rest/RestOAuthCallback.java` | 4-5 |
| `connections/McpAuthChallengeParser.java` | 4b |
| `connections/ConnectionStartupGuard.java` | 5 |
| `docs/connections.md`, `docs/mcp-client.md` (**does not exist today**) | 2-5 |

#### MODIFY

| File | Change |
|---|---|
| `configs/mcpcalls/IRestMcpCallsStore.java`, `rest/RestMcpCallsStore.java` | 0.2, 0.4 |
| `configs/apicalls/IRestApiCallsStore.java`, `rest/RestApiCallsStore.java` | 0.2 |
| `engine/runtime/LogCaptureFilter.java` | 0.3 |
| `secrets/sanitize/SecretScrubber.java` | 0.5 |
| `modules/llm/impl/A2AToolProviderManager.java` | 0.6, §5.3, C2 |
| `src/main/resources/application.properties` | 0.1, 0.7, §6.2 permit path, `eddi.connections.*` |
| `modules/llm/impl/AgentOrchestrator.java` | 1.1 |
| `modules/llm/impl/McpToolsProvider.java`, `A2AToolsProvider.java` | 1.3 |
| `modules/llm/impl/McpToolProviderManager.java` | 1.4, §5.3 supplier reuse, 4b, §7 |
| `integrations/channels/ChannelTargetRouter.java` | 1.4 |
| `modules/apicalls/impl/ApiCallExecutor.java` | §5.3 fourth resolution step |
| `modules/llm/impl/ChatModelRegistry.java`, `EmbeddingModelFactory.java`, `EmbeddingStoreFactory.java` | §5.3 `SERVICE`-only |
| `secrets/VaultGrantChecker.java` | Phase 2 — traverse connection configs |
| `configs/mcpcalls/model/McpCallsConfiguration.java` | 3a `sse` alias; §7.2 if built |
| `EDDI-Manager/src/lib/operator/tool-scopes.ts` | C6 — exclude connection writes |
| `docs/changelog.md`, `AGENTS.md §3` roadmap | every phase |

#### DELETE

None. All changes are additive; existing `apiKey` fields keep working.

---

## 10. Metrics

Required for new features per the repo guideline (`operator-write-scope-plan.md:173-181`).
Micrometer, exposed at `/q/metrics`.

| Metric | Type | Tags |
|---|---|---|
| `connection.resolve.count` | Counter | `connection`, `binding`, `outcome` |
| `connection.resolve.time` | Timer | `connection` |
| `connection.grant.missing.count` | Counter | `connection`, `binding` — the per-user "not connected yet" signal |
| `connection.oauth.authorize.count` | Counter | `connection`, `outcome` |
| `connection.oauth.callback.count` | Counter | `connection`, `outcome` (`success`/`bad_state`/`expired_state`/`exchange_failed`) |
| `connection.token.refresh.count` | Counter | `connection`, `outcome` |
| `connection.token.refresh.singleflight.collapsed` | Counter | `connection` — validates §5.4 |
| `connection.grant.status` | Gauge | `status` |
| `mcp.auth.challenge.count` | Counter | `server`, `outcome` — 4b |
| `guardrail.toolresult.count` | Counter | `action`, `source` — 1.2 |

---

## 11. Tests and verification

```bash
./mvnw compile && ./mvnw test
```

Integration tests and anything binding a loopback socket are CI-only
(`AGENTS.md`, and the local-sandbox caveat). Treat a green CI run as the source of
truth for `*IT.java`.

Behavioural tests that must exist, each written so that reverting the fix makes it
fail:

- **0.1** — startup fails with `/mcp` reachable, `authorization.enabled=false`, no
  opt-out; boots with the opt-out; boots in dev.
- **0.2** — the GET forms reject a non-empty credential param; the POST forms work;
  the generated ApiCall no longer contains `apiAuth`.
- **0.5** — a secret inside a `List<String>`, a header named `X-Api-Token`, and
  `https://user:pass@host` in `targetServerUrl` are all scrubbed on export. These
  are the three documented holes; assert each separately.
- **1.1** — a tool result containing `DIRECTIVE_PATTERN` text arrives at the model
  delimited and labelled.
- **1.3** — a gated MCP call produces an approval payload containing target origin
  and a fingerprint, and a fingerprint mismatch at execution time refuses.
- **§5.3** — `${connection:}` in a URL, body or query param is rejected at
  validate time; a `PER_USER` connection on a scheduled turn throws rather than
  falling back to the service grant; a target origin outside `baseUrlAllowlist` is
  refused.
- **§5.4** — two concurrent resolves of an expired grant produce exactly one token
  request (assert on the collapsed counter), and the optimistic-lock loser adopts
  the winner's token.
- **§6.2** — a replayed `state` is refused; an expired `state` is refused; a
  `state` for user A cannot install a grant for user B; a `state` issued by
  replica 1 is honoured by replica 2 (persistent state store).
- **§6.2 Decision 4** — startup fails when a `PER_USER` connection exists and
  `authorization.enabled=false`; startup fails when an OAuth connection exists
  and the vault is inert; a `SERVICE` static connection deploys fine under both
  conditions.
- **§5.3 verified principal** — with a client-supplied user header and no OIDC
  identity, `PER_USER` resolution throws; it never resolves a grant keyed on the
  asserted userId. Mutation-check this one: revert the verified-principal check
  and confirm the test fails.

Note the `-Dtest=Class#method` caveat for `@Nested` classes — filter by whole
class or the run silently passes with zero tests.

---

## 12. What I would NOT do

- **Not flip the global `authorization.enabled` default.** Per-surface guard plus
  path policy (C1). Flipping it breaks every shipped compose file and k8s manifest
  simultaneously, and the repo has already chosen the other pattern twice.
- **Not resolve connections inside `SecretResolver`.** C3. The vault stays a static
  secret store; connections use it for client secrets and live above it.
- **Not store a token anywhere outside `EnvelopeCrypto`**, and not let a plaintext
  token appear in a config, an export, a log, conversation memory, or a REST
  response. Only `${connection:name}` is inherited (C4).
- **Not add a sixth bespoke `HttpClient`.** The OAuth token client uses
  `SafeHttpClient.sendValidated` (C2). Ideally Phase 4 also converts
  `A2AToolProviderManager` while it is being touched anyway.
- **Not offer `PER_USER` binding on LLM, embedding or vector-store connections.**
  Those cache built objects on unresolved parameters by design; a per-request
  identity there is incoherent. Reject at validate time rather than degrading.
- **Not build native stdio in the first pass.** Sidecar first (§7.1); native only
  behind an admin template catalog, and never with a config-editable command array.
- **Not implement dynamic client registration (RFC 7591).** An admin registers once.
- **Not build a proactive refresh sweeper in Phase 5.** Lazy + single-flight is
  correct; add the sweeper only if telemetry shows cold-start latency matters.
- **Not add bespoke tenant scoping.** `tenantId` field + `"default"` +
  `Provider<TenantContext>` when Phase 1 of the multi-tenancy plan lands (C9).
  Register the new stores in that plan's Phase 2 table rather than forking it.
- **Not accept a client-supplied header as the grant principal — ever.** Not even
  behind a `trust-user-headers`-style toggle. The `/v1` adapter's toggle trades
  impersonation risk for Open WebUI compatibility on *conversation* data; a token
  store raises the stakes to credential theft, and no compatibility argument
  survives that.
- **Not treat `toolsWhitelist`/`toolsBlacklist` as a security boundary.** They are
  context-window management. The security boundary is the connection's
  `baseUrlAllowlist` plus the HITL gate. #668 makes this concrete: the resource
  bridge is *deliberately* not subject to `toolsWhitelist`, because that filter
  governs server-advertised names and the bridge's are EDDI-synthesized — so a
  whitelist is not even a complete inventory of what a config exposes.
- **Not enumerate credential field names anywhere.** The 2026-08-10 vault decision
  chose serialize-and-scan precisely because *"enumeration is how this kind of check
  rots when a new credential field appears."* `VaultGrantChecker`'s connection
  traversal follows the same rule.
- **Not export connection grants**, and not add connections to agent ZIPs without a
  scrubbing story — G11 shows what happens when export outruns scrubbing.

---

## 13. Open questions

1. **Group conversations and per-user tokens.** When a group agent acts inside a
   `GroupConversation`, whose Drive token does it use? The invoking user's, the
   group owner's, or a service grant? `GroupConversationService.discuss()` creates
   individual member conversations, so the principal may not be the human at all.
   This needs a decision before Phase 5 and may add a `groupBinding` field.
2. **How many providers ship natively in Phase 5** before Option D (§4) takes over
   the long tail. A buy/build call, not a technical one.
3. **Row-level tenant filtering vs database-per-tenant** — inherited unresolved
   from `multi-tenancy-plan.md:509`. The grant store's index design follows whatever
   that resolves to.
4. **Should connection-backed writes require HITL approval by default?** Argument
   for: a per-user OAuth token plus an LLM plus a write tool is the configuration
   most likely to cause real damage. Argument against: it makes every connector feel
   broken out of the box. If yes, it must be expressed as a wildcard plus an exempt
   list (C11), never an enumerated write list.
5. **Does `${connection:}` supersede `${caller:token}`** as a `SERVICE`-vs-relay
   distinction, or do both stay? They overlap for EDDI-calling-EDDI. Leaning: both
   stay, `${caller:token}` documented as the same-origin special case.
6. **Grant revocation propagation.** Deleting a grant row stops resolution
   immediately, but should EDDI also call the provider's revocation endpoint? Doing
   so is correct and adds a per-provider config field; not doing so leaves live
   tokens at the provider after a user disconnects.
