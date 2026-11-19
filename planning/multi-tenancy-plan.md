# Multi-Tenancy Plan — Shared Organizations on One EDDI Cluster

**Status:** Design approved for implementation, nothing of the org model built yet. Shipped
pieces this plan builds on: tenant quotas on Mongo and Postgres
([`docs/tenant-quotas.md`](../docs/tenant-quotas.md)), per-user/team workspaces
([`docs/workspaces.md`](../docs/workspaces.md)), per-tenant DEKs in the vault
([`docs/secrets-vault.md`](../docs/secrets-vault.md)).
**Created:** 2026-04-17 · **Rewritten:** 2026-11-19. This version replaces the April draft
entirely. The April draft's "Strategy B" (a `@RequestScoped TenantContext`, filtering in
`MongoResourceStorage`/`PostgresResourceStorage` only, Phase 4 as a one-line change) was
verified against the code and **does not work**. §2 says why. Do not resurrect it.
**Scope:** backend first (EDDI repo), then Manager/Workforce, Helm and docs.
**Audience:** a coding agent picking this up cold. Everything needed is in here. Counts
and file lists were taken from `main` at `985ebb608f` (2026-11-19). Re-run the commands in
Appendix A before relying on a count, because they drift.

Read first, in this order: `AGENTS.md` (§2 workflow rules and §4 Java rules are binding),
[`docs/project-philosophy.md`](../docs/project-philosophy.md),
[`docs/workspaces.md`](../docs/workspaces.md), [`docs/security.md`](../docs/security.md),
[`docs/secrets-vault.md`](../docs/secrets-vault.md), [`docs/clustering.md`](../docs/clustering.md),
then this file.

---

## 0. Goal, non-goals, hard requirements

**Goal.** Several customers ("organizations", §1) share one EDDI cluster: the same pods,
the same database server, the same NATS. Each org's data, configuration, secrets, identity,
spend and operations are isolated as strongly as if it had its own instance. The operator
is a platform team that runs the cluster.

**Hard requirements (from the product owner):**

1. **Super secure.** A missed check in one endpoint must not be enough to leak data
   between orgs. Defense in depth, fail closed, provable by tests.
2. **Zero pain with one tenant.** A deployment that never uses multi-tenancy needs no new
   configuration, keeps its roles, URLs, UI and performance, and upgrades without manual
   steps. **Both modes must work flawlessly**, so both modes are tested in CI on every PR.
3. **Well designed, future-proof, scalable.** Thousands of small orgs in a pooled tier.
   Large or regulated orgs can move to a dedicated tier (own database) or out to their
   own instance without code changes.

**Non-goals (for this plan):** cross-org features (shared agent marketplace, cross-org
group conversations, cross-org user memories), billing/invoicing, data residency across
regions. Keep the seams open (§12 Open decisions), but do not build them.

**Context:** the owner already runs "one EDDI instance per customer" (Helm release per
namespace, chart NetworkPolicy, own DB, own NATS, own Keycloak realm, own vault master key).
That stays the top isolation tier and the fallback for customers who need it.

---

## 1. Terminology — read this before touching code

The word **tenant** already means three different things in EDDI. A fourth meaning would
be a permanent bug source, so the customer boundary is called an **organization (org)**.

| Term | Meaning | Where it lives today |
|---|---|---|
| **org** (NEW) | The customer isolation boundary. Every piece of customer data belongs to exactly one org. | This plan |
| vault / variables **tenant** | Namespace inside the secrets vault and global variables: `default`, plus per-space tenants `t.<slug>.<hash>` (team) and `u.<slug>.<hash>` (user) | `secrets/model/SecretReference.java` (`tenantId, keyName`), `engine/security/spaces/SpaceTenants.java`, `${vault:<tenant>/key}`, `${vars:<tenant>/key}` |
| quota **tenant** | Key of the quota/usage counters, always `eddi.tenant.default-id` today | `engine/tenancy/*`, `/administration/quotas/{tenantId}` |
| Quarkus OIDC **tenant** | An IdP configuration in Quarkus OIDC. `quarkus.oidc.tenant-enabled` is simply "auth on/off" | `application.properties` |
| **space** | Workspace inside an org: `user:<principal>` or `team:<group path>` | `engine/security/spaces/*` |
| **platform** | The operator of the cluster, above all orgs | This plan |

Rules:
- **No new code, config key or doc may use "tenant" for the org concept.** New config keys
  live under `eddi.org.*`. New classes are named `Org*`.
- The quota "tenant id" **becomes** the org id. Keep the REST path
  `/administration/quotas/{tenantId}` working as an alias, document `{orgId}`.
- The vault/variables tenant **stays** a namespace *inside* an org. The key becomes
  `(orgId, vaultTenant, keyName)`. Reference syntax `${vault:<tenant>/key}` stays unchanged
  and is resolved **relative to the current org**.
- Ignore `ScheduleConfiguration.tenantId` (`engine/schedule/model/ScheduleConfiguration.java`).
  It is `@Deprecated(since = "6.8.0")`, never read, and kept only so stored rows stay valid.
  Add a new `orgId` field. **Do not reuse the deprecated one.**

---

## 2. Current state — verified inventory and why the naive approach fails

### 2.1 What exists and is reusable

| Piece | What it gives us | Files |
|---|---|---|
| Descriptor ownership + visibility (workspaces) | owner, space, visibility (`private`/`space`/`internal`/`published`), grants, listing restriction **in the query** | `engine/security/spaces/ResourceAccessGuard.java` (used by 92 files), `datastore/DescriptorStore.readDescriptorsRestricted(...)`, `configs/descriptors/model/DocumentDescriptor.java` |
| `X-EDDI-Space` header → one stamp point for all creates | every create ends in `ResourceAccessGuard.stampNewDescriptor` | `engine/security/spaces/SpaceContext.java`, `SpaceHeaderFilter.java` |
| Per-tenant DEKs, envelope encryption | DEK per vault tenant, wrapped by a KEK from the master key (PBKDF2) | `secrets/impl/VaultSecretProvider.java`, `secrets/crypto/*`, `secrets/persistence/*` |
| Tenant-keyed quotas, cluster-safe | atomic counters in Mongo/Postgres, keyed by a tenant string | `engine/tenancy/TenantQuotaService.java`, `MongoTenantQuotaStore.java`, `PostgresTenantQuotaStore.java` |
| Reserved principal prefixes | rejects tokens whose principal looks like `system:`, `slack:`, … | `ReservedPrincipals` (see `docs/security.md`) |
| Cluster message signing | HMAC on NATS messages | `engine/cluster/ClusterMessageSigner.java` |
| Configurable NATS prefix | `eddi.nats.prefix=EDDI` | `engine/cluster/ClusterSubjects.java` |
| Loopback calls forward the caller's bearer | in-request internal REST calls carry the user's token | `engine/runtime/client/factory/LoopbackCallerAuthFilter.java` |
| `ScopedValue` precedent | lexically bound per-call context | `modules/rules/impl/conditions/TemplateDataScope.java` |
| Postgres generic resource table | `resources(collection_name, id, version, data JSONB)` + `resources_history` | `datastore/postgres/PostgresResourceStorage.java` |
| Helm NetworkPolicy | default-deny, release-namespace ingress, private-range egress block | `helm/eddi/templates/networkpolicy.yaml` |

### 2.2 Facts that break the April plan (code-verified 2026-11-19)

