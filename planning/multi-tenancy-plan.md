# Multi-Tenancy Architecture Plan

**Status:** Planning  
**Created:** 2026-04-17  
**Scope:** Backend (EDDI repo) — no frontend changes

---

## 1. Problem Statement

EDDI's current architecture is **single-tenant by design**. All agents, conversations, user memories, audit logs, and configuration resources live in a **single shared database namespace** with **no organizational boundary**. When Keycloak is enabled, authentication proves _who you are_, but nothing prevents one authenticated user from seeing or modifying another organization's data.

This document evaluates exactly what exists, what's missing, and proposes two deployment strategies: **siloed** (instance-per-tenant, zero code changes) and **shared** (logical tenant isolation, deep code changes). An implementing conversation should be able to pick up this plan and execute without additional context.

---

## 2. Current State — Honest Inventory

### 2.1 What Already Works

#### Tenant Quota System (`ai.labs.eddi.engine.tenancy`)

**Files:**

- [TenantQuotaService.java](../src/main/java/ai/labs/eddi/engine/tenancy/TenantQuotaService.java) — Quota enforcement engine
- [ITenantQuotaStore.java](../src/main/java/ai/labs/eddi/engine/tenancy/ITenantQuotaStore.java) — Store interface with documented atomicity contract
- [InMemoryTenantQuotaStore.java](../src/main/java/ai/labs/eddi/engine/tenancy/InMemoryTenantQuotaStore.java) — In-memory implementation (single-instance only)
- [TenantQuota.java](../src/main/java/ai/labs/eddi/engine/tenancy/model/TenantQuota.java) — Record with `tenantId`, `maxConversationsPerDay`, `maxAgentsPerTenant`, `maxApiCallsPerMinute`, `maxMonthlyCostUsd`, `enabled`
- REST API: `GET/PUT/DELETE /admin/tenants/{tenantId}`, usage + reset endpoints

**Assessment:** Well-built. Atomic check-and-increment. Per-tenant metrics (`eddi.tenant.quota.allowed/denied`). BUT: the only _caller_ is `AgentOrchestrator.java:283` which hardcodes `tenantQuotaService.getDefaultTenantId()`. No request ever passes a real tenant ID derived from authentication context. The multi-tenant API surface exists but is inert.

#### Tenant-Scoped Secrets Vault (`ai.labs.eddi.secrets`)

**Files:**

- [SecretReference.java](../src/main/java/ai/labs/eddi/secrets/model/SecretReference.java) — `record(tenantId, keyName)`
- [VaultSecretProvider.java](../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java) — Per-tenant DEK management
- REST API: `/{tenantId}/{keyName}` for all CRUD + DEK rotation

**Assessment:** **Genuinely multi-tenant.** Secrets are partitioned by `(tenantId, keyName)`. Each tenant gets its own Data Encryption Key. DEK rotation is per-tenant. This is the one subsystem that truly works for multi-tenancy as-is.

#### Per-User Data Isolation

**Files:**

- [IUserMemoryStore.java](../src/main/java/ai/labs/eddi/configs/properties/IUserMemoryStore.java) — Queries scoped by `(userId, agentId)`
- [ConversationMemorySnapshot.java](../src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java) — Has `userId`, `agentId`, but **no `tenantId`**
- [RestGdprAdmin.java](../src/main/java/ai/labs/eddi/engine/gdpr/RestGdprAdmin.java) — Cascade delete by `userId`

**Assessment:** Good user-level isolation. Conversations are scoped to `userId + agentId`. GDPR erasure works per-user. But user isolation ≠ tenant isolation — a user in org A can query data belonging to org B's agents because agents themselves have no tenant ownership.

#### Authentication / Authorization

**Files:**

- [application.properties](../src/main/resources/application.properties) lines 130–158 — OIDC config
- [RestAgentManagement.java](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java) lines 286–289 — `checkUserAuthIfApplicable()`
- [McpToolUtils.java](../src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java) lines 33–39 — `requireRole()`

