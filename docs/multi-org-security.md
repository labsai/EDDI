# Multi-Org Security — Threat Model (draft)

> **Status: draft, Phase 0.** Organizations are **not built yet**. This page is the STRIDE threat
> model for the shared-cluster org design in
> [`planning/multi-tenancy-plan.md`](../planning/multi-tenancy-plan.md) and
> [ADR 0001](adr/0001-organizations.md). It is updated at the end of every implementation phase,
> and an external pentest reviews it before the first external org goes live. A deployment that
> never sets `eddi.org.mode=multi` is out of scope: it behaves as it does today
> ([Security](security.md)).

## 1. Assets

| Asset | Why it matters |
| --- | --- |
| Org data: configurations, conversations, memories, attachments, vector stores, DB logs, audit ledger, export archives | Disclosure to another org is the primary threat. These are the Appendix B org-scoped stores |
| Org secrets: vault values, LLM and channel keys, OAuth grants | Disclosure lets the attacker act as the org outside EDDI |
| Org identity: memberships, roles, teams, `(orgId, subject)` principals | Tampering means privilege inside, or across, orgs |
| Routing keys: hostnames, `/v1` key hashes, Keycloak org ids, integration ids, OAuth state | They select the org. Forging one means acting as another org |
| Platform keys: master key, audit key, NATS signing key, export signing key | Exposing them exposes every org (§11.2). Until per-org KMS exists, this is a single point |
| Shared capacity: pods, DB, NATS, LLM spend | Exhausting it is denial of service against other orgs |

## 2. Actors and trust boundaries

- **Anonymous internet caller.** Reaches public chat, A2A, `/.well-known/*`, the branded shell,
  channel webhooks, `/connections/callback`.
- **Org member** in any org role (`eddi-viewer` … `eddi-admin`). Trusted only inside their own
  org. **The design assumes a malicious org admin.**
- **Agent author.** An org member who writes configs. Treat configs as attacker-controlled:
  URLs, templates, vector-store names, prompts.
- **The LLM.** Attacker-influenced through prompt injection. It may call any tool its agent
  exposes, repeatedly.
- **Platform staff** (`eddi-platform-*`). Trusted for operations, and **not** for customer
  content without break-glass.
- **A compromised EDDI pod.** Out of scope for isolation. It holds the platform keys. The
  dedicated tier and per-instance deployments are the answer for orgs that cannot accept this.

The boundaries: internet → ingress → EDDI (host binding and token verification), EDDI → DB
(app filter plus second layer), EDDI → NATS (signed headers), EDDI → outbound (forced SSRF
policy), EDDI → IdP (verified access token only).

## 3. Cross-cutting controls (defense in depth)

| Layer | PostgreSQL | MongoDB |
| --- | --- | --- |
| 1. App filter | `OrgSql` adds `AND org_id = ?` and stamps `org_id` | `OrgCollections` adds the filter or `$match` and stamps `orgId` |
| 2. Independent backstop | RLS `ENABLE` and `FORCE`, GUCs set by `OrgRlsPoolInterceptor`; app role `NOSUPERUSER NOBYPASSRLS`, required in multi mode (ADR DV-1) | `OrgGuardedCollection` refuses unscoped commands (deny by default). `OrgReadVerifier` refuses foreign documents at decode. The `$jsonSchema` validator requires `orgId` on writes |
| 3. Context | `ScopedValue` binding with an epoch. An unbound access in multi mode is `OrgContextMissingException` → 500 | same |
| 4. Keys | per-org KEK, AAD v2 bound to the org, per-org audit key and chain | same |
| 5. Isolation tier | dedicated tier (own database), then own instance | same |

**Known single-layer spots, accepted for now:**

- **Mongo aggregations, `distinct` and counts** are protected by the guard's pipeline and filter
  check only. Their output carries no `orgId` to verify (ADR DV-5). Each call site is reviewed and
  allow-listed, and covered by conformance and mutation tests.
- **SQL injection on Postgres.** The app role can run `set_config('eddi.platform','on')`. RLS
  stops missed filters, not injection. Parameterised statements and the build guards are the
  control. With the per-connection memo (ADR DV-2, accepted), an injected change to
  `eddi.*` GUCs also **persists** for later borrowers of that connection in the same org, until
  the binding changes. Always overwriting would reset it on every borrow. A build guard forbids
  any SQL other than the interceptor's from naming these GUCs.
