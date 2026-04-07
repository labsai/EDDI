# Phase 11a: Persistent User Memory

## Overview

Persistent User Memory extends EDDI's existing property system with cross-conversation, cross-agent fact and preference retention. It adds a `Visibility` dimension to properties, a unified structured memory store, LLM tools for agent-driven memory management, and a background Dream consolidation cycle.

**Core principle**: EDDI already has 80% of persistent memory via the `longTerm` property flow. This phase closes the remaining gaps with minimal new code, zero pipeline changes, and full backward compatibility.

> [!IMPORTANT]
> **Implementation Status (2026-04-07):**
> - **Data model**: ✅ `IUserMemoryStore`, `MongoUserMemoryStore`, `PostgresUserMemoryStore`, `UserMemoryEntry`, `Property.Visibility` — all implemented
> - **LLM tools**: ✅ `UserMemoryTool` (rememberFact/recallMemories/forgetFact) — implemented with guardrails
> - **MCP tools**: ✅ `McpMemoryTools` — implemented
> - **REST API**: ✅ `IRestUserMemoryStore` + `RestUserMemoryStore` — implemented
> - **Dream consolidation**: ✅ `DreamService` — implemented (uses `SummarizationService`, `ScheduleFireExecutor`)
> - **Migration**: ✅ `PropertiesMigrationService` — implemented
> - **Conversation integration**: ✅ `Conversation.java` loads/stores user memories
> - **Conversation Chaining** (Strategy 3 from window management plan): ❌ Not yet implemented
> - See `docs/changelog.md` for implementation details


## Architecture

### Two Orthogonal Dimensions on Property

```
                    Scope (lifetime)
            ┌───────────────────────────┐
            │  step → conversation →    │
            │        longTerm / secret  │
            └───────────────────────────┘
                          ×
                Visibility (who sees it)
            ┌───────────────────────────┐
            │   self → group → global   │
            └───────────────────────────┘
```

- **`Scope`** (existing): `step`, `conversation`, `longTerm`, `secret`
- **`Visibility`** (new): `self` (default), `group`, `global`

Visibility only applies to `longTerm` properties. Defaults to `self` when unset — fully backward compatible.

### Runtime Flow

```
┌─────────────────────────────────────────────────────────────────┐
│       Property Flow (unified, replaces old IPropertiesStore)    │
│                                                                 │
│  Conversation.init()                                            │
│      └─→ IPropertiesHandler.loadProperties()                    │
│              └─→ IUserMemoryStore.readProperties(userId)        │
│                      └─→ "properties" collection (unchanged)    │
│      └─→ loadUserMemories(userId, agentId, groupIds)  [NEW]    │
│              └─→ IUserMemoryStore.getVisibleEntries(...)        │
│                      └─→ "usermemories" collection              │
│                      └─→ filter: self(agentId)                  │
│                                + group(groupIds)                │
│                                + global                         │
│      └─→ conversationProperties.putAll(...)                     │
│                                                                 │
│  Pipeline runs...                                               │
│      PropertySetterTask sets scope=longTerm + visibility=...    │
│      LlmTask reads {properties.key} in system prompts          │
│      LlmTask agent may call rememberFact/forgetFact tools       │
│                                                                 │
│  Conversation.postConversationLifecycleTasks()                  │
│      └─→ storePropertiesPermanently()                           │
│              └─→ IUserMemoryStore.upsert(entries)               │
│                      └─→ "usermemories" collection              │
│                                                                 │
│  Background (scheduled)                                         │
│      └─→ DreamConsolidationTask                                 │
│              └─→ runs on ScheduleFireExecutor (cron/heartbeat)  │
│              └─→ prune noise, resolve contradictions, summarize │
└─────────────────────────────────────────────────────────────────┘
```

---

## Configuration

Memory is configured at **three levels**, all within existing config models — no new workflow step types or extension URIs.

### Level 1: Agent Configuration — Master switch & memory behavior