| April assumption | Reality | Consequence for this plan |
|---|---|---|
| ~15 stores, filter in the storage SPI | **31** files use `IResourceStorageFactory`. **48** files use `MongoCollection<>` directly. **~52** classes use a `DataSource` directly (28 real stores in `datastore/postgres/`, 19+ outside it: connections, email ledger, Slack approvals, team budget, vault, pgvector) | The SPI covers configuration resources only. Most runtime data (conversation memory, user memory, audit, logs, schedules, HITL, attachments, ratings, alerts, ingestion, OAuth, grants) bypasses it. **Every** store needs the org (§5) |
| `@RequestScoped TenantContext` + `Provider<>` in stores | ~60–75 files run work on executors, virtual threads, `@Scheduled` (20 files), NATS consumers (17 files), channel inbound (Slack, webhook, email sweeper), A2A, `/v1` | There is no request scope in most places data is touched. The context must be a value carried explicitly (§4) |
| Loopback REST calls have no identity | `LoopbackCallerAuthFilter` forwards the caller's bearer. **Without a caller** (background, scheduler, OIDC off) the call goes out anonymous | Background loopback calls must carry an org (§4.4) or be replaced by in-process calls |
| Phase 4 is "1 line" | `getDefaultTenantId()` has **22 call sites** (Appendix A.6) | Its own task in Phase 1 |
| Quota store is in-memory | Mongo and Postgres quota stores exist | Done. Reuse |
| `SET LOCAL` per transaction for RLS | **380** `getConnection()` sites, **0** `@Transactional`, ~7 `setAutoCommit(false)`. Stores run in autocommit, where `SET LOCAL` has no effect | RLS needs an explicit transaction helper (§5.4) |
| Cache keys prefixed in the cache factory | Only a handful of `ICacheFactory` users. **39 files build Caffeine directly** (Appendix A.5) | Every cache is audited and keyed by org (§5.6) |
| Add `org_id` leading in the Postgres PK | PK is `(collection_name, id)` on fresh installs and `(id, collection_name)` on old ones. Startup **refuses** PK rewrites (`PostgresResourceStorage.java` ~L98-108). `resources_history` differs again | Never rewrite PKs. Add composite indexes (§5.4) |
| Mongo: pick a database per org in the driver | One `MongoDatabase` bean (`datastore/bootstrap/PersistenceModule.java`), collections bound in constructors (`MongoResourceStorage`) | The dedicated tier needs an `OrgDatabaseRouter` touching all 49 injecting files. Build it last (Phase 7) |
| Workspaces could be the customer boundary | Workspaces rely on a guard call in 92 files. `internal` = **every signed-in user of the deployment**. The directory finds any user by exact username or verified email. `eddi-admin` is global | Workspaces remain *intra-org* sharing. The org boundary sits below them, at persistence |

### 2.3 Leak paths specific to shared hosting (must all be closed before the first external org)

Severity ordered. Each one maps to a task in §11.

1. **SSRF into EDDI itself or other orgs.** `eddi.security.ssrf-protection.enabled=false` is
   the default (`application.properties` ~L1039). An org's `httpcalls`, MCP client server
   URL, RAG/vector host or connection endpoint can reach `127.0.0.1:7070` (where background
   loopback calls are anonymous), NATS, the DB and cloud metadata.
2. **RAG/vector stores.** The physical table/collection name is author-chosen
   (`modules/llm/impl/EmbeddingStoreFactory.java`, `physicalName(...)`, `databaseName`
   default `eddi`). pgvector uses its own pools (`PgVectorConnectionPools`), outside any
   app or RLS layer. On a platform-shared vector store, org A can name org B's table.
3. **Postgres table owner bypasses RLS.** The app creates its tables at startup, so it owns
   them, and RLS is inert without `FORCE ROW LEVEL SECURITY` plus a role split.
4. **Shared NATS control plane.** One subject root (`ClusterSubjects`), one HMAC key.
   KV/dead-letter/tombstone keys are bare conversation ids (`ErasureTombstones`,
   `JetStreamDeadLetterStore`). Cluster-admin views (dead letters, diagnose, activity log)
   are cross-org by construction.
5. **Platform Operator and MCP server.** Roles are global (`engine/mcp/McpToolUtils.java`).
   The Operator allow-list (`ui/manager/src/lib/operator/tool-scopes.ts`) includes
   `GET /administration/logs` and `cluster/*`. An org admin's Operator would read platform data.
6. **Audit ledger.** One HMAC key and one chain (`engine/audit/AuditKeyring.java`). A per-org
   export would expose the verification key and cross-org ordering.
7. **Import / export / agent sync.** Import matches by `originId`
   (`backup/impl/RestImportService.java`, `documentDescriptorStore.findByOriginId(...)`).
   Unscoped, an import in org B merges into org A's resource.
8. **Shared LLM provider keys.** Platform-supplied keys mean shared provider rate limits,
   prompt-cache timing side channels and merged billing.
9. **Identity collisions.** User memories are keyed on `user_id` alone
   (`PostgresUserMemoryStore`, `MongoUserMemoryStore`). Attachments are keyed by
   `conversationId`/`userId` metadata (`GridFsAttachmentStore`). The same `sub` or email
   in two IdPs collides.
10. **`/v1` OpenAI-compatible API.** One deployment-wide key (`integrations/openai/OpenAiCompatConfig.java`),
    and the user id is a caller-supplied header.
11. **Metrics/tracing.** Metrics already tag `tenant` and `agentId`. `/q/metrics` is
    platform-only data, with a cardinality risk.
12. **DB logs, error messages, correlation ids.** Platform-wide `logs` collection.
    Substring-search/GIN indexes are global (index timing).
13. **Channels.** Slack/webhook/email share endpoints. The org must come from the
    **verified signing secret / integration record**, never from a payload field such as
    `team_id` (`integrations/slack/SlackEventHandler` already does this within one deployment).
14. **Templating.** `{vars.*}`, `{snippets.*}`, `${vault:<tenant>/…}`, `${vars:<tenant>/…}`
    name namespaces textually and must resolve relative to the current org.
15. **HITL approvals, Slack approval records, group conversations, dynamic/sub-agents,
    OAuth state and grants, team budgets, schedules, triggers** all start or resume work
    later. Each must carry `orgId`.
16. **Backups/restore per org** are impossible in the pooled tier without a logical export (§9).

---

## 3. Design principles (binding)

1. **Org is the outer boundary.** Spaces, workspaces, vault tenants and quotas live inside it.
2. **Enforce at the persistence boundary.** Every read, write, query, aggregation, delete,
   cache lookup, NATS message and vector-store access is scoped by org **in the data layer**.
   Endpoint guards (workspaces) handle sharing *within* an org only.
3. **Fail closed.** In multi mode, data access without a bound org throws
   `OrgContextMissingException` (500, logged `[SECURITY]`). Crossing orgs is possible only
   inside `OrgScope.platform(reason, () -> …)`, which is audited and restricted to an
   allow-listed set of classes (§10 build guard).
4. **One code path for both modes.** Single mode = multi mode with one org, `default`.
   No `if (multi)` in stores. The context is always bound. What differs between modes is
   only (a) how the org is resolved and (b) what happens when it is missing (§7).
5. **The org is data.** Everything that starts or resumes work without a user request stores
   its `orgId` and re-binds it: conversations, schedules, triggers, channel integrations,
   group conversations, HITL pauses, ingestion jobs, NATS messages, OAuth states, team budgets.
6. **Defense in depth:** app filter (always), Postgres RLS (multi mode; opt-in single),
   per-org crypto keys, forced SSRF policy, dedicated tier, then a separate instance.
7. **404, never 403, across orgs.** An id from another org is "not found". Existence is never
   disclosed. Ids stay globally unique (ObjectId/UUID). No org id appears in public ids.
8. **Platform plane ≠ org plane.** Platform operators manage the cluster. They see no
   customer content by default, and break-glass access is explicit, time-boxed and audited
   into the **customer's** ledger.
9. **Isolation tier is a property of the org** (`POOLED` | `DEDICATED`), resolved in one
   place, so moving an org needs no code change.

---

## 4. Org context and propagation

### 4.1 The primitive

New package `ai.labs.eddi.engine.org`.

```java
public record OrgId(String value) {            // validated: ^[a-z0-9][a-z0-9-]{1,62}$, immutable, opaque
    public static final OrgId DEFAULT = new OrgId("default");
}

public final class OrgContext {
    private static final ScopedValue<OrgId> CURRENT = ScopedValue.newInstance();

    /** Throws OrgContextMissingException in multi mode; returns DEFAULT in single mode (and logs once per call site). */
    public static OrgId require();
    public static Optional<OrgId> current();
    public static <T> T callAs(OrgId org, Callable<T> work);   // ScopedValue.where(CURRENT, org).call(...)
    public static void runAs(OrgId org, Runnable work);
}

public final class OrgScope {
    /** Only for allow-listed platform code (§10). Binds a PLATFORM marker; storage refuses unfiltered
        access unless inside this scope. Every entry is logged + metered with the reason. */
    public static <T> T platform(String reason, Callable<T> work);
}
```