**Assessment:** Keycloak OIDC with bearer tokens, roles `admin`/`editor`/`viewer`. `@RolesAllowed("eddi-admin")` on sensitive endpoints. But roles are **global** — an admin is admin of _everything_. `SecurityIdentity` is injected in MCP tools and `RestAgentManagement`, but **no code ever extracts a tenant/org claim** from the JWT.

#### Observability

Quota metrics already tagged with `tenant` label. OpenTelemetry tracing with service name `eddi`. This is ready for multi-tenant dashboards.

---

### 2.2 What Does NOT Exist (The Gaps)

#### Gap 1: No Tenant Context Propagation

There is no concept of "current tenant" flowing through the request lifecycle. Specifically:

- No JWT claim extraction for `tenant_id` or `org_id`
- No `TenantContext` CDI bean or similar
- No middleware/filter that resolves tenant from request
- `TenantQuotaService.getDefaultTenantId()` always returns `"default"` (from config)

**Impact:** Even if you added tenant fields to every data model, there's no mechanism to set them from the authenticated user's context.

#### Gap 2: No Data Isolation in Resource Stores

The data layer stack is:

```
REST API → IResourceStore<T> → HistorizedResourceStore<T> → IResourceStorage<T>
                                                              ├── MongoResourceStorage
                                                              └── PostgresResourceStorage
```

Key observations:

- `IResourceStorage.read(id, version)` — no tenant parameter
- `IResourceStorageFactory.create(collectionName, ...)` — tenant-agnostic
- `HistorizedResourceStore` — no tenant filtering on any query
- `DescriptorStore.findResources(filters, ...)` — filters by field values but no tenant constraint
- `ResourceDescriptor` has `createdBy` (a URI) — this is the creating _user_, not a _tenant_

**This means:** Any agent configuration, workflow, behavior rule set, LLM config, etc. is globally visible. There is **zero** tenant scoping on any of the ~15 resource store implementations (agents, packages, behaviors, httpcalls, langchain, output, dictionaries, regulardictionaries, properties, prompts, groups, schedules, etc.).

#### Gap 3: No Tenant Scoping on Conversations

`ConversationMemorySnapshot` fields: `conversationId`, `agentId`, `agentVersion`, `userId`, `environment`, `conversationState`. **No `tenantId`.**

`ConversationMemoryStore` queries by `conversationId` or lists by `agentId`. No tenant filter.

`ConversationDescriptor` has `createdByUserName` — user attribution, not tenant.

#### Gap 4: No Tenant Scoping on User Memories

`IUserMemoryStore` queries by `(userId, agentId, groupIds)`. No tenant dimension. If two tenants happen to share the same userId scheme (e.g., both use email), their memories are indistinguishable.

#### Gap 5: Global RBAC

`McpToolUtils.requireRole(identity, authEnabled, "eddi-admin")` checks for a _global_ realm role. There is no per-tenant role check. Admin of tenant A can delete tenant B's agents. The `@RolesAllowed("eddi-admin")` on `RestGdprAdmin` is similarly global.

#### Gap 6: In-Memory Quota Store (Cluster Unsafe)

`InMemoryTenantQuotaStore` uses `ConcurrentHashMap` + `synchronized`. Correct for single-instance, but:

- All counters lost on restart
- In a 3-instance cluster, each tracks independently → quotas can be exceeded 3×
- The `ITenantQuotaStore` interface Javadoc already documents the need for DB-backed atomicity

#### Gap 7: No Tenant Lifecycle

No tenant onboarding, suspension, deletion, or billing integration. Tenants are implicitly created when a quota is set via REST. No tenant admin portal, no tenant-level configuration (e.g., allowed LLM providers per tenant).

---

## 3. Deployment Strategies

### Strategy A: Siloed Multi-Tenancy (Instance-per-Tenant)

**Approach:** Deploy a separate EDDI + database instance per tenant. Use infrastructure (Kubernetes namespaces, Docker Compose stacks, or cloud accounts) for isolation.

