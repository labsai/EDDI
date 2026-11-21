# ADR 0001 — Organizations: Shared Multi-Tenancy on One EDDI Cluster

| | |
| --- | --- |
| **Status** | **Accepted** on 2026-11-21 by the product owner, with DV-2 decided as the memoised interceptor. Decision 9 locks only the bundle container; its record rules stay a proposal for Phase 6 |
| **Date** | 2026-11-21 |
| **Plan** | [`planning/multi-tenancy-plan.md`](../../planning/multi-tenancy-plan.md) (the "plan"; § numbers below are its sections) |
| **Threat model** | [`multi-org-security.md`](../multi-org-security.md) (draft) |
| **Supersedes** | the 2026-04 "Strategy B" design (`@RequestScoped TenantContext`, filtering only in the storage SPI) |

This ADR locks the decisions the plan marks **LOCK** in §16. Each one is expensive or impossible
to change after Phase 1. It records the results of the four Phase 0 spikes and the deviations
from the plan that they forced. A critical review after acceptance found further holes in the
MongoDB design and in the bundle's trust model. They are folded in as DV-14 to DV-17 and in
decision 9; nothing in them reverses a decision that was accepted.

Every fact below was checked against `main` at `a2acb28e86`, on 2026-11-21. The inventory counts
the plan relies on were regenerated with its Appendix A commands, and all of them are still
correct: 31 storage-SPI files, 48 `MongoCollection<` files, 51 `DataSource` files, 380
`getConnection()` calls, 39 `Caffeine.newBuilder` files, 22 `getDefaultTenantId()` call sites,
20 `@Scheduled` classes, 21 MCP tool files, 33 A.12 classes, 0 `@Transactional`, and 7 files that
call `setAutoCommit(false)`.

---

## 1. Context

Several customer organizations ("orgs") are to share one EDDI cluster, each isolated as if it
had its own instance. A deployment that never enables this must not notice it. The plan (§1–§14)
holds the full design. This ADR covers only what has to be fixed now.

## 2. Spike results

The spike code is throwaway. It lives on the local branch `test/org-phase0-spikes`, which is not
pushed and never will be: `6e3031066b` (A), `6e5f1065ee` (D), `d75ae514fa` (C), and
`f5aeb96dc0`. The last one holds the booted interceptor, the probe of HTTP during the guard,
the update and `filter()` rules and the GridFS trace. The test logs quoted below are under
`spike-evidence/`. Results that were only printed to the console are copied into
`spike-evidence/console-transcript.md`: process exit codes, the `curl` status codes and the
collection and table listings. Spike B is the script `spike-evidence/spike_b_keycloak.py`, run against a
throwaway realm. All four ran on this workstation against Testcontainers
`postgres:16-alpine`, `mongo:7.0` and `quay.io/keycloak/keycloak:26.8.0`. The timings are
loopback numbers from Docker on Windows: they compare variants and are not production figures.
The Phase 5 benchmark remains the gate (§5.1, "≤ 3 % p95").

### 2.1 Spike A — PostgreSQL RLS through an Agroal pool interceptor (§8.4)

The spike used Quarkus 3.40.1.2 with Agroal 3.2.1 and PostgreSQL 16. It ran a real Agroal pool
with the same connection cache Quarkus installs (`QuarkusNettyConnectionCache`, a per-thread
`FastThreadLocal` cache), RLS-enabled tables, a `NOSUPERUSER NOBYPASSRLS` role, and the plan's
policies.

| Question | Result |
| --- | --- |
| Registered for the default datasource? | **Yes, booted.** A plain `@ApplicationScoped` `AgroalPoolInterceptor` bean ran in the packaged jar with the `postgres` profile. It logged `onConnectionCreate` and `onConnectionAcquire` from Quarkus' own pool, including the guard's first acquire on `main`. The bytecode agrees: `io.quarkus.agroal.runtime.DataSources` collects every interceptor bean with the datasource's qualifier (`@Default` here) and passes them to `AgroalDataSource.setPoolInterceptors(...)` |
| Fires on every acquire, thread-local cache hits included? | **Yes.** 5 `getConnection()` calls produced 5 `onConnectionAcquire` calls. The last 4 reused the same cached physical connection |
| On the caller's thread? Sees the `ScopedValue`? | **Yes**, on platform and on virtual threads. A virtual-thread caller bound to org `b` read only `b`'s rows |
| Values carried over to the next borrower? | **No**, as long as every acquire overwrites both GUCs. An unbound acquire after a bound one read `[]` |
| An exception in `onConnectionAcquire` aborts the borrow? | **Yes.** The caller gets the exception. The connection is not leaked: afterwards all 4 of the pool's 4 connections could be borrowed |
| Harmless when `quarkus.datasource.active=false`? | **Yes, booted.** The same jar on MongoDB, with the datasource inactive, started normally. After three API requests it had logged **0** interceptor calls. The requests' status codes were not recorded |
| Overhead | A single-threaded microbenchmark, not the p95 gate. Contention on the memo map was not measured. Naive interceptor (one `set_config` round trip per acquire), one-statement autocommit pattern: **210 → 460 µs/op**, so one extra round trip on every DB call, and most of the ~380 call sites are short. **Memoised interceptor** (skips `set_config` when the physical connection already holds the bound values): **210 → 220 µs/op, within noise**. In the steady state, 1 round trip in 15,000 acquires. The memo was chosen (DV-2) |
| Does `WITH CHECK` refuse a foreign-org insert, or an insert that relies on the column `DEFAULT 'default'` while another org is bound? | **Yes**, both fail with SQLSTATE `42501` |
| Does a table owner bypass RLS under `FORCE`? | **No.** With `FORCE`, the owning plain role sees only its org |
| Can the app role escape on its own? | **Yes**, as the plan's threat model states: `set_config('eddi.platform','on')` makes every row visible. RLS stops missed filters, not SQL injection |
| **Does a superuser bypass RLS?** | **Yes, even with `FORCE`.** `rolsuper` and `rolbypassrls` ignore every policy. The role the stock images create, `POSTGRES_USER` in `docker-compose.postgres-only.yml` and in `helm/eddi/templates/postgres.yaml`, **is a superuser**. See deviation **DV-1** |

