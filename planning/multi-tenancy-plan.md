# Multi-Tenancy Plan — Shared Organizations on One EDDI Cluster

**Status:** Design ready for implementation. Nothing of the org model is built yet. It builds on
shipped work: tenant quotas ([`docs/tenant-quotas.md`](../docs/tenant-quotas.md)), workspaces
([`docs/workspaces.md`](../docs/workspaces.md)) and per-tenant DEKs
([`docs/secrets-vault.md`](../docs/secrets-vault.md)).

**History:**
- 2026-04-17: created.
- 2026-11-19: rewritten.
- 2026-11-21: revised three times after independent reviews. This is the final version.

The 2026-04 "Strategy B" design (a `@RequestScoped TenantContext` with filtering only in the
generic storage SPI) does not work against the current code (§3.2). Do not resurrect it.

**Facts as of** `main` at `985ebb608f` (2026-11-19). Counts drift. Re-run the commands in
Appendix A before relying on any count.

---

## 0. Execution brief: read this first if you are implementing the plan

You are implementing this plan in the EDDI repository: Java 25, Quarkus, MongoDB **or**
PostgreSQL, NATS JetStream, Keycloak 26.8. `AGENTS.md` loads automatically and is binding.
In particular:

- **Branching.** Branch from `origin/main`:
  `git fetch origin main && git checkout -b feat/org-<phase>-<slug> origin/main`.
  If the worktree tool gave you a `claude/*` branch, rename it before the first commit.
- **Push and merge.** Never push without the user's explicit approval. Never force-push.
  Never merge without a completed CodeRabbit review (AGENTS §2 rule 4).
- **Commits.** Stage files by name only. Every commit must build with `./mvnw compile`
  (Windows: `.\mvnw.cmd`).
- **Changelog.** Every PR adds a fragment under `docs/changelog.d/YYYY-MM-DD-<slug>.md`.
  Give its relative links one extra `../`. Never edit `docs/changelog.md` by hand.
- **Attribution.** No AI attribution anywhere.
- **Tests before pushing.** Run the plain `./mvnw test`. Targeted `-Dtest=` runs skip the
  repo-wide guards. Redirect Maven output to a file and check the exit code; never pipe it
  through `grep`.
- **Formatting.** Format with `./mvnw formatter:format`. Checkstyle fails on unused imports
  in `src/main/java`.
- **Reading.** Read [`docs/project-philosophy.md`](../docs/project-philosophy.md), then the
  docs each phase names.

**How to proceed:**

1. Read this whole file once.
2. **Phase 0 is a stop point.** Run the four spikes (§15 Phase 0) and write the ADR (§16
   LOCK items) and the threat model. Present them to the user. Write no production code
   until the user approves.
3. Do the phases strictly in order (§15). **Stop after each phase**, report against its exit
   criteria, and wait for the user. Every item marked **STOP** in this file needs a decision
   from the user before you continue.
4. Keep PRs small, one package each.
5. Single-tenant behaviour must not change in any phase (§5.1). If a change would alter it,
   stop and ask.
6. Where this plan and the code disagree, the code wins. Verify, adapt, and record the
   deviation in the PR description and in the §16 Deviations log.
7. A store, entry point, setting or leak path this plan does not list goes into Appendix A/B
   and §3.3 in the same PR.

---

## 1. Goal, requirements, non-goals

**Goal.** Several customers ("organizations", §2) share one EDDI cluster: the same pods, the
same database server, the same NATS. Each org's data, configuration, secrets, identity, spend
and operations are isolated as if it ran on its own instance. A platform team runs the cluster.

**Hard requirements (from the product owner):**

1. **Super secure.** A missed check in one place must not be enough to leak data between
   orgs. Defense in depth, fail closed, proven by tests.
2. **Zero pain with one tenant.** A deployment that never enables multi-tenancy needs no new
   configuration and no manual migration step. Its roles, URLs, UI, behaviour and
   performance stay the same. The only upgrade-time change is that Phase 1 adds a defaulted
   `org_id` column on PostgreSQL (§5.2), automatically and as a metadata-only change.
   **Both modes must work flawlessly**, and CI tests both on every PR.
3. **Well designed, future-proof, scalable.** Thousands of small orgs share a pooled tier.
   Large or regulated orgs move to a dedicated tier or to their own instance without code
   changes. An existing single instance can be imported as an org.

**Non-goals:**

- cross-org features: marketplace, cross-org groups or memories, cross-org A2A except as
  external peers;
- billing;
- multi-region residency.

Leave the seams open for these (§16).

**Context.** The owner already runs one EDDI instance per customer: a Helm release per
namespace with NetworkPolicy, its own DB, NATS, realm and master key. That stays the top
isolation tier.

---

## 2. Terminology (binding)

"Tenant" already means three things in EDDI, so the customer boundary is called an
**organization (org)**.

| Term | Meaning | Where |
|---|---|---|
| **org** (NEW) | Customer isolation boundary | `ai.labs.eddi.engine.org` |
| **platform** (NEW) | The cluster operator, above all orgs | this plan |
| vault/variables **tenant** | Namespace inside the vault and global variables: `default`, plus per-space `t.<slug>.<hash>` / `u.<slug>.<hash>` | `secrets/model/SecretReference.java`, `engine/security/spaces/SpaceTenants.java` |
| quota **tenant** | Quota/usage counter key, `eddi.tenant.default-id` today | `engine/tenancy/*` |
| Quarkus OIDC **tenant** | An IdP config. `quarkus.oidc.tenant-enabled` means "auth on/off" | `application.properties` |
| **space** | Workspace inside an org: `user:<principal>` / `team:<path>` | `engine/security/spaces/*` |

**Rules:**

- **Naming.** New config keys use `eddi.org.*`. New classes are named `Org*`. Never call
  the org a "tenant".
- **Org ids.** The default org id is the constant `default` and is not configurable.
  - Reserved ids that can never be created: `default` (pre-existing only), `platform`,
    `current`, `system`, `eddi`, `admin`, `api`.
  - Format: `^[a-z0-9][a-z0-9-]{1,62}$`. Ids are immutable and opaque, and never reused
    after deletion.
- **Quota key.** Single mode keeps the configured `eddi.tenant.default-id` as its quota key,
  so nothing is re-keyed (§12).
- **Vault tenants.** The vault tenant stays a namespace inside the org. Storage keys become
  `(orgId, vaultTenant, keyName)`. Reference syntax is unchanged and resolves relative to
  the current org.
- **Deprecated field.** Do **not** reuse `ScheduleConfiguration.tenantId`
  (`@Deprecated(since = "6.8.0")`, never read). Add `orgId` instead.

---

## 3. Current state (verified)

Paths are relative to `src/main/java/ai/labs/eddi/` unless they start with a top-level
directory. `a.p` is short for `src/main/resources/application.properties`.

### 3.1 Reusable foundations

| Piece | Gives | Files |
|---|---|---|
| Workspaces | Owner, space, visibility and grants on descriptors; listings restricted in the query | `engine/security/spaces/ResourceAccessGuard.java` (92 call-site files), `datastore/DescriptorStore.readDescriptorsRestricted` |
| Caller-derived context | `SpaceContext` derives state from `SecurityIdentity` and the current Vert.x request | `engine/security/spaces/SpaceContext.java` |
| Envelope encryption | DEK per vault tenant, AAD `(tenant\|key\|dekId)`, legacy no-AAD values | `secrets/impl/VaultSecretProvider.java` |
| Quotas | Atomic counters keyed by string | `engine/tenancy/*` |
| NATS signing | HMAC over subject, timestamp, nonce and body (**not headers**). Unsigned when no key is set | `engine/cluster/ClusterMessageSigner.java` |
| Cluster presence | Nodes publish version and payload protocol | `engine/cluster/ClusterPresence.java` (writer), `admin/ClusterWatcher.java` (reader) |
| Audit keyring | Records key ids on entries | `engine/audit/AuditKeyring.java` |
| Loopback bearer forwarding | Forwards **only** `Authorization` | `engine/runtime/client/factory/LoopbackCallerAuthFilter.java` |
| Migrations, leases, startup auth guard | | `configs/migration/MigrationManager`, `datastore/postgres/PostgresMigrationManager`, `engine/cluster/lease/*`, `engine/security/AuthStartupGuard.java` |
| Rolling-upgrade tolerance | Jackson ignores unknown fields | `SerializationCustomizer` |

### 3.2 Facts every design choice must respect (code-verified)

| Fact | Consequence |
|---|---|
| **31** files use `IResourceStorageFactory`. **48** use `MongoCollection<>` directly and **51** use `DataSource` directly (A.2/A.3) | Every store is scoped, through helpers (§8) |
| Work off the request thread: executors, virtual threads, 20 `@Scheduled` classes (A.7), NATS classes (A.8), 44 `AsyncResponse` uses, the coordinator `Callable` queues, SSE, `StreamingOutput`, LLM streaming | The org is a value carried explicitly (§6) |
| Code outside resource methods (A.12): the quarkus-mcp-server Vert.x route, raw Vert.x routes, JAX-RS filters (`UserDirectoryRecorder` writes the directory asynchronously on every request), `SecurityIdentityAugmentor`s, 18 `ExceptionMapper`s, response serialisation after the method returns | Binding cannot rely on resource interceptors alone (§6.2) |
| `@RolesAllowed` sits on `IRest*` **interfaces** (~80 of 107). Interceptor bindings on interfaces are not inherited by the 87 implementations | Bindings go on impl classes. Background code never calls `IRest*` (§7.3) |
| `getDefaultTenantId()` has **22** call sites (A.6) | Phase 1 |
| Postgres: 380 `getConnection()` calls, no `@Transactional`, mostly autocommit; 7 files use `setAutoCommit(false)` | RLS through an Agroal pool interceptor (§8.4) |
| DataSource injection: 96 `Instance<DataSource>`, 8 constructor `DataSource`. The 11 `AgroalDataSource` uses are hand-built pgvector pools. `quarkus.datasource.active=false` by default (`a.p:126`), so every Mongo deployment has an inactive datasource | §8.4 spike |
| Claims today: plain SELECT plus compare-and-set (`PostgresScheduleStore` ~L904–914, `MongoScheduleStore` `findOneAndUpdate`) | §7.4 keeps this |
| 60 `ON CONFLICT (` targets, 18 SQL `UNIQUE`, 25 Mongo `unique(true)` (B.2) | Marker-driven switch (§5.3) |
| 39 files build Caffeine directly (A.5) | §8.6 |
| Postgres `resources` PK order differs between old and new installs. Startup refuses PK rewrites | Never rewrite PKs |
| One `MongoDatabase` bean | Dedicated tier comes last |
| `AgentFactory` holds every deployed agent in memory, keyed by `AgentId(id, version)`. `DeploymentInfo` has no org. Everything is deployed on every node | §8.7, §12 |
| Migration managers have **no** "data newer than binary" refusal | A floor release is needed (§5.4) |
| Roles: `quarkus.oidc.roles.role-claim-path=realm_access/roles` (`a.p:883`). Realm roles are global, and Keycloak Organizations has no org-scoped roles | §9.3 |
| Team spaces come from the realm-global `groups` claim (`SpaceContext` ~L152–173, `keycloak/eddi-realm.json` group mappers) | §9.4 |
| `/q/metrics` policy defaults to `eddi-admin,eddi-metrics` (`a.p:1150-1151`). `/openapi` is readable by every EDDI role (`a.p:~1177`) | §9.3, §7.1 |
| CORS headers are `…, x-eddi-space` (`a.p:569`) | Add `x-eddi-org` |
| Auth is off by default (`quarkus.oidc.tenant-enabled=false`, `a.p:830`) | Enablement is guarded (§5.3) |
| Deployment-wide settings that are really per customer: one SMTP sender and `eddi.mail.notify.allowed-recipient-domains`, `eddi.openai-compat.api-key`, `eddi.slack.*`, `SelfUrlResolver` (links in HITL mail), CORS origins, update-check notices to `WorkspaceNotifications` | A.13, `OrgSettings` |
| `ClusterArchiveStore` holds agent export ZIPs in the shared `<prefix>_ARCHIVES` bucket, plus node-local `tmp/archives` (`backup/impl/RestExportService`) | Org data (§8.9) |
| `eddi.security.ssrf-protection.enabled=false` by default | Forced on (§11.1) |
| `StructuredTaskScope` is still preview in Java 25 | Do not rely on it |
| Versions: Keycloak 26.8.0, Mongo 7.0, Postgres 16 | Document minimums: Mongo 5.2 (`$topN`/`$jsonSchema`), PG 11 (metadata-only `ADD COLUMN … DEFAULT`) |