**Code changes required:** Zero.

**What you get:**

- Perfect data isolation (separate databases)
- Independent scaling per tenant
- Independent upgrades per tenant
- Independent secrets vaults
- Independent quota enforcement (even in-memory is fine — single instance per tenant)

**What you lose:**

- Higher operational overhead (more containers)
- No shared agent marketplace
- No cross-tenant analytics without aggregation
- Higher base resource cost

**When to use:** When you have few tenants (< 50), each with significant workloads. This is how AWS, Salesforce, and many SaaS products handle their largest enterprise customers.

**Implementation:**

1. Create a Kubernetes Helm chart or docker-compose template parameterized by `TENANT_ID`
2. Each tenant gets: EDDI container + MongoDB/PostgreSQL instance + Keycloak realm
3. Route traffic via subdomain (`tenant-a.eddi.example.com`) or path prefix
4. Use the existing secrets vault with default tenant `"default"` — each instance is a tenant
5. Document the pattern in `docs/deployment-multi-tenant.md`

---

### Strategy B: Shared Multi-Tenancy (Logical Isolation)

**Approach:** All tenants share one EDDI deployment and one database. Tenant boundaries are enforced in code at the data layer.

**Code changes required:** Deep. Touches ~30+ files across 6 packages.

**When to use:** When you want true SaaS with 100+ tenants, shared infrastructure costs, and a centralized admin plane.

> [!TIP]
> Despite touching ~30 files, the changes are **highly repetitive** — every store gets the same pattern (inject `Provider<TenantContext>`, add `tenantId`, filter queries). Each phase must build cleanly, compile, and pass all tests before the next begins. Do NOT attempt to implement all phases at once.

---

## 4. Shared Multi-Tenancy — Detailed Design

### Phase 1: Tenant Context Foundation

**Goal:** Establish the mechanism for extracting and propagating a tenant ID from every authenticated request.

#### 1a. Tenant Context Bean

**New file:** `src/main/java/ai/labs/eddi/engine/tenancy/TenantContext.java`

```java
@RequestScoped
public class TenantContext {
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String requireTenantId() {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return tenantId;
    }
}
```

#### 1b. Tenant Resolver Filter

**New file:** `src/main/java/ai/labs/eddi/engine/tenancy/TenantResolverFilter.java`

A JAX-RS `@PreMatching` `ContainerRequestFilter` that:

1. If OIDC enabled: extracts `tenant_id` claim from JWT (configurable claim name via `eddi.tenant.jwt-claim`). **If the claim is missing or blank, the filter MUST reject the request with HTTP 403** (fail-closed) to prevent accidental cross-tenant access via fallback.
2. If OIDC disabled: uses `eddi.tenant.default-id` from config (existing property)
3. Sets the resolved tenant ID into the `TenantContext` bean
4. Adds `tenantId` to MDC for structured logging

#### 1c. Configuration

**Modify:** `application.properties`

```properties
# Existing:
eddi.tenant.default-id=default
# New:
eddi.tenant.jwt-claim=tenant_id
```

#### 1d. Keycloak Configuration

Document how to add a `tenant_id` claim to JWTs:

- Keycloak user attribute `tenant_id`
- Protocol mapper: User Attribute → Token Claim

**Tests:**

- Unit test for `TenantContext` bean
- Unit test for `TenantResolverFilter` (mock JWT with/without claim)
- Integration test that verifies tenant propagation through a REST call

---

### Phase 2: Data Layer Tenant Isolation

**Goal:** Add `tenantId` to all stored resources and filter all queries by tenant.

This is the largest and most critical phase. There are two sub-strategies:

#### Option 2A: Per-Tenant Database (MongoDB database / PostgreSQL schema)

**Approach:** `IResourceStorageFactory.create()` uses the current tenant from `TenantContext` to select the target database/schema.

**Pros:** Zero changes to individual store implementations. Clean blast radius.  
**Cons:** Connection pool per tenant. Dynamic database selection is trickier with Quarkus/MongoDB driver.