Two further findings:

- **Wrapper identity.** The `Connection` passed to the interceptor is a different wrapper
  object from the one returned to the caller. A map keyed by the wrapper never matches. The
  epoch and memo map must be keyed by the physical connection,
  `connection.unwrap(PGConnection.class)`. See **DV-2**.
- **JTA bypass.** In Agroal's bytecode, `internalGetConnection` goes through
  `handlerFromTransaction()`. A connection already enlisted in a JTA transaction is handed
  out again **without** calling `onConnectionAcquire`. EDDI has no `@Transactional` today, so
  this path is unused. A rebinding inside one JTA transaction would keep the first binding's
  GUCs. See **DV-3**.

### 2.2 Spike D — MongoDB second layer (§8.5a)

The spike used driver 5.6.5 and Mongo 7.0, with EDDI's real codec registry (the
`PersistenceModule` shape, including `JacksonProvider`). It ran a prototype guarded database and
collection, plus a decode-time verifier.

**Which collection methods the code calls.** These counts come from an identifier regex over
`src/main/java`, so they are approximate: the regex also matches things like `matcher.find(`
and misses chained calls. The method *set* is what matters, and the guard denies anything
outside it by default. The approximate counts: `find` ~313, `createIndex` ~110, `updateOne`
~81, `deleteMany` ~52, `deleteOne` ~34, `insertOne` ~32, `updateMany` ~31, `replaceOne` ~28,
`findOneAndUpdate` ~26, `withWriteConcern` 5, `withReadPreference` 4, `listIndexes` 4,
`dropIndex` 4, `estimatedDocumentCount` 3, `renameCollection` 2, `insertMany` 2, `withTimeout` 1,
`withDocumentClass` 1, `drop` 1, `createIndexes` 1.

Counted exactly per call site in the 48 A.2 files plus `MongoResourceStorage`: **9 `aggregate`**,
in `MongoDeploymentStorage`, `UserMemoryIdentityIndexes`, `AuditStore`,
`ConversationMemoryStore` ×3, `ConversationRatingStore` ×2 and `MongoIngestedFileStore`;
**3 `distinct`**, in `AlertEventStore`, `ConversationMemoryStore` and `MongoUserDirectoryStore`;
and **40 `countDocuments`** across all of `src/main/java`.

- **Unfiltered and database-level calls.** Unfiltered `find()` calls exist in
  `MongoDeploymentStorage` and in the migrations; under the guard they become platform
  methods. Database-level calls exist too: `MongoResourceStorage` runs
  `runCommand({collMod: …})` and `listCollections()`.
- **Absent.** There is no `bulkWrite` and no `watch`. `ClientSession` appears only in an
  unrelated HTTP-client class.
- **Clients.** There are two `MongoClients.create` calls: `PersistenceModule`, and
  `EmbeddingStoreFactory`, which connects to author-supplied Atlas vector stores.
- **GridFS.** There are two `GridFSBuckets.create` calls: `GridFsAttachmentStore` and
  `MongoIngestedFileStore`.

| Probe (bound org `a`) | Guarded result |
| --- | --- |
| `find(orgId=a)`, `find(and(orgId=a,_id=…))`, ClientSession overload, `withDocumentClass(BsonDocument)` | allowed |
| `find(_id=b1)`, `find()`, `find(orgId=b)`, `find(or(orgId=a,…))`, `find(orgId in [a,b])` | refused `[SECURITY]` |
| Same unscoped find through `withReadPreference(...)` or a `ClientSession` overload | refused: every `with*` result is wrapped again |
| `countDocuments()`, `estimatedDocumentCount`, `drop`, `watch` | refused (the last three are platform-only) |
| `aggregate` without a leading org `$match`; `$lookup` (no pipeline) or `$unionWith` on an org collection whose sub-pipeline is unscoped | refused |
| `aggregate` with a leading org `$match`, then `$merge`/`$out` into an org collection, or a `$facet` with unscoped sub-pipelines | **not probed, and the prototype would let it through**: it inspected only `$lookup`, `$unionWith` and `$graphLookup`, at top level. Found in review. Decision 12 now allow-lists stage types (DV-14) |
| `updateOne` / `deleteMany` without org equality | refused |
| **An update document that moves data out of the org:** `updateOne(orgId=a, $set orgId=b)`, `updateMany(orgId=a, $unset orgId)`, `$rename n→orgId`, `$set orgId.x`, a pipeline-style update | refused, by the update-document rule (DV-12). Without that rule the filter check alone let all of these through, and the `$jsonSchema` validator only requires that `orgId` is *present* |
| `$setOnInsert orgId=a` on an upsert; an upsert whose filter has only the org equality | allowed. The upserted document gets `orgId: "a"` from the equality filter |
| **`find(orgId=a).filter(_id=b1)`**: `FindIterable.filter()` *replaces* the filter | without a `filter()` rule: **returned org `b`'s document** with the verifier off, and was refused at decode with it on. With the `FindIterable.filter()` rule: refused (DV-13) |
| `insertOne` without `orgId`, or with a foreign `orgId`, for `Document` and for a Jackson POJO (the POJO is encoded to check it) | refused |
| A projection that excludes `orgId` | rewritten, so `orgId` is still returned |
| No org bound | refused |

**The second layer alone.** In this run the guard was turned off, to simulate a store whose
helper call is wrong, and the verifier stayed on:

- a foreign `find`, an unfiltered `find`, and a foreign `findOneAndUpdate` on `Document` and on a
  Jackson POJO were all **refused at decode** (`orgId=b bound=a`);