### 3.3 Leak paths in shared hosting (all closed before the first external org)

1. SSRF into EDDI, infrastructure or other orgs (§11.1).
2. Author-chosen vector store names (`EmbeddingStoreFactory.physicalName`) (§8.8).
3. Table owners bypass RLS without `FORCE`; connections reused across bindings (§8.4).
4. NATS: one subject root, unsigned headers, cross-org admin views, archives bucket (§8.9).
5. In-memory registries: deployed agents, coordinator queues, SSE audiences,
   credential-bearing model registries, `WorkflowTraversal` memo, limiter and breaker state
   (§8.7).
6. Platform Operator allow-list (`administration/logs`, `cluster/*`), global MCP roles (§9.5).
7. One audit key and one chain (§11.3).
8. `originId` import matching (§8.2).
9. Shared LLM keys (§11.4).
10. Identity collisions: user memories keyed by `user_id`, attachments by user metadata
    (§9.2).
11. `/v1`: one key, user id taken from a header (§7.1).
12. Anonymous entry points with no org: public chat, A2A, `/.well-known/*`, the branded
    shell, `/manage/__auth_config__.js` (§7.1).
13. Shared channel endpoints (§7.1).
14. Metrics with `tenant`/`agentId` tags, the `eddi_agents_deployed` gauge, org admins
    reaching `/q/metrics` (§11.5, §9.3).
15. Global DB logs and search indexes (§10).
16. Templating namespaces (§10).
17. Work-starters (§6.4).
18. Per-org backup (§13).
19. Realm roles and realm groups that apply in every org (§9.3, §9.4).
20. Loopback calls that drop the org selection (§7.3).
21. Per-customer settings held deployment-wide (A.13).
22. A rolled-back binary reading the shared layout unfiltered (§5.4).

---

## 4. Design principles (binding)

1. **The org is the outer boundary.** Everything else lives inside it.
2. **Enforce at the data layer:** stores, caches, in-memory registries, NATS, vector stores.
   Workspace guards decide sharing *within* an org only.
3. **Fail closed, never leak.** A missed propagation is an error, never a silent wrong org.
   Crossing orgs works only through `OrgScope.platform(reason, …)`, which is audited and
   allow-listed.
4. **One code path.** Single mode is the same code with exactly one org. The persisted
   **marker** (§5.3), not the mode flag, decides predicates, unique-key shape and RLS.
5. **The org is data.** Every durable work-starter stores its `orgId`.
6. **Defense in depth: two independent layers on both datastores.** No single missed
   check may leak.
   - **PostgreSQL:** the app filter (`OrgSql`), plus row-level security.
   - **MongoDB:** the app filter (`OrgCollections`), plus the guarded collection boundary
     and decode-time read verification (§8.5a), plus a `$jsonSchema` validator requiring
     `orgId`.
   - **Both:** per-org keys, forced SSRF, then the dedicated tier, then a separate instance.

   **Both datastores are fully supported in the pooled tier** (§16 decision 12). MongoDB has
   no row-level security, so its second layer runs in the driver boundary instead of the
   database.
7. **404, never 403, across orgs.** Ids are globally unique. An org id never appears in a
   public id.
8. **The platform plane is separate from the org plane.** Platform staff see no customer
   content without an audited break-glass grant.
9. **The isolation tier is a property of the org** (`POOLED` | `DEDICATED`).
10. **Single-tenant installs pay nothing they don't use.** Until the marker exists, no
    predicate is added, so performance is unchanged. All expensive steps happen in the
    explicit enablement.

---

## 5. Modes, marker, enablement, floor

### 5.1 The contract

| Aspect | Single mode (`eddi.org.mode=single`, **default**) | Multi mode (`eddi.org.mode=multi`) |
|---|---|---|
| Startup preconditions | none | Startup is **refused** unless all of these hold:<br>• the marker is `ENABLED`;<br>• `eddi.security.ssrf-protection.enabled=true`;<br>• `eddi.vault.master-key` is set;<br>• auth is on and `eddi.security.allow-unauthenticated` is not set;<br>• platform roles are configured (§9.3);<br>• no org role appears in `eddi.metrics.roles-allowed` or in any platform `quarkus.http.auth.policy.*`;<br>• a NATS signing key is set (if NATS is used);<br>• `eddi.org.multi.preview-acknowledged=true` (until the pentest);<br>• on Mongo: the guarded database and the read verifier are active (§8.5a) |
| Org resolution | constant `default` | `OrgResolver` (§9.1) |
| Unbound context | `default`, WARN once per call site, `eddi_org_context_fallback_total{site}` (stays 0 in CI) | `OrgContextMissingException` → 500 `[SECURITY]` |
| Other orgs | Binding any org other than `default` throws, and org creation is refused | allowed |
| Data predicates | **none before the marker**. After the marker, strict (a single-mode deployment may run the marker layout) | strict |
| Roles | today's; `eddi-admin` also holds platform rights | split (§9.3) |
| UI | no org UI | org switcher, platform console |
| Performance | ≤ 3 % p95 on `scripts/loadtest`, counts included | measured in Phase 5 |
| Switching | Run enablement, then set `mode=multi` and restart | multi → single is refused when more than one org exists |

### 5.2 Before the marker (single-mode default)

- **Writes always stamp.** Every write sets `orgId`/`org_id` to `default`, so the data is
  ready for enablement.
- **No predicate is added to any read**, because only `default` can exist. Binding another
  org throws, and org creation is refused.
- **PostgreSQL, Phase 1:**
  - Add `org_id TEXT NOT NULL DEFAULT 'default'` with `ADD COLUMN IF NOT EXISTS`, under
    `SET lock_timeout = '5s'` with retry.
  - This is a metadata-only change on PG ≥ 11. It takes only a brief `ACCESS EXCLUSIVE`
    lock and rewrites nothing.
  - Old nodes in a rolling upgrade get the default from the column.
- **MongoDB:** documents written before Phase 1 have no `orgId` until enablement backfills
  them.

### 5.3 Marker and enablement migration

**Types.** `OrgMarker` and `IOrgMarkerStore` live in the platform plane, on Mongo and
Postgres. The marker states are `ABSENT` → `SWITCHING` → `ENABLED`. Every node:

- loads the marker at startup and polls it every 10 s;
- publishes `markerState` in `ClusterPresence`.

**Fresh install in multi mode.** An empty database started with `eddi.org.mode=multi` is
bootstrapped directly into the `ENABLED` layout. `OrgTestProfile` uses this.

**Who can run it.** `OrgEnablementMigration` runs **while the deployment is still in
single mode**, where `eddi-admin` holds platform rights.

- `POST /platform/org-enablement` is refused unless:
  - auth is on;
  - `eddi.org.enablement.allowed=true` is set;
  - the body carries `{"confirm": "enable-organizations", "backupTaken": true}`.
- Progress: `GET /platform/org-enablement`.
- It runs under a cluster lease and is resumable.
- When it finishes: set `mode=multi` and restart.

**Precondition:** every live member advertises `org-layout-1` (§5.4). A single node
without NATS passes trivially.

**Steps** (each is idempotent and checkpointed in the migration log):

1. **Backfill.** Stamp `orgId: "default"` where it is missing in every Mongo collection of
   Appendix B. Use `_id`-ordered batches (`eddi.org.enablement.batch-size`, default 1,000)
   and throttle them. Postgres needs nothing here.
2. **Indexes.**
   - Build org-leading indexes: Mongo background builds, Postgres `CREATE INDEX CONCURRENTLY`.
   - Claim-query indexes keep `(status, due, …)` leading (§7.4).
   - Build the B.2 org-including unique keys **alongside** the old ones.
3. **Switch. This step is the point of no return.**
   1. Set the marker to `SWITCHING`.
   2. Each node, on seeing `SWITCHING`, starts using:
      - predicates (strict, `{orgId: "default"}`);
      - org-including upsert targets: `OrgSql.conflictTarget`, `OrgCollections.uniqueFilter`;
      - NATS signature v2 (§8.9).

      It then reports `SWITCHING` in presence.
   3. **Barrier:** only after every live member has reported `SWITCHING` for one full
      presence TTL:
      - drop the old unique keys;
      - on Postgres, `ALTER COLUMN org_id DROP DEFAULT` on every org table;
      - on Mongo, `collMod` each org collection with
        `validator: {$jsonSchema: {required: ["orgId"]}}`, `validationAction: "error"`;
      - set `layout.required = org-layout-1` (§5.4).
4. **RLS (Postgres).** Per org table: `ENABLE` + `FORCE ROW LEVEL SECURITY` and the policies
   (§8.4). Activate `OrgRlsPoolInterceptor`.
5. **Crypto.** Create the `default` org KEK and rewrap the DEKs. Values sealed earlier keep
   AAD v1 and open only in `default`; new seals use AAD v2, which includes the org (§11.2).
   The existing audit chain becomes the genesis of `default`'s chain under its recorded key
   id (§11.3).
6. **Integrity.** Check that:
   - no record lacks an org;
   - every org table is `FORCE`d and every Mongo validator is present;
   - every member reports `ENABLED`-capable.
7. Set the marker to `ENABLED`.

A deployment may stay in single mode with the marker `ENABLED`. That is a valid, tested
state: one org on the strict layout.

### 5.4 Floor release and capabilities (no unsafe rollback)

- **Phase 1 ships `DataLayoutGuard`.** At startup it reads the platform record
  `layout.required` and refuses to start if its own supported layout level is lower. The
  first release containing the guard is the **floor release**; release notes must say so.
- **Capabilities advertised in `ClusterPresence`:**
  - `org-ctx-1`, from Phase 1 (context and stamping);
  - `org-layout-1`, only from the release that implements all of §5.3.
- **What can't be prevented.** A binary older than the floor cannot be stopped from
  starting. On Mongo it would read every org unfiltered. Rollback below the floor is
  therefore forbidden once `layout.required` is set, and `docs/multi-org.md` and the release
  notes state this. Postgres RLS still contains such a binary, but only if it connects as a
  role without `BYPASSRLS` and the table is `FORCE`d.

---

## 6. Org context and propagation

### 6.1 The primitive (`ai.labs.eddi.engine.org`)

```java
public record OrgId(String value) { public static final OrgId DEFAULT = new OrgId("default"); } // validated §2
public final class OrgContext {
    private static final ScopedValue<Binding> CURRENT = ScopedValue.newInstance(); // Binding = Org(OrgId, epoch) | Platform(reason, epoch)
    public static OrgId require();       // bound org; throws inside Platform; multi: throws if unbound; single: DEFAULT + WARN/counter if unbound
    public static Optional<OrgId> current();
    public static boolean isPlatform();
    public static long epoch();          // unique per binding; used by §8.4
    public static <T> T callAs(OrgId org, Callable<T> work) throws Exception;
    public static void runAs(OrgId org, Runnable work);
}
public final class OrgScope {
    public static <T> T platform(String reason, Callable<T> work); // allow-listed callers only (§14.2); logged + counted per reason
}
```

**Binding transitions:**

| From | To | Result |
|---|---|---|
| unbound | any | allowed |
| Org X | Org X | no-op |
| Org X | Org Y | **throws** |
| Platform | Org | allowed (per-item processing) |
| Org | Platform | allowed only from allow-listed classes |