#### Option 2B: Shared Database, Row-Level Filtering (Recommended)

**Approach:** Add `tenantId` field to every document/row. Filter all queries.

**Files to modify (resource config stores — all follow the same pattern):**

| Store              | Collection/Table         | File                                                          |
| ------------------ | ------------------------ | ------------------------------------------------------------- |
| AgentStore         | `agentstore`             | `configs/agents/mongo/AgentStore.java` + postgres equivalent  |
| WorkflowStore      | `packagestore`           | `configs/packages/mongo/WorkflowStore.java`                   |
| BehaviorStore      | `behaviorstore`          | `configs/behavior/mongo/BehaviorRulesStore.java`              |
| HttpCallsStore     | `httpcallsstore`         | `configs/httpcalls/mongo/HttpCallsStore.java`                 |
| LlmStore           | `langchainstore`         | `configs/langchain/mongo/LangChainStore.java`                 |
| OutputStore        | `outputstore`            | `configs/output/mongo/OutputStore.java`                       |
| DictionaryStore    | `regulardictionarystore` | `configs/regulardictionary/mongo/RegularDictionaryStore.java` |
| PropertyStore      | `propertystore`          | `configs/properties/mongo/PropertyStore.java`                 |
| PromptSnippetStore | `promptsnippets`         | `configs/prompts/mongo/PromptSnippetStore.java`               |
| GroupStore         | `groupstore`             | `configs/agents/mongo/GroupStore.java`                        |
| DescriptorStore    | multiple collections     | `datastore/mongo/DescriptorStore.java`                        |

**Files to modify (runtime data stores):**

| Store                       | Collection/Table          | File                                                        |
| --------------------------- | ------------------------- | ----------------------------------------------------------- |
| ConversationMemoryStore     | `conversationmemories`    | `engine/memory/ConversationMemoryStore.java`                |
| ConversationDescriptorStore | `conversationdescriptors` | `engine/memory/descriptor/ConversationDescriptorStore.java` |
| UserConversationStore       | `userconversations`       | `engine/triggermanagement/mongo/UserConversationStore.java` |
| UserMemoryStore             | `usermemories`            | `datastore/mongo/MongoUserMemoryStore.java` + postgres      |
| AuditStore                  | `auditledger`             | `engine/audit/AuditStore.java`                              |
| ScheduleStore               | `schedules`               | `engine/schedule/mongo/ScheduleStore.java`                  |
| DatabaseLogs                | `logs`                    | `engine/runtime/DatabaseLogs.java`                          |

**Implementation approach — modify `IResourceStorage` SPI:**

The cleanest approach is to modify `IResourceStorageFactory`:

```java
// Before:
<T> IResourceStorage<T> create(String collectionName, IDocumentBuilder builder,
                                Class<T> type, String... indexes);

// After — add TenantContext:
<T> IResourceStorage<T> create(String collectionName, IDocumentBuilder builder,
                                Class<T> type, String... indexes);
// (unchanged — factory receives TenantContext via CDI injection)
```

The `MongoResourceStorage` and `PostgresResourceStorage` implementations add a `tenantId` filter to every `find()`, `read()`, `store()`, `remove()`, and `findResources()` call. The `tenantId` is injected via the factory at construction time from the `TenantContext`.

**Critical consideration — request-scoped vs application-scoped:**
Resource stores are `@ApplicationScoped` singletons. `TenantContext` is `@RequestScoped`. The stores cannot hold a reference to a single tenant. Options:

1. **Inject `TenantContext` directly** into each store method call (breaks existing interface)
2. **Thread-scoped storage via method parameter passthrough** — ugly but explicit
3. **Inject `Provider<TenantContext>`** — stores inject `jakarta.inject.Provider<TenantContext>` and call `.get()` per request. This is the standard CDI pattern for scope mismatch. **Recommended.**

**MongoDB migration:**

- Run a one-time migration script that adds `tenantId: "default"` to every document in every collection
- Add a compound index on `(tenantId, _id)` to each collection
- All queries get an additional `Filters.eq("tenantId", tenantContext.get().requireTenantId())`