- **Mongo writes that move data between orgs.** The guard refuses update operators that change,
  unset or rename onto `orgId` (ADR DV-12). It also refuses a `FindIterable.filter()` that drops
  the org equality (DV-13).

## 4. STRIDE per entry point

Legend: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of
service, **E**levation of privilege. The § numbers point at the plan.

### 4.1 Authenticated REST, SSE, MCP

| | Threat | Control |
| --- | --- | --- |
| S | A token from org A is used against org B | Org comes from the verified access token's `organization` object, by Keycloak org **id** (§9.1, ADR LOCK 7). `X-EDDI-Org` must name an org in the claim. A missing or unknown claim gives 403 and never falls back to `default` |
| S | A forged `X-EDDI-Org` header | Checked against the claim. A loopback re-validates it (§7.3) |
| T/E | Realm role `eddi-admin` grants admin in every org | `OrgRoleAugmentor` drops every token `eddi-*` role in multi mode and grants `OrgMembership` roles for the request's org only (§9.3). Spike B confirmed realm roles are identical in every org's token |
| E | Realm groups grant team spaces in every org | Only `/orgs/<kc-org-id>/…` groups or `OrgMembership.teams` count (§9.4) |
| I | Ids from org A used as org B | Data-layer predicates plus the second layer, and 404 (never 403) across orgs (§4) |
| I | Lazy entities serialised after the scope ended | Entities are materialised inside the scope. Streaming outputs capture the org and rebind it (§6.2) |
| I | `/openapi` lists `/platform/*` operations | The OpenAPI document is filtered per plane (§7.1) |
| R | An admin action cannot be attributed | Org audit ledger with a per-org key and chain (§11.3) |
| D | One org's turns starve others | Per-org turn semaphores, LLM and tool buckets, quotas (§12) |

### 4.2 Anonymous public chat, A2A, `/.well-known/*`, branded shell, `/manage/__auth_config__.js`

| | Threat | Control |
| --- | --- | --- |
| S | Spoofed `X-Forwarded-Host` selects another org | Only Quarkus' proxy-aware authority, with `quarkus.http.proxy.*` configured, selects the org. The raw header is never trusted (§7.1) |
| I | An unmapped host lists every org's agents or brand | An unmapped host gives 404, or the platform brand for the shell. Agent cards list only the host's org (§7.1) |
| I | Branding cache crosses orgs | Caches are keyed `(org, revision)` (§7.1, §8.6) |
| D | Anonymous floods | Per-org admission control and rate buckets (§12) |

### 4.3 Channel inbound (Slack, webhooks, email)

| | Threat | Control |
| --- | --- | --- |
| S | A payload field claims another org's workspace | The org comes from the integration id in the path, through a platform read by unguessable id, and the signature is verified with **that** integration's secret. Payload fields never select the org (§7.1) |
| S | Email sent to org B's mailbox processed as org A | The org of the swept mailbox integration wins (§7.1) |
| T | Replayed webhook | The existing signature and timestamp checks, per integration |

### 4.4 `/v1` OpenAI-compatible API

| | Threat | Control |
| --- | --- | --- |
| S | A single deployment key reaches every org | Per-org `OrgApiKey`, hashed, resolved through `OrgRoutingIndex` (§7.1) |
| S | The user id taken from a header collides across orgs | The header user id is namespaced inside the key's org, as an `(orgId, subject)` pair (§9.2) |

### 4.5 OAuth `/connections/callback`

| | Threat | Control |
| --- | --- | --- |
| S/T | The state value is reused, or swapped into another org | Single-use `OAuthState` stores the org. The callback binds that org through a platform read by state value (§7.1) |

### 4.6 Background jobs, NATS, cluster