- `countDocuments()` returned every document in the collection: 3 in the first run, and 5 in
  the final run after its upserts. The verifier never sees counts;
- an `aggregate` with `$group` was refused, because its output has no `orgId`. The verifier
  therefore cannot be applied to aggregation output at all (**DV-5**).

**Decode-path findings that change §8.5a:**

1. A codec in the **central registry cannot know the collection**. `Codec.decode` has no
   namespace, so it cannot tell org collections from platform ones. The verifier must be
   installed per collection by the guarded collection, through
   `withCodecRegistry(verifying ∘ base)`. See **DV-4**.
2. The codec registry is consulted for nested sub-documents too, and those carry no `orgId`
   (`sub: {x: 1}` and the POJO's nested object). An outermost-only depth counter, a
   thread-local that is safe because the sync driver decodes on the caller's thread, fixes it.
   Nested documents decoded fine.
3. The Jackson path already decodes into a `RawBsonDocument` before mapping
   (`JacksonCodec.decode`), so the check can read `orgId` from those raw bytes for free.
4. **GridFS cannot be built on the guarded database.** `GridFSBuckets.create(guardedDb)` fails
   on its first upload. A trace with the guard off, recording every collection call the bucket
   made, shows:
   - **Upload:** `files.find()` with no filter (its index check), `listIndexes` and
     `createIndex` on both collections, `chunks.insertOne({files_id, n, data})`, then
     `files.insertOne(GridFSFile)`.
   - **`find(metadata.orgId=a)`:** `files.find()` with the caller's filter applied afterwards
     through `FindIterable.filter(...)`.
   - **Download by id:** `files.find()` plus `filter(_id)`, then
     `chunks.find({files_id, n: {$gte: 0}})`.
   - **Delete by id:** `files.deleteOne({_id})`, then `chunks.deleteMany({files_id})`.

   None of the chunk operations carry an org, and the index check is unfiltered by design. See
   **DV-6**.

**Overhead.** 200-document scans and 2,000 point finds, 3 rounds per mode. Two runs were made:
`spikeD-overhead.log`, and the final `spikeD.log`, which also repeats the no-copy mode. The
ranges below cover both runs:

| Mode | Point find | Jackson scan | `Document` scan |
| --- | --- | --- | --- |
| Raw driver (baseline) | 366–651 µs | 1.0–3.6 ms | 1.0–1.9 ms |
| Guard + verifier, **with an extra raw copy** | −12 to +8 % | **0 to +65 %** | **−4 to +71 %** |
| Guard + verifier, **no extra copy** (check the decoded `Document`; check inside `JacksonCodec`) | −8 to +5 % | **−4 to +33 %** | −16 to +25 % |
| Guard only | −5 to +7 % | −8 to +8 % | −12 to +25 % |

These are loopback runs of about 50 scans each on a Windows workstation, and the round-to-round
spread is as large as the effect. They show that the extra copy is the expensive part. They do
**not** show that the no-copy verifier meets the 3 % budget. The extra-copy variant is rejected,
and the no-copy variant is the design (DV-4). Whether it meets the budget is decided by the
Phase 5 load test, not by these numbers.

### 2.3 Spike B — Keycloak 26.8 Organizations (§9.1)

The spike used a throwaway realm `orgspike` with `organizationsEnabled=true`, two orgs `acme`
and `globex`, user `both` in both orgs (plus realm role `eddi-admin` and group `team-x`), user
`one` in `acme` only, and a public client with direct grants. Below are the decoded **access
tokens**, verbatim and reduced to the relevant claims.

The built-in `organization` client scope is an **optional** client scope. Its single mapper is
`oidc-organization-membership-mapper`, configured as `claim.name=organization`,
`multivalued=true`, access, ID and introspection tokens all `true`. `addOrganizationId` is **not
set**.

With the default mapper, scope `organization:*`, user `both`:

```json
"organization": ["globex", "acme"],
"realm_access": {"roles": ["default-roles-orgspike", "offline_access", "uma_authorization", "eddi-admin"]},
"scope": "organization:* profile email"
```

After setting `addOrganizationId=true` on that mapper, scope `organization:*`, user `both`:

```json
"organization": {
  "globex": {"id": "76ac7d16-a4f0-4d6f-a9fc-14c11011e7ec"},
  "acme":   {"id": "8503affa-ef72-4c2f-9a29-4cc23cdf8812"}
}
```

Scope `organization:acme`, user `both`: `"organization": {"acme": {"id": "8503affa-…"}}`.

| Request | Access-token `organization` claim |
| --- | --- |
| Default scopes, or `openid` | **absent** |
| `scope=organization`, password grant | **absent**: the scope was granted (`"scope": "profile organization email"`) but no claim was issued. In the browser flow this scope asks the user to pick an org. That flow was **not** exercised in the spike |
| `organization:*` | every org the user belongs to |
| `organization:<alias>`, member | that one org |
| `organization:globex`, user `one` (not a member) | **absent, silently**: the scope is dropped from the token's `scope` and no error is raised |

Further results:

- Realm roles (`eddi-admin`) are identical in every org's token. Keycloak issues no
  org-scoped roles. This confirms §9.3.
- No `groups` claim appears without a groups mapper. EDDI's realm adds one, and its groups are
  realm-global. This confirms §9.4.
- **An alias cannot be renamed in 26.8.** `PUT` with a new alias returns
  `400 {"errorMessage":"Cannot change the alias"}`. Matching by id stays the rule anyway
  (DV-9).

### 2.4 Spike C — floor guard at startup (§5.4)

The prototype `DataLayoutGuardSpike` is a `StartupEvent` observer at
`@Priority(Integer.MIN_VALUE)`. It reads `layout.required`. On Mongo that is a document in
`platform_layout`; on Postgres it is a row, checked with `to_regclass` first. It throws when the
required level is above the supported one. It ran in the packaged jar
(`./mvnw package -DskipUi=true`, `java -jar quarkus-run.jar`) against fresh containers:

| Datastore | `layout.required` | Result |
| --- | --- | --- |
| MongoDB 7.0 | 2 (binary supports 1) | exit **1**; `IllegalStateException: [SECURITY] The data layout requires level 2 …`. The database still holds **only** `platform_layout`, so nothing wrote before the guard |
| PostgreSQL 16, guard held for 15 s (`spike.guard.sleep-ms`) | 0 | `curl` against the fixed HTTP port **during** the guard returned no connection (`000`). After the guard returned, `/q/health/ready` answered `200`. **The HTTP listener opens only after the guard**, so no request can reach EDDI before it |
| MongoDB 7.0 | 1 | normal start ("started in 5.064s. Listening on …") |
| PostgreSQL 16 (`postgres` profile) | 2 | exit **1**, same message. The schema still holds **only** `platform_layout`, so no store created a table first |

The guard took about 130 ms, mostly the first connection. It ran **before**
`AuthStartupGuard` (`PLATFORM_BEFORE` = 0), `PostgresStartupSchemaLock` (1), `ClusterBootstrap`
(50), `AgentDeploymentManagement` (60) and every `@Startup(1000)` module. It reads without the
schema lock, which is fine because it only reads.

**Not covered by the prototype.** It read through EDDI's one client, built with
`ReadPreference.nearest()` (`PersistenceModule`). On a replica set, a lagging secondary can
miss a just-raised `layout.required`, so the real guard reads with primary read preference and
majority read concern (DV-11). Exit codes, the `curl` results and the collection and table
listings are in `spike-evidence/console-transcript.md`.

---

## 3. Decisions

### LOCK 1 — Org id format, reserved ids, principal shape

**Accepted as in plan §2 and §9.2.**

- Ids match `^[a-z0-9][a-z0-9-]{1,62}$`. They are immutable and opaque, and never reused after
  deletion.
- `default` is a constant and cannot be configured.
- Reserved and never creatable: `platform`, `current`, `system`, `eddi`, `admin`, `api`.
  `default` exists only because it was there before.
- Principals are `(orgId, subject)` pairs, with `orgId` in its own field. A string prefix is
  never used.
- The Keycloak organization id (a UUID) is a routing key in `OrgRoutingIndex`. It is never the
  EDDI org id.

### LOCK 2 — Storage key shape

**Accepted as in plan §8 and §16 decision 2.**

- Every org collection or table has an `orgId` / `org_id` field.
- After the marker, predicates are strict and indexes lead with the org, except the claim
  indexes.
- Unique keys include the org after the switch.
- Primary keys are never rewritten.
- Ids stay globally unique.
- NATS and KV keys stay keyed by id, with the org in the value.
- Requests across orgs get 404.

### LOCK 3 — Context contract

**Accepted as in plan §6.1, with DV-2, DV-3 and DV-10.**

- The binding is a `ScopedValue` with an epoch, and follows the transition table in §6.1.
- Every hand-off is explicit (`OrgPropagation`).
- `OrgCollections` and `OrgSql` read the context themselves. Store interfaces get no `OrgId`
  parameter.
- A connection never spans a change of binding. The epoch assertion compares against a value
  recorded per **physical** connection (DV-2).
- JTA-enlisted acquires are outside the contract (DV-3).

### LOCK 4 — Marker, floor, enablement

**Accepted as in plan §5, with DV-11.**

- No predicates are added before the marker.
- Enablement runs in single mode.
- The switch has two steps, `SWITCHING` and then the barrier, separated by one presence TTL.
- The floor release ships `DataLayoutGuard` as above, and rollback below the floor is
  forbidden.

### LOCK 5 — Planes, roles, teams

**Accepted as in plan §9.3–§9.4.**

- **Platform plane:** platform roles are client roles of `eddi-platform`, read from
  `resource_access/eddi-platform/roles`. Spike B showed client roles under
  `resource_access.<client>.roles`, but only for the built-in `account` client. No
  `eddi-platform` client was created, so Phase 4 verifies that client's mapping before relying
  on it.
- **Org plane:** org roles and teams come from `OrgMembership`. In multi mode, realm roles and
  realm groups are ignored for orgs. Spike B shows they would otherwise grant `eddi-admin` in
  every org.
- The "never visible to an org admin" list in §9.3 stands.

### LOCK 6 — Trust model for author-supplied infrastructure

**Accepted as in plan §11.1, §8.8 and §8.9.**

- SSRF protection is forced on.
- RAG names are derived and hashed.
- LLM keys are per org by default.
- Anonymous access is bound to a host.
- NATS uses signature v2.
- **Addition (DV-7):** a second `MongoClient`, the one in `EmbeddingStoreFactory` for Atlas
  vector stores, is allowed on the same terms as the pgvector pools. It is controlled by derived
  names and per-org credentials, not by the guarded database.

### LOCK 7 — IdP: Keycloak Organizations

**Accepted as in plan §9.1, pinned to the claim shape Spike B observed (DV-9).**

- The client requests `organization:*` explicitly, or `organization:<alias>` once the org is
  known. Plain `organization` is not relied on.
- The realm's `organization` mapper must set `addOrganizationId=true`. The resolver accepts
  **only** the object shape `{"<alias>": {"id": "<uuid>", …}}` and resolves by `id` through
  `OrgRoutingIndex`. It refuses the list-of-aliases shape with 403 `[SECURITY]` and a log line
  that names the missing mapper setting.
- A missing claim, an empty claim, or an id that does not resolve gives 403. Several orgs
  without `X-EDDI-Org` give 403. `X-EDDI-Org` must name one of the orgs in the claim.
- The claim is read from the verified access token, never from UserInfo.
- The Phase 4 realm example (`keycloak/eddi-realm.json`) ships the mapper with
  `addOrganizationId=true`. The multi-mode startup check cannot see the realm, so the resolver's
  refusal and the docs carry that requirement.

### LOCK 8 — Key hierarchy and AAD

**Accepted as in plan §11.2–§11.3.**

- Each org has a KEK, wrapped by the platform master key, and the KEK wraps the org's DEKs.
- AAD v2 is `(orgId|tenant|key|dekId)`. v1 is kept for values sealed before enablement, which
  open only in `default`.
- Each org's audit key is derived with HKDF from the platform audit key and the org id, and
  each org has its own chain. The pre-enablement chain becomes `default`'s genesis.
- Per-org KMS comes later. Until then the threat model records that exposing the master key
  exposes every org.

### Decision 9 — Org bundle format (container LOCKed; record rules are a proposal)

The **container** and its **compatibility rules** are locked, because third parties will read
them. The per-store record schemas, the rewrite rules and the import trust rules below are a
**proposal**. The Phase 6 spec (`docs/org-migrations.md`) settles them. None of this was
exercised in Phase 0.

**How it relates to the existing agent ZIP** (`backup/impl`,
[Import/Export an Agent](../import-export-an-agent.md)):

- **The agent ZIP** exports one agent's configurations, plus its snippets, schedules and
  connection documents. It is scrubbed (`${vault:REDACTED}`). On import, ids are re-minted and
  matched through `originId`. Conversations are not included.
- **The org bundle** is the opposite on every point: it holds **all** of an org's data,
  runtime included, and **keeps ids** (§13.1).
- The two stay separate formats. The agent ZIP remains the way to share or promote an agent,
  inside an org or between orgs. The bundle is only for moving a whole org.

```
<orgId>-<createdAt>.eddi-org.zip
├── manifest.json            REQUIRED, read first
├── stores/<catalog>.ndjson  one per OrgDataCatalog entry; one JSON record per line
├── blobs/attachments/<id>   attachment bytes (GridFS on Mongo, the BYTEA column on Postgres)
├── blobs/archives/<name>    export archives (ClusterArchiveStore)
├── audit/chain.ndjson       the org's audit chain, entries in order, with key ids
├── secrets/metadata.ndjson  secret names, vault tenants, dekIds, timestamps: NEVER values
├── secrets/sealed.bin       OPTIONAL, values re-encrypted for a recipient public key
└── signature.json           detached signature over manifest.json
```

- **`manifest.json`** holds:
  - `format: "eddi-org-bundle"` and `formatVersion: "1.0"`;
  - `sourceEddiVersion`, `sourceDatastore` (`mongodb` or `postgres`), `orgId`, `createdAt`;
  - `bundleId` (a UUID);
  - `stores[]`, each with `{name, file, records, sha256}`;
  - `blobs[]` with `{path, bytes, sha256}`;
  - `audit` with `{entries, sha256, keyIds[]}`;
  - `secrets` with `{metadataSha256, sealed: bool, sealedSha256?, recipientKeyId?, alg?}`. When
    `sealed.bin` is present its checksum is in the manifest, so the signature covers it like
    every other file;
  - `target` with `{deploymentId?, notAfter}`. It binds the bundle to one target, when known,
    and gives it an expiry, so a captured bundle cannot be replayed anywhere, indefinitely.
- **Records are datastore-neutral.** Each is the logical model, serialised through EDDI's
  Jackson configuration (`SerializationCustomizer`). Records are never raw BSON and never SQL
  rows, so M5 (Mongo ↔ Postgres) is the same import. `orgId` is **omitted** from records: the
  importer stamps the target org (§8.1).
- **Integrity.**
  - **Coverage.** Every file in the bundle except `signature.json` has a SHA-256 checksum in
    the manifest. `signature.json` holds `{alg: "Ed25519", keyId, signature}` over the
    manifest's exact bytes, which covers every file. Ed25519 is what `configs/agents/crypto`
    already uses, and the key is the source deployment's export key.
  - **Order.** An importer **verifies the signature, every checksum and `notAfter` before
    writing anything**.
  - **Archive safety.** Entry names are validated as for the agent ZIP: no absolute paths, no
    `..`, nothing outside the listed directories, and no duplicates. An entry that is not in
    the manifest refuses the import.
- **Trust.** A valid signature proves the bundle is intact, **not** that its source can be
  trusted.
  - **Who is trusted.** The target accepts only export keys that a platform admin has
    registered for that move (`keyId`). An unknown key refuses the import.
  - **M1 sources are customer-controlled.** For an M1 move the customer runs the source
    instance, so the signed content is treated as untrusted input either way.
  - **Routing records are never imported as trusted.** Hostnames, `OrgApiKey` hashes, channel
    integration ids and secrets, Keycloak org ids and pending `OAuthState` are only *listed* in
    the import report. A platform admin re-creates or confirms each one at the target. That
    re-checks the global uniqueness of hostnames and integration ids, and stops a bundle from
    claiming another org's routing.
- **Versioning.**
  - A reader refuses an unknown **major** version.
  - Within a major version it ignores unknown fields and unknown store files, and lists them
    in the import report.
  - A writer never removes or renames a field within a major version.
- **Secrets.** Values travel only in `secrets/sealed.bin`, encrypted to the target's public
  key, and only when the exporter passes `--with-secrets --recipient <key>`. The manifest
  records `alg`. The algorithm is fixed in the Phase 6 spec, and the container supports
  changing it by version.

**Proposed rewrite rule.** A value **derived from the org id or from a key** is never copied. The
importer **re-derives** it at the target. Plain references are kept as they are, because ids are
kept. The derived values known today:

| Value | At the target |
| --- | --- |
| `orgId` fields, including the `orgId` half of `(orgId, subject)` principals in owners, grants, memberships, `decidedBy`, user memories and GDPR subjects | stamped with the target org. Subjects are kept |
| `SpaceTenants` vault and variable tenant names (`t.<slug>.<hash>` and `u.<slug>.<hash>`, whose hash includes the org after §9.4) | recomputed. Secret metadata and `${vault:…}` references are rewritten to match |
| RAG physical names (`eddi_<org>_<rag>_<hash8>`, §8.8) | recomputed. Vectors are re-ingested, or copied under the new name |
| DEKs, KEK, AAD v2 | values are re-sealed under the target org's keys, from `sealed.bin` or after re-entry |
| Audit chain (HMAC key derived from the platform audit key and the org) | the imported chain is kept as a read-only, signature-verified segment. The target starts a new chain whose genesis records the last imported hash |
| Agent signing keys and nonce caches | not moved. Agents are re-signed at the target |

Open questions for Phase 6:

- whether quota counters and usage history travel;
- how grants that are in flight are handled (proposed: pending `OAuthState` is dropped, and
  users relink);
- whether `blobs/archives/` is worth carrying at all, since archives are transient.

### Decision 12 — MongoDB second layer (LOCKed after Spike D)

**Accepted with DV-4 to DV-8 and DV-12 to DV-17.** The two layers are the guarded boundary and
the per-collection decode verifier, plus the `$jsonSchema` validator on writes. The spike
prototype is the starting point, not the specification: the review after Spike D found holes in
it that this section closes (DV-14 to DV-17).

1. **`OrgGuardedMongoDatabase` and `OrgGuardedCollection` deny by default.** They are
   `java.lang.reflect.Proxy` instances over `MongoDatabase`, `MongoCollection` and **every
   iterable these return** (`FindIterable`, `DistinctIterable`, `AggregateIterable`,
   `ListIndexesIterable`), not hand-written delegates. A proxy covers every overload
   (`ClientSession`, `Class<T>`), and a method that a newer driver adds is refused until someone
   classifies it. The rules per method class are those of the spike table above, plus the ones
   below.
   - **Database methods are classified too (DV-15).** `getCollection` returns a guarded
     collection. `listCollections`, `createCollection` and `runCommand` are platform-only:
     today's only `runCommand` is the `collMod` in `MongoResourceStorage`, a schema change made
     at startup. `aggregate` and `watch` at database level are refused. Anything else is denied
     by default.
   - `with*` results are wrapped again.
   - **Iterables (DV-13, DV-16).**
     - `filter(...)` on `FindIterable` and `DistinctIterable` must carry the org equality,
       because it *replaces* the filter given to `find` or `distinct`.
     - `projection(...)` is rewritten so that `orgId` is always returned.
   - **Collation (DV-16).** Any `collation(...)` other than the simple binary one is refused
     on org collections, whether on find, count, distinct, update, delete or aggregate. A
     collation such as `alternate: "shifted"` ignores punctuation, so `orgId: "acme-x"` would
     also match `acmex`, and both are valid ids.
   - `createIndex`, `createIndexes`, `listIndexes`, `dropIndex` and `dropIndexes` pass, because
     they change schema and read no data.
   - `drop`, `estimatedDocumentCount`, `renameCollection`, `watch`, `mapReduce` and
     `bulkWrite` are platform-only.
   - Platform collections (Appendix B) are returned unwrapped.
   - **Update documents (DV-12).** `update*` and `findOneAndUpdate` refuse any operator that
     touches `orgId` or `orgId.*`, `$rename` onto it included. The one exception is `$set` or
     `$setOnInsert` of the **bound** org. Pipeline-style updates on org collections are refused.
     `replace*` and `insert*` documents must carry the bound org.
2. **`OrgReadVerifier` is installed per collection, not centrally (DV-4).** The guarded
   collection carries a `withCodecRegistry` that wraps the codecs, so only org collections are
   verified.
   - Only the outermost document is checked, tracked by a depth counter.
   - **Every document class is covered (DV-17).**
     - `Document`, `BsonDocument` and `RawBsonDocument` are checked on the decoded value.
     - `JacksonCodec` gets the check inside its existing `RawBsonDocument` step. That is the
       only change to the central registry, and it is a hook that is off unless the guarded
       collection passes a verification context.
     - A document class the verifier has no rule for is refused on org collections, not
       passed through. The prototype returned `null` for `BsonValue` subclasses other than
       `BsonDocument`, so `withDocumentClass(RawBsonDocument.class)` would have gone
       unverified.
   - `orgId` must be a **string** equal to the bound org. An array such as `["a", "b"]` is
     refused: an equality filter `orgId: "a"` matches it. For the same reason the
     `$jsonSchema` validator requires `orgId` with `bsonType: "string"`, not mere presence.
   - There is no extra copy. The prototype measured the no-copy variant for `Document` and
     Jackson; Phase 3 measures `BsonDocument` and `RawBsonDocument` when it builds them.
3. **Aggregation output is not decode-verified (DV-5); the pipeline is checked by an
   allow-list (DV-14).** `$group` output and value lists carry no `orgId`, so for aggregations
   the runtime layer is the pipeline check:
   - **First stage.** The first stage must be `$match` with the org equality.
   - **Allowed stages.** Every stage is from an allow-list: `$match`, `$project`, `$addFields`
     / `$set`, `$unset`, `$sort`, `$limit`, `$skip`, `$group`, `$count`, `$unwind`,
     `$replaceRoot` / `$replaceWith`, `$sortByCount`, `$bucket`, `$facet`, `$lookup`,
     `$unionWith`. Anything else is refused.
   - **Writing stages.** `$out` and `$merge` are refused on org collections. A leading org
     `$match` followed by `$merge into: <org collection>` could overwrite another org's
     documents by their global `_id`, and `$out` replaces the whole collection.
   - **Sub-pipelines.** The rules apply recursively inside `$facet`, `$lookup` and
     `$unionWith`. A `$lookup` or `$unionWith` on an org collection needs a sub-pipeline that
     starts with the org `$match`, so `localField`/`foreignField` alone is refused there.
     `$graphLookup` on an org collection is refused.
   - **Counts and distinct.** `distinct` and `countDocuments` filters must carry the org
     equality, and their iterables are wrapped as above.
   - **Call sites.** There are 9 `aggregate`, 3 `distinct` and 40 `countDocuments` call sites.
     Each `aggregate` and `distinct` site is listed in the guard's allow-list with its reviewed
     shape.
   - The conformance suite (§14.1) and the mutation checks (§14.3) cover each one.
   - `countDocuments` is likewise covered by the guard alone.
4. **GridFS goes through `OrgGridFsBucket`, not the guarded database (DV-6).** It is a facade
   over a bucket created on the raw database. It:
   - requires `metadata.orgId` equality on every files query;
   - stamps `metadata.orgId` on upload;
   - opens, downloads, renames and deletes by id only after reading the files document and
     verifying its `metadata.orgId`. Chunks are reachable only through a verified files id,
     which matches the traced internals: chunk calls are keyed only by `files_id`;
   - offers no lookup by filename (`openDownloadStream(String)`, `downloadToStream(String)`),
     because filenames are not unique across orgs, and no `drop()`, which is platform-only.

   The bucket's own unfiltered index-check `find()` stays inside the facade. The facade
   exposes no `find` without an org. GridFS is therefore a **single runtime layer**: chunks
   carry no `orgId`, so nothing can verify them at decode. The threat model lists it among the
   single-layer spots.

   The build guard (§14.2) changes accordingly: `GridFSBuckets.create` may appear only in
   `OrgGridFsBucket` (and in `OrgDatabaseRouter` in Phase 7).
5. **Exactly one second `MongoClient` is allowed:** `EmbeddingStoreFactory`, for author-supplied
   Atlas vector stores (DV-7). Like the pgvector pools (marked † in plan Appendix A.3: an
   author-supplied external database), it is outside the guarded database, and its controls
   are those of §8.8.

---

## 4. Deviations from the plan

These rows are also appended to the plan's §16 Deviations log.

| # | Where | Deviation | Why |
| --- | --- | --- | --- |
| DV-1 | §8.4, §5.1, §5.3 | **The role split is required in multi mode**, not optional hardening. The check that the connected role has neither `rolsuper` nor `rolbypassrls` runs at two points. First as an **enablement precondition**, before `SWITCHING`, so no deployment passes the point of no return on a role that RLS cannot bind. Then at **every multi-mode startup**. Single mode is unchanged and checks nothing. `docs/multi-org.md` documents creating `eddi_app` (`NOSUPERUSER NOBYPASSRLS`, owner or granted) and pointing `quarkus.datasource.username` at it. The Helm chart and compose files get an opt-in app role in Phase 5 | Spike A: superusers ignore RLS even under `FORCE`, and the stock `POSTGRES_USER` in compose and Helm is a superuser. Under the plan's wording, the most common install would have had no second layer at all |
| DV-2 | §8.4 | **Decided 2026-11-21: memo.** The plan said "every acquire overwrites both values". Instead, `OrgRlsPoolInterceptor` **memoises** per physical connection, keyed by `unwrap(PGConnection.class)` in a weak map, and issues `set_config` only when the bound `(org, platform)` pair differs from what it last set on that connection. The tightening rules:<br>• a memo entry is recorded only when `set_config` ran in autocommit. A connection that is not in autocommit at acquire time is always overwritten and never memoised, because a session `set_config` inside a rolled-back transaction reverts;<br>• **any existing memo entry is removed first** when an acquire is not in autocommit, and also when `set_config` fails. Otherwise the memo would keep saying X after a non-autocommit acquire set Y, and a later X acquire would skip `set_config` and run as Y;<br>• the binding epoch is recorded on **every** acquire, memo hit or not;<br>• the entry is dropped in `onConnectionDestroy`;<br>• a new build guard forbids any SQL other than the interceptor's from naming `eddi.org_id` or `eddi.platform`.<br>**Trade-off:** with the memo, a GUC changed by injected SQL persists for later borrowers of the same connection *in the same org* until the binding changes. Always overwriting resets it on every borrow. The threat model records this. The alternative is to keep the plan (always overwrite) and accept one extra round trip per acquire | Spike A: the naive interceptor adds a round trip to every acquire, and the memo removes it in the steady state. Separately from the choice: the caller's wrapper is a different object from the interceptor's, so the key must be the physical connection either way |
| DV-3 | §6.1, §14.2 | **No JTA on org data paths.** A new build guard refuses `@Transactional`, `QuarkusTransaction` and `UserTransaction` there; `narayana-jta` is an installed feature, so all three are available. `OrgSql` asserts the epoch on every statement path, so a JTA reuse after a rebinding fails closed instead of silently | Agroal hands out a JTA-enlisted connection again without calling the interceptor |
| DV-4 | §8.5a (2) | The read verifier is installed **per collection** by the guarded collection (`withCodecRegistry`), checks only the outermost document, and never copies. It is not a decorator in the central registry | `Codec.decode` has no namespace. Nested sub-documents pass through the registry too. The extra-copy variant cost up to +65 to +71 % on scans in the loopback runs |
| DV-5 | §8.5a, §14.1 | Aggregation, `distinct` and count results are **not decode-verified**. Their runtime layer is the pipeline/filter check, plus per-call-site review, conformance and mutation coverage | Their outputs carry no `orgId` by construction. Spike D shows the verifier refusing a legitimate `$group` |
| DV-6 | §8.5, §8.5a, §14.2 | GridFS goes through `OrgGridFsBucket` over the raw database, and the build guard allows `GridFSBuckets.create` only there. It replaces "GridFSBuckets.create only takes the guarded database" | The traced internals: an unfiltered index-check `find()`, filters applied through `FindIterable.filter()`, and chunk reads and deletes keyed only by `files_id` |
| DV-7 | §14.2, §8.8, App. A.2 | `EmbeddingStoreFactory`'s `MongoClients.create` is a second allowed client (author-supplied external Atlas store) | Found by Spike D's grep; the plan listed only `PersistenceModule` |
| DV-8 | §8.5a (1) | The guarded types are `Proxy`-based and deny by default | Overload coverage without hand-written delegates. New driver methods fail closed |
| DV-9 | §9.1 | `organization:*` is always requested explicitly. `addOrganizationId=true` is a documented realm requirement, and the list shape is refused with a diagnostic. A dropped `organization:<alias>` scope gives 403 | Spike B: plain `organization` issued no claim in the password grant. The default mapper emits aliases only. A non-member's `organization:<alias>` is dropped silently |
| DV-10 | §6.1 | `OrgContext.callAs(OrgId, Callable)` adapts to `ScopedValue.CallableOp` (`work::call`) | `ScopedValue.Carrier.call` takes `CallableOp<T, X>`, not `Callable` (Java 25) |
| DV-11 | §5.4, §14.3 | `DataLayoutGuard` is a `StartupEvent` observer at `@Priority(Integer.MIN_VALUE)`. That priority is reserved for it: `OrgConfig` observes at `MIN_VALUE + 1`, so nothing ties with it. Its record lives in the platform plane (`platform_layout`; the final name is set in Phase 1) and is read before the schema lock. On Mongo it reads with primary read preference and majority read concern, not through the client's `nearest()` default. Postgres checks `to_regclass` first, so a fresh install passes | Spike C. Review: a lagging secondary could hide a just-raised `layout.required` |
| DV-12 | §8.5a (1) | **An update-document rule.** No update operator may change, unset or rename onto `orgId` or `orgId.*`, except `$set`/`$setOnInsert` of the bound org. Pipeline updates on org collections are refused | Spike D: the plan's rule checked only filters. `updateOne(orgId=a, {$set: {orgId: "b"}})` would have moved a document into another org, and the validator only requires that `orgId` is present |
| DV-13 | §8.5a (1) | `FindIterable` is wrapped: `filter()` must carry the org equality, and `projection()` keeps `orgId` | Spike D: `find(orgId=a).filter(_id=b1)` *replaces* the filter and returned org `b`'s document with the verifier off. GridFS uses exactly this pattern internally |
| DV-14 | §8.5a (1) | Aggregation pipelines are checked against a **stage allow-list**, recursively (`$facet`, `$lookup`, `$unionWith`). `$out` and `$merge` on org collections are refused. `watch`, `bulkWrite` and `$graphLookup` on org collections are refused rather than allowed when scoped: today's code uses none of them | Review: the prototype checked only three stage types at top level, so a scoped `$match` followed by `$merge` into an org collection would have written across orgs. The plan allowed scoped `watch` and `$graphLookup`; with no caller, refusing them is the smaller surface |
| DV-15 | §8.5a (1), App. A.2 | `MongoDatabase` methods are classified: guarded `getCollection`; platform-only `runCommand`, `listCollections` and `createCollection`; database-level `aggregate` and `watch` refused; everything else denied by default | Review: the prototype passed every database method through, and EDDI calls `runCommand(collMod)` and `listCollections` |
| DV-16 | §8.5a (1) | Every iterable the guarded collection returns is wrapped deny-by-default, `DistinctIterable.filter()` included. A non-simple collation is refused on org collections | Review: `DistinctIterable.filter()` replaces the filter like `FindIterable.filter()`, and `distinct` output is not decode-verified (DV-5). A punctuation-ignoring collation makes `acme-x` equal `acmex` |
| DV-17 | §8.5a (2), §5.3 | The verifier covers every document class, refusing unknown ones on org collections. `orgId` must be a string, and the `$jsonSchema` validator requires `bsonType: "string"` | Review: `RawBsonDocument` was unverified in the prototype, and an array `orgId` matches an equality filter for each element |

---

## 5. Consequences

- **Single-tenant installs.** Nothing in this ADR changes their behaviour. DV-1 applies only
  in multi mode. With the DV-2 memo, the eventual RLS cost is negligible. On Mongo, the guard and verifier
  only count until the marker is set (§8.5a).
- **Phase 1 is the floor release.** Release notes must say so (DV-11).
- **What Phase 5 must still measure.** The microbenchmarks above are loopback numbers. Phase 5
  must show the 3 % p95 gate on `scripts/loadtest` with RLS (in the variant chosen for DV-2) and the Mongo guard and
  verifier (no-copy) **active**.
- **What the spikes did not verify.**
  - Phase 4 exercises each of these before LOCK 7 is relied on in production:
    - the browser authorization-code flow with plain `organization` (org picker);
    - token refresh carrying the selected org;
    - Keycloak's behaviour when an org is disabled;
    - client roles of an `eddi-platform` client.
  - Phase 3 builds the Mongo rules that DV-14 to DV-17 added after the spike. The prototype
    did not implement them, so each one needs its own refusal test and mutation check there.
  - The bundle's import trust rules (decision 9) are a design only.

## 6. Alternatives rejected

| Alternative | Why not |
| --- | --- |
| `@RequestScoped TenantContext` + SPI-only filtering (2026-04 Strategy B) | Most data access runs off the request thread, and most stores bypass the SPI (§3.2) |
| `SET LOCAL` + `@Transactional` everywhere | The stores run in autocommit, and there are 380 acquire sites. It would also land on the JTA path, which skips the interceptor (DV-3). It stays the fallback in §8.4 only if a later finding breaks the interceptor |
| A central Mongo codec decorator | It cannot tell org collections from platform ones, and it trips on nested documents (DV-4) |
| A `MongoCollection` wrapper that **rewrites** filters | It cannot cover aggregations, `$lookup`, GridFS internals or future methods, and silent rewriting hides missed scoping. Verify-and-refuse is kept (§8.3) |
| An org prefix in ids | It leaks the org into public ids, and every reference would be rewritten (LOCK 2) |
| One database or schema per org for the pooled tier | It does not scale to thousands of small orgs. That is the dedicated tier (Phase 7) |