**PostgreSQL migration:**

- `ALTER TABLE ... ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'`
- Add index on `(tenant_id)` for every table
- All queries get `AND tenant_id = ?`

**Tests:**

- Unit tests for each store verifying tenant filtering
- Integration test: create resource as tenant A, verify invisible to tenant B
- Migration test: verify existing data gets `tenantId: "default"`

---

### Phase 3: Tenant-Scoped Authorization

**Goal:** Replace global RBAC with per-tenant role checks.

#### 3a. Keycloak Multi-Org Setup

Two viable approaches:

**Option A — Keycloak Organizations (v26+):**
Use Keycloak's built-in Organizations feature. Each org = a tenant. Users are members of orgs. Roles are per-org.

**Option B — Custom claim + application-level enforcement:**
Add `tenant_id` claim to JWT (Phase 1). Application code checks that the authenticated user's tenant matches the resource's tenant.

**Recommendation:** Option B is simpler and doesn't depend on Keycloak version.

#### 3b. Code Changes

**Modify `McpToolUtils.requireRole()`** to also check tenant:

```java
static void requireTenantAccess(SecurityIdentity identity, boolean authEnabled,
                                  TenantContext ctx, String resourceTenantId) {
    if (!authEnabled) return;
    String userTenant = ctx.getTenantId();
    if (!resourceTenantId.equals(userTenant)) {
        throw new ForbiddenException("Cross-tenant access denied");
    }
}
```

**Modify `RestGdprAdmin`** — verify the GDPR operation targets a user belonging to the admin's tenant.

**Modify `RestAgentManagement.checkUserAuthIfApplicable()`** — add tenant check.

**Modify all REST endpoints** that return lists — filter by tenant.

---

### Phase 4: Wire Quota System to Tenant Context

**Goal:** Make `TenantQuotaService` use the real tenant ID from the request context instead of `getDefaultTenantId()`.

**Files to modify:**

- [AgentOrchestrator.java](../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java) line 283 — change `tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId())` to use tenant from conversation memory or context
- [LlmTask.java](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java) — propagate tenant ID to orchestrator

**Note:** Inside the pipeline, `TenantContext` (request-scoped) may not be available. The tenant ID should be stored as a `ConversationProperty` at conversation init and read from memory in pipeline tasks. This follows the existing pattern (userId is stored in memory, not extracted from request context in tasks).

---

### Phase 5: Cluster-Safe Quota Store

**Goal:** Replace `InMemoryTenantQuotaStore` with a DB-backed implementation.

**New files:**

- `engine/tenancy/MongoTenantQuotaStore.java` — MongoDB implementation using `findAndModify` with conditional update
- `engine/tenancy/PostgresTenantQuotaStore.java` — PostgreSQL implementation using `UPDATE ... WHERE count < limit RETURNING`

**The `ITenantQuotaStore` interface already documents the atomicity contract** (see Javadoc lines 12–24). Implementations must use storage-level atomic operations. Java `synchronized` is NOT sufficient for cluster deployments.

**Activation:** Use `@LookupIfProperty(name = "eddi.datastore.type", stringValue = "mongodb")` (or `"postgres"`) to auto-select. Keep `InMemoryTenantQuotaStore` as `@DefaultBean` for zero-dependency dev mode.

**Migration:** Add `tenant_quotas` and `tenant_usage` collections/tables.

---

### Phase 6: Tenant Lifecycle (Optional — SaaS only)

**Goal:** Admin API for managing tenants as first-class entities.

**New files:**

- `engine/tenancy/model/Tenant.java` — record with `id`, `name`, `status` (active/suspended/archived), `createdAt`, `plan`
- `engine/tenancy/ITenantStore.java` — CRUD for tenants
- `engine/tenancy/rest/RestTenantAdmin.java` — REST endpoints: create, suspend, delete tenant
- MCP tools for tenant management