| | Threat | Control |
| --- | --- | --- |
| I | A job processes org A's item under org B | Claim across orgs, then bind per item with `runAs(item.orgId)` on a new connection. A connection never spans a change of binding, and the epoch assertion enforces it (§7.4, §8.4) |
| D | One org's backlog starves the others' jobs | Per-org caps per tick, round-robin start org (§7.4) |
| S/T | A NATS message with a forged org header | Signature v2 covers `Eddi-Org`. Signing is mandatory in multi mode, and messages without the header are rejected (§8.9) |
| I | Cluster admin and dead-letter views show other orgs | These are platform-plane views. An org admin sees only their own org's entries (§8.9) |
| I | Shared archive bucket | Object names are `<org>/<file>`, with per-org byte caps and a purge on deletion (§8.9) |

### 4.7 Agent author and LLM (configs and tool calls)

| | Threat | Control |
| --- | --- | --- |
| I/E | SSRF into EDDI, NATS, the DB, Keycloak admin, metadata services or other orgs | SSRF protection is forced on after the marker, with a non-overridable deny list and `eddi.org.ssrf.cluster-cidrs` (§11.1) |
| I | Author-chosen vector store names collide with another org's | Names are derived and hashed, and author-supplied physical names are refused (§8.8) |
| I | Templates read another org's variables, secrets or snippets | Templating resolves within the current org only. No syntax names another org (§10) |
| I | Credential-bearing caches (model registries, `SecretResolver`) cross orgs | These caches are always org-keyed (§8.6) |
| D | Cost exhaustion through the LLM | Per-org cost quotas and LLM buckets. The platform provider pool is opt-in with per-org buckets (§11.4, §12) |

### 4.8 Platform plane and break-glass

| | Threat | Control |
| --- | --- | --- |
| E | An org admin reaches `/platform/*`, `/q/metrics` or cluster admin | Platform roles come only from `resource_access/eddi-platform/roles`, a client only staff hold, with MFA. The multi-mode startup check refuses org roles in the metrics and platform policies (§5.1, §9.3) |
| I | Platform staff read customer content | Only through a break-glass grant: a reason, a TTL of at most 4 h, org admins notified, every action in the org's ledger, optional four-eyes approval (§9.3) |
| R | Platform actions are not attributable to the org | Platform ledger, plus the org ledger for any action under a grant |

### 4.9 Upgrade, rollback, migration

| | Threat | Control |
| --- | --- | --- |
| I | A binary below the floor reads every org unfiltered on Mongo | `DataLayoutGuard` refuses binaries below the floor (Spike C: exit 1 before any write, on both datastores). Rollback below the floor is forbidden and documented, because a pre-floor binary cannot be stopped (§5.4) |
| I | A pre-floor binary on Postgres | Contained only if it connects as `eddi_app` (NOBYPASSRLS) against `FORCE`d tables. This is why DV-1 makes the role split mandatory |
| T | Enablement interrupted halfway | Leased, checkpointed and idempotent steps. The switch has a presence barrier (§5.3) |
| I | Org bundle tampered with in transit (moves M1–M5) | Ed25519 signature over the manifest and SHA-256 per stream, verified before any write. Secrets travel only re-encrypted for the target (ADR decision 9) |

## 5. Residual risks

| Risk | Treatment |
| --- | --- |
| Exposure of the platform master key exposes every org | Accepted until per-org KMS (BYOK). Orgs that cannot accept it go to a dedicated tier or their own instance |
| A compromised pod sees every org | Accepted. The tiers above are the answer |
| SQL injection defeats RLS (`set_config` is callable by the app role) | Parameterised SQL only, plus build guards. RLS is a backstop for missed filters, not for injection |
| Mongo aggregations and counts have one runtime layer | Allow-listed call sites, conformance suite, mutation checks (ADR DV-5) |
| PgBouncer transaction pooling breaks session GUCs | Session mode, or `eddi.org.postgres-rls=off-i-understand` with a WARN, as a STOP decision per deployment (§8.4) |
| Revocation latency | Bounded by the 60 s membership cache and the 3-minute OIDC token cache (§9.3) |

## 6. Verification

The controls above are proven by these tests and reviews. Nothing exists yet; they arrive in the
phases named:

- **`MultiOrgConformanceIT`** probes every REST operation and MCP tool, as every role of org B
  and anonymously, with org A's ids. It runs on both datastores, including the backstop run
  with the app filter disabled (§14.1).
- **Build guards** (§14.2) and **mutation checks** (§14.3).
- **`DataLayoutGuard` IT** (Phase 1).
- **External pentest** before the first external org.