- **Why `ScopedValue`:** final in Java 25, safe with virtual threads, cannot leak between
  pooled threads like a `ThreadLocal`, already used in the codebase. A `ScopedValue`
  **cannot** be carried by Quarkus `ThreadContextProvider` (that API sets and restores
  imperatively), so do not try. Hand-offs are explicit (§4.3).
- **Org id format:** opaque, immutable, lower-case slug, max 63 chars. Not derived from
  the customer's name, never reused after deletion. The display name lives in the `Org` record.
- **Why not explicit parameters everywhere:** ~100 stores and hundreds of call sites. Use
  the `ScopedValue` for the ambient value, **plus** an explicit `OrgId` argument on the thin
  store APIs of §5.3, where the cost is low and a missing value is a compile error.

### 4.2 Entry points that bind the org

| Entry point | How the org is resolved | Class to add/change |
|---|---|---|
| REST (JAX-RS), SSE, MCP over HTTP | `OrgResolver` from the **verified** token (§6.1); optional cross-check against `Host` → org mapping. Never from a header alone | new `OrgRequestFilter` (`@Priority(Priorities.AUTHENTICATION + 10)` `ContainerRequestFilter`) that binds the value for the resource invocation via a Quarkus interceptor `@OrgBound` around resource methods (a filter cannot wrap a lexical scope, so the binding happens in an interceptor; the filter only resolves and stores the `OrgId` on the request context) |
| MCP tools (`engine/mcp/*`, 19 classes) | same as REST, from `SecurityIdentity` | interceptor binding on the tool classes |
| Public chat endpoints, anonymous | org of the **agent** being addressed (agent descriptor carries `orgId`), plus optional `Host` mapping | `RestAgentEngine` / conversation start path |
| Slack, webhook (`/integrations/*`) | org of the `ChannelIntegration` whose signing secret verified the request | `RestSlackWebhook`, `RestWebhookChannel`, `SlackEventHandler`, `WebhookEventHandler` |
| Email inbound | org of the mailbox/integration record | `integrations/email/EmailInboundSweeper` + ledger stores |
| A2A (`/a2a/*`, `/.well-known/*`) | org of the addressed agent. The deployment-level card lists only agents of the `Host`-mapped org, or none in multi mode | `engine/a2a/RestA2AEndpoint.java`, `AgentCardService` |
| `/v1` (OpenAI-compatible) | **per-org API keys** (new `OrgApiKey` store, hashed). Header user ids are namespaced under that org | `integrations/openai/*` |
| `@Scheduled` jobs (Appendix A.7) | iterate orgs explicitly: `for org in orgStore.active(): OrgContext.runAs(org, …)` with per-org fairness (round-robin, per-org budget per tick) | each job; shared helper `OrgIterator` |
| Schedule fires (`SchedulePollerService`, `ScheduleFireExecutor`) | `ScheduleConfiguration.orgId` (new field) | engine/schedule/* |
| NATS consumers (Appendix A.8) | `Eddi-Org` header, covered by `ClusterMessageSigner` | engine/cluster/* |
| Conversation turns resumed later (HITL, `CONTINUE_CONVERSATION`, group member turns, sub-agents) | `orgId` on the conversation memory snapshot / group conversation | engine/memory, engine/internal/groups |
| Startup / migrations | `OrgScope.platform("migration:<name>", …)` | configs/migration/*, datastore/postgres/PostgresMigrationManager |

### 4.3 Hand-offs (where a `ScopedValue` is lost)

Every hand-off to another thread must capture and rebind. Provide one helper and use it
everywhere:

```java
public final class OrgPropagation {
    public static Runnable wrap(Runnable r);                 // captures OrgContext.require() at wrap time
    public static <T> Callable<T> wrap(Callable<T> c);
    public static <T> Supplier<T> wrap(Supplier<T> s);
    public static Executor wrap(Executor e);                 // decorates every submit
}
```

Apply it to `ExecutorService`/`ManagedExecutor`/virtual-thread executors,
`CompletableFuture.*Async`, Mutiny `emitOn`/`runSubscriptionOn`, SSE emitters, and the
LLM streaming callbacks in `modules/llm/impl/*`. Search patterns are in Appendix A.9.
The build guard (§10) flags new raw `Executors.new*`, `CompletableFuture.supplyAsync(` without
an executor, and similar.

### 4.4 Internal loopback REST calls

12 files use `IRestInterfaceFactory` (Appendix A.4). Rules:
- With a caller: keep forwarding the bearer. The org comes from the token on the receiving side.
- Without a caller (background): forward a short-lived **internal context token**
  (HMAC-signed, `{orgId, actor: "system:<job>", exp ≤ 60s, aud: "eddi-loopback"}`) in header
  `X-EDDI-Internal-Context`, accepted **only** from loopback addresses and only with a
  valid signature. Key: per-deployment, held next to `ClusterMessageSigner`'s.
- Preferred long-term: replace loopback calls with in-process calls. `ResourceAccessGuard`
  and the MCP tools already hold injected `IRest*Store` beans for most paths.

---

## 5. Persistence enforcement

### 5.1 What every stored record gets

- Field `orgId` (Mongo) / column `org_id TEXT NOT NULL` (Postgres) on **every**
  collection/table holding org data. Appendix A lists them.
- Platform-only data (Appendix B, "platform" rows) has **no** `orgId` and is reachable only
  inside `OrgScope.platform`.
- Values are stamped by the store layer from `OrgContext.require()`. **A caller-supplied
  `orgId` in a document is overwritten, never trusted** (imports, REST bodies, ZIPs).

### 5.2 Generic resource SPI (configuration resources, 31 files)

- `IResourceStorageFactory.create(...)` keeps its signature. Mongo and Postgres factories
  return storages that **always** add `orgId = OrgContext.require()` to every
  `read/readHistory/store/createNew/remove/removeAllPermanently/findResources/...`
  filter, and stamp it on writes. Same for `DescriptorStore` (descriptors carry `orgId`).
  `HistorizedResourceStore`, `ModifiableHistorizedResourceStore` and
  `AbstractResourceStore` need no change if the storage enforces it. Verify by test.
- `DocumentDescriptorStore.findByOriginId(...)` becomes org-scoped (closes leak 7).
- Substring search (`ISubstringSearchIndexing`, `PostgresSubstringSearchIndexes`) filters by org.

### 5.3 Direct stores (~100 classes, Appendix A.2/A.3)

Do **not** build a "MongoCollection view that rewrites filters". It cannot cover
aggregations, `$lookup`, `$unionWith`, bulk writes, change streams or GridFS correctly.
Instead:

- **Mongo:** `OrgCollections` helper with explicit methods:
  `filter(OrgId, Bson)`, `stamp(OrgId, Document)`, `matchStage(OrgId)` (first stage of every
  pipeline), `gridFsMetadata(OrgId, …)`. Every direct store method takes `OrgId org` as
  its **first parameter**, internally or through its interface, so a missing org is a
  compile error. Interfaces (`IUserMemoryStore`, `IConversationMemoryStore`, …) gain the
  parameter, and callers pass `OrgContext.require()`.
- **Postgres:** `OrgJdbc` helper:
  `inTx(OrgId, SqlWork<T>)` opens a connection, `setAutoCommit(false)`,
  `SELECT set_config('eddi.org_id', ?, true)`, runs the work, commits, and restores
  autocommit on return to the pool. Every SQL statement in a store adds
  `AND org_id = ?` (app filter, always) and runs inside `inTx` (needed for RLS).
- Unique constraints/upsert keys gain `org_id` as the first column (e.g. user memory
  `(org_id, user_id, agent_id, key, visibility)`; connection names; email ledger; OAuth state).

### 5.4 Postgres Row-Level Security (backstop)

Introduced in Phase 5, after the app filter is proven (§11):

- Role split: `eddi_migrator` (DDL, owns tables) and `eddi_app` (DML, `NOBYPASSRLS`).
  Config: `eddi.datastore.postgres.migration-username/-password` (falls back to the main
  credentials in single mode, where RLS is off by default).
- Per table: `ALTER TABLE t ENABLE ROW LEVEL SECURITY; ALTER TABLE t FORCE ROW LEVEL SECURITY;`
  `CREATE POLICY org_isolation ON t USING (org_id = current_setting('eddi.org_id', true))
   WITH CHECK (org_id = current_setting('eddi.org_id', true));`
  `current_setting(..., true)` returns NULL when unset, so **no rows** come back: fail closed.
- Platform access: a separate policy for `current_setting('eddi.platform', true) = 'on'`,
  set only by `OrgScope.platform` inside `OrgJdbc`.
- Indexes: **never rewrite PKs.** Add `CREATE INDEX CONCURRENTLY … (org_id, <existing leading cols>)`.
  `resources`/`resources_history` get `(org_id, collection_name, id)`.
- PgBouncer: transaction pooling only. `set_config(..., true)` is transaction-local, so this is safe.
- Measure: one extra round trip per transaction. The benchmark is part of the Phase 5 exit criteria.
- pgvector pools (`PgVectorConnectionPools`) use `OrgJdbc` semantics too, or a per-org table (§5.7).

### 5.5 MongoDB

- Pooled tier: `orgId` field, **leading** in every compound index (replace indexes in a
  background, resumable migration; build new, then drop old), future shard-key prefix
  `{orgId: 1, _id: 1}` (hashed `orgId` if orgs are very uneven).
- Do not rely on "absent `orgId` = default" beyond the migration window. It defeats index
  prefixes. Backfill stamps `default` (§8).
- GridFS (`GridFsAttachmentStore`): `metadata.orgId`, index `(metadata.orgId, metadata.conversationId)`.
- Dedicated tier (Phase 7): `OrgDatabaseRouter` replaces the single `MongoDatabase` bean
  (`PersistenceModule`). Stores resolve collections per call (`router.db(org).getCollection(name)`)
  and cache handles per org. Database-per-org does **not** scale to thousands of orgs
  (~60 collection names plus `.history` and their indexes, each a WiredTiger file), so it
  is reserved for at most tens of dedicated orgs.

### 5.6 Caches (39 direct Caffeine builders + `ICacheFactory` + `ClusterInvalidatingCache` + `SharedKvCache`)

- Audit each cache in Appendix A.5 and classify: **org data** (key must include `OrgId`),
  **platform data** (no change, document why), **id-keyed with globally unique ids**
  (still prefix with org: a cached object fetched by one org must not be served to another,
  even by id).
- Introduce `OrgKey<K>(OrgId org, K key)` and use it as the key type. Make
  `ClusterInvalidatingCache` / `SharedKvCache` / NATS KV keys `<org>.<key>`.
- Build guard: new `Caffeine.newBuilder()` outside an allow-list fails the build.

### 5.7 RAG / vector stores

- Physical names are **derived**, not chosen: `eddi_<org>_<ragConfigId>` (sanitised,
  length-capped, hashed if too long). Author-supplied `collectionName`/`tableName`/
  `databaseName`/`indexName` are refused in multi mode unless the caller is a platform admin
  and the store is org-dedicated. In single mode, existing names keep working (the
  `storeNamespace` mechanism from 6.6 already shows how to migrate layouts; see
  [`docs/rag.md`](../docs/rag.md#knowledge-base-isolation)).
- External vector providers configured per org with the org's own credentials, never a
  platform-shared credential that can address arbitrary indexes.

### 5.8 NATS / cluster

- Message header `Eddi-Org` on every org-scoped message, included in the HMAC
  (`ClusterMessageSigner`). Consumers bind the org from it.
- Subjects stay on the common root (subject-per-org explodes consumers). KV/dead-letter/
  tombstone keys become `<org>.<id>`.
- Cluster-admin views (dead letters, diagnose, activity log, `cluster/*` REST) are
  **platform plane**. An org admin sees only entries for its org, filtered by header.
- Mixed-version rollout: `eddi.org.nats.require-org-header=false` first (accept and treat
  as `default`), then `true` (§8).

---

## 6. Identity, roles and the two planes

### 6.1 Resolving the org from identity

`OrgResolver` SPI with two built-in implementations. Choose via `eddi.org.resolver`.

| Resolver | How | When |
|---|---|---|
| `keycloak-organizations` (**default for multi**) | One realm, Keycloak 26 Organizations. Claim `organization` (configurable `eddi.org.claim`), mapped to `OrgId` through the `Org.idpBinding`. A user in several orgs selects one via `X-EDDI-Org`, validated against the claim | SaaS, platform-managed users |
| `issuer` | One Quarkus OIDC tenant (issuer) per org, via a `TenantConfigResolver` reading `Org.idpBinding.issuer`. The org = the issuer that validated the token | BYO-IdP enterprise customers |
| `single` (single mode) | Always `default` | default |

Missing or unknown org claim in multi mode: **403**, logged `[SECURITY]`. Never a fallback to `default`.
Host cross-check (optional `eddi.org.host-binding=true`): the `Host` must map to the same org.

### 6.2 Principal namespacing

- Every stored principal becomes the pair `(orgId, subject)`. Store `orgId` in its own field.
  **Do not** string-prefix principals. Ownership, grants, user memories, directory entries,
  conversation `userId` and HITL `decidedBy` are compared as pairs.
- `ReservedPrincipals` stays. In multi mode the subject is additionally checked to be
  unique only within the org.

### 6.3 Roles

| Plane | Roles | Scope |
|---|---|---|
| Platform | `eddi-platform-admin`, `eddi-platform-operator`, `eddi-metrics` | the cluster: orgs, quotas, cluster admin, platform catalog, metrics, platform logs. **No customer content** unless break-glass |
| Org | `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer`, `eddi-approver` (unchanged names) | only inside the caller's org |

- Platform roles come from a separate client or realm (recommended), require MFA, and are
  never mapped from an org's IdP. `issuer` resolver: platform roles are accepted only from
  the platform issuer.
- **Break-glass:** `POST /platform/orgs/{orgId}/access` with reason and TTL (≤ 4h) yields a
  scoped grant. Every action under it is written into the **target org's** audit ledger and
  the platform ledger.
- Single mode: `eddi-admin` implies the platform roles. Behaviour is identical to today.
- `RestTenantQuota` (quotas) becomes platform plane. An org admin may read its own usage.
- Keep role checks literal (`hasRole`, no hierarchy). `McpRoleTierParityTest` must be
  extended with the platform tier.

### 6.4 Workspaces inside an org

- Spaces, visibility and grants work unchanged, evaluated within the org.
- `internal` = signed-in members **of the same org**. `published` = anonymous callers on that
  org's public endpoints (agent's org, optional host binding).
- User directory (`engine/security/spaces/directory/*`): entries carry `orgId`. Search and
  exact lookup are org-local. An admin sees only its org.
- `legacy-visibility=shared` applies to legacy resources **within the org** (after backfill,
  all legacy data is in `default`).
- The setup API's unowned resources are stamped with the caller's org.

### 6.5 Platform Operator and MCP server

- Two Operator profiles: **org operator** (allow-list without `administration/logs`,
  `cluster/*`, quotas write, platform endpoints) and **platform operator**. Files:
  `ui/manager/src/lib/operator/tool-scopes.ts`, `system-prompt.ts`. **Bump
  `operator-revision.json`** (`operator-revision.test.ts` enforces it) and follow
  `ui/manager/AGENTS.md` → Platform Operator.
- MCP tools: every tool binds the org. Platform tools (cluster, quotas, org admin) move
  to a platform tier. Update `docs/mcp-server.md#role-mapping` and the Claude plugin skills
  (`integrations/claude-plugin/`; `SkillToolReferencesTest`).

---

## 7. Single-tenant mode contract (must never regress)

| Aspect | Single mode (`eddi.org.mode=single`, **default**) | Multi mode (`eddi.org.mode=multi`) |
|---|---|---|
| Config needed | none | `eddi.org.mode=multi`, `eddi.org.resolver`, platform role setup, `eddi.security.ssrf-protection.enabled=true` (**startup refuses multi mode without it**), `eddi.vault.master-key` |
| Org resolution | constant `default` | `OrgResolver` (§6.1) |
| Unbound context | falls back to `default`, WARN once per call site, counter `eddi_org_context_fallback_total{site}` | `OrgContextMissingException` (fail closed) |
| Roles | today's roles. `eddi-admin` = platform + org admin | split (§6.3) |
| Postgres RLS | off by default (`eddi.org.postgres-rls=off`), opt-in | on (startup refuses `multi` + `off` unless `eddi.org.postgres-rls=off-i-understand`) |
| App filter | **on** (always `org_id = 'default'`) | on |
| UI | no org UI at all | org switcher (if multi-org user), platform console for platform roles |
| Performance | one extra indexed equality predicate. Phase 3 exit criterion: ≤ 3% p95 regression on `scripts/loadtest` | same |
| Upgrade | automatic, background backfill, no manual step (§8) | — |
| Switch single → multi | set the mode, existing data = org `default` (rename by creating an alias display name, the id stays `default`) | — |

**CI:** the full unit suite runs in single mode. A `MultiOrgConformanceIT` (§10) plus a
chosen subset of ITs runs in multi mode with two orgs on both Mongo and Postgres, on every PR.

Existing properties: `eddi.tenant.default-id` becomes a deprecated alias of
`eddi.org.default-id` (default `default`). Changing it in multi mode is refused.

---

## 8. Migration and rolling upgrades

Three releases. A mixed-version cluster is always valid.

| Release | Writes | Reads | NATS | DB |
|---|---|---|---|---|
| **N** (Phase 1) | stamp `orgId` on all new writes, everywhere | unchanged (tolerant: absent = default) | send `Eddi-Org` header, don't require it | add nullable column / no Mongo change |
| **N+1** (Phases 2–3) | stamp | **filter enforced**: `orgId = current` with a temporary `OR orgId missing` only for `default` until backfill reports complete | still tolerant | background backfill job (`OrgBackfillMigration`): resumable, idempotent, batched (e.g. 1,000 docs, configurable, throttled), progress in `migrationlog`. Postgres: `UPDATE … SET org_id='default' WHERE org_id IS NULL` in batches by PK range. Then new composite indexes `CONCURRENTLY` / Mongo background index builds |
| **N+2** (Phase 5) | stamp | strict (no missing-tolerance) | `require-org-header=true` | `org_id NOT NULL` (Postgres: add `CHECK … NOT VALID`, then `VALIDATE`, then `SET NOT NULL`), RLS on in multi mode, old indexes dropped |

- The backfill runs in `OrgScope.platform("migration:org-backfill", …)` and uses the existing
  `MigrationManager` / `PostgresMigrationManager` with a cluster lease (`NatsLeaseManager`)
  so only one node runs it.
- Large collections: no full-collection rewrite in a single transaction, no blocking index
  builds. The backfill must survive restarts.
- Imports of old ZIPs: no `orgId` inside, stamped with the importer's org.
- Rollback: release N+1 can be rolled back to N (old nodes ignore `orgId`). After N+2,
  rollback only to N+1.

---

## 9. Feature-by-feature rules

| Area | Rule | Main files |
|---|---|---|
| Conversation memory and snapshots | `orgId` on snapshot and descriptor. Reads by id filter by org. `returnDetailed` unchanged | `engine/memory/*`, `PostgresConversationMemoryStore` |
| User memories, Dream, lessons | key `(orgId, userId, …)`. Dream/lesson consolidation iterate per org with per-org cost ceilings | `configs/properties/*`, `engine/runtime/internal/DreamService.java`, `LessonConsolidationService.java` |
| Attachments | `metadata.orgId`. Per-org size caps | `GridFsAttachmentStore`, `PostgresAttachmentStore` |
| Audit ledger | **per-org HMAC key** (derived via HKDF from the platform audit key + `orgId`, or org KEK-wrapped) and **per-org chain**. Platform ledger separate. Exports are per org and verifiable with that org's key only | `engine/audit/*` (`AuditKeyring`, `AuditStore`, `PostgresAuditStore`) |
| DB logs | `orgId` on entries. Org admins see their org. Platform sees all via platform scope | `engine/runtime/DatabaseLogs.java`, `PostgresDatabaseLogs` |
| Secrets vault | key `(orgId, vaultTenant, keyName)`. **Per-org KEK**: org DEKs wrapped by the org KEK, the org KEK wrapped by the platform master key (later: per-org KMS key, BYOK). AAD includes `orgId`. Org deletion destroys the org KEK (crypto-shredding). Today's KEK = org `default` | `secrets/*` |
| Global variables, prompt snippets, branding, alert rules, model prices | per org. A platform catalog provides read-only defaults (§9.1) | respective stores |
| Connections, OAuth state, grants | `orgId` on all. The OAuth callback resolves the org from the stored state, never from the request | `connections/*`, `configs/connections/*` |
| Channels (Slack, webhook, email) | integration record carries `orgId`. The inbound org comes from the verified record | `integrations/*`, `configs/channels/*` |
| HITL, oversight, approvals, Slack approval records | `orgId`. Approver must be in the same org | `engine/hitl/*`, `integrations/slack/hitl/*` |
| Groups, team budgets, handoffs, shared artifacts, task lists | `orgId`. All members, moderator and facilitator must be agents of the same org (validated at authoring and run) | `engine/groups/*`, `configs/groups/*` |
| Sub-agents / dynamic agents | inherit the parent conversation's org | `modules/llm/impl/*ToolsProvider` |
| Schedules, triggers | `orgId`. A fire binds it | `engine/schedule/*`, `engine/triggermanagement/*` |
| Import/export, agent sync, upgrade executor | export carries no `orgId`. Import stamps the importer's org. `originId` matching is org-scoped. Instance-to-instance sync authenticates into one org | `backup/impl/*`, `engine/setup/ApiAgentSyncService.java` |
| GDPR erase/export | scoped to org + subject. Org admin may act for its org only | `engine/gdpr/*` |
| Agent tests, ratings, outcomes, spend control changes | `orgId` | respective packages |
| Templating | `{vars}`/`{snippets}`/`${vault:…}`/`${vars:…}`/`${connection:…}` resolve within the current org. A reference cannot name another org (there is no syntax for it, keep it that way) | `modules/templating/*`, `configs/variables/GlobalVariableResolver.java`, `secrets/SecretResolver.java` |
| Outbound HTTP / SSRF | multi mode forces `eddi.security.ssrf-protection.enabled=true` and adds a non-overridable deny for self addresses, the NATS URL, the DB host(s), the Keycloak admin API and cloud metadata. Allow-list entries (`eddi.security.ssrf-protection.allowed-hosts` or equivalent) are platform config, per org override only by platform admin | `engine/httpclient/outbound/*`, `UrlValidationUtils`, `modules/apicalls/*`, MCP client, RAG |
| LLM provider keys | multi mode: **per-org keys** (in the org vault) are the default. Platform-provided keys only through an explicit platform "provider pool" with per-org rate buckets, per-org spend attribution, and prompt caching namespaced by org where the provider allows it | `modules/llm/*`, `engine/pricing/*` |
| Metrics / tracing | tag `org` only when `eddi.org.metrics.tag-org=true` (default false in multi mode above `eddi.org.metrics.max-tagged-orgs`, default 200). Per-org usage is exposed through the quota/usage API, not Prometheus. Spans carry `eddi.org` as an attribute. `/q/metrics` is platform plane | `engine/tenancy/TenantQuotaService.java`, metrics classes |
| Errors | no other org's ids, names or counts in error text | everywhere |

### 9.1 Platform catalog (shared, read-only to orgs)

Model price catalog defaults, documentation, built-in group templates, built-in Operator
definition, default agents/templates, branding defaults. Stored without `orgId`, written
only by platform admins, read by any org. Orgs can **override** with their own
org-scoped copy (an org price table, org templates). Resolution order: org first, then platform.

---

## 10. Verification — what makes it "super secure"

1. **`MultiOrgConformanceIT`** (Testcontainers, Mongo **and** Postgres, multi mode, two orgs
   A/B with users of every role):
   - As A, create one of every resource type, a conversation, a memory, an attachment,
     a secret, a schedule, a channel integration, a group conversation, an audit entry.
   - As B (every role, plus anonymous), call **every** REST operation from the OpenAPI
     document and **every** MCP tool with A's ids. Expect 404 / empty list / refusal,
     never A's data. The operation list is generated from `/openapi` and the MCP tool
     registry, so a **new endpoint without a test case fails the build**. Explicit,
     reviewed exemptions live in one file.
   - Channel inbound with A's signing secret cannot address B's agents.
2. **Build guards** (JUnit, style of `ImportStyleTest`/`BuildQualityGatesTest`), report-only
   in Phase 1, failing from Phase 3:
   - No `MongoDatabase`/`MongoCollection`/`DataSource`/`getConnection()` outside
     `datastore/**` and the allow-listed store classes, and each allow-listed store
     method that touches the DB takes an `OrgId`.
   - No `Caffeine.newBuilder()` outside an allow-list with a written classification.
   - Every JAX-RS resource method and MCP tool is `@OrgBound` or `@PlatformPlane`.
   - Every `@Scheduled` method uses `OrgIterator` or is `@PlatformPlane`.
   - Every class that persists a "work starter" (list in §9) has an `orgId` field.
   - `OrgScope.platform(` is used only in allow-listed classes.
   - No new use of the word `tenant` in new `eddi.org.*` keys or `Org*` classes.
3. **Mutation checks:** remove the org predicate in each storage/helper, and the conformance
   suite must go red. Keep a scripted check (cf. memory "mutation-check behavioural tests").
4. **RLS backstop test:** disable the app filter in a test profile and run the conformance
   suite on Postgres with RLS: still green.
5. **Single-mode regression:** the full existing unit and IT suites stay green in single mode.
   `scripts/loadtest` p95 within 3%.
6. **Threat model** document `docs/multi-org-security.md` (STRIDE per entry point) and an
   **external pentest** before the first external org goes live.

---

## 11. Phases — tasks, files, exit criteria

Each phase is one or more PRs. Every PR: branch from `origin/main` (`feat/org-…`), changelog
fragment under `docs/changelog.d/`, docs updated, `./mvnw test` green, both modes in CI.
**Do not start a phase before the previous one's exit criteria hold.**

### Phase 0 — ADR and threat model (docs only)
- `docs/adr/` (create if absent) `0001-organizations.md`, locking the §12 decisions marked LOCK.
- `docs/multi-org-security.md` threat model draft.
- Exit: the product owner approves the ADR.

### Phase 1 — Org context everywhere, constant `default` (no behaviour change)
1. `engine/org/`: `OrgId`, `OrgContext`, `OrgScope`, `OrgPropagation`, `OrgIterator`,
   `OrgContextMissingException`, `OrgConfig` (`eddi.org.mode`, `eddi.org.default-id`,
   alias `eddi.tenant.default-id`).
2. Interceptor `@OrgBound` + `OrgRequestFilter` resolving `default` (single resolver only).
3. Bind the org at every entry point of §4.2 (single mode: always `default`).
4. Wrap every hand-off (§4.3, Appendix A.9).
5. Replace all 22 `getDefaultTenantId()` call sites (Appendix A.6) with `OrgContext.require().value()`.
   `TenantQuotaService.getDefaultTenantId()` becomes `@Deprecated`.
6. Add `orgId` fields to all work-starter models (§9) and **stamp on write** in every store
   (Mongo: field. Postgres: nullable `org_id` column added with `ADD COLUMN IF NOT EXISTS`).
   Reads unchanged.
7. NATS: send the `Eddi-Org` header (signed). Accept missing.
8. Build guards in **report-only** mode (print violations, don't fail).
9. Docs: `docs/configuration-reference.md` (new keys), changelog fragment.
- Exit: all suites green in single mode. Stamp coverage report: every collection/table in
  Appendix B shows `orgId` on new writes (a test that writes one of each and inspects raw DB).
  Fallback counter stays at 0 in the IT suite.

### Phase 2 — Backfill + conformance suite (red is expected)
1. `OrgBackfillMigration` (Mongo + Postgres) per §8, with lease, batching and progress.
2. `MultiOrgConformanceIT` per §10.1, plus an `OrgTestProfile` with two orgs and a fake
   `OrgResolver` that reads the org from a test claim.
3. CI job `Multi-Org Conformance` (non-required, reports).
- Exit: backfill proven on a copy of a large dataset (≥ 10M conversation docs on Mongo,
  measured duration and DB load documented). Conformance suite runs and lists every leak.

### Phase 3 — Enforce the app filter
1. SPI enforcement (§5.2), `findByOriginId` scoped.
2. Direct stores: `OrgCollections` / `OrgJdbc`, `OrgId` parameter on store interfaces
   (§5.3, Appendix A.2/A.3). Do it package by package, one PR each.
3. Caches (§5.6), NATS keys and consumer binding (§5.8).
4. RAG physical naming (§5.7).
5. Multi mode refuses to start with SSRF protection off. Add the self/infra deny list (§9).
6. Loopback internal context token (§4.4).
7. Indexes with `orgId` leading (background builds).
8. Build guards switch to **failing**.
- Exit: conformance suite green for data access (identity tests may still be pending),
  RLS-less mutation checks red as expected, single-mode load test within 3%.

### Phase 4 — Identity, roles, planes
1. `OrgResolver` SPI + `keycloak-organizations` + `issuer` resolvers (§6.1), `X-EDDI-Org` selection.
2. `Org` record + `IOrgStore` (Mongo/Postgres, platform plane): id, displayName, status
   (`ACTIVE`/`SUSPENDED`/`DELETING`), tier (`POOLED`/`DEDICATED`), idpBinding, createdAt, quotas ref, kmsKeyRef.
   Suspended orgs get 403 at the resolver.
3. Principal pairs (§6.2): ownership, grants, directory, memories, HITL deciders.
4. Platform roles + break-glass (§6.3). Quotas move to the platform plane.
5. Workspaces nested (§6.4).
6. Per-org `/v1` keys, channel/A2A binding (§4.2).
7. Operator split + revision bump, MCP tiers, plugin skills (§6.5).
8. Keycloak realm changes: `keycloak/eddi-realm.json` gets an example org, an Organizations
   mapper and a platform client. Document it in `docs/multi-org.md` (new).
- Exit: conformance suite fully green (including identity, roles, anonymous, channels,
  `/v1`, A2A). `McpRoleTierParityTest` extended and green.

### Phase 5 — Crypto, RLS, fairness
1. Per-org KEK hierarchy in the vault + AAD with `orgId` (§9 vault row). Migration rewraps
   existing DEKs under org `default`'s KEK.
2. Per-org audit key + chain (§9 audit row).
3. Postgres RLS (§5.4) with the role split, plus `org_id NOT NULL` (release N+2 of §8).
4. Fairness: per-org concurrent-turn semaphore in the conversation coordinator, per-org LLM
   and tool rate buckets, scheduler round-robin, per-org NATS consumer share, storage caps.
   Wire `TenantQuotaService` limits per org.
- Exit: RLS backstop test green. Benchmark documented. A noisy-neighbour test (org A at
  10× load) keeps org B's p95 within its SLO.

### Phase 6 — Org lifecycle and platform console
1. REST `/platform/orgs` (create, suspend, resume, delete, export) + MCP platform tools.
2. Create: org record, KEK, quota row, default branding/catalog links, IdP binding
   (optional Keycloak admin API integration behind a feature flag).
3. Export: full logical org bundle (configs via the agent export format, conversations,
   memories, attachments, audit chain, secrets metadata, never values) for offboarding
   or moving to option A.
4. Delete: `DELETING` → destroy the org KEK (crypto-shred) → asynchronous purge of all rows
   per Appendix B → deletion certificate in the platform ledger.
5. Manager: platform console (org list, status, quotas, usage). Org admins see their org
   settings. Workforce: org switcher for multi-org users. Read `ui/manager/AGENTS.md` and
   `ui/workforce/AGENTS.md` first.
- Exit: create → use → export → delete round trip in an IT, and nothing of the org is left in
  any collection/table (a test sweeps Appendix B).

### Phase 7 — Dedicated tier
1. `OrgDatabaseRouter` (Mongo database per org / Postgres schema or database per org),
   replacing the single `MongoDatabase` bean and `DataSource` usage behind `OrgJdbc`.
2. Move org POOLED → DEDICATED online (copy, dual-read window, cutover) and back.
- Exit: conformance suite green with one org pooled and one dedicated.

**Smallest valuable first slice:** Phase 1 alone. No read path changes, so it is low risk,
and it makes every later phase mechanical.

---

## 12. Decisions

LOCK = decide in the Phase 0 ADR. These are expensive to change later.

| # | Decision | Recommendation | Status |
|---|---|---|---|
| 1 | Org id format and principal shape | opaque immutable slug in its own field. Principals are `(orgId, subject)` pairs, never string-prefixed | LOCK |
| 2 | Storage key shape | `orgId` leading in Mongo indexes and shard key, new composite indexes in Postgres (no PK rewrite), globally unique ids, 404 across orgs | LOCK |
| 3 | Context contract | `ScopedValue` + explicit hand-off + `OrgId` parameter on direct-store APIs. Fail closed in multi, `default` fallback in single. One `OrgScope.platform` escape. Three-release protocol | LOCK |
| 4 | Platform vs org plane | as §6.3. Written list of what an org admin can never see: other orgs, platform logs, cluster admin, metrics, platform catalog writes | LOCK |
| 5 | Trust model for author-supplied infrastructure | SSRF forced on in multi, RAG names derived, per-org LLM keys by default, one `OrgResolver` SPI | LOCK |
| 6 | Default IdP model | Keycloak Organizations. `issuer` resolver for BYO-IdP | recommend |
| 7 | KMS / BYOK | key hierarchy now, external KMS later | recommend |
| 8 | Dedicated tier threshold | an org moves when it exceeds the platform's configured size/regulation criteria. Not automatic | open |
| 9 | Cross-org features (marketplace, cross-org A2A) | none. Cross-org A2A only as external peers over the public A2A endpoint | recommend |
| 10 | Data residency | add a `region` field to `Org` now (unused), so nothing has to change later | recommend |

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| A missed store or hand-off leaks data | fail-closed context, build guards, generated conformance suite, RLS backstop, mutation checks |
| Single-mode regression or slowdown | always-on app filter tested in single mode, load-test gate, `default` fallback |
| Backfill overload on large databases | batched, throttled, resumable, leased. Measured in Phase 2 |
| Mixed-version cluster during upgrade | three-release protocol (§8), header tolerance switch |
| Postgres RLS cost | measured gate in Phase 5. RLS is a backstop, the app filter does the work |
| Scope creep into the UI | backend phases 1–5 need no UI change except the Operator split |
| Naming confusion (tenant vs org) | §1 rules, build guard on new keys/classes |

---

## Appendix A — inventories (regenerate before use)

Paths relative to `src/main/java/ai/labs/eddi/`. Commands run from that directory.

**A.1 Storage SPI users** (`grep -rl IResourceStorageFactory --include=*.java .`, 31):
configs/agents/mongo/AgentStore · configs/agenttests/mongo/AgentTestSuiteStore ·
configs/apicalls/mongo/ApiCallsStore · configs/channels/mongo/ChannelIntegrationStore ·
configs/connections/mongo/ConnectionStore · configs/descriptors/mongo/DocumentDescriptorStore ·
configs/dictionary/mongo/DictionaryStore · configs/groups/IGroupConversationStore ·
configs/groups/mongo/{AgentGroupStore, GroupConversationStore, GroupWorkspaceStore, SharedArtifactStore} ·
configs/llm/mongo/LlmStore · configs/mcpcalls/mongo/McpCallsStore · configs/output/mongo/OutputStore ·
configs/parser/mongo/ParserStore · configs/propertysetter/mongo/PropertySetterStore ·
configs/rag/mongo/RagStore · configs/rules/mongo/RuleSetStore · configs/snippets/mongo/PromptSnippetStore ·
configs/workflows/mongo/WorkflowStore · datastore/{AbstractResourceStore, DataStoreProducers,
DescriptorStore, IResourceStorage, IResourceStorageFactory, ISubstringSearchIndexing} ·
datastore/mongo/MongoResourceStorageFactory · datastore/postgres/PostgresResourceStorageFactory ·
engine/agenttests/AgentTestRunStore · engine/memory/ConversationDescriptorStore

**A.2 Direct Mongo** (`grep -rl "MongoCollection<" --include=*.java .`, 48):
configs/branding/mongo/BrandingStore · configs/connections/names/MongoConnectionNameClaimStore ·
configs/deployment/mongo/MongoDeploymentStorage · configs/migration/{MigrationLogStore,
MigrationManager, V6QuteMigration, V6RenameMigration} · configs/properties/mongo/{MongoUserMemoryStore,
PropertiesMigrationService, UserMemoryIdentityIndexes} · configs/variables/mongo/GlobalVariableStore ·
connections/grants/MongoConnectionGrantStore · connections/oauth/MongoOAuthStateStore ·
connections/settings/MongoConnectionSettingsStore · datastore/mongo/{GridFsAttachmentStore,
MongoResourceStorage} · engine/alerts/mongo/{AlertEventStore, AlertRuleStore} · engine/audit/AuditStore ·
engine/groups/budget/MongoTeamBudgetStore · engine/groups/lessons/mongo/MongoLessonHistoryStore ·
engine/groups/outcomes/mongo/MongoOutcomeEventStore ·
engine/hitl/oversight/analytics/mongo/MongoApprovalDecisionStore ·
engine/hitl/oversight/autonomy/mongo/MongoAutonomyStateStore ·
engine/hitl/oversight/samples/mongo/MongoAuditSampleStore · engine/hitl/tools/mongo/HitlToolJournalStore ·
engine/memory/{ConversationMemoryStore, MongoConversationCheckpointStore} ·
engine/pricing/mongo/MongoModelPriceCatalogStore · engine/ratings/mongo/ConversationRatingStore ·
engine/runtime/DatabaseLogs · engine/schedule/mongo/MongoScheduleStore ·
engine/security/spaces/directory/MongoUserDirectoryStore ·
engine/security/spaces/notifications/MongoWorkspaceNotificationStore ·
engine/security/spaces/settings/MongoWorkspaceSettingsStore ·
engine/spendcontrol/mongo/MongoSpendControlChangeStore · engine/tenancy/MongoTenantQuotaStore ·
engine/triggermanagement/mongo/{AgentTriggerStore, UserConversationStore} ·
engine/updatecheck/MongoSystemSettingStore · integrations/email/ledger/{MongoEmailMailboxStateStore,
MongoEmailMessageLedger, MongoEmailSuppressionStore} · integrations/slack/hitl/MongoSlackApprovalRecordStore ·
modules/ingestion/mongo/{MongoIngestedFileStore, MongoIngestionStateStore} ·
modules/llm/tools/quota/MongoToolQuotaStore · secrets/persistence/MongoSecretPersistence

**A.3 Direct Postgres** (`grep -rlE "\bDataSource\b" --include=*.java .`, ~52):
configs/connections/names/PostgresConnectionNameClaimStore · connections/grants/PostgresConnectionGrantStore ·
connections/oauth/PostgresOAuthStateStore · connections/settings/PostgresConnectionSettingsStore ·
datastore/postgres/{PostgresAgentTriggerStore, PostgresAlertEventStore, PostgresAlertRuleStore,
PostgresApprovalDecisionStore, PostgresAttachmentStore, PostgresAuditSampleStore, PostgresAuditStore,
PostgresAutonomyStateStore, PostgresBrandingStore, PostgresConversationCheckpointStore,
PostgresConversationMemoryStore, PostgresConversationRatingStore, PostgresDatabaseLogs,
PostgresDeploymentStorage, PostgresFieldIndexSwaps*, PostgresGlobalVariableStore, PostgresHealthCheck*,
PostgresHitlToolJournalStore, PostgresIngestedFileStore, PostgresIngestionStateStore,
PostgresLessonHistoryStore, PostgresMigrationLogStore*, PostgresMigrationManager*,
PostgresModelPriceCatalogStore, PostgresOutcomeEventStore, PostgresResourceStorage,
PostgresResourceStorageFactory, PostgresScheduleStore, PostgresSpendControlChangeStore,
PostgresStartupSchemaLock*, PostgresSubstringSearchIndexes, PostgresUserConversationStore,
PostgresUserMemoryStore} · engine/groups/budget/PostgresTeamBudgetStore ·
engine/security/spaces/directory/PostgresUserDirectoryStore ·
engine/security/spaces/notifications/PostgresWorkspaceNotificationStore ·
engine/security/spaces/settings/PostgresWorkspaceSettingsStore · engine/tenancy/PostgresTenantQuotaStore ·
engine/updatecheck/PostgresSystemSettingStore · integrations/email/ledger/{PostgresEmailMailboxStateStore,
PostgresEmailMessageLedger, PostgresEmailSuppressionStore} ·
integrations/slack/hitl/PostgresSlackApprovalRecordStore · modules/llm/impl/{EmbeddingStoreFactory,
PgVectorConnectionPools, PgVectorPoolHandle} · modules/llm/tools/quota/PostgresToolQuotaStore ·
secrets/persistence/PostgresSecretPersistence
(* = infrastructure helper, platform plane. No `org_id` filter, but still behind the guard allow-list.)

**A.4 Loopback REST** (`grep -rl IRestInterfaceFactory --include=*.java .`, 12): backup/impl/StructuralMatcher ·
configs/agents/AgentCascadeService · configs/rest/RestVersionInfo · engine/mcp/{McpAdminTools,
McpConversationTools, McpToolUtils} · engine/runtime/client/factory/{CurrentVersionRouting,
IRestInterfaceFactory, RestInterfaceFactory} · engine/security/spaces/ResourceAccessGuard ·
engine/setup/{AgentSetupService, ApiAgentSyncService}

**A.5 Direct Caffeine** (`grep -rl "Caffeine.newBuilder" --include=*.java .`, 39): configs/agents/{AgentConfigurationCache,
AgentSigningService} · configs/branding/BrandingService · configs/variables/GlobalVariableResolver ·
connections/ConnectionRegistry · engine/a2a/AgentCardService · engine/alerts/AlertRuleCache ·
engine/audit/AuditAgentSigner · engine/caching/CacheFactory · engine/cluster/ClusterMessageSigner ·
engine/hitl/oversight/analytics/ApprovalAnalyticsService · engine/hitl/oversight/autonomy/AutonomyOverlayService ·
engine/internal/TurnIdempotencyService · engine/internal/groups/GroupSigningGuard · engine/mail/MailDnsResolver ·
engine/ratings/ConversationRatingPolicy · engine/security/ConversationReviewPolicy ·
engine/security/spaces/directory/UserDirectory · engine/security/spaces/notifications/WorkspaceNotifications ·
integrations/email/reply/EmailReplySender · integrations/openai/AgentModelResolver ·
integrations/webhook/WebhookEventHandler · modules/llm/guardrails/impl/ExpressionGuardrail ·
modules/llm/impl/{ChatModelRegistry, ContextualToolsProvider, EmbeddingModelFactory, EmbeddingStoreFactory,
LlmCircuitBreakers, PgVectorConnectionPools, PromptSnippetService, TokenCounterFactory, WorkflowTraversal} ·
modules/llm/tools/ToolRateLimiter · modules/nlp/impl/RestSemanticParser · modules/rag/RagIngestionService ·
modules/templating/impl/TemplatingEngine · secrets/SecretResolver · ui/{BrandedShellRenderer, BrandedSwaggerUiRoute}
Note: `WorkflowTraversal` has a static, agentId-keyed memo. `ChatModelRegistry` and
`EmbeddingModelFactory` cache models built with **credentials**, so they must be org-keyed.

**A.6 `getDefaultTenantId()` call sites** (22): engine/groups/budget/TeamBudgetService ·
engine/internal/ConversationTitleService (×2) · engine/internal/RestAgentAdministration (×2) ·
engine/pricing/SystemSpendMeter (×3) · engine/runtime/internal/DreamService (×3) ·
engine/runtime/internal/LessonConsolidationService (×2) · engine/spendcontrol/SpendControlChangeService ·
engine/tenancy/TurnCostMeter (×2) · modules/llm/impl/LlmSpendGate (×3) ·
modules/llm/impl/LoopSideCallSpend · modules/llm/impl/ToolLoopRunner (×2)

**A.7 `@Scheduled`** (20): backup/impl/RestExportService · connections/oauth/OAuthStateMaintenance ·
engine/agenttests/AgentTestRunRecovery · engine/groups/budget/TeamBudgetMaintenance ·
engine/hitl/{HitlCrashRecoveryObserver, HitlPendingMetrics} · engine/hitl/oversight/OversightMetrics ·
engine/hitl/tools/HitlToolJournalMaintenance · engine/memory/rest/RestConversationStore ·
engine/pricing/ModelPriceCatalogService · engine/runtime/DatabaseLogRetention ·
engine/runtime/internal/{AgentDeploymentManagement, SchedulePollerService} · engine/security/AuthStartupGuard ·
engine/updatecheck/UpdateCheckService · integrations/email/{EmailInboundSweeper,
notify/HitlEmailNotifier, reply/EmailReplySender} · modules/llm/impl/ChatModelRegistry ·
modules/llm/tools/quota/ToolQuotaMaintenance

**A.8 NATS consumers / KV** (17): configs/variables/GlobalVariableResolver · connections/ConnectionRegistry ·
engine/caching/CacheFactory · engine/cluster/{AuditClusterSupport, NatsSharedKv,
admin/ClusterActivityLog, admin/ClusterWatcher, events/IClusterEventBus, events/JetStreamEventBus,
events/LocalEventBus, lease/NatsLeaseManager, rpc/NatsClusterRpc, ErasureTombstones,
ClusterArchiveStore} · engine/mail/AutoReplyDetector · engine/runtime/internal/{AgentDeploymentManagement,
WorkflowFactory} · engine/userevents/UserEventPublisher · secrets/SecretResolver

**A.9 Hand-off search patterns:**
`grep -rnE "Executors\.new|newVirtualThread|ManagedExecutor|supplyAsync\(|runAsync\(|thenApplyAsync|emitOn\(|runSubscriptionOn\(|Thread\.ofVirtual|StructuredTaskScope" --include=*.java .`

**A.10 Inbound endpoints without a user token:** integrations/openai/RestOpenAiAdapter (`/v1`) ·
integrations/slack/rest/RestSlackWebhook · integrations/webhook/rest/RestWebhookChannel ·
engine/a2a/RestA2AEndpoint (`/a2a/*`, `/.well-known/*`) · email sweeper (A.7) ·
connections OAuth callback (`/connections/callback`) · public chat endpoints (anonymous production).

**A.11 Existing `tenantId` fields** (vault/variables/quota/connection meaning; see §1):
`grep -rlE "String tenantId" --include=*.java .`. About 60 files across `configs/connections`,
`configs/variables`, `connections/*`, `engine/tenancy/*`, `engine/security/spaces/*`,
`engine/pricing/*`, `engine/spendcontrol/*`, attachments and secrets. Each keeps its meaning.
The org is added next to it, not merged into it.

## Appendix B — data classification

| Class | Org-scoped (`orgId` required) | Platform (no `orgId`) |
|---|---|---|
| Configuration resources (agents, workflows, rules, llm, apicalls, mcpcalls, output, parser, dictionary, propertysetter, rag, snippets, groups, channels, connections, agent tests, descriptors) | ✓ | |
| Runtime (conversation memory, descriptors, checkpoints, user conversations, user memories, attachments, ratings, outcomes, lessons, HITL journal, approvals, autonomy, audit samples, schedules, triggers, team budgets, spend control changes, alerts, alert rules, ingestion state/files, tool quota, email ledger, Slack approval records, OAuth state, grants, connection names/settings, workspace settings/notifications, user directory, branding, global variables, secrets, audit ledger, DB logs) | ✓ | |
| Tenant quotas/usage | ✓ (keyed by orgId) | read/write = platform plane |
| Model price catalog | org overrides | platform defaults |
| Migration log, system settings, update check, schema lock, health, cluster archive/activity, NATS leases | | ✓ |
| Org records, org API keys, platform audit ledger, break-glass grants | | ✓ |

Every new collection/table must be added to this table in the PR that creates it.
`MultiOrgConformanceIT` and the deletion sweep (Phase 6) read the list from one Java constant
`OrgDataCatalog`, which a test compares against the actual collections/tables at startup.