```json
{
  "workflows": ["eddi://ai.labs.workflow/xyz?version=1"],

  "enableUserMemory": true,
  "userMemoryConfig": {
    "defaultVisibility": "self",
    "maxRecallEntries": 50,
    "maxEntriesPerUser": 500,
    "onCapReached": "evict_oldest",
    "recallOrder": "most_recent",
    "autoRecallCategories": ["preference", "fact"],
    "guardrails": {
      "maxKeyLength": 100,
      "maxValueLength": 1000,
      "maxWritesPerTurn": 10,
      "allowedCategories": ["preference", "fact", "context"]
    },
    "dream": {
      "enabled": false,
      "schedule": "0 3 * * *",
      "detectContradictions": true,
      "contradictionResolution": "keep_newest",
      "pruneStaleAfterDays": 90,
      "summarizeInteractions": false,
      "llmProvider": "openai",
      "llmModel": "gpt-4o-mini",
      "maxCostPerRun": 5.00,
      "batchSize": 50,
      "maxUsersPerRun": 1000
    }
  }
}
```

| Field | Default | Description |
|---|---|---|
| `enableUserMemory` | `false` | Master switch — when off, behaves exactly as today |
| `defaultVisibility` | `"self"` | Default for new entries when not specified |
| `maxRecallEntries` | `50` | Max entries loaded on conversation init |
| `maxEntriesPerUser` | `500` | Hard storage cap per user |
| `onCapReached` | `"evict_oldest"` | `"evict_oldest"` (auto-evict, return note) or `"reject"` (return error) |
| `recallOrder` | `"most_recent"` | Ordering when loading entries: `"most_recent"` (updatedAt DESC, zero overhead) or `"most_accessed"` (accessCount DESC, adds write-on-read) |
| `autoRecallCategories` | `["preference", "fact"]` | Which categories to auto-load |
| `guardrails.maxKeyLength` | `100` | Max characters for a memory key |
| `guardrails.maxValueLength` | `1000` | Max characters for a memory value |
| `guardrails.maxWritesPerTurn` | `10` | Max remember+forget operations per conversation turn |
| `guardrails.allowedCategories` | `["preference", "fact", "context"]` | Accepted categories — closed enum, unknown values default to `"fact"` with logged warning |
| `dream.enabled` | `false` | Background consolidation |
| `dream.schedule` | `"0 3 * * *"` | Cron expression — uses `ScheduleFireExecutor` infrastructure |
| `dream.detectContradictions` | `true` | LLM-based contradiction detection |
| `dream.contradictionResolution` | `"keep_newest"` | `"keep_newest"` (overwrite, log old value) or `"keep_both"` (flag both as conflicted) |
| `dream.pruneStaleAfterDays` | `90` | Deterministic pruning (0=disabled) |
| `dream.summarizeInteractions` | `false` | LLM summarization via shared `SummarizationService` (expensive, opt-in) |
| `dream.maxCostPerRun` | `5.00` | Hard cost ceiling in dollars — abort and resume next run when exceeded |
| `dream.batchSize` | `50` | Users processed per batch within a run |
| `dream.maxUsersPerRun` | `1000` | Max users processed per run (round-robin via `lastDreamProcessedAt`) |

### Level 2: PropertySetter Configuration — Properties with visibility

The existing PropertySetter config gains an optional `visibility` field on each property instruction:

```json
{
  "setOnActions": [
    {
      "actions": ["greet"],
      "setProperties": [
        {
          "name": "preferred_language",
          "fromObjectPath": "context.detected_language",
          "scope": "longTerm",
          "visibility": "global"
        },
        {
          "name": "user_timezone",
          "valueString": "{{context.timezone}}",
          "scope": "longTerm",
          "visibility": "self"
        },
        {
          "name": "greeting_count",
          "valueInt": 1,
          "scope": "conversation"
        }
      ]
    }
  ]
}
```

When `visibility` is omitted → uses `AgentConfiguration.userMemoryConfig.defaultVisibility`. When `enableUserMemory=false`, the field is ignored — properties persist the old way.