**This phase is only needed for shared multi-tenancy.** For siloed deployments, "tenant" = "deployment instance" and there's nothing to manage.

---

## 5. Migration Strategy

### For Existing Single-Tenant Deployments

All existing data gets `tenantId: "default"`. The system continues to work exactly as before. `eddi.tenant.default-id=default` remains the fallback.

### For New Multi-Tenant Deployments

1. Enable Keycloak with `tenant_id` claim
2. Create tenants via REST API
3. Create agents under each tenant (tenant comes from JWT)
4. All queries automatically scoped

### Backward Compatibility

- `quarkus.oidc.tenant-enabled=false` → all operations use default tenant → same as today
- No config changes needed for existing deployments
- Old ZIP exports without `tenantId` import into the default tenant
- Agent sync between instances preserves tenant boundaries

---

## 6. Verification Plan

### Unit Tests

- `TenantContext` bean — set/get/require
- `TenantResolverFilter` — JWT extraction, fallback to default
- Each store — create as tenant A, query as tenant B → empty
- `TenantQuotaService` — quota enforcement with real tenant IDs
- Cross-tenant access denial for GDPR, MCP, REST endpoints

### Integration Tests

- Full request lifecycle: authenticate → tenant resolved → create agent → visible only to same tenant
- Conversation: start conversation as tenant A → invisible to tenant B
- GDPR erasure: only deletes data within the requesting tenant
- Quota: tenant A exhausts quota → tenant B unaffected

### Commands

```bash
# Compile
./mvnw compile

# Unit tests
./mvnw test

# Integration tests
./mvnw verify -Pintegration-tests

# Smoke test with Docker
docker compose -f docker-compose.yml up -d
```

---

## 7. Recommended Path

Both strategies target **v6.x**. Strategy B is fully backward-compatible (opt-in via OIDC + JWT claim) and the code changes are repetitive/mechanical.

### Step 1: Strategy A — Siloed Documentation (Quick Win)

1. Document the siloed deployment pattern (`docs/deployment-multi-tenant.md`)
2. Create a parameterized docker-compose template
3. Create a Kubernetes Helm chart with per-tenant namespace isolation

### Step 2: Strategy B — Shared Tenancy Implementation

4. Phase 1: Tenant context foundation (3 new files)
5. Phase 2: Data layer tenant isolation (~20 stores, same pattern each)
6. Phase 3: Tenant-scoped authorization (~5 files)
7. Phase 4: Wire quota system to tenant context (1 line change)
8. Phase 5: Cluster-safe quota store (independent, useful even for single-tenant)
9. Phase 6: Tenant lifecycle REST API (optional, only if SaaS admin UI is needed)

Each phase is a separate commit, reviewed and tested independently.

### Backward Compatibility Guarantee

- `quarkus.oidc.tenant-enabled=false` (default) → all operations use `"default"` tenant → **identical behavior to today**
- Startup migration adds `tenantId: "default"` to all existing documents — idempotent, automatic
- REST API signatures do not change — tenant is implicit from JWT, not URL parameters
- Agent ZIP imports without `tenantId` are assigned to the importing user's tenant (or `"default"`)
- No changes to JSON config format (agent.json, langchain.json, etc.)
- quarkus-eddi SDK and EDDI-Manager require **zero changes**

---

## 8. Open Questions

1. **JWT claim name:** What should the tenant claim be called? `tenant_id`? `org_id`? (Keycloak Organizations uses `org.id`)
2. **MongoDB vs separate databases:** For shared tenancy, should we use one database with row-level filtering, or one database per tenant? Row-level is simpler for management; separate DBs give stronger isolation.
3. **Quota store priority:** Should the cluster-safe quota store be implemented regardless of multi-tenancy? (Recommended: yes — it's a standalone improvement.)
4. **Agent marketplace:** Should there be a concept of "shared" agents visible across tenants? (e.g., system agents, templates)
5. **Cross-tenant groups:** Can a group conversation include agents from different tenants? (Probably not — but needs explicit design decision.)