Under `Platform`, any method that writes or reads org data needs a nested `runAs`. The only
exceptions are the dedicated cross-org platform methods (§8.1).

**Platform data versus cross-org access:**

- Platform tables (Appendix B) have no RLS. Their stores read no org and can be called from
  any binding, including ordinary org requests that read catalog defaults.
- `OrgScope.platform` is used **only** for cross-org access to org tables: claim queries,
  lookups by unguessable id (integrations, OAuth state), migrations, integrity checks,
  platform admin views.

**Why `ScopedValue`:**

- It is final in Java 25 and safe on virtual threads.
- It cannot leak to the next task on a pooled thread, so a missed propagation fails closed
  instead of serving a stale org.
- `ThreadContextProvider` cannot carry it, so every hand-off is explicit (§6.3).

**Store API (ADR LOCK 3).** The org-aware helpers `OrgCollections` and `OrgSql` read
`OrgContext.require()` themselves. Store interfaces do **not** get an `OrgId` parameter. An
explicit parameter is no safer when every caller passes `OrgContext.require()`, and it would
churn ~100 interfaces. The guards (§14.2) ensure every DB access goes through the helpers.

### 6.2 Binding on request paths

- **`OrgResolution.resolve()`** is request-scoped and memoised on the Vert.x `RoutingContext`.
  It derives the `OrgId` from `SecurityIdentity`, the proxy-aware authority, the routing keys
  (§7.1) and `X-EDDI-Org`, the way `SpaceContext` does. In single mode it returns `DEFAULT`.
- **`@OrgBound` interceptor** (`@AroundInvoke`, `Interceptor.Priority.PLATFORM_BEFORE`, so
  outermost; `McpErrorResultInterceptor` is `PLATFORM_AFTER`). It binds the resolved org,
  and is a no-op when the same org is already bound. Apply it to:
  - every JAX-RS **implementation** class;
  - every MCP tool class (`engine/mcp/*`: 21 files with `@Tool`).
- **Code outside resource methods (A.12):** filters, augmentors, exception mappers and raw
  Vert.x routes.
  - Each one that touches org data wraps that work in `OrgRequest.callBound(() -> …)`.
  - The others are marked `@PlatformPlane` or `@OrgNeutral`, with a comment saying why.
  - `UserDirectoryRecorder` must capture the org before its `executor.execute(...)`.
- **Work after the method returns.** The scope ends when the method returns, so:
  - work for `AsyncResponse` (44 uses) is wrapped (§6.3);
  - `StreamingOutput`, `SseEventSink`, `Multi` and LLM streaming callbacks capture the org at
    creation and rebind in every callback;
  - response entities are fully materialised inside the scope. Lazy iterables that hit the
    DB during serialisation are forbidden (`RestLogAdmin`, `RestClusterAdmin`,
    `RestCoordinatorAdmin`, `RestUserEvents`, `RestOpenAiAdapter` and `RestA2AEndpoint`
    stream; check each).

### 6.3 Hand-offs

```java
public final class OrgPropagation {
    public static Runnable wrap(Runnable r);   // captures the current binding now, rebinds when run
    public static <T> Callable<T> wrap(Callable<T> c);
    public static <T> Supplier<T> wrap(Supplier<T> s);
    public static Executor wrap(Executor e);
}
```

Apply it to:

- executors (`ExecutorService`, `ManagedExecutor`, virtual threads);
- `CompletableFuture.*Async`;
- Mutiny `emitOn` / `runSubscriptionOn`;
- every `Callable` enqueued in `AbstractQueuedConversationCoordinator`;
- group-member turns and sub-agent calls;
- SSE and streaming handlers.

Use the A.9 search to find the call sites. The guard (§14.2) flags raw async calls that
bypass `OrgPropagation`.

### 6.4 Work-starters persist their org

Each of these gets an `orgId`, stamped on create. Resuming work from it binds that org:

- conversation memory snapshot and conversation descriptor;
- group conversation;
- schedule;
- agent trigger;
- channel integration;
- HITL pause and tool journal;
- Slack approval record;
- email ledger;
- OAuth state and grant;
- team budget;
- ingestion state;
- agent test run;
- **`DeploymentInfo`** and the cluster deploy/undeploy events;
- export archives;
- dynamic and sub-agents (they inherit the parent's org).

---

## 7. Entry points and org routing

### 7.1 Org source per entry point (multi mode)

| Entry point | Org source | Classes |
|---|---|---|
| Authenticated REST, SSE, MCP | Verified token via `OrgResolver` (§9.1). With host binding and a mapped host, the host's org must match, otherwise 404 | `OrgResolution` |
| Anonymous public chat | **Host binding (mandatory).** Unmapped host → 404 | `engine/api/IRestAgentEngine` (`/agents/...`) and the streaming variant |
| A2A, `/.well-known/*` | Host binding; cards list only that org's agents | `engine/a2a/RestA2AEndpoint.java`, `AgentCardService` |
| Branded shell and static config (`ui/Branded*Route`, `/manage/__auth_config__.js` from `ui/IRestManagerResource`) | Host binding; an unmapped host gets the platform brand; caches keyed `(org, revision)`. `planning/white-label-ui-plan.md` reserves `BrandingService.current(request)` | `ui/*`, `configs/branding/BrandingService` |
| `/openapi`, Swagger UI | Org callers never see `/platform/*` operations (filter the document per plane) | `ui/BrandedOpenApiRoute` |
| Slack / webhook | Integration id in the path (`/integrations/slack/{integrationId}/…`, `/integrations/webhook/{integrationId}/…`; old paths stay in single mode). A cross-org platform read by that unguessable id, then the signature is verified with **that** integration's secret. Payload fields never select the org | `integrations/slack/rest/RestSlackWebhook`, `integrations/webhook/rest/RestWebhookChannel`, `SlackEventHandler`, `WebhookEventHandler` |
| Email inbound | Org of the swept mailbox integration | `integrations/email/EmailInboundSweeper` |
| `/v1` | Per-org `OrgApiKey` (hashed, via the routing index). The header user id is namespaced inside that org | `integrations/openai/*` |
| `/connections/callback` | Org of the stored single-use `OAuthState` (cross-org platform read by state value) | `connections/*` |
| Jobs | Item's `orgId` (§7.4) | A.7 |
| NATS consumers | Signed `Eddi-Org` header (§8.9) | A.8 |
| Startup / migrations | `OrgScope.platform("migration:<name>", …)` | migration classes |

**Host binding:**

- The host comes from Quarkus' proxy-aware authority, with `quarkus.http.proxy.*`
  configured. Never use raw `X-Forwarded-Host`.
- Hostnames are lower-cased, globally unique, and set by platform admins only.

### 7.2 `OrgRoutingIndex` (platform plane)

`OrgRoutingIndex` maps a routing key to an org and returns nothing else. Its keys are:

- hostnames;
- `/v1` key hashes;
- Keycloak organization ids.

It is maintained in the same write as its source record and cached. Only `OrgResolution`,
the `/v1` filter and the org store may call it (§14.2).

### 7.3 Loopback and `IRest*` calls

- **With a caller,** the loopback forwards the bearer token and also **`X-EDDI-Org`** with
  the bound org (extend `LoopbackCallerAuthFilter`). The receiver re-validates the header
  against the claim. Add `x-eddi-org` to `quarkus.http.cors.headers`.
- **Anonymous host-bound callers never go through the loopback,** because the self address
  is an unmapped host. Convert those call paths to direct service calls.
- **Background code never calls `IRest*` beans,** neither over the loopback nor in-process,
  because `@RolesAllowed` on the interfaces would see an anonymous identity. In Phase 3,
  convert every such call (A.4) to the underlying store or service, inside the item's org.
  There is no internal token.

### 7.4 Background jobs: claim across orgs, bind per item

**Work-queue jobs** (schedule poller, HITL timeouts, OAuth maintenance, log retention, email
sweeper, Dream/lesson queues, agent-test recovery) keep today's predicate and
compare-and-set claim.

- **Before the marker:** run today's query unchanged.
- **After the marker,** inside `OrgScope.platform("job:<name>", …)`:
  - **Postgres:** select candidate ids from the oldest `:scan` due rows (default 20 × batch),
    apply `row_number() OVER (PARTITION BY org_id ORDER BY due)` with
    `rn <= :cap` (`eddi.org.jobs.max-per-org-per-tick`, default 50), and limit to `:batch`.
    **Close the connection.** No `FOR UPDATE`.
  - **Mongo:** an aggregation over the same bounded window: `$match` → `$sort {due:1}` →
    `$limit :scan` → `$group {_id:"$orgId", ids:{$topN:{n:cap, sortBy:{due:1}, output:"$_id"}}}`.
  - Rotate the starting org each tick, round-robin over the group results.
  - For each id: `OrgContext.runAs(item.orgId, …)`, then the CAS claim and the processing,
    on a **new** connection.

**Per-org aggregate jobs** (quota maintenance, per-org metrics) use `OrgIterator`
(Phase 5): paged, with a per-tick budget and a persisted cursor.

---

## 8. Persistence and runtime enforcement

### 8.1 Field and stamping rules

- Every org collection or table carries `orgId` / `org_id` (Appendix B). The helpers stamp
  it from the context.
- **A caller-supplied `orgId` is always overwritten.** This covers bodies, imports, ZIPs and
  NATS payloads.
- Platform tables carry no org field. Their stores are callable from any binding (§6.1).
  Cross-org reads of org tables happen only through dedicated `platform*` store methods,
  which require `OrgContext.isPlatform()`.

### 8.2 Generic resource SPI (31 files)

- `IResourceStorageFactory.create(...)` keeps its signature. The Mongo and Postgres storages
  call the helpers for every read, history read, find, update and delete, and stamp on every
  write. `DescriptorStore` does the same.
- `DocumentDescriptorStore.findByOriginId` and substring search (`ISubstringSearchIndexing`,
  `PostgresSubstringSearchIndexes`) become org-scoped.

### 8.3 Direct stores (A.2/A.3)

Do not build a `MongoCollection` wrapper that rewrites filters. It cannot cover
aggregations, `$lookup`/`$unionWith`, bulk writes, change streams or GridFS.

- **`OrgCollections`** (Mongo):
  - `filter(Bson)`: no-op before the marker, strict after;
  - `stamp(Document)`;
  - `matchStage()`: the mandatory first pipeline stage;
  - `gridFsMetadata(…)`;
  - `uniqueFilter(Bson)`: shaped by the marker.
- **`OrgSql`** (Postgres):
  - `whereOrg()`: an empty fragment before the marker, `AND org_id = ?` after;
  - `bind(...)`;
  - `conflictTarget(key)`: the B.2 target for the current marker state;
  - `insertColumns()`;
  - the connection-epoch assertion (§8.4).
- `estimatedDocumentCount` is platform-only, because it cannot be filtered.

### 8.4 PostgreSQL RLS (backstop after the marker)

**Policies.** Apply to every org table:

```sql
ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <t> FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON <t>
  USING (org_id = current_setting('eddi.org_id', true))
  WITH CHECK (org_id = current_setting('eddi.org_id', true));
CREATE POLICY platform_access ON <t> USING (current_setting('eddi.platform', true) = 'on');
```

An unset GUC is NULL, so the policy returns no rows. Platform tables have no RLS.

**`OrgRlsPoolInterceptor`** (`@ApplicationScoped`, `io.agroal.api.AgroalPoolInterceptor`):

- `onConnectionAcquire` runs on the caller's thread. It executes
  `SELECT set_config('eddi.org_id', ?, false), set_config('eddi.platform', ?, false)`:
  - with an org binding: the org and an empty platform flag;
  - inside `OrgScope.platform`: an empty org and `on`;
  - unbound: both empty.
- It records the binding epoch on the connection, e.g. in a `WeakHashMap<Connection, Long>`
  keyed by the wrapped connection.
- No `RESET ALL`. Each acquire overwrites both values, and a reset would also wipe the
  `new-connection-sql` settings.
- It does nothing until the marker enables RLS.

**A connection never spans a binding change.**

- Claim methods materialise their results and close the connection before `runAs`.
- `OrgSql` asserts that the connection's acquire epoch equals `OrgContext.epoch()`. A
  mismatch throws `[SECURITY]`.
- Code that holds a connection across a hand-off is a defect.

**Spike (Phase 0).** Prove each of these:

- the interceptor is registered for the default datasource;
- it runs on the acquiring thread for the `Instance<DataSource>` users;
- **an exception in `onConnectionAcquire` aborts the borrow.** If it does not, also clear
  both GUCs in `onConnectionReturn`;
- the bean is harmless when `quarkus.datasource.active=false`, as on every Mongo deployment;
- the per-acquire overhead.

If the spike fails, the fallback is `OrgSql.inTx(work)` with `set_config(..., true)` around
every statement path. The ~380 sites are touched in Phase 1 anyway. **STOP:** ask the user
before taking the fallback.

**Further rules:**

- **Role split** (`eddi_migrator` owns, `eddi_app` `NOBYPASSRLS`): optional hardening, since
  `FORCE` binds the owner. It is required only to contain pre-floor binaries (§5.4).
- **Threat model:** `eddi_app` can call `set_config('eddi.platform','on')` itself, so RLS
  stops missed filters, not SQL injection.
- **PgBouncer** in transaction pooling mode breaks session GUCs. Either run it in session
  mode, or set `eddi.org.postgres-rls=off-i-understand`, which leaves the app filter only
  and logs a WARN. **STOP** if a deployment needs this. Document it in `docs/multi-org.md`.
- **pgvector pools** (`PgVectorConnectionPools`) connect to author-supplied external
  databases and are not covered. The controls there are §8.8 names and per-org credentials.
- **Never rewrite PKs.** `resources` and `resources_history` get
  `(org_id, collection_name, id)`.

### 8.5 MongoDB specifics

- After the marker, `orgId` leads every index except the claim indexes.
- Each org collection has the `$jsonSchema` validator requiring `orgId`.
- Future shard key: `{orgId: 1, _id: 1}`, or hashed `orgId` if orgs are very uneven.
- GridFS: `metadata.orgId`, index `(metadata.orgId, metadata.conversationId)`.
- Dedicated tier (Phase 7): `OrgDatabaseRouter` replaces the single `MongoDatabase` bean.
  Use it for at most tens of orgs.

### 8.5a MongoDB second layer (replaces RLS)

MongoDB cannot filter rows per session, so EDDI adds two checks of its own. Both are
independent of `OrgCollections`, so a missed or wrong helper call is caught by them and
fails closed. Both live where every Mongo access already passes:
- the single `MongoDatabase` producer, `PersistenceModule.provideMongoDB`;
- the central codec registry, `PersistenceModule` (`fromRegistries(...)`).

1. **`OrgGuardedMongoDatabase` / `OrgGuardedCollection`: refuses unscoped commands.**
   - The producer returns a delegating `MongoDatabase` whose `getCollection(...)` hands out
     a delegating `MongoCollection`.
   - For collections in `OrgDataCatalog`, the wrapper **verifies** each operation and does
     not rewrite it. It throws `[SECURITY]` unless the operation carries the bound org:
     - `find`, `count`, `distinct`, `update*`, `replace*`, `delete*`, `findOneAnd*`:
       the filter must have a top-level `orgId` equality, or an `$and` containing one,
       equal to `OrgContext.require()`;
     - `aggregate`: the first stage must be `$match` with that equality, and `$lookup`,
       `$unionWith` and `$graphLookup` sub-pipelines on org collections must start with
       one too;
     - `insert*` and `bulkWrite`: every document or filter must carry the bound org;
     - `watch`: the pipeline must start with the org `$match`;
     - `estimatedDocumentCount` and `drop` are allowed only inside `OrgScope.platform`.
   - Inside `OrgScope.platform`, only the dedicated `platform*` store methods may run
     cross-org operations. They call `guarded.platform(reason)`, which is allow-listed and
     audited.
   - Before the marker the wrapper only counts unscoped operations (metric
     `eddi_org_mongo_unscoped_total`, must be 0 in CI). Single mode therefore pays nothing
     and fails nothing.
   - GridFS is created only through the guarded database (`GridFSBuckets.create(guardedDb, …)`),
     and its metadata filters are verified the same way.
2. **`OrgReadVerifier`: checks every decoded document.**
   - A codec decorator in the central registry, for `Document`, `BsonDocument` and EDDI's
     `JacksonCodec`, checks every document decoded from an org collection.
   - After the marker, an `orgId` that differs from the bound org, or a missing `orgId`,
     throws `[SECURITY]` and the document is never returned.
   - Projections on org collections must include `orgId`. The wrapper adds it when a
     projection omits it.
   - Counts return no documents, so they are covered by the wrapper (1) and the guards (§14.2).
3. **`$jsonSchema` validator** requiring `orgId` on every org collection (§5.3 step 3).
   It covers writes only.
4. **Dedicated tier** (Phase 7) for orgs that need isolation at the database level.

**Cost:** one BSON inspection per command and one field read per decoded document. This is
measured in the Phase 5 benchmark, within the same 3 % gate. **Spike D** (Phase 0) proves
three things:
- the delegating wrapper covers every `MongoCollection` method the code uses, including
  `ClientSession` overloads and GridFS;
- the codec decorator sees every decode path;
- the measured overhead.

### 8.6 Caches (A.5 plus `ICacheFactory`, `ClusterInvalidatingCache`, `SharedKvCache`)

- Classify each cache:
  - **org data:** the key includes `OrgKey<K>(OrgId, K)`. This is mandatory whenever the key
    is not a globally unique id (names, keys, aliases) and recommended always;
  - **platform data:** document why.
- Credential-bearing caches are always org-keyed: `ChatModelRegistry`,
  `EmbeddingModelFactory`, `SecretResolver`, `ConnectionRegistry`.
- A new `Caffeine.newBuilder()` outside the classified list fails the build.

### 8.7 In-memory runtime registries

- **Deployed agents.**
  - `IAgent.orgId` comes from `DeploymentInfo.orgId`, and the deploy events carry it too.
  - The map stays keyed by `AgentId(id, version)`, since ids are globally unique. Every
    lookup compares the agent's org with the bound org and returns `null` on a mismatch.
  - `getAllLatestAgents(env)` filters to the bound org. A platform variant exists.
  - `AgentDeploymentManagement` deploys each agent inside its own org scope.
  - The `eddi_agents_deployed` gauge is platform plane.
- **Coordinator queues** are keyed `<org>.<conversationId>`, and tasks are wrapped.
- **SSE and user events** (`UserEventPublisher`, `GroupConversationEventSink`) use the
  audience `(orgId, userId)`.
- **Org-keyed state:** `TurnIdempotencyService`, `LlmCircuitBreakers`, `ToolRateLimiter`,
  `GroupSigningGuard`, `ConversationReviewPolicy`, `ConversationRatingPolicy`,
  `AutonomyOverlayService`, `ApprovalAnalyticsService`, and the `WorkflowTraversal` memo.

### 8.8 RAG and vector stores

- **Derived names after the marker:** `eddi_<sanitised org>_<sanitised ragConfigId>_<hash8(orgId + "/" + ragConfigId)>`.
  The hash prevents sanitising collisions such as `a-b` versus `a_b`.
- **Author-supplied physical names are refused.** That covers `collectionName`,
  `tableName`, `databaseName` and `indexName`. The only exception is a platform admin on a
  dedicated org.
- **Before the marker,** existing names keep working
  ([`docs/rag.md`](../docs/rag.md#knowledge-base-isolation)).
- **External providers** use the org's own credentials.

### 8.9 NATS and cluster

- **Signature v2.** `ClusterMessageSigner` gains a v2 signed text that covers the
  `Eddi-Org` header.
  - Before the marker, receivers accept v1 and v2.
  - Senders switch to v2 at `SWITCHING` (§5.3).
  - After the barrier, only v2 is accepted for org-scoped subjects.
- **Signing key.** After the marker, a NATS deployment needs a signing key, and unsigned
  sending stops.
- **Consumers** bind the org from the header and reject org-scoped messages without one.
- **Keys stay id-keyed.** That covers KV, tombstone (`ErasureTombstones`), lease
  (`KvLeaseManager`, `NatsLeaseManager`) and dead-letter (`JetStreamDeadLetterStore`) keys.
  Ids are globally unique, and re-keying would orphan existing erasure tombstones.
- **Values and dead-letter payloads carry `orgId`.** Listings filter on it, and the
  dead-letter forwarder rebinds the payload's org.
- **Archives** (`ClusterArchiveStore`, `<prefix>_ARCHIVES` bucket, node-local
  `tmp/archives`) are org data:
  - object names are `<org>/<file>`;
  - each org has a byte cap;
  - they are purged on org deletion.
- **Platform-plane views:** cluster admin, dead letters, diagnose and activity
  (`ClusterAdminService`, `ClusterWatcher`, `ClusterActivityLog`). An org admin sees only
  their own org's entries.
- **Coordinator.** In NATS mode (`ClusterConversationCoordinator`), turns carry the org
  header.

---

## 9. Identity, roles, planes

### 9.1 `OrgResolver` SPI (`eddi.org.resolver`)

| Resolver | How | Use |
|---|---|---|
| `single` | Always `default` | Phase 1 |
| `keycloak-organizations` (multi default) | Reads the `organization` claim from the **verified access token**, never from UserInfo. The client requests `scope=organization:*` explicitly. Only the `{alias: {id: …}}` shape is accepted (configure the mapper to include ids). The org is matched by **Keycloak's immutable organization id** via `OrgRoutingIndex`, never by alias. Verify the claim shape against 26.8 in the Phase 0 spike | SaaS users |
| `issuer` (optional, after Phase 6) | One Quarkus OIDC tenant per org via `TenantConfigResolver` | BYO-IdP |

- **Several orgs in the token:** select one with `X-EDDI-Org`, checked against the claim.
  Several orgs and no header → 403.
- **Missing or unknown org** → 403 `[SECURITY]`. Never fall back to `default`.

### 9.2 Principals

- Principals are stored as `(orgId, subject)` pairs with `orgId` in its own field, never as a
  string prefix.
- Pairs are used for ownership, grants, user memories, the directory, conversation `userId`,
  HITL `decidedBy`, attachments and GDPR subjects.
- `ReservedPrincipals` stays.

### 9.3 Roles and planes

**Platform plane.**

- **Roles:** `eddi-platform-admin`, `eddi-platform-operator`, `eddi-metrics`.
- **Source:** **client roles of the `eddi-platform` client**, read from
  `eddi.org.platform.role-claim-path` (default `resource_access/eddi-platform/roles`). Only
  platform staff hold them, with MFA. They are never mapped from an org IdP.
- **Scope:** orgs, enablement, quotas, cluster admin, platform catalog, metrics, platform
  logs. **No customer content without break-glass.**

**Org plane.**

- **Roles:** `eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-viewer`, `eddi-approver`.
- **Source:** **EDDI-side `OrgMembership(orgId, subject, roles, teams)`**.
- **Scope:** the caller's org only.

**`OrgRoleAugmentor`** (a `SecurityIdentityAugmentor`, multi mode only):

- It builds a **fresh** identity, not `QuarkusSecurityIdentity.builder(identity)`.
- It drops every token `eddi-*` role.
- It adds the membership roles for the request's org, plus the platform roles from the
  platform claim.
- It looks memberships up through `context.runBlocking(...)` and caches them for at most
  60 s, with cluster invalidation.
- Revocation latency is bounded by that cache and by the 3-minute OIDC token cache (`a.p:861`).

**Rules:**

- **New members** get `Org.defaultMemberRoles` (default `eddi-user`). Org admins manage
  members via `/orgs/current/members`.
- **Single mode:** realm roles work as today, and `eddi-admin` implies the platform roles.
- **Break-glass** is temporary emergency access by platform staff to one org's content, for
  example to debug a customer's incident. It is the only way the platform plane sees
  customer data.
  - `POST /platform/orgs/{orgId}/access` with a reason and a TTL of at most 4 h, by
    `eddi-platform-admin`.
  - The org's admins are notified when access is granted and when it ends.
  - Every action under the grant is written to the **org's** audit ledger, which they can
    read, and to the platform ledger.
  - A **second platform admin's approval** is configurable:
    `eddi.org.break-glass.require-second-approver`, default `false`. When it is `true`, the
    grant stays pending until a different `eddi-platform-admin` approves it.
- **Role checks stay literal.** Extend `McpRoleTierParityTest` with the platform tier.
- **Never visible to an org admin:** other orgs, platform logs, cluster admin, `/q/metrics`,
  platform catalog writes, enablement state, other orgs' quotas, `/platform/*` in OpenAPI.

### 9.4 Workspaces inside the org

- Spaces, visibility and grants are unchanged, evaluated within the org.
- **Team spaces in multi mode** come from `OrgMembership.teams`, or from Keycloak groups
  under `/orgs/<kc-org-id>/` only. All other groups are ignored.
- `SpaceTenants` hashes include the org.
- `internal` means signed-in members of the org. `published` means anonymous callers on the
  org's hosts.
- The directory is org-local. `legacy-visibility` applies within the org.
- Setup-API resources are stamped with the caller's org.

### 9.5 Platform Operator and MCP

- **Operator.** It gets two profiles, **org** and **platform**. The org profile has no
  `administration/logs`, `cluster/*` or quota writes.
  - Files to change: `ui/manager/src/lib/operator/tool-scopes.ts`, `system-prompt.ts`.
  - Bump `operator-revision.json`.
  - Read `ui/manager/AGENTS.md` → Platform Operator first.
- **MCP.** Every MCP tool class is `@OrgBound` or `@PlatformPlane`.
- **Docs.** Update `docs/mcp-server.md#role-mapping` and `integrations/claude-plugin/`.

### 9.6 Per-org settings (`OrgSettings`)

Settings that are deployment-wide today but belong to a customer move into the org-scoped
`OrgSettings`, with the deployment value as the fallback:

- mail sender identity and allowed recipient domains;
- the Slack app and signing secrets (per integration);
- CORS origins (per org host);
- the public base URL (`SelfUrlResolver`, per org host);
- the `/v1` keys (`OrgApiKey`);
- branding.

A.13 classifies every `eddi.*` key. Platform update-check notices go to platform admins
only, not to org `WorkspaceNotifications`.

---

## 10. Rules per feature area

| Area | Rule | Main files |
|---|---|---|
| Conversation memory, descriptors, checkpoints | `orgId`; reads by id org-filtered | `engine/memory/*` |
| User memories, Dream, lessons | `(orgId, userId, …)`; claim pattern; per-org cost ceilings | `configs/properties/*`, `DreamService`, `LessonConsolidationService` |
| Attachments | `metadata.orgId`; per-org caps | `GridFsAttachmentStore`, `PostgresAttachmentStore` |
| Secrets, variables, snippets, branding, alert rules, model prices | Per org; platform catalog defaults (§10.1) | respective stores |
| Connections, OAuth, grants | `orgId`; callback takes the org from state | `connections/*`, `configs/connections/*` |
| Channels | §7.1; outbound identity from `OrgSettings` | `integrations/*` |
| HITL, oversight, approvals | `orgId`; approver must be in the same org | `engine/hitl/*`, `integrations/slack/hitl/*` |
| Groups, budgets, handoffs, artifacts, tasks | `orgId`; all members in the same org (checked at authoring and at run) | `engine/groups/*`, `configs/groups/*` |
| Sub-agents, dynamic agents | Inherit the parent's org | `modules/llm/impl/*ToolsProvider` |
| Schedules, triggers | `orgId`; a fire binds it | `engine/schedule/*`, `engine/triggermanagement/*` |
| Import/export/sync | Exports carry no org; imports stamp the importer's org; `originId` match org-scoped; archives per §8.9 | `backup/impl/*`, `engine/setup/ApiAgentSyncService` |
| GDPR | Org + subject | `engine/gdpr/*` |
| Tests, ratings, outcomes, spend changes, alerts | `orgId` | respective packages |
| DB logs | `orgId`; org admins see their own; retention uses the claim pattern | `engine/runtime/DatabaseLogs`, `PostgresDatabaseLogs` |
| Templating | Resolves within the current org; no syntax names another org | `modules/templating/*`, `GlobalVariableResolver`, `SecretResolver` |
| Agent signing | Signing keys and nonce caches per org | `configs/agents/AgentSigningService`, `configs/agents/crypto/*` |
| Errors | Never contain another org's ids, names or counts | everywhere |

### 10.1 Platform catalog

The platform catalog holds:

- model price defaults;
- documentation;
- built-in group templates;
- the built-in Operator;
- default agents and templates;
- branding defaults.

Platform admins write it, and every binding reads it (§6.1). An org may override any entry;
the org's copy wins over the platform default.

---

## 11. Security controls

### 11.1 SSRF

- **After the marker,** `eddi.security.ssrf-protection.enabled=true` is forced.
- **Non-overridable deny list:**
  - EDDI's own addresses and port;
  - NATS, the DB hosts and Keycloak admin;
  - cloud metadata;
  - `eddi.org.ssrf.cluster-cidrs`.
- Allow-list entries are platform config.
- **Applies to** everything that goes through `engine/httpclient/outbound/*`,
  `UrlValidationUtils` and the langchain4j model builders: httpcalls, the MCP client, RAG
  hosts, connections, A2A peers, outbound webhooks and email-link fetches.

### 11.2 Keys

- **Hierarchy.** Each org has a KEK that wraps its DEKs. The platform master key wraps the
  KEK; a per-org KMS (BYOK) comes later.
- **AAD v2** = `(orgId|tenant|key|dekId)`. Values sealed before enablement keep v1 and open
  only in `default`.
- **Deleting an org** destroys its KEK (crypto-shredding).
- **Threat model:** until KMS, exposure of the platform master key exposes all orgs.

### 11.3 Audit

- Each org has its own HMAC key, derived with HKDF from the platform audit key and the
  `orgId`, recorded by key id. Each org also has its own chain.
- The pre-enablement chain is `default`'s genesis.
- The platform ledger is separate.
- Exports are per org and verifiable with that org's key.

### 11.4 LLM keys

- **Default:** per-org keys in the org vault.
- **Platform provider pool:** opt-in, with per-org rate buckets, per-org spend attribution
  and an org-namespaced prompt cache.

### 11.5 Metrics and tracing

- The `org` tag is off by default (`eddi.org.metrics.tag-org`, cap
  `eddi.org.metrics.max-tagged-orgs` = 200).
- Per-org usage is exposed through the usage API.
- Spans carry `eddi.org`.
- `/q/metrics` is platform plane: the multi-mode startup check refuses org roles in its policy.

---

## 12. Fairness and scalability

- **Quotas.**
  - `OrgQuotaKeys.keyFor(OrgId)` returns the configured `eddi.tenant.default-id` in single
    mode and the org id in multi mode.
  - Limits enforced: cost, conversations per day, API calls per minute, agents, and storage
    caps (attachments, vectors, memories, archives).
- **Bulkheads:**
  - a per-org concurrent-turn semaphore in the coordinator;
  - per-org LLM and tool buckets;
  - job caps (§7.4);
  - per-org admission control before publishing to JetStream.
- **Agent working set (Phase 7).** Agents deploy lazily on first use per node, with LRU
  eviction (`eddi.org.agents.max-resident`). Trigger it on the measured startup-time or heap
  threshold. Single mode stays eager.
- **Noisy-neighbour test (Phase 5):** org A runs at 10× load while org B's p95 stays within
  its SLO.

---

## 13. Org lifecycle

**`Org` record** (platform plane, `IOrgStore`, Mongo and Postgres). Fields:

- `id`, `displayName`;
- `status`: `ACTIVE` | `SUSPENDED` | `DELETING`;
- `tier`: `POOLED` | `DEDICATED`;
- `idpBinding` (Keycloak org id), `hostnames[]`, `defaultMemberRoles`;
- `region` (reserved);
- a quotas reference, `kmsKeyRef`, `createdAt`.

**Operations** (`/platform/orgs` plus MCP platform tools):

- **Create.** Creates the record, KEK, quota row, routing entries and `OrgSettings`.
  Creating the Keycloak Organization is optional, behind a flag.
- **Suspend / resume.** A suspended org gets 403 at the resolver, and the claim queries skip it.
- **Export.** A logical bundle: configs, conversations, memories, attachments, the audit
  chain and secret metadata (never secret values).
- **Import an instance as an org.** An existing option-A single instance comes in as a new
  org. Ids are kept; any collision is a hard refusal with a report.
- **Delete.** `DELETING` → crypto-shred → async purge of every Appendix B store and the
  archives → a deletion certificate in the platform ledger.
- **Move tier** (Phase 7): copy, dual-read window, cutover.

---

## 14. Verification

### 14.1 `MultiOrgConformanceIT`

Runs with Testcontainers on Mongo **and** Postgres, in multi mode with the `ENABLED` layout
(fresh bootstrap), orgs A and B, every role plus anonymous.

1. **Setup.** As A, create one of everything:
   - every resource type;
   - a conversation, memory, attachment and secret;
   - a schedule, trigger and channel integration;
   - a group conversation, audit entry and vector store;
   - an OAuth state, deployed agent and export archive;
   - branding.
2. **Probe.** As every role of B, and anonymously on B's host, call **every** REST operation
   from `/openapi` and every MCP tool, using A's ids. Expect 404, an empty list or a refusal.
   - A fixture registry maps parameter names to A's ids. An unknown parameter name fails
     the test.
   - Exemptions live in one reviewed file.
3. **Side channels.**
   - Channel inbound signed with A's integration cannot reach B.
   - A's `/v1` key cannot reach B.
   - A NATS message carrying B's org and A's ids is refused or filtered.
   - A loopback call made with `X-EDDI-Org: A` by a B-only user is refused.
4. **Second-layer backstop, on both datastores.** In a test profile, disable the app filter
   (`OrgSql` / `OrgCollections` predicates). The suite must stay green:
   - on Postgres, because RLS returns no foreign rows;
   - on Mongo, because `OrgGuardedCollection` refuses the unscoped commands, and with the
     wrapper also disabled, `OrgReadVerifier` refuses foreign documents.
   Every probe must end in 404, an empty result or a `[SECURITY]` refusal, never in another
   org's data.

### 14.2 Build guards

Report-only in Phase 1, failing the build from Phase 3. The guards check that:

- raw `MongoDatabase`, `MongoCollection`, `DataSource` and `getConnection()` appear only in
  `datastore/**` and the listed stores, and that every org-table access goes through
  `OrgCollections` / `OrgSql`;
- `Caffeine.newBuilder()` appears only in classified classes;
- `MongoClients.create`, `MongoClient` and `getDatabase(` appear only in `PersistenceModule`
  and `OrgDatabaseRouter`, and `GridFSBuckets.create` only takes the guarded database, so
  nothing can bypass `OrgGuardedMongoDatabase` (§8.5a);
- every JAX-RS implementation class and MCP tool class is `@OrgBound` or `@PlatformPlane`;
- every A.12 class is annotated or uses `OrgRequest.callBound`;
- every `@Scheduled` method uses the §7.4 patterns or is `@PlatformPlane`;
- every work-starter (§6.4) has `orgId`;
- `OrgScope.platform(`, `platform*` store methods and `OrgRoutingIndex` are used only by
  allow-listed classes;
- background code never injects `IRest*`;
- there are no raw `Executors.new*`, unwrapped `supplyAsync(`/`runAsync(` or
  `Thread.ofVirtual` outside `OrgPropagation`;
- `OrgDataCatalog` matches the actual collections and tables (an IT);
- every `eddi.*` key is classified in A.13 (a test diffs `application.properties` against
  the list);
- no `tenant` appears in new `eddi.org.*` keys or `Org*` classes.

### 14.3 Other checks

- **Mutation checks.** Remove each predicate or comparison (storage, helper, registry,
  cache, epoch assertion) and confirm the suite goes red. Script it.
- **Single mode.** All suites pass with the marker `ABSENT` and again with it `ENABLED`. The
  fallback counter stays 0, and the load test stays within 3 %.
- **Floor guard.** An IT sets `layout.required` above the binary's level and expects startup
  to be refused.
- **Docs.** Write `docs/multi-org.md` (operations) and `docs/multi-org-security.md` (STRIDE
  threat model per entry point), and add both to `docs/SUMMARY.md`.
- **Pentest.** An external pentest happens before the first external org.

---

## 15. Phases

**Stop after each phase** and wait for the user. PR counts are estimates.

### Phase 0 — Spikes, ADR, threat model (~2 PRs, no production code)

**Tasks:**

1. **Spike A, Agroal interceptor (§8.4).** Throwaway branch. Report the results.
   **Spike D, MongoDB second layer (§8.5a).** Use a throwaway branch: a delegating guarded
   database, a collection and a codec decorator. Report the methods covered, the decode
   paths and the overhead.
2. **Spike B, Keycloak 26.8 Organizations.** Use a compose realm with two orgs and one user
   in both. Capture the exact access-token claim for `scope=organization:*` and for the
   default scope. Confirm that ids are included, and how roles and groups appear.
3. **Spike C, floor guard.** Prototype `DataLayoutGuard` and confirm it can be enforced at
   startup on both datastores.
4. Write `docs/adr/0001-organizations.md` (create `docs/adr/`) covering every §16 LOCK item,
   updated with the spike results.
5. Draft `docs/multi-org-security.md`.

**Exit:** the user approves the ADR. **STOP**

### Phase 1 — Context and stamping, no behaviour change (~6–9 PRs)

**Tasks:**

1. Add `engine/org/`:
   - `OrgId`, `OrgContext` (bindings, transitions, epoch), `OrgScope`, `OrgPropagation`;
   - `OrgContextMissingException`, `OrgConfig` (`eddi.org.mode`), `OrgQuotaKeys`;
   - the `OrgResolver` SPI with `SingleOrgResolver`;
   - `OrgResolution`, `OrgRequest.callBound`;
   - the annotations `@OrgBound`, `@PlatformPlane`, `@OrgNeutral`;
   - `OrgMarker` and `IOrgMarkerStore` (state `ABSENT`);
   - `DataLayoutGuard` and `layout.required`. This makes Phase 1 the **floor release**.
2. Add `@OrgBound` to all JAX-RS implementation and MCP tool classes, and handle A.12 and
   the after-return work (§6.2).
3. Bind the org in every background entry point (A.7, A.8, startup and deploy paths) and
   wrap the hand-offs (§6.3) and the coordinator tasks.
4. Replace the 22 `getDefaultTenantId()` sites with
   `OrgQuotaKeys.keyFor(OrgContext.require())`, and deprecate the method.
5. Add `OrgCollections` and `OrgSql`, with predicates as no-ops before the marker, and route
   **every** store's DB access through them. **Stamp on every write.** Add the Postgres
   `org_id` column (§5.2). Add `orgId` to the §6.4 models, including `DeploymentInfo`, the
   deploy events and archives.
6. NATS: an unsigned `Eddi-Org` header on org-scoped messages. Presence advertises
   `org-ctx-1` and `markerState`.
7. Loopback forwards `X-EDDI-Org`; add `x-eddi-org` to the CORS headers.
8. `OrgDataCatalog` (Appendix B), the A.13 settings classification, and the build guards in
   report mode.
9. Docs (`docs/configuration-reference.md`, release notes naming the floor) and a changelog
   fragment.

**Exit:**

- All suites are green in single mode.
- A test writes one record per Appendix B store and asserts the `orgId` in the raw DB.
- The fallback counter is 0.
- The load test is within 3 %.
- The floor-guard IT is green.

### Phase 2 — Test harness (~2–3 PRs)

**Tasks:**

1. `OrgTestProfile`: an empty DB, multi mode, bootstrapped `ENABLED` (§5.3), two orgs, and a
   test resolver.
2. `MultiOrgConformanceIT` (§14.1) as a non-required CI job. It is **expected to be red**;
   its failures are the leak list.
3. `OrgEnablementMigration` steps 1 and 6 (backfill, integrity), proven on at least 10M
   Mongo conversation documents, with timing and load documented.

**Exit:** the harness runs in CI. The leak list is attached.

### Phase 3 — Enforce (~10–15 PRs, one per package)

**Tasks:**

1. SPI and descriptors, `findByOriginId`, substring search (§8.2).
2. Direct-store predicates. B.2 target pairs. Enablement steps 2–3: indexes, the
   marker switch with the barrier, `DROP DEFAULT`, the Mongo validators.
   `OrgGuardedMongoDatabase`, `OrgGuardedCollection` and `OrgReadVerifier` (§8.5a) in
   count-only mode. Their counters must be 0 across the conformance suite.
3. Registries (§8.7) and caches (§8.6).
4. NATS signature v2, value/payload org, archives, consumer binding (§8.9).
5. RAG names (§8.8).
6. SSRF forcing (§11.1).
7. Convert background `IRest*`/loopback calls (§7.3).
8. Claim-pattern jobs (§7.4).
9. Build guards fail the build.

**Exit:**

- The conformance suite is green for data access.
- The mutation checks behave as expected.
- Single mode is unchanged in both marker states, within 3 %.

### Phase 4 — Identity, routing, planes, settings (~8–10 PRs)

**Tasks:**

1. `Org`, `IOrgStore`, `OrgRoutingIndex`, `OrgApiKey`, `OrgSettings` (§9.6).
2. The `keycloak-organizations` resolver, `X-EDDI-Org` selection and host binding (§7.1, §9.1).
3. `OrgMembership` (roles and teams), `OrgRoleAugmentor`, `/orgs/current/members`, platform
   client roles, break-glass, and the multi-mode startup checks (§5.1, §9.3).
4. Principal pairs (§9.2) and org-scoped team spaces (§9.4).
5. Entry points: public chat, A2A, branding/shell/auth-config host binding,
   integration-id webhooks, `/v1`, OAuth state, the OpenAPI filter (§7.1).
6. The Operator split and revision bump, MCP tiers, plugin skills (§9.5).
7. `keycloak/eddi-realm.json`: an example org, the organization scope with ids, the
   `eddi-platform` client. Write `docs/multi-org.md`.

**Exit:**

- The conformance suite is fully green.
- `McpRoleTierParityTest` is extended and green.

### Phase 5 — RLS, crypto, fairness, complete enablement (~5–7 PRs)

**Tasks:**

1. `OrgRlsPoolInterceptor` per the Phase 0 spike, plus the connection-epoch assertion (§8.4).
   On Mongo, switch `OrgGuardedCollection` and `OrgReadVerifier` from counting to refusing
   (§8.5a). Both were built in Phase 3, task 2, in count-only mode.
2. Enablement steps 4–5 and the `ENABLED` marker. From here on, presence advertises
   `org-layout-1`.
3. Per-org KEK and AAD v2 (§11.2), per-org audit key and chain (§11.3).
4. Bulkheads and buckets. `OrgIterator` for the aggregate jobs.
5. `OrgIntegrityCheck`.

**Exit:**

- The RLS backstop run is green.
- The RLS overhead is benchmarked.
- The noisy-neighbour test passes.
- Enablement on a copy of a large dataset succeeds and resumes after being killed.
- The procedure is documented, including "no rollback below the floor".

### Phase 6 — Lifecycle and console (~5–7 PRs, includes UI)

**Tasks:**

1. `/platform/orgs` (create, suspend, resume, export, delete) and instance-as-org import (§13).
2. The Manager platform console (orgs, status, quotas, usage, enablement, members) and the
   org admin's settings. The Workforce org switcher. Read `ui/manager/AGENTS.md` and
   `ui/workforce/AGENTS.md` first.
3. Optional: the `issuer` resolver.

**Exit:**

- A create → use → export → delete round trip leaves nothing behind.
- An option-A export imports as an org and passes the conformance suite.

### Phase 7 — Dedicated tier and scale (~4–6 PRs)

**Tasks:**

1. `OrgDatabaseRouter` and online tier moves.
2. Lazy agent deployment with an LRU working set.

**Exit:**

- The conformance suite is green with one pooled and one dedicated org.
- A 1,000-org load test is documented: startup, heap, p95.

---

## 16. Decisions

LOCK means: decide it in the Phase 0 ADR. These decisions are expensive or impossible to
change later.

| # | Decision | Recommendation | Status |
|---|---|---|---|
| 1 | Org id format, reserved ids, principal shape | §2 format and reserved list; `default` constant; `(orgId, subject)` pairs | LOCK |
| 2 | Storage key shape | `orgId` field/column; strict predicates and org-leading indexes after the marker (claim indexes exempt); org-including unique keys after the switch; no PK rewrites; globally unique ids; NATS/KV keys stay id-keyed with the org in the value; 404 across orgs | LOCK |
| 3 | Context contract | `ScopedValue` with an epoch; the §6.1 transition table; explicit hand-offs; helpers read the context (no `OrgId` parameters on store interfaces); a connection never spans a binding change | LOCK |
| 4 | Marker, floor, enablement | No predicates before the marker; enablement runs in single mode; two-step switch with a presence barrier; floor release with `DataLayoutGuard`; no rollback below the floor | LOCK |
| 5 | Planes, roles, teams | Platform roles = client roles of `eddi-platform`; org roles and teams from `OrgMembership`; realm roles and groups ignored for orgs in multi mode; the "never visible" list | LOCK |
| 6 | Trust model for author-supplied infra | SSRF forced on; derived, hashed RAG names; per-org LLM keys by default; host-bound anonymous access; NATS signature v2 | LOCK |
| 7 | IdP | Keycloak Organizations, matched by org id from the access token; `issuer` later | LOCK (after Spike B) |
| 8 | Key hierarchy and AAD | Per-org KEK; AAD v2 with the org, v1 kept for `default`; KMS later | LOCK |
| 9 | Moving an org to the dedicated tier | No automatic criteria. A platform admin moves an org explicitly (§13 "Move tier"), informed by the per-org usage API. The plan provides the mechanism, not a policy | decided |
| 10 | Cross-org features | None; cross-org A2A only as external peers | recommend |
| 11 | Data residency | `region` field reserved | recommend |
| 12 | Datastores for the pooled tier | **Both MongoDB and PostgreSQL, fully supported and equally tested** (product owner, 2026-11-21). Second layer: RLS on Postgres; on Mongo the guarded collection boundary plus decode-time read verification (§8.5a). The conformance suite and the backstop run (§14.1 step 4) run on both | decided (LOCK the §8.5a design after Spike D) |
| 13 | Break-glass approval | One `eddi-platform-admin` with a reason and a TTL of at most 4 h; org admins are notified; full audit in the org's ledger; four-eyes approval optional via `eddi.org.break-glass.require-second-approver` | decided |

**Deviations log** (the implementer appends: date, phase, what, why):

- *(empty)*

---

## 17. Risks

| Risk | Mitigation |
|---|---|
| A missed store, cache, registry, filter or hand-off | Fail-closed context, helper-only DB access, guards, generated conformance suite, RLS (Postgres), mutation checks |
| A missed filter on Mongo pooled | The guarded collection refuses unscoped commands; the read verifier refuses foreign documents; `$jsonSchema` on writes; backstop run and mutation checks on Mongo too (§8.5a, §14.1) |
| A code path bypasses the guarded Mongo wrapper (e.g. a second `MongoClient`) | Build guard: `MongoClient`/`MongoClients` and `getDatabase(` appear only in `PersistenceModule` and the dedicated-tier router. Spike D lists every method that is used |
| Rollback below the floor | Floor release, `DataLayoutGuard`, documented prohibition; on Mongo a pre-floor binary reads unfiltered |
| Single-mode regression | No predicates before the marker, both marker states tested, 3 % gate, fallback counter |
| Marker switch while nodes run | `SWITCHING` with a presence barrier for one TTL; old and new unique keys coexist until then |
| Enablement load | Batched, throttled, resumable, leased; proven in Phases 2 and 5 |
| Agroal interceptor assumptions | Phase 0 spike; STOP before the fallback |
| Keycloak claim shape | Phase 0 spike before the IdP decision is locked |
| Thousands of orgs × eager deploy | Lazy deployment (Phase 7) |
| Realm roles or groups leaking across orgs | `OrgMembership` plus the augmentor drops token roles |
| Naming confusion | §2 plus a guard |

---

## Appendix A — inventories (regenerate before use)

Run the commands from `src/main/java/ai/labs/eddi/`.

**A.1 Storage SPI users** — `grep -rl IResourceStorageFactory --include=*.java .` (31):
- configs/agents/mongo/AgentStore
- configs/agenttests/mongo/AgentTestSuiteStore
- configs/apicalls/mongo/ApiCallsStore
- configs/channels/mongo/ChannelIntegrationStore
- configs/connections/mongo/ConnectionStore
- configs/descriptors/mongo/DocumentDescriptorStore
- configs/dictionary/mongo/DictionaryStore
- configs/groups/IGroupConversationStore
- configs/groups/mongo/{AgentGroupStore, GroupConversationStore, GroupWorkspaceStore, SharedArtifactStore}
- configs/llm/mongo/LlmStore
- configs/mcpcalls/mongo/McpCallsStore
- configs/output/mongo/OutputStore
- configs/parser/mongo/ParserStore
- configs/propertysetter/mongo/PropertySetterStore
- configs/rag/mongo/RagStore
- configs/rules/mongo/RuleSetStore
- configs/snippets/mongo/PromptSnippetStore
- configs/workflows/mongo/WorkflowStore
- datastore/{AbstractResourceStore, DataStoreProducers, DescriptorStore, IResourceStorage, IResourceStorageFactory, ISubstringSearchIndexing}
- datastore/mongo/MongoResourceStorageFactory
- datastore/postgres/PostgresResourceStorageFactory
- engine/agenttests/AgentTestRunStore
- engine/memory/ConversationDescriptorStore

**A.2 Direct Mongo** — `grep -rl "MongoCollection<" --include=*.java .` (48):
- configs/branding/mongo/BrandingStore
- configs/connections/names/MongoConnectionNameClaimStore
- configs/deployment/mongo/MongoDeploymentStorage
- configs/migration/{MigrationLogStore, MigrationManager, V6QuteMigration, V6RenameMigration}
- configs/properties/mongo/{MongoUserMemoryStore, PropertiesMigrationService, UserMemoryIdentityIndexes}
- configs/variables/mongo/GlobalVariableStore
- connections/grants/MongoConnectionGrantStore
- connections/oauth/MongoOAuthStateStore
- connections/settings/MongoConnectionSettingsStore
- datastore/mongo/{GridFsAttachmentStore, MongoResourceStorage}
- engine/alerts/mongo/{AlertEventStore, AlertRuleStore}
- engine/audit/AuditStore
- engine/groups/budget/MongoTeamBudgetStore
- engine/groups/lessons/mongo/MongoLessonHistoryStore
- engine/groups/outcomes/mongo/MongoOutcomeEventStore
- engine/hitl/oversight/analytics/mongo/MongoApprovalDecisionStore
- engine/hitl/oversight/autonomy/mongo/MongoAutonomyStateStore
- engine/hitl/oversight/samples/mongo/MongoAuditSampleStore
- engine/hitl/tools/mongo/HitlToolJournalStore
- engine/memory/{ConversationMemoryStore, MongoConversationCheckpointStore}
- engine/pricing/mongo/MongoModelPriceCatalogStore
- engine/ratings/mongo/ConversationRatingStore
- engine/runtime/DatabaseLogs
- engine/schedule/mongo/MongoScheduleStore
- engine/security/spaces/directory/MongoUserDirectoryStore
- engine/security/spaces/notifications/MongoWorkspaceNotificationStore
- engine/security/spaces/settings/MongoWorkspaceSettingsStore
- engine/spendcontrol/mongo/MongoSpendControlChangeStore
- engine/tenancy/MongoTenantQuotaStore
- engine/triggermanagement/mongo/{AgentTriggerStore, UserConversationStore}
- engine/updatecheck/MongoSystemSettingStore
- integrations/email/ledger/{MongoEmailMailboxStateStore, MongoEmailMessageLedger, MongoEmailSuppressionStore}
- integrations/slack/hitl/MongoSlackApprovalRecordStore
- modules/ingestion/mongo/{MongoIngestedFileStore, MongoIngestionStateStore}
- modules/llm/tools/quota/MongoToolQuotaStore
- secrets/persistence/MongoSecretPersistence

**A.3 Direct Postgres** — `grep -rlE "\bDataSource\b" --include=*.java .` (51). `*` marks platform infrastructure; `†` marks an author-supplied external database, which RLS does not cover:
- configs/connections/names/PostgresConnectionNameClaimStore
- connections/grants/PostgresConnectionGrantStore
- connections/oauth/PostgresOAuthStateStore
- connections/settings/PostgresConnectionSettingsStore
- datastore/postgres/{PostgresAgentTriggerStore, PostgresAlertEventStore, PostgresAlertRuleStore,
  PostgresApprovalDecisionStore, PostgresAttachmentStore, PostgresAuditSampleStore, PostgresAuditStore,
  PostgresAutonomyStateStore, PostgresBrandingStore, PostgresConversationCheckpointStore,
  PostgresConversationMemoryStore, PostgresConversationRatingStore, PostgresDatabaseLogs,
  PostgresDeploymentStorage, PostgresFieldIndexSwaps*, PostgresGlobalVariableStore, PostgresHealthCheck*,
  PostgresHitlToolJournalStore, PostgresIngestedFileStore, PostgresIngestionStateStore,
  PostgresLessonHistoryStore, PostgresMigrationLogStore*, PostgresMigrationManager*,
  PostgresModelPriceCatalogStore, PostgresOutcomeEventStore, PostgresResourceStorage,
  PostgresResourceStorageFactory, PostgresScheduleStore, PostgresSpendControlChangeStore,
  PostgresStartupSchemaLock*, PostgresSubstringSearchIndexes, PostgresUserConversationStore,
  PostgresUserMemoryStore}
- engine/groups/budget/PostgresTeamBudgetStore
- engine/security/spaces/directory/PostgresUserDirectoryStore
- engine/security/spaces/notifications/PostgresWorkspaceNotificationStore
- engine/security/spaces/settings/PostgresWorkspaceSettingsStore
- engine/tenancy/PostgresTenantQuotaStore
- engine/updatecheck/PostgresSystemSettingStore*
- integrations/email/ledger/{PostgresEmailMailboxStateStore, PostgresEmailMessageLedger, PostgresEmailSuppressionStore}
- integrations/slack/hitl/PostgresSlackApprovalRecordStore
- modules/llm/impl/{EmbeddingStoreFactory†, PgVectorConnectionPools†, PgVectorPoolHandle†}
- modules/llm/tools/quota/PostgresToolQuotaStore
- secrets/persistence/PostgresSecretPersistence

**A.4 Loopback / `IRest*` in background** — `grep -rl IRestInterfaceFactory --include=*.java .` (12):
- backup/impl/StructuralMatcher
- configs/agents/AgentCascadeService
- configs/rest/RestVersionInfo
- engine/mcp/{McpAdminTools, McpConversationTools, McpToolUtils}
- engine/runtime/client/factory/{CurrentVersionRouting, IRestInterfaceFactory, RestInterfaceFactory}
- engine/security/spaces/ResourceAccessGuard
- engine/setup/{AgentSetupService, ApiAgentSyncService}

**A.5 Direct Caffeine** — `grep -rl "Caffeine.newBuilder" --include=*.java .` (39):
- configs/agents/{AgentConfigurationCache, AgentSigningService}
- configs/branding/BrandingService
- configs/variables/GlobalVariableResolver
- connections/ConnectionRegistry
- engine/a2a/AgentCardService
- engine/alerts/AlertRuleCache
- engine/audit/AuditAgentSigner
- engine/caching/CacheFactory
- engine/cluster/ClusterMessageSigner
- engine/hitl/oversight/analytics/ApprovalAnalyticsService
- engine/hitl/oversight/autonomy/AutonomyOverlayService
- engine/internal/TurnIdempotencyService
- engine/internal/groups/GroupSigningGuard
- engine/mail/MailDnsResolver
- engine/ratings/ConversationRatingPolicy
- engine/security/ConversationReviewPolicy
- engine/security/spaces/directory/UserDirectory
- engine/security/spaces/notifications/WorkspaceNotifications
- integrations/email/reply/EmailReplySender
- integrations/openai/AgentModelResolver
- integrations/webhook/WebhookEventHandler
- modules/llm/guardrails/impl/ExpressionGuardrail
- modules/llm/impl/{ChatModelRegistry, ContextualToolsProvider, EmbeddingModelFactory, EmbeddingStoreFactory, LlmCircuitBreakers, PgVectorConnectionPools, PromptSnippetService, TokenCounterFactory, WorkflowTraversal}
- modules/llm/tools/ToolRateLimiter
- modules/nlp/impl/RestSemanticParser
- modules/rag/RagIngestionService
- modules/templating/impl/TemplatingEngine
- secrets/SecretResolver
- ui/{BrandedShellRenderer, BrandedSwaggerUiRoute}

**A.6 `getDefaultTenantId()` call sites** (22). The 23rd grep hit is the declaration:
- engine/groups/budget/TeamBudgetService
- engine/internal/ConversationTitleService (×2)
- engine/internal/RestAgentAdministration (×2)
- engine/pricing/SystemSpendMeter (×3)
- engine/runtime/internal/DreamService (×3)
- engine/runtime/internal/LessonConsolidationService (×2)
- engine/spendcontrol/SpendControlChangeService
- engine/tenancy/TurnCostMeter (×2)
- modules/llm/impl/LlmSpendGate (×3)
- modules/llm/impl/LoopSideCallSpend
- modules/llm/impl/ToolLoopRunner (×2)

**A.7 `@Scheduled` classes** — `grep -rl "@Scheduled" --include=*.java .` (20):
- backup/impl/RestExportService
- connections/oauth/OAuthStateMaintenance
- engine/agenttests/AgentTestRunRecovery
- engine/groups/budget/TeamBudgetMaintenance
- engine/hitl/{HitlCrashRecoveryObserver, HitlPendingMetrics}
- engine/hitl/oversight/OversightMetrics
- engine/hitl/tools/HitlToolJournalMaintenance
- engine/memory/rest/RestConversationStore
- engine/pricing/ModelPriceCatalogService
- engine/runtime/DatabaseLogRetention
- engine/runtime/internal/{AgentDeploymentManagement, SchedulePollerService}
- engine/security/AuthStartupGuard
- engine/updatecheck/UpdateCheckService
- integrations/email/{EmailInboundSweeper, notify/HitlEmailNotifier, reply/EmailReplySender}
- modules/llm/impl/ChatModelRegistry
- modules/llm/tools/quota/ToolQuotaMaintenance

**A.8 NATS, KV, presence, cluster** — `grep -rlE "io\.nats|JetStream|KeyValue|ClusterSubjects|IClusterEventBus" --include=*.java .`. Known members:
- configs/variables/GlobalVariableResolver
- connections/ConnectionRegistry
- engine/caching/CacheFactory
- engine/cluster/{AuditClusterSupport, NatsSharedKv, ErasureTombstones, ClusterArchiveStore, ClusterPresence, ClusterProducers, JetStreamDeadLetterStore}
- engine/cluster/admin/{ClusterActivityLog, ClusterWatcher, ClusterAdminService}
- engine/cluster/events/{IClusterEventBus, JetStreamEventBus, LocalEventBus}
- engine/cluster/lease/{NatsLeaseManager, KvLeaseManager}
- engine/cluster/rpc/NatsClusterRpc
- `ClusterConversationCoordinator` (find its package)
- engine/mail/AutoReplyDetector
- engine/runtime/internal/{AgentDeploymentManagement, WorkflowFactory}
- engine/userevents/UserEventPublisher
- secrets/SecretResolver

**A.9 Hand-off and after-return search:**
`grep -rnE "Executors\.new|newVirtualThread|ManagedExecutor|supplyAsync\(|runAsync\(|thenApplyAsync|emitOn\(|runSubscriptionOn\(|Thread\.ofVirtual|AsyncResponse|executor\.(execute|submit)|StreamingOutput|SseEventSink|Multi<" --include=*.java .`

**A.10 Entry points without a user token:**
- integrations/openai/RestOpenAiAdapter
- integrations/slack/rest/RestSlackWebhook
- integrations/webhook/rest/RestWebhookChannel
- engine/a2a/RestA2AEndpoint
- the email sweeper
- `/connections/callback`
- public chat in `engine/api/IRestAgentEngine`
- `ui/Branded*Route`
- `/manage/__auth_config__.js` (`ui/IRestManagerResource`)

**A.11 Existing `tenantId` fields** (vault, variables, quota or connection meaning, see §2) — `grep -rlE "String tenantId" --include=*.java .` (~60). Each keeps its meaning; the org is added next to it.

**A.12 Code outside resource methods** — `grep -rlE "implements (ContainerRequestFilter|ContainerResponseFilter|SecurityIdentityAugmentor|ExceptionMapper)|@ServerRequestFilter|@Route\(|Router router" --include=*.java .` (33), plus the quarkus-mcp-server route:
- engine/audit/rest/AdminActionAuditFilter
- engine/exception/{ClientErrorExceptionMapper, ConnectionExceptionMapper, ConversationNotFoundExceptionMapper, IllegalArgumentExceptionMapper, InputTooLargeExceptionMapper, MismatchedJsonInputExceptionMapper, ParameterConversionExceptionMapper, RejectedExecutionExceptionMapper, ResourceAlreadyExistsExceptionMapper, ResourceModifiedExceptionMapper, ResourceNotFoundExceptionMapper, ResourceStoreExceptionMapper, UnreadableBodyExceptionMapper}
- engine/gdpr/{ProcessingRestrictedExceptionMapper, ProcessingRestrictionUnavailableExceptionMapper}
- engine/hitl/oversight/rest/AgentOriginatedRequestFilter
- engine/internal/{MetricsScrapeRoute, RequestArrivalStamp}
- engine/runtime/rest/interceptors/{DocumentDescriptorFilter, LegacyPathRewriteFilter}
- engine/security/NamelessPrincipalAugmentor
- engine/security/spaces/directory/UserDirectoryRecorder
- engine/security/spaces/SpaceHeaderFilter
- engine/tenancy/rest/{QuotaAccountingUnavailableExceptionMapper, QuotaExceededExceptionMapper}
- integrations/openai/{OpenAiAuthFilter, OpenAiExceptionMapper}
- integrations/webhook/{rest/RestWebhookChannel, WebhookEventHandler}
- ui/{BrandedOpenApiRoute, BrandedShellRoute, BrandedSwaggerUiRoute}

**A.13 Settings classification.** Produce it in Phase 1 from `src/main/resources/application.properties` and the `@ConfigProperty`/`@ConfigMapping` classes. Classify every `eddi.*` key as one of:
- **platform**, e.g. NATS, datastore, SSRF deny list, metrics;
- **org setting with deployment fallback** (moves to `OrgSettings`, §9.6), e.g. mail sender and allowed domains, Slack, CORS origins, public base URL, `/v1` keys, branding;
- **per-agent config**, which needs no change.

## Appendix B — data classification (`OrgDataCatalog`)

### B.1 Classes

| Class | Org-scoped | Platform |
|---|---|---|
| Configuration resources: agents, workflows, rules, llm, apicalls, mcpcalls, output, parser, dictionary, propertysetter, rag, snippets, groups, channels, connections, agent tests, descriptors, deployments | ✓ | |
| Runtime: conversation memory, descriptors, checkpoints, user conversations, user memories, attachments, ratings, outcomes, lessons, HITL journal, approvals, autonomy, audit samples, schedules, triggers, team budgets, spend-control changes, alert events and rules, ingestion state and files, tool quota, email ledger, Slack approval records, OAuth state, grants, connection names and settings, workspace settings and notifications, user directory, branding, global variables, secrets, audit ledger, DB logs, vector stores, `OrgMembership`, `OrgSettings`, **export archives** (`ClusterArchiveStore`, `tmp/archives`), agent signing keys | ✓ | |
| Quota rows and usage | ✓ (key per §12) | admin in the platform plane |
| Model price catalog | org overrides | platform defaults |
| Org records, `OrgRoutingIndex`, `OrgApiKey` lookup, `OrgMarker`, `layout.required`, platform audit ledger, break-glass grants | | ✓ |
| Migration log, system settings, update check, schema lock, health, cluster activity, NATS leases (keys), erasure tombstones (keys; values carry the org) | | ✓ |

Every PR that creates a collection or table adds it here and to `OrgDataCatalog`.

### B.2 Unique keys and upsert targets (switch at the marker, §5.3)

In Phase 1, write the table `store · today's key / ON CONFLICT target · post-switch key with org` for each store below.

**Mongo `unique(true)`** (25 indexes in 18 files):
- configs/connections/names/MongoConnectionNameClaimStore
- configs/deployment/mongo/MongoDeploymentStorage
- configs/properties/mongo/{MongoUserMemoryStore, UserMemoryIdentityIndexes}
- connections/grants/MongoConnectionGrantStore
- connections/oauth/MongoOAuthStateStore
- engine/alerts/mongo/AlertEventStore
- engine/groups/outcomes/mongo/MongoOutcomeEventStore
- engine/hitl/oversight/autonomy/mongo/MongoAutonomyStateStore
- engine/hitl/tools/mongo/HitlToolJournalStore
- engine/ratings/mongo/ConversationRatingStore
- engine/tenancy/MongoTenantQuotaStore
- engine/triggermanagement/mongo/{AgentTriggerStore, UserConversationStore}
- integrations/email/ledger/MongoEmailMessageLedger
- integrations/slack/hitl/MongoSlackApprovalRecordStore
- modules/ingestion/mongo/MongoIngestionStateStore
- secrets/persistence/MongoSecretPersistence

**Postgres `UNIQUE` / `ON CONFLICT (`** (18 constraints, 60 targets):
- configs/connections/names/PostgresConnectionNameClaimStore
- connections/grants/PostgresConnectionGrantStore
- connections/settings/PostgresConnectionSettingsStore
- datastore/postgres/{PostgresAlertEventStore, PostgresApprovalDecisionStore, PostgresAuditStore,
  PostgresAutonomyStateStore, PostgresBrandingStore, PostgresConversationRatingStore,
  PostgresDeploymentStorage, PostgresGlobalVariableStore, PostgresHitlToolJournalStore,
  PostgresIngestedFileStore, PostgresIngestionStateStore, PostgresLessonHistoryStore,
  PostgresMigrationLogStore*, PostgresModelPriceCatalogStore, PostgresOutcomeEventStore,
  PostgresResourceStorage, PostgresUserConversationStore, PostgresUserMemoryStore}
- engine/audit/{AuditLedgerService, AuditStore}
- engine/groups/budget/PostgresTeamBudgetStore
- engine/security/spaces/directory/PostgresUserDirectoryStore
- engine/security/spaces/settings/PostgresWorkspaceSettingsStore
- engine/tenancy/PostgresTenantQuotaStore
- engine/updatecheck/PostgresSystemSettingStore*
- integrations/email/ledger/{PostgresEmailMailboxStateStore, PostgresEmailMessageLedger}
- integrations/slack/hitl/PostgresSlackApprovalRecordStore
- modules/llm/tools/quota/PostgresToolQuotaStore
- secrets/persistence/PostgresSecretPersistence

Example: in `PostgresUserMemoryStore`, `ON CONFLICT (user_id, key) WHERE visibility = 'global'` becomes `(org_id, user_id, key) WHERE visibility = 'global'`.

## Appendix C — configuration keys

| Key | Default | Meaning |
|---|---|---|
| `eddi.org.mode` | `single` | `single` \| `multi` |
| `eddi.org.resolver` | `single` (single), `keycloak-organizations` (multi) | §9.1 |
| `eddi.org.claim` | `organization` | Token claim that carries the org |
| `eddi.org.platform.role-claim-path` | `resource_access/eddi-platform/roles` | Platform roles (§9.3) |
| `eddi.org.host-binding` | `false` (single), `true` (multi) | §7.1 |
| `eddi.org.enablement.allowed` | `false` | Must be `true` to start enablement (§5.3) |
| `eddi.org.enablement.batch-size` | `1000` | Backfill batch size |
| `eddi.org.postgres-rls` | follows the marker | `on`, or `off-i-understand` (PgBouncer transaction mode) |
| `eddi.org.jobs.max-per-org-per-tick` / `scan-factor` | `50` / `20` | §7.4 |
| `eddi.org.metrics.tag-org` / `max-tagged-orgs` | `false` / `200` | §11.5 |
| `eddi.org.ssrf.cluster-cidrs` | (empty) | §11.1 |
| `eddi.org.agents.max-resident` | (unset = eager) | Phase 7 |
| `eddi.org.multi.preview-acknowledged` | `false` | Required for multi mode until the pentest |
| `eddi.org.break-glass.require-second-approver` | `false` | Four-eyes approval for break-glass (§9.3) |
| `eddi.datastore.postgres.migration-username` / `-password` | (main credentials) | Optional role split (§8.4) |
| `quarkus.http.cors.headers` | add `x-eddi-org` | §7.3 |

`eddi.tenant.default-id` keeps its meaning: the quota key in single mode. There is no `eddi.org.default-id`.

## Appendix D — new types at a glance

- **Context and binding:** `OrgId`, `OrgContext`, `OrgScope`, `OrgPropagation`, `OrgContextMissingException`, `OrgConfig`, `OrgQuotaKeys`, `OrgResolution`, `OrgRequest`, `@OrgBound`, `@PlatformPlane`, `@OrgNeutral`.
- **Identity and resolution:** `OrgResolver` (+ `SingleOrgResolver`, `KeycloakOrganizationsOrgResolver`, later `IssuerOrgResolver`), `OrgMembership`, `OrgRoleAugmentor`, `OrgRoutingIndex`, `OrgApiKey`.
- **Org records:** `Org`, `IOrgStore`, `OrgSettings`.
- **Marker, layout and migration:** `OrgMarker`, `IOrgMarkerStore`, `DataLayoutGuard`, `OrgEnablementMigration`, `OrgIntegrityCheck`, `OrgDataCatalog`.
- **Data access:** `OrgCollections`, `OrgSql`, `OrgRlsPoolInterceptor`, `OrgGuardedMongoDatabase`, `OrgGuardedCollection`, `OrgReadVerifier`, `OrgKey<K>`, `OrgDatabaseRouter` (Phase 7).
- **Jobs:** `OrgIterator` (Phase 5).
- **Tests:** `MultiOrgConformanceIT`, `OrgTestProfile`.