### Level 3: LLM Configuration — Memory tools for the agent

Memory tools are enabled via the existing `builtInTools` list on LLM tasks:

```json
{
  "tasks": [
    {
      "id": "main",
      "type": "openai",
      "actions": ["talk"],
      "agentMode": true,
      "builtInTools": ["calculator", "dateTime", "userMemory"],
      "parameters": {
        "model": "gpt-4o",
        "systemMessage": "You are a helpful assistant. You remember facts about users across conversations.\n\nUser memories:\n{{#each properties}}{{@key}}: {{this.valueString}}{{this.valueObject}}{{this.valueInt}}\n{{/each}}"
      }
    }
  ]
}
```

When `"userMemory"` is in `builtInTools`, the LLM agent gets three tools:
- `rememberFact(key, value, category, visibility)` — userId is implicit (from conversation context)
- `recallMemories()` — returns all memories for the current user
- `forgetFact(key)` — removes a memory for the current user

When not listed → LLM never sees the tools. Agent creator has full control.

> **Note**: The LLM tools do NOT take `userId` as a parameter. The tool implementation gets userId, agentId, and groupIds automatically from the conversation context — the conversation always knows who the user is. This is the same pattern as other conversation-aware operations in EDDI.

---

## Data Model

### Property.java — Updated

```java
public class Property {
    public enum Scope { step, conversation, longTerm, secret }
    public enum Visibility { self, group, global }

    private String name;
    private Scope scope = Scope.conversation;
    private Visibility visibility;  // null = self (backward compat)
    // ... existing value fields unchanged

    /** Effective visibility — never null, defaults to self */
    public Visibility effectiveVisibility() {
        return visibility != null ? visibility : Visibility.self;
    }
}
```

### UserMemoryEntry.java — New

```java
public record UserMemoryEntry(
    String id,                          // store-generated
    String userId,                      // owner
    String key,                         // semantic key
    Object value,                       // String, Map, List, Integer, Float, Boolean
    String category,                    // "preference" | "fact" | "context"
    Property.Visibility visibility,     // self | group | global
    String sourceAgentId,               // which agent stored this
    List<String> groupIds,              // for group visibility
    String sourceConversationId,        // provenance
    boolean conflicted,                 // true if Dream flagged a contradiction (keep_both mode)
    int accessCount,                    // for most_accessed recall ordering (incremented on read)
    Instant createdAt,
    Instant updatedAt
) {}
```

### Upsert key semantics

Upsert keys are split by visibility to prevent race conditions:

| Visibility | Upsert key | Semantics |
|---|---|---|
| `self` | `(userId, key, sourceAgentId)` | Each agent owns its own entries — no collisions possible |
| `group` | `(userId, key, sourceAgentId)` | Each agent maintains its own entries within the group |
| `global` | `(userId, key)` | **Single shared entry** — one truth for all agents |

For `global` visibility, the entry is intentionally shared. If Agent B overwrites Agent A's value, the `sourceAgentId` field updates to reflect who last wrote it. The store logs a warning on cross-agent overwrites, and the Dream cycle can flag "contested memories" (same global key updated by multiple agents) for admin review.

---

## Unified Store

### IUserMemoryStore.java — Replaces IPropertiesStore

```java
public interface IUserMemoryStore {
    // === Legacy compat (replaces IPropertiesStore) ===
    Properties readProperties(String userId);
    void mergeProperties(String userId, Properties properties);
    void deleteProperties(String userId);

    // === Structured entries ===
    String upsert(UserMemoryEntry entry);
    void deleteEntry(String entryId);

    // === Queries ===
    List<UserMemoryEntry> getVisibleEntries(String userId, String agentId, List<String> groupIds,
                                             String recallOrder, int maxEntries);
    List<UserMemoryEntry> filterEntries(String userId, String query);  // v1: text filter, v2: semantic search
    List<UserMemoryEntry> getEntriesByCategory(String userId, String category);
    Optional<UserMemoryEntry> getByKey(String userId, String key);
    List<UserMemoryEntry> getAllEntries(String userId);

    // === GDPR ===
    void deleteAllForUser(String userId);
    long countEntries(String userId);
}
```

### Implementations

- **`MongoUserMemoryStore`** — `usermemories` collection with compound index on `(userId, visibility, sourceAgentId, groupIds)`
- **`PostgresUserMemoryStore`** — `usermemories` table with same indexing
- Both implementations include `readProperties()`/`mergeProperties()` for legacy compat (delegate to existing `properties` collection)
- `@DefaultBean` / `@LookupIfProperty` per existing DB-agnostic pattern

### Template access

User memories are loaded as `longTerm` conversation properties. They are accessible in templates via the existing `{properties.key}` syntax — the same mechanism already used for all longTerm properties. **No new template keys or changes to MemoryItemConverter needed.**

```
System prompt: "The user prefers {{properties.preferred_language}} and works at {{properties.company}}."
```

---

## LLM Memory Tools

### UserMemoryTool.java

3 langchain4j tools, registered when `"userMemory"` is in `builtInTools`:

| Tool | Parameters | Description |
|---|---|---|
| `rememberFact` | `key`, `value`, `category`, `visibility` | Store a fact/preference about the current user |
| `recallMemories` | _(none)_ | List all remembered facts about the current user |
| `forgetFact` | `key` | Remove a specific memory entry |

`userId`, `agentId`, and `groupIds` are implicit — obtained from the conversation context. The `AgentOrchestrator` constructs `UserMemoryTool` per-invocation with these values from `IConversationMemory`, which is always available during pipeline execution. No ThreadLocal or request-scoped workarounds needed.

### Write guardrails

All write operations (via LLM tools, REST, and MCP) are validated against `guardrails` config:

| Guardrail | Violation response |
|---|---|
| `maxKeyLength` exceeded | *"Cannot remember: key exceeds 100 character limit. Use a shorter, descriptive key."* |
| `maxValueLength` exceeded | *"Cannot remember: value exceeds 1000 character limit. Summarize the information first."* |
| `maxWritesPerTurn` exceeded | *"Cannot remember: write limit reached (10/10 this turn). Prioritize the most important facts."* |
| Unknown `category` | *"Cannot remember: category 'xyz' not allowed. Use one of: preference, fact, context."* |

All error messages are actionable — the LLM can self-correct on the next tool call.

### Storage cap handling

When `maxEntriesPerUser` is reached:

| `onCapReached` | LLM tool response | REST/MCP response |
|---|---|---|
| `evict_oldest` (default) | *"Remembered 'company=ACME'. Note: oldest memory 'first_visit=2024-01' was evicted to stay within the 500 entry limit."* | `200 OK` with eviction note |
| `reject` | *"Cannot remember: memory limit reached (500/500). Use forgetFact to remove unneeded memories first."* | `409 Conflict` with count |

---

## Dream Consolidation

### DreamConsolidationTask.java

Background maintenance triggered by `ScheduleFireExecutor` — the same scheduling infrastructure used for all scheduled tasks, inheriting cluster-aware execution, fire logging, and retry semantics.

Three independently toggleable features:

| Feature | Type | Cost | Description |
|---|---|---|---|
| `detectContradictions` | LLM-based | Low (batch) | Find conflicting facts (e.g., "prefers dark mode" vs "prefers light mode") |
| `pruneStaleAfterDays` | Deterministic | Zero | Remove entries not accessed in N days |
| `summarizeInteractions` | LLM-based | Higher | Merge many interaction notes into concise summaries |

### Contradiction Resolution

When `detectContradictions` finds conflicting entries, the configured `contradictionResolution` policy determines the outcome:

| Policy | Behavior |
|---|---|
| `keep_newest` (default) | Newer entry overwrites the older one. The old value is logged in the Dream audit trail for recoverability. |
| `keep_both` | Both entries are kept. A `conflicted: true` flag is set on both. The agent sees both values on next recall and can decide. |

### Incremental Processing

Dream tracks `lastDreamProcessedAt` per user (stored as a field on the user's memory document). Each Dream run queries **only users with entries updated since their last Dream processing**, preventing unnecessary work.

For a deployment with 10,000 users where only 200 have new entries since the last run, Dream processes only those 200 users — not all 10,000.

### Cost Ceiling & Batching

Dream has hard per-run budget controls to prevent runaway costs at scale:

- **`maxCostPerRun=5.00`**: Hard cost ceiling in dollars. After each user batch, accumulated cost is checked. If exceeded, Dream finishes the current user (no partial work), records the watermark, and stops. Next scheduled run resumes from the watermark.
- **`batchSize=50`**: Users processed per batch. Between batches, cost is checked against the ceiling.
- **`maxUsersPerRun=1000`**: Hard cap on total users. Users are processed in `lastDreamProcessedAt ASC` order (least recently processed first), ensuring fair round-robin across the user base.

Execution order within a run:
1. Query users with `updatedAt > lastDreamProcessedAt` (incremental)
2. Sort by `lastDreamProcessedAt ASC` (oldest-pending first)
3. Process in batches of `batchSize`:
   a. **Prune stale** (deterministic, zero LLM cost) — always completes
   b. **Detect contradictions** (LLM, within cost budget) — apply `contradictionResolution` policy
   c. **Summarize interactions** (LLM, within remaining budget) — uses shared `SummarizationService`
4. After each batch: check accumulated cost against `maxCostPerRun`; if exceeded → stop
5. Update `lastDreamProcessedAt` timestamp on each processed user

### Shared Summarization Infrastructure

Dream's `summarizeInteractions` and the conversation rolling summary (see [conversation-window-management.md](conversation-window-management.md)) both use the shared `SummarizationService` — a single `@ApplicationScoped` service that handles LLM-based summarization with integrated cost tracking, rate limiting, and metrics. Each caller configures it with their own provider/model/prompt but shares the execution path.

### Observability

Dream exposes Micrometer metrics:
- `dream.users.processed` (counter)
- `dream.entries.pruned` (counter)
- `dream.contradictions.found` (counter)
- `dream.cost.total` (gauge, dollars)
- `dream.duration.ms` (timer)
- `dream.aborted_on_budget` (counter)

---

## REST API

Designed for admin UX — search, stats, audit, bulk operations:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/usermemory/{userId}` | List all memories |
| `GET` | `/usermemory/{userId}?category={cat}&visibility={vis}` | Filtered listing |
| `GET` | `/usermemory/{userId}/search?q={query}` | Search across keys/values |
| `GET` | `/usermemory/{userId}/stats` | Count by category/visibility, current/max entries |
| `GET` | `/usermemory/{userId}/history` | Audit trail: who stored what, when |
| `POST` | `/usermemory/{userId}` | Create entry |
| `POST` | `/usermemory/{userId}/batch` | Bulk create/update |
| `PUT` | `/usermemory/{userId}/{id}` | Update entry |
| `DELETE` | `/usermemory/{userId}/{id}` | Delete single |
| `DELETE` | `/usermemory/{userId}` | GDPR delete-all |
| `GET` | `/usermemory/{userId}/export` | Export as JSON (GDPR data access) |

---

## MCP Tools

Designed for AI agent UX — the MCP tools operate **outside** of a conversation context (called by external AI agents or admin tooling), so `userId` is explicitly required:

| Tool | Args | Description |
|---|---|---|
| `remember_user_fact` | `userId`, `key`, `value`, `category`, `visibility` | Store a fact |
| `recall_user_memories` | `userId`, optional: `category`, `query`, `limit` | Flexible retrieval with search |
| `recall_user_memory` | `userId`, `key` | Direct lookup |
| `forget_user_fact` | `userId`, `key` | Remove a fact |
| `delete_all_user_memories` | `userId`, `confirm=true` | GDPR delete-all |
| `user_memory_stats` | `userId` | Category/visibility breakdown |
| `batch_remember` | `userId`, `entries[]` | Bulk store after analysis |

> **MCP vs LLM tools**: MCP tools require explicit `userId` because they're invoked by external callers with no conversation context. LLM tools (inside a conversation) get userId implicitly.

---

## Migration

### Database

**No schema migration needed.** `Visibility` is an additive nullable field. Existing `PropertySetterConfiguration` documents deserialize with `visibility=null` → interpreted as `self`. The new `usermemories` collection is created on first write.

A `V6MemoryMigration` class (following the `V6RenameMigration` pattern) runs at startup to create indexes:
- Compound index on `(userId, visibility, sourceAgentId, groupIds)`
- Controlled by `eddi.migration.v6-memory.enabled` (default: true)
- Idempotent — safe to run on every startup

### ZIP Import/Export

**No changes needed.** User memories are runtime state, not configuration. `AgentConfiguration` fields (including new `userMemoryConfig`) flow through Jackson serialization automatically.

---

## GroupId Lifecycle

### Source of groupIds

GroupIds are populated from `GroupConversation` context. When a conversation is part of a group discussion (via `GroupConversationService`), the active groupId is derived from the `AgentGroupConfiguration`. During `Conversation.init()`, if the conversation was created as part of a group, the groupId is stored as a conversation property and made available to `PropertySetterTask` and `UserMemoryTool`.

### Agent removal from group

When an agent is removed from an `AgentGroupConfiguration`, its `group`-visibility entries become **naturally invisible** — on recall, `getVisibleEntries()` filters by the agent's current group membership. Since the removed agent is no longer in the group's member list, entries referencing that group are excluded from results. **No data is deleted.**

### Group deletion

Orphaned entries (referencing groups that no longer exist) remain in storage but are naturally invisible — no active group matches the deleted groupId. They cause no harm and cost only storage.

Cleanup options:
- **Dream pruning** (opt-in): A Dream sub-task detects orphaned groupIds and flags them for admin review
- **Admin REST endpoint**: `POST /usermemory/cleanup/orphaned-groups` for manual cleanup
- **Default**: Do nothing — orphaned entries are invisible and harmless. They become visible again if a group with the same ID is recreated

---

## Planned v2 Extensions

| Extension | Description | Builds On |
|---|---|---|
| Semantic memory search (`recallOrder: "relevance"`) | Embed memory entry key+value → query by similarity, replacing text filter | RAG infrastructure (pgvector, embedding pipelines) |
| Memory versioning | Track changes to entries over time with undo capability | Audit trail foundation |
| Cross-tenant memory sharing | Share memories across tenant boundaries (enterprise) | Multi-tenancy infrastructure |
| PII detection on write | Intercept `rememberFact` writes to classify and auto-tag sensitive data | Guardrails infrastructure |

---

## File Impact Summary

| File | Change | Effort |
|---|---|---|
| `Property.java` | MODIFY — add `Visibility` enum + field + `effectiveVisibility()` | +20 lines |
| `UserMemoryEntry.java` | NEW | ~35 lines |
| `IUserMemoryStore.java` | NEW (replaces IPropertiesStore) | ~35 lines |
| `MongoUserMemoryStore.java` | NEW | ~150 lines |
| `PostgresUserMemoryStore.java` | NEW | ~150 lines |
| `V6MemoryMigration.java` | NEW — index creation | ~60 lines |
| `UserMemoryTool.java` | NEW — 3 LLM tools + guardrail enforcement | ~120 lines |
| `DreamConsolidationTask.java` | NEW — background maintenance with incremental processing + cost ceiling | ~250 lines |
| `SummarizationService.java` | NEW — shared LLM summarization (Dream + conversation summary) | ~120 lines |
| `McpMemoryTools.java` | NEW — 7 MCP tools | ~160 lines |
| `IRestUserMemoryStore.java` + `RestUserMemoryStore.java` | NEW — 11 REST endpoints | ~130 lines |
| `Conversation.java` | MODIFY — load/store user memories, groupId population | +50 lines |
| `ConversationService.java` | MODIFY — wire IUserMemoryStore | +10 lines |
| `LlmTask.java` | MODIFY — constructor param | +3 lines |
| `AgentOrchestrator.java` | MODIFY — tool registration + per-invocation construction | +15 lines |
| `AgentDeploymentManagement.java` | MODIFY — wire V6MemoryMigration | +5 lines |
| `IPropertiesStore.java` | DEPRECATE | +2 lines |
| **Total** | | **~1270 lines new, ~105 lines modified** |

---

## Verification Plan

### Automated Tests

1. `MongoUserMemoryStoreTest` — CRUD, visibility filtering, upsert key semantics (self vs global), filter, GDPR, cap enforcement, legacy compat
2. `PostgresUserMemoryStoreTest` — Same against PostgreSQL
3. `UserMemoryToolTest` — Tool gating, remember/recall/forget, cap behavior (evict vs reject), guardrail enforcement (key length, value length, writes per turn, category validation with default-to-fact fallback)
4. `DreamConsolidationTest` — Stale pruning, contradiction detection mock, `contradictionResolution` policy enforcement (keep_newest vs keep_both), incremental processing (only users with updated entries), cost ceiling enforcement (maxCostPerRun + batchSize), round-robin user processing
5. `SummarizationServiceTest` — Shared summarization with cost tracking, used by both Dream and conversation summary
6. `PropertyVisibilityTest` — `self`/`group`/`global` filtering, null defaults
7. `GlobalVisibilityRaceTest` — Two agents writing same global key → single entry, last-write-wins, collision logged
8. `RecallOrderingTest` — `most_recent` (updatedAt DESC) vs `most_accessed` (accessCount DESC), verify access count incremented on read when opted-in
9. `GroupIdLifecycleTest` — Agent removal from group → entries invisible, group deletion → entries orphaned but invisible, orphaned entries invisible to all agents
10. `RestUserMemoryStoreTest` — 11 endpoint contract tests, guardrail validation on write endpoints
11. `McpMemoryToolsTest` — Tool discovery + execution
12. Backward compat: Agent WITHOUT `enableUserMemory` → zero behavior change
13. Migration: `V6MemoryMigration` on empty DB → indexes created
14. Integration: Agent A `visibility=group` → Agent B same group sees it → Agent C doesn't

### Build Verification

```bash
./mvnw compile && ./mvnw test
```

### Manual Verification

- Agent with `enableUserMemory=true`, `builtInTools=["userMemory"]` → chat → `rememberFact` → new conv → recalled
- Guardrails: `rememberFact` with 2000-char value → rejected with actionable message. 11th write in one turn → rejected. Unknown category → defaults to `fact` and proceeds.
- Cap handling: fill to 500 entries → next `rememberFact` → verify eviction note or rejection
- Recall ordering: configure `recallOrder: "most_accessed"` → frequently recalled entries appear first
- Global race: Agent A stores `preferred_language=EN` (global) → Agent B stores `preferred_language=DE` (global) → verify single entry, B's value wins, collision logged
- Group: 2 agents in group, one stores `visibility=group` → other sees it. Remove agent from group → entries invisible.
- Dream: manual trigger → verify incremental processing (only updated users), stale entries pruned, contradiction resolution applied, LLM operations stop at cost ceiling
- Dream contradiction: two conflicting entries → `keep_newest` overwrites with audit log; `keep_both` flags both as conflicted
- GDPR: `DELETE /usermemory/{userId}` → complete removal
- MCP: `recall_user_memories userId=X query="language"` → filtered results
- Cross-repo: `SummarizationService` shared by Dream + conversation rolling summary (see [conversation-window-management.md](conversation-window-management.md))
