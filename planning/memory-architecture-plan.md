# EDDI v6.0.0 — Memory Architecture Plan

> **Scope**: WISC Framework integration, Skeptical Memory (Strict Write Discipline), AutoDream Property Consolidation, and proactive memory curation.
>
> **Focus**: This document deals **exclusively** with memory management — how EDDI stores, validates, curates, and consolidates conversational and cross-conversation state.
>
> **Governing Principles**: All changes **must** conform to the [Nine Pillars](../docs/project-philosophy.md). Memory machinery is Java infrastructure; memory *policy* is JSON configuration.

---

## 1. The Problem: Context Entropy

As EDDI conversations grow longer and agents interact across multiple sessions, the system faces **context entropy** (also known as "context rot"):

1. **Token bloat** — the LLM context window fills with raw transcripts, failed tool attempts, repetitive explanations, and outdated reasoning
2. **Contradiction accumulation** — earlier verified facts become obsolete as the agent changes approach, but older entries persist
3. **Memory pollution** — failed operations, hallucinated attempts, and debug noise are written to permanent memory alongside successful results
4. **Retrieval degradation** — as stored data grows, RAG retrieval quality drops because near-duplicates and stale entries dilute relevance
5. **Cost escalation** — feeding excessive, degraded tokens to LLMs burns budget without improving output quality
6. **Unbounded property growth** — `longTerm` properties accumulate across conversations without lifecycle management, eventually degrading startup time and template rendering performance

Research (including analysis of Claude Code's internal architecture) demonstrates that blindly expanding context windows does **not** solve these problems — it actively worsens them.

---

## 2. Current Memory Architecture

### 2.1 What Already Exists

EDDI v6 has a solid memory foundation:

| Memory Layer | Status | Mechanism |
|---|---|---|
| **Type-safe memory access** | ✅ Implemented | `MemoryKey<T>` with phantom type parameters on `IConversationMemory` |
| **Token-aware windowing** | ✅ Implemented | `ConversationHistoryBuilder` — controls what the LLM sees (last N turns) |
| **Slot-filling** (PropertySetter) | ✅ Implemented | Config-driven extraction of important facts into properties |
| **`longTerm` property persistence** | ✅ Implemented | `IPropertiesHandler` — properties survive conversation boundaries |
| **RAG / vector store retrieval** | ✅ Implemented (Phase 8c) | Config-driven pgvector, httpCall RAG |
| **Scheduled task execution** | ✅ Implemented | `ScheduleFireExecutor` + `SchedulePollerService` with `TriggerType.CRON` / `HEARTBEAT` |
| **Multi-model cascading** | ✅ Implemented | Sequential model escalation with confidence routing |
| **Immutable audit ledger** | ✅ Implemented | HMAC-secured, write-once event log for compliance and forensics |

### 2.2 The Critical Distinction

> [!IMPORTANT]
> Conversation memory has **two audiences**, and they face different problems:
>
> 1. **The LLM** sees a **windowed view** assembled by `ConversationHistoryBuilder` — last N conversational outputs converted to ChatMessages. The problem here is **context window quality** (what the model sees).
>
> 2. **The MongoDB document** stores the **full memory** — all steps, all data keys, all properties. The problem here is **storage bloat and retrieval degradation** (load time, query performance, duplicate pollution).
>
> Most context management strategies only affect #1. This plan addresses **both**.

### 2.3 The Property Lifecycle (Key Context)

Properties have a well-defined lifecycle managed by `Conversation.java`:

```
1. Conversation.init()
   └─→ loadLongTermProperties()
       └─→ IPropertiesHandler.loadProperties(userId)
       └─→ Loaded into conversationProperties with scope=longTerm
       └─→ Available as {{properties.key}} in all templates

2. Pipeline runs
   └─→ PropertySetterTask sets properties based on actions
       └─→ scope=step (cleared per turn)
       └─→ scope=conversation (lives for session)
       └─→ scope=longTerm (persisted across conversations)
       └─→ scope=secret (auto-vaulted via SecretsVault)

3. Conversation turn ends
   └─→ storePropertiesPermanently()
       └─→ All longTerm properties saved via IPropertiesHandler
       └─→ Secret properties scrubbed and vaulted
```

> [!NOTE]
> **Key insight**: The `longTerm` properties system IS EDDI's "memory index" — functionally equivalent to Claude Code's `MEMORY.md`. Properties are always loaded at conversation init and always available in templates. The system already implements the **retention** half of intelligent memory. What's missing is the **validation** half (confirming success before committing), the **lifecycle management** half (preventing unbounded growth), and the **consolidation** half (periodic cleanup of stale/contradictory data).

### 2.4 Target Agent Profiles

Not all agents benefit equally from memory management features. The plan must be evaluated against realistic agent profiles:

| Feature | FAQ Bot (1-3 turns) | Support Bot (5-15 turns) | Analyst Agent (20-50 turns) | Multi-Agent Orchestrator (unbounded) |
|---|---|---|---|---|
| Staging Buffer (Phase A) | ❌ Unnecessary | ⚠️ Nice-to-have | ✅ Essential | ✅ Essential |
| RAG Threshold (Phase B) | ❌ No RAG | ⚠️ Helpful | ✅ Essential | ✅ Essential |
| Context Selection (Phase C) | ❌ Too few turns | ⚠️ Minor benefit | ✅ High value | ✅ High value |
| Auto-Compaction (Phase D) | ❌ Never hits threshold | ❌ Rarely hits threshold | ✅ Essential | ✅ Essential |
| Property Consolidation (Phase E) | ❌ | ❌ | ✅ Essential | ✅ Essential |
| Scout Pattern (Phase F) | ❌ | ❌ | ⚠️ Useful | ✅ High value |

> [!NOTE]
> All features default to **disabled**. The agent profile table is a guide for admins deciding which features to enable per agent, not a prescriptive mandate.

---

## 3. The WISC Framework: Write, Isolate, Select, Compress

The WISC framework is a battle-tested methodology for maintaining lean context windows while preserving essential knowledge. Each pillar maps to specific EDDI infrastructure.

### 3.1 Write — Externalize Memory

**Principle**: Agents should not rely on conversational history as reliable long-term storage. Important decisions and facts must be **explicitly externalized** to structured stores.

**EDDI mapping**: This is already implemented via `PropertySetterTask` with `scope=longTerm`. Properties persist across conversation boundaries and are always available in templates.

The enhancement needed is not a new storage mechanism but better **lifecycle management** of what's already stored:

1. **Property value validation** — `PropertySetterTask` should validate that property keys are declared in the agent's schema before writing (prevents hallucinated key names)
2. **Decision recording via audit ledger** — agent decisions (delegations, action selections) are logged to the existing HMAC-secured audit ledger with event type `DECISION`, providing compliance-grade queryability without new infrastructure
3. **Property schema declaration** — agents declare expected property keys in configuration, enabling both validation and documentation

#### 3.1.1 Property Schema (Optional)

```json
// Agent configuration — new optional field
"memoryPolicy": {
  "propertySchema": {
    "user_language": {"type": "string", "description": "User's preferred language"},
    "delegation_target": {"type": "string", "description": "Last delegation target agent"},
    "financial_context": {"type": "string", "description": "Active financial analysis context"}
  },
  "strictSchema": false  // if true, reject writes to undeclared keys
}
```

When `strictSchema: true`, `PropertySetterTask` rejects writes to undeclared property keys — preventing the LLM from polluting the property store with hallucinated keys. When `false` (default), the schema is advisory only.

### 3.2 Isolate — Contain Context Bloat

**Principle**: Heavy data gathering should happen in **isolated execution branches** that return only synthesized summaries to the main context.

**EDDI mapping**: Use the existing group conversation infrastructure to implement the **Scout Pattern**:

1. The main agent spawns a "research subagent" as a group conversation with a single member
2. The subagent has its own conversation and context window — fully isolated
3. The subagent returns a synthesized summary
4. Only the summary enters the main agent's `IConversationMemory`

**Pre-DAG interim solution** via `GroupConversationService`:

```json
// behavior.json — scout pattern
{
  "name": "research-with-isolation",
  "actions": ["scout_research"],
  "conditions": [
    {"type": "inputMatcher", "config": {"pattern": ".*research.*"}}
  ]
}

// Corresponding group config — single-member "group" for isolation
{
  "agentGroupId": "research-scout",
  "members": [
    {"agentId": "research-agent", "role": "scout"}
  ],
  "discussionStyle": "single_response",
  "maxTurns": 1,
  "resultHandling": "summary_only",
  "scoutConfig": {
    "toolScope": "READ_ONLY",
    "maxToolCalls": 10,
    "maxTokenBudget": 5000
  }
}
```

Key design decisions:

- `resultHandling: "summary_only"` tells `GroupConversationService` to compress the scout's response before injecting it into the parent conversation's memory
- `toolScope: "READ_ONLY"` restricts the scout to read-only tools only — scouts performing research must NOT invoke state-changing MCP tools or HTTP write operations
- `maxToolCalls` and `maxTokenBudget` provide hard caps on scout resource consumption

### 3.3 Select — Build a Context Pyramid

**Principle**: Don't load all available data into every prompt. Build a **layered context pyramid** and select only the most relevant layers for the current task.

**EDDI mapping**: The `AgentOrchestrator` and `MemoryKey<T>` registry enable selective context injection.

#### 3.3.1 Conditional Context Loading

Extend `LlmTask` configuration with conditional context rules:

```json
// langchain.json — context selection rules
{
  "contextSelection": {
    "rules": [
      {
        "when": {"action": "financial_analysis"},
        "include": ["properties.financial_*", "rag.financial_docs"],
        "exclude": ["rag.general_knowledge"]
      },
      {
        "when": {"action": "casual_chat"},
        "include": ["properties.user_name", "properties.preferences"],
        "maxHistoryTurns": 5
      },
      {
        "when": {"default": true},
        "include": ["*"],
        "maxHistoryTurns": 10
      }
    ]
  }
}
```

**LLM view ← this phase addresses the LLM's windowed view (audience #1).**

**How it works:**
1. When `LlmTask` builds the prompt, it evaluates context selection rules against the current action set
2. Only matching `MemoryKey<T>` values are injected into the prompt template
3. `maxHistoryTurns` overrides the global window size for specific contexts
4. This prevents the model from being overwhelmed with irrelevant system instructions during simple interactions

### 3.4 Compress — Semantic Truncation

**Principle**: When context grows beyond optimal limits, aggressively summarize and replace raw history with dense semantic summaries.

**EDDI mapping**: Automated compression using the existing multi-model cascading infrastructure.

**LLM view ← this phase addresses the LLM's windowed view (audience #1).**

#### 3.4.1 Auto-Compaction

A new `ContextCompactionTask` (an `ILifecycleTask`) that triggers when context size exceeds a threshold:

```json
// langchain.json — compaction configuration
{
  "contextCompaction": {
    "enabled": true,
    "triggerThresholdTokens": 8000,
    "targetTokens": 3000,
    "compactionModel": "gemini-flash",
    "preserveLastNTurns": 3,
    "compactionPrompt": "Summarize the following conversation history into a dense, factual summary. Preserve all decisions, action items, and user preferences. Convert any relative time references ('yesterday', 'earlier', 'last week') to absolute dates based on the current timestamp: {{currentTimestamp}}. Discard greetings, filler, and repetition."
  }
}
```

**How it works:**
1. `ConversationMetricsService` monitors token usage per conversation
2. When `triggerThresholdTokens` is reached, the system inserts a `ContextCompactionTask` into the pipeline
3. The compaction task routes conversation history to a **fast, low-cost model** (e.g., Gemini Flash, GPT-4o-mini) via the existing cascading routing
4. The model generates a dense summary with temporal anchoring (relative dates → absolute)
5. The summary **replaces** the raw history in what the LLM is given (the old turns stay stored and readable — AGENTS.md §4.7: full data is never deleted by optimization; the shipped rolling summary is a derived view, see `conversation-window-management.md`, and §11 makes "only load the recent window" a storage concern rather than a deletion)
6. The last N turns are preserved raw (configurable) to maintain conversational continuity

**Cost management:** Compaction uses the cheapest available model via `Small → Better` routing, ensuring that the compaction itself doesn't consume expensive premium tokens.

#### 3.4.2 Manual Compaction (Operator Override)

Expose a REST endpoint for manual compaction:

```
POST /v6/conversations/{id}/compact
```

This allows operators to trigger compaction on-demand via the Manager UI, equivalent to Claude Code's `/compact` command.

---

## 4. Skeptical Memory: Strict Write Discipline

### 4.1 The Problem

Currently, EDDI's `IConversationMemory` operates on a **sequential append-only model**. As tasks execute in the `LifecycleManager`, their outputs — including reasoning tokens, tool invocation parameters, and intermediate results — are appended to conversation history **regardless of outcome**.

This means:
- Failed HTTP call parameters are permanently stored alongside successful ones
- Hallucinated API endpoints are written to memory
- Error traces from tool failures pollute the token stream
- The LLM sees failed reasoning paths in subsequent turns, wasting tokens and potentially reinforcing errors

### 4.2 The Solution: Commit Flags

Implement a **flag-based commit system** on memory data. Data written during a task execution is marked as `uncommitted`. On success, it is promoted to `committed`. On failure, it remains `uncommitted` and is excluded from LLM prompt assembly for subsequent turns.

> [!IMPORTANT]
> **Why flags, not a separate buffer**: Tasks in the pipeline are sequential — downstream tasks READ the results of upstream tasks within the same turn. A physically separate staging buffer would break this coherence. The flag-based approach preserves pipeline coherence: data is visible to downstream tasks immediately but is excluded from the LLM's next-turn prompt if the originating task failed.

#### 4.2.1 Memory Lifecycle with Commit Flags

```
1. Task begins execution
   └─→ Task writes data to memory normally (downstream tasks see it)
   └─→ Data is marked as "uncommitted" for this task scope

2. Task execution completes
   ├─→ SUCCESS:
   │   └─→ All data written by this task is marked "committed"
   │   └─→ Data is included in LLM prompt assembly for future turns
   │
   └─→ FAILURE:
       └─→ Data remains "uncommitted"
       └─→ Error logged to audit ledger (not to conversation memory)
       └─→ LLM prompt assembly for next turn EXCLUDES uncommitted data
       └─→ LLM receives a targeted error notification (action string, not full trace)
       └─→ Uncommitted data remains in memory for audit/debug but doesn't pollute the LLM view
```

#### 4.2.2 Implementation

**This is NOT a new `ILifecycleTask`** — commit tracking is a session-level concern, managed in `Conversation.java`'s pipeline execution logic (per AGENTS.md guidance).

Changes required:

1. **Extend `IData<T>`** with a commit flag:
   ```java
   // IData<T> — new field
   boolean isCommitted();   // default: true (backwards-compatible)
   void setCommitted(boolean committed);
   ```

2. **Modify `LifecycleManager`** to wrap task execution in commit logic:
   ```java
   // Pseudocode — in LifecycleManager task execution loop
   List<IData<?>> preTaskData = snapshot(memory.getCurrentStep());
   try {
       task.execute(memory);
       // Success → all new data is committed (already default)
   } catch (Exception e) {
       // Failure → mark all NEW data written by this task as uncommitted
       markNewDataUncommitted(memory.getCurrentStep(), preTaskData);
        auditLedger.logTaskFailure(task.getId().name(), e, conversationId);
        // Inject error action, NOT the exception, into the pipeline
        memory.getCurrentStep().storeData(new Data<>("actions", List.of("task_failed_" + task.getId().name())));
   }
   ```

3. **Modify `ConversationHistoryBuilder`** to filter uncommitted data:
   ```java
   // When building the LLM's view of conversation history
   // Skip IData entries where isCommitted() == false
   ```

4. **Tasks require NO changes** — they continue to write to memory normally. The commit flag mechanism is transparent to task implementations.

#### 4.2.3 Configuration

Commit tracking is enabled at the agent level:

```json
// Agent configuration
"memoryPolicy": {
  "strictWriteDiscipline": {
    "enabled": true,
    "onFailure": "exclude_from_llm"  // "exclude_from_llm" (default), "keep_all" (backwards-compatible)
  }
}
```

- `exclude_from_llm` — uncommitted data is excluded from LLM prompt assembly (recommended)
- `keep_all` — all data is included regardless of commit status (backwards-compatible with current behavior)

#### 4.2.4 Benefits

1. **Token savings** — failed reasoning paths don't consume context tokens in subsequent turns
2. **Preserved pipeline coherence** — downstream tasks within the same turn still see all data
3. **Clean audit trail** — uncommitted data remains in memory for debugging/audit, but doesn't pollute the LLM's reasoning
4. **Zero task refactoring** — tasks write to memory exactly as before; the commit mechanism is transparent
5. **Alignment with Pillar 2** — deterministic error handling via action strings, not injected exceptions

---

## 5. Property Consolidation: Proactive Memory Lifecycle Management

### 5.1 The Problem

The `longTerm` properties system has no lifecycle management. Over time, properties accumulate:
- **Contradictions** — `user_language` set to "German" in one conversation, "English" in another
- **Duplicates** — the same fact stored under slightly different keys (`preferred_lang` vs `user_language`)
- **Stale data** — preferences that were relevant months ago but no longer apply
- **Unbounded growth** — every new property increases conversation init time and template token consumption

> [!NOTE]
> This is distinct from **intra-conversation history compression** (handled by Auto-Compaction in Phase D). Property consolidation operates across conversations, targeting the persistent `longTerm` property store that all future conversations inherit.

### 5.2 The Solution: Scheduled Property Consolidation

A `PropertyConsolidationJob` — a scheduled background job that uses LLM-assisted semantic analysis to clean, deduplicate, and prune the `longTerm` property store.

**MongoDB storage ← this phase addresses the MongoDB document (audience #2).**

#### 5.2.1 Consolidation Phases

Inspired by Claude Code's AutoDream process, adapted for EDDI's property-centric architecture:

| Phase | Activity | EDDI Implementation |
|---|---|---|
| **1. Orientation** | Survey existing property structure | `PropertyConsolidationJob` loads all `longTerm` properties for the target user via `IPropertiesHandler` |
| **2. Signal Gathering** | Scan for contradictions and staleness | Query recent audit ledger entries for `DECISION` events and `task_failed_*` patterns; check property timestamps |
| **3. Consolidation** | Resolve contradictions, merge duplicates, timestamp-anchor | Route the property set to a fast model (Gemini Flash / GPT-4o-mini) via `Small → Better` cascading for semantic deduplication |
| **4. Prune & Write** | Replace stale properties, enforce size limits | Atomically update the property store; archive pruned properties to audit ledger |

#### 5.2.2 Scheduling

> [!NOTE]
> **Not a new `ILifecycleTask`** — consolidation is background/scheduled work, not pipeline work. It uses the existing `ScheduleFireExecutor` + `SchedulePollerService` infrastructure (per AGENTS.md guidance).

```json
// AgentConfiguration — consolidation schedule
"memoryPolicy": {
  "consolidation": {
    "enabled": true,
    "schedule": "0 3 * * *",
    "model": "gemini-flash",
    "maxCostPerRun": 0.05,
    "maxPropertyCount": 200,
    "batchGatekeeper": {
      "enabled": true,
      "evaluationPrompt": "For each property below, evaluate: (1) Is this a durable user preference or factual decision? (2) Is it redundant with another property? (3) Is it stale? Respond with KEEP or PRUNE for each, with a brief reason."
    }
  }
}
```

**How it works:**
1. `TriggerType.CRON` fires the job on the configured schedule (e.g., `0 3 * * *` — daily at 3 AM)
2. The job queries all users with `longTerm` properties associated with this agent
3. For each user, the four-phase consolidation process runs
4. Cost is tracked via `ToolCostTracker` with `maxCostPerRun` enforcement — if the consolidation exceeds the budget, it halts gracefully and reports partial results

> [!NOTE]
> **Simple CRON over idle-detection**: A scheduled cleanup job is simpler, more predictable, and easier to debug than a chain of idle-detection → event dispatch → async processing. The `ScheduleFireExecutor` already guarantees exactly-once execution across horizontal deployments via `findOneAndUpdate` guards.

#### 5.2.3 Consolidation Prompt

The consolidation model receives a structured prompt:

```
You are a memory consolidation agent. Given the following user properties,
perform the following operations:

1. RESOLVE: If two properties contradict each other, keep the more recent one.
2. MERGE: If two properties express the same fact with different keys, merge them into one.
3. TIMESTAMP: Convert any relative dates ("yesterday", "last week") to absolute timestamps.
4. PRUNE: Remove properties that are session-specific or clearly stale.
5. RANK: Order remaining properties by likely future relevance — user preferences first.

Current timestamp: {{currentTimestamp}}

Output format: A JSON array of consolidated properties, each with:
- "key": the property key
- "value": the consolidated value
- "action": "KEEP" | "PRUNE" | "MERGE_INTO:<target_key>"
- "reason": brief explanation
```

#### 5.2.4 Batch Gatekeeper

Instead of a real-time LLM call on every property write (which costs ~900x more than the storage saved), property quality evaluation is performed as a **batch operation** during consolidation. This achieves the same quality filtering goal at a fraction of the cost.

#### 5.2.5 Safety Guards

- **Atomicity**: Property updates use optimistic concurrency — if the user started a new conversation during consolidation, the consolidation results are discarded and rescheduled
- **Audit trail**: The pre-consolidation property snapshot is archived to the audit ledger before replacement
- **Rollback**: If the consolidation model produces more properties than it received (likely hallucinated), the consolidation is aborted and a warning metric is emitted
- **Budget**: `maxCostPerRun` prevents runaway LLM costs; uses dollar amounts, not call counts (per AGENTS.md guidance)

---

## 6. RAG Relevance Threshold

### 6.1 The Problem

Current RAG ingestion stores all retrieved data without quality filtering. Over time, this leads to:
- Near-duplicate entries from similar queries
- Low-relevance passages that dilute search quality
- Increased vector store costs without proportional retrieval improvement

### 6.2 Relevance Threshold

Add a configurable cosine similarity threshold to RAG ingestion:

```json
// RAG configuration extension
{
  "ingestion": {
    "relevanceThreshold": 0.85,
    "minRelevanceScore": 0.30,
    "maxChunksPerIngestion": 50
  }
}
```

**Implementation:**
1. Before writing a new chunk to the vector store, compute cosine similarity against existing entries
2. If similarity exceeds `relevanceThreshold`, the chunk is a near-duplicate — skip it
3. If relevance to the originating query is below `minRelevanceScore`, the chunk is noise — skip it
4. Log filtered chunks to metrics: `rag.ingestion.filtered.count`

**Effort**: Low — a few lines of logic in the existing RAG ingestion path. No LLM cost.

---

## 7. Implementation Sequence

Each phase is labeled with which memory audience it primarily serves: **⟐ LLM view** (what the model sees) or **⟐ Storage** (MongoDB document quality).

```
Tier 1: Essential (implement first)
──────────────────────────────────────────────

Phase A: Strict Write Discipline — Commit Flags  ⟐ LLM view
  Prerequisites: None
  ├── Commit flag on IData<T>
  ├── LifecycleManager commit/uncommit wrapper
  ├── ConversationHistoryBuilder filters uncommitted data
  ├── Configuration for strictWriteDiscipline
  └── Metrics: memory.commit.count, memory.uncommit.count

Phase B: RAG Relevance Threshold  ⟐ Storage
  Prerequisites: None (parallel with Phase A)
  ├── Cosine similarity check before vector store writes
  ├── Configurable threshold and max chunks
  └── Metrics: rag.ingestion.filtered.count, rag.ingestion.stored.count


Tier 2: High Value (implement second)
──────────────────────────────────────────────

Phase C: Selective Context Loading (WISC: Select)  ⟐ LLM view
  Prerequisites: Phase A (commit flags must be stable)
  ├── Context selection rules in LlmTask configuration
  ├── Conditional MemoryKey<T> injection based on current action set
  └── Per-context maxHistoryTurns override

Phase D: Auto-Compaction (WISC: Compress)  ⟐ LLM view
  Prerequisites: Phase A, Phase C
  ├── ContextCompactionTask
  ├── Token threshold monitoring via ConversationMetricsService
  ├── Temporal anchoring in compaction prompt
  ├── Compaction via Small → Better routing
  ├── REST endpoint for manual compaction
  └── Metrics: context.compaction.count, context.compaction.tokens_saved, context.compaction.cost


Tier 3: Strategic (implement when needed)
──────────────────────────────────────────────

Phase E: Property Consolidation  ⟐ Storage
  Prerequisites: Phase A
  ├── PropertyConsolidationJob as scheduled background job (CRON)
  ├── Four-phase consolidation process
  ├── Batch gatekeeper (replaces real-time per-write LLM evaluation)
  ├── Budget-capped LLM consolidation calls
  ├── Property schema validation in PropertySetterTask
  ├── Atomic property replacement with audit trail
  └── Metrics: consolidation.runs.count, consolidation.properties.pruned, consolidation.cost

Phase F: Scout Pattern — Context Isolation (WISC: Isolate)  ⟐ LLM view
  Prerequisites: None (can start anytime), but benefits from Phase D
  ├── "scout" discussion style in GroupConversationService
  ├── summary_only result handling
  ├── READ_ONLY tool scope restriction for scouts
  ├── maxToolCalls and maxTokenBudget caps
  └── Integration with behavior rules for isolated research

Phase G: Conversation Storage Layout — steps as records  ⟐ Storage  (design: §11)
  Prerequisites: throughput plan WS3a (one full load per turn)
  ├── G1 header counters: stepCount, lastInteraction (projections stop scanning arrays)
  ├── G2 split write/read path behind eddi.conversation.storage.layout=split (MongoDB + PostgreSQL)
  ├── G3 window + lazy StepSource; returnCurrentStepOnly reads one record
  ├── G4 background migration sweep (and its reverse for downgrades)
  ├── G5 header digests for all-history consumers, driven by measurements
  ├── G6 default flip to split; layout-1 writes removed a release later
  └── Metrics: conversation.steps.loaded, conversation.steps.lazy_loaded{consumer}, conversation.storage.migrated
```

---

## 8. Cross-Cutting Concerns

### 8.1 Thread Safety

All memory operations must be thread-safe. The commit flag is conversation-scoped and accessed only by the lifecycle pipeline thread — no contention. The consolidation job uses `findOneAndUpdate` with optimistic concurrency checks.

### 8.2 Cost Tracking

Every LLM call made for memory management (compaction, consolidation) flows through `ToolExecutionService.executeToolWrapped()` and is tracked by `ToolCostTracker`. All memory operations have explicit budget caps (`maxCostPerRun`).

### 8.3 Observability

New Micrometer metrics for all memory operations:

| Operation | Audience | Metrics |
|---|---|---|
| Commit Flags | LLM view | `memory.commit.count`, `memory.uncommit.count` |
| RAG Filtering | Storage | `rag.ingestion.filtered.count`, `rag.ingestion.stored.count` |
| Compaction | LLM view | `context.compaction.count`, `context.compaction.tokens_saved`, `context.compaction.cost` |
| Consolidation | Storage | `consolidation.runs.count`, `consolidation.properties.pruned`, `consolidation.cost`, `consolidation.failures` |

### 8.4 Audit Trail

All memory mutations are logged to the immutable HMAC-secured audit ledger:
- Commit flag changes (commits and uncommits)
- Compaction events (before/after token counts)
- Consolidation events (full pre-consolidation property snapshot archived)
- Agent decisions logged as `DECISION` event type

### 8.5 Configuration-Driven Everything

Per Pillar 1, all memory policies are JSON configuration:
- Enable/disable commit flags, compaction, consolidation independently
- Per-agent configuration — a simple FAQ bot doesn't need consolidation; a financial advisor agent does
- Sensible defaults that work out-of-the-box (all features disabled, current behavior preserved)

### 8.6 Backwards Compatibility

All features default to **disabled** or to current behavior:
- `strictWriteDiscipline.enabled: false` → all data auto-committed (current behavior)
- `contextCompaction.enabled: false` → current token windowing only
- `consolidation.enabled: false` → no background processing

Existing agent configurations continue to work without modification. The commit flag defaults to `committed = true`, so the existing write path is unchanged unless explicitly opted in.

### 8.7 Cost Model

Estimated LLM cost for memory management features (reference deployment: 100 agents, moderate activity):

| Feature | LLM Cost | Per-Agent/Day | Fleet Monthly |
|---|---|---|---|
| Commit Flags (Phase A) | None | $0.00 | $0.00 |
| RAG Threshold (Phase B) | None | $0.00 | $0.00 |
| Context Selection (Phase C) | None | $0.00 | $0.00 |
| Auto-Compaction (Phase D) | Gemini Flash | ~$0.02/trigger | ~$60 |
| Property Consolidation (Phase E) | Gemini Flash | ~$0.05/run | ~$150 |
| Scout Pattern (Phase F) | Depends on scout model | Variable | Variable |
| **Total (Phases A-E)** | | | **~$210/month** |

> [!NOTE]
> Phases A, B, and C have **zero LLM cost** — they are pure logic changes. This makes them excellent first priorities. Phases D and E introduce LLM costs but are protected by per-run budget caps (`maxCostPerRun`).

---

## 9. Out of Scope

The following items are explicitly **not** covered by this plan:

- **DAG execution model** — covered in Phase 9 of the main roadmap; the commit flag system is designed to integrate cleanly when DAG lands
- **Session forking and state snapshotting** — execution concerns covered in the [Agentic Improvements Plan](./agentic-improvements-plan.md)
- **A2A improvements, multimodal attachments, cryptographic identity** — covered in the [Agentic Improvements Plan](./agentic-improvements-plan.md)
- **Persistent memory across users** (Phase 11a) — this plan focuses on intra-user memory management
- **Vector store selection/optimization** — the RAG foundation (Phase 8c) handles store-level concerns; this plan adds a quality layer on top
- **Dedicated decision log store** — agent decisions are recorded via the existing audit ledger; a separate queryable store is deferred pending production evidence of need
- **Real-time memory gatekeeper** (per-write LLM evaluation) — cost/benefit analysis shows this is ~900x more expensive than the storage it saves; batch evaluation during consolidation achieves the same quality goal at negligible marginal cost
- **Full MemSkill architecture** — deferred to future planning after simpler approaches (threshold + consolidation) are validated

---

## 10. Success Criteria

| Metric | Target | Phase |
|---|---|---|
| **Failed reasoning pollution** | 0 failed tool invocation parameters in LLM prompt for next turn | Phase A |
| **RAG duplicate rate** | ≤ 5% near-duplicate entries in vector store | Phase B |
| **Context quality** | Measurable improvement in LLM output quality for conversations > 20 turns | Phase C |
| **Token waste reduction** | ≥ 30% fewer tokens consumed per long conversation | Phase D |
| **Property store size** | ≤ 200 properties per user after consolidation runs | Phase E |
| **Consolidation cost** | ≤ $0.05 per consolidation run | Phase E |
| **Long-conversation cost** | Last-quarter p50 ≤ 1.2× first-quarter p50 over 200 turns, MongoDB and PostgreSQL | Phase G |
| **Turn read size** | Header + window of steps per turn, not the whole conversation | Phase G |
| **PostgreSQL storage** | ≤ 2× live data after 2000 appends (today ~110×) | Phase G |
| **Backwards compatibility** | All existing integration tests pass without modification after each phase | All |

---

---

## 11. Conversation Storage Layout (Phase G) ⟐ Storage

**MongoDB / PostgreSQL storage ← this phase addresses the stored conversation (audience #2), and through it the cost of every turn.**

> [!IMPORTANT]
> **Status (2026-10-17): design only, nothing implemented.** Written from load-test and profiling measurements: `scripts/loadtest/results/profile-2026-10-15.md` and `db-comparison-2026-10-16.md` on branch `feat/loadtest-profiling`, plus the throughput plan's WS6/WS7. It honours AGENTS.md §4.7: **full data is never deleted by optimization.** Every step stays stored and readable, and only what a turn *loads* changes.

### 11.1 The Problem

One conversation is one document: a MongoDB document in `conversationmemories`, or one `data` JSONB value in PostgreSQL's `conversation_memories`. It holds every step, every output, properties, the HITL bookmark and the redo cache. Every turn loads all of it and converts all of it, and on PostgreSQL every turn rewrites all of it.

| Measured (WS2 build, harness `long-conversation`: 10 × 200 turns, no LLM delay) | MongoDB | PostgreSQL |
|---|---|---|
| Turns/s | 138 | 84–114 |
| p50, first quarter → last quarter of the conversation | 43–49 → 80–83 ms (1.6–1.9×) | 42–66 → 106–145 ms (2.2–2.5×) |
| Full conversation loads per turn | 2.0 | 2.45–2.8 |
| Database time spent on the append | — (`$push` of the new step) | ~62 % (`UPDATE … data = (…) \|\| jsonb_build_object(…)` rewrites the whole value) |
| Storage after 2000 turns | — | 256 kB live data in **28 MB** (a new row version and TOAST chunks per append) |

- **Both backends slow down with conversation length.** Part of that is EDDI itself: every turn decodes the whole history (`JacksonCodec` was the largest CPU item in the profile) and rebuilds `IConversationMemory` step by step. PostgreSQL adds the full rewrite on top.
- **Nothing bounds a document.** No guard exists for the 16 MB BSON ceiling (WS6 adds one). Production 5.x conversations averaged about 410 KB, according to `ConversationMemoryStore`. The only mitigations are opt-in rollovers: `eddi.schedule.persistent-conversation-max-steps` and `eddi.openai-compat.max-conversation-steps`.
- **Several operations rewrite the whole document even on MongoDB:**
  - undo, redo and rerun;
  - the HITL pause commit and the resume (`storeConversationMemorySnapshotIfState`);
  - any write after a merged append (baseline -1).
- **Responses don't need what they load.** `GET /agents/{id}` with the default `returnCurrentStepOnly=true` loads the full snapshot and trims it afterwards. So does every turn's response.

### 11.2 Goals and Non-Goals

**Goals**
1. A turn reads O(window), not O(conversation), and writes O(new step), on both backends.
2. Every step stays stored and readable through the same REST, MCP, UI and GDPR surfaces. API shapes do not change.
3. Every pipeline consumer keeps its current semantics (inventory in §11.3), including absolute step indexes.
4. Existing conversations keep working. Migration runs online, conversation by conversation, and can be paused.
5. The cluster contract holds: one writer per conversation under its lease, fencing (`_fence`), revision CAS (`_rev`), and `_histRev` for history-rewriting writes.

**Non-goals**
- Summaries, compaction and chaining (§3.4, `conversation-window-management.md`). Those change what the LLM *sees*. This changes what a turn *loads*. They compose, but neither requires the other.
- Changing agent configuration. The window a turn loads is derived from fields agents already set (§11.5), so there is no new agent-level knob.

### 11.3 Who Reads History, and How Far

An inventory of the code (2026-10-16). The design has to serve every row of this table.

| Need | Consumers |
|---|---|
| **Current step only** | Default `say`/`rerun`/`GET` responses (`returnCurrentStepOnly=true`), the `/v1` bridge, Slack, MCP talk/chat, group member turns, the HITL resume bookmark, `MemoryItemConverter` `memory.current` |
| **Last step** | `PropertySetterTask` catch-all input, `InputParserTask` quick-reply dictionaries, matchers with `occurrence: lastStep`, `memory.last` |
| **Bounded window** (from LLM config) | `ConversationHistoryBuilder` step mode (last `conversationHistoryLimit` outputs, default 10, after `skipSteps`), `ToolLoopResumer` fallback, rolling summary (`recentWindowSteps` 5 plus the outputs not yet summarized) |
| **All history, by scan** | Matchers with `occurrence: anyStep` / `never`; the `occurrence` condition (`behavior_rules:success` counts); `OutputGenerationTask` `timesOccurred`; `memory.past`; `conversationLog` without a size; token-mode history (anchors the first `anchorFirstSteps` and fills backwards to `maxContextTokens`, built from all outputs); `DynamicAgentToolsProvider` (created/retained agent ids, delegation depth from step 0); `ConversationGroups.resolveGroupIds`; `attachmentsFromPreviousTurns`; `everyPropertyVersion` (vault cleanup, GDPR) |
| **Random access by absolute index** | `ConversationRecallTool` (any summarized turn), `ConversationSummarizer` (outputs from `summary_through_step`, from 0 on first enable), idempotency replay (`MAX_STEPS_SCANNED` 50, plus the prefix) |
| **Total count / absolute index only** | `memory.size()` for audit and checkpoints, `conversationStepCount` in listings, the append baseline, `LlmTask.rebuildWithHalvedWindow` |
| **Full history, outside the turn path** | `GET /agents/{id}?returnCurrentStepOnly=false`, `/agents/{id}/log`, `/conversationstore/conversations/{id}` (raw and simple), tool history, template preview, MCP `read_conversation(_log)`, A2A `derive`, GDPR export, the Manager and Chat UI transcript views |

The consequence: a strict window would break the "all history" rows. The design therefore loads a **window eagerly and the rest lazily**, by page and on demand (§11.5). Section 11.7 adds per-conversation digests, so the commonest all-history scans stop needing the history at all.

### 11.4 Target Layout

The conversation splits into a small **header**, read and CAS-updated on every turn, and **one record per step**, written once and read by range.

**Header**: everything that is not per-step, plus the counters that let projections stop scanning arrays.

| Field | Notes |
|---|---|
| `_id`, `_rev`, `_histRev`, `_fence`, `schemaVersion` | As today. `_histRev` moves on any write that changes existing steps (undo, redo, rerun) |
| `layout` | `1` = embedded (today's single document), `2` = split. Absent means 1 |
| `stepCount` | Number of committed steps. The source of truth for which step records are visible |
| `lastInteraction` | Set on every append. Replaces the `$reduce` / `jsonb_path_query` over every task timestamp that listings and the idle sweep run today |
| agentId/Version, `compatibilityGeneration`, userId, environment, state, `endReason`, `resolutionProvenance` | As today |
| `conversationProperties`, `pendingLongTermWrites` | As today. Properties are conversation-scoped and already loaded in full at init |
| HITL bookmark (`hitlPaused*`, `hitlPendingToolCalls`) | As today; it describes the current paused step |
| `redoCache` | As today; small, and cleared by every new turn |
| `digests` | §11.7 |

**Step record**: one per step, carrying the absolute index.

| Field | Notes |
|---|---|
| `conversationId`, `stepIndex` | Unique together. The index is the absolute step number every consumer already uses |
| `step` | The `ConversationStepSnapshot` (`workflows[].lifecycleTasks[]`), unchanged shape |
| `output` | That step's `ConversationOutput`. Kept with its step, because they are paired by index everywhere |
| `createdAt` | Step start; the input to `lastInteraction` |
| `writer` | The append's id (§11.6), used to tell a lost race from a retry of the same append |

**MongoDB**
- The header stays in `conversationmemories` (same `_id`), with the arrays removed.
- Steps go to a new `conversationsteps` collection: `_id: {c: <conversationId>, i: <stepIndex>}`, which gives a unique key and an ordered range scan without a secondary index.
- It is the same split the audit ledger and HITL journal already use: per-step documents with a natural key, inserted, never rewritten.
- Multi-document transactions are **not** used, because EDDI supports standalone MongoDB. The write protocol in §11.6 needs none.

**PostgreSQL**
- `conversation_memories` keeps its columns. `data` loses the two arrays and gains the header fields, and `step_count` and `last_interaction` become real columns, indexed where listings and the idle sweep need them.
- A new table: `conversation_steps (conversation_id UUID, step_index INTEGER, step JSONB NOT NULL, output JSONB, created_at TIMESTAMPTZ, writer TEXT, PRIMARY KEY (conversation_id, step_index))`.
- It follows `conversation_checkpoints` and `hitl_tool_execution_journal`. The step and the header update share one transaction. The append's TOAST churn disappears, because existing rows are never updated.

### 11.5 Reading: Window Plus Lazy History

A turn loads the header and the **last W step records** in two queries: one indexed range scan, and one primary-key read for the header. W is derived from the agent's existing configuration:

```
W = max(1,                                     // current/last step consumers
        conversationHistoryLimit (if >= 0),    // step-mode history (default 10)
        recentWindowSteps + unsummarized,      // rolling summary, when enabled
        eddi.conversation.storage.window-min)  // deployment floor (default 12)
```

and `anchorFirstSteps` adds steps `[0, anchorFirstSteps)` when token mode is on. If an agent uses `conversationHistoryLimit = -1`, W is everything, and that agent gets today's behaviour.

`IConversationMemory` keeps its interface. Its step stack is backed by a **`StepSource`**: steps in the window are resident, and a step outside it is fetched **on first access**, in pages (default 50), then cached for the rest of the turn.
- Consumers keep reading by absolute index: `size()` comes from `stepCount`, and `get(i)` / `getAllSteps()` fault pages in.
- Correctness never depends on W, and W only decides cost. An agent whose rules use `anyStep` pays for the pages it scans, the same total I/O as today but only on the turns that scan.
- The fault-in counter (`eddi.conversation.steps.lazy_loaded`, tagged by consumer) shows operators which agents still read whole histories. That is the input for §11.7.

**Full-history surfaces** (the last row of §11.3) read the header plus all step records, ordered by `stepIndex`. They page through rows and assemble the same `ConversationMemorySnapshot` as today. `returnCurrentStepOnly=true` reads only the last record, which makes the default `GET /agents/{id}` and every turn response O(1).

### 11.6 Writing

**Append** (a normal turn): write the new step records, then CAS the header.

*PostgreSQL*, in one transaction:
```sql
INSERT INTO conversation_steps (conversation_id, step_index, step, output, created_at, writer) VALUES (…);
UPDATE conversation_memories
   SET data = … (header fields, _rev + 1), step_count = ?, last_interaction = ?
 WHERE id = ? AND COALESCE((data->>'_rev')::bigint, 0) = ? AND … [fence clause];
-- 0 rows → ROLLBACK and take the existing merge/refuse path
```

*MongoDB*, without a transaction. The unique step key is the claim:
1. `insertMany` the step records `{_id: {c, i}, …, writer: W}` at indexes `[stepCount, stepCount + n)`, unordered.
2. A duplicate-key error on an index means another writer claimed it:
   - if that record's `writer` is not W, re-key the tail after the current `stepCount`, retrying within today's `MAX_APPEND_ATTEMPTS` (5), which keeps "both turns survive";
   - if it is W, this is a retry of the same append, which is fine.
3. `updateOne` the header with `_rev` and `_fence` in the filter: `$set` the header fields, `$max: {stepCount: end}`, `$set lastInteraction`, `$inc _rev`. This is the same filter as today's append, minus the arrays.
4. **Readers only trust step records below the header's `stepCount`.** A record left above it by a crash between steps 1 and 3 is invisible and is reclaimed by the next append to that index. A fenced writer fails at step 3, and its records stay invisible.

**History-rewriting writes** become narrow:

| Operation | Today | Split layout |
|---|---|---|
| Undo | Full replace | Move the last record into `redoCache` (header) and decrement `stepCount`, in one header CAS that also bumps `_histRev`. The orphaned record is deleted afterwards and is invisible anyway |
| Redo | Full replace | Re-insert the step record from `redoCache` at index `stepCount`, then header CAS |
| Rerun | Full replace (no step growth) | Upsert record `stepCount - 1`, then header CAS (`_histRev`) |
| HITL pause / resume (`…IfState`) | Full replace | Upsert the current step record, then header CAS with the state condition |
| Narrow updates (state, end reason, fence, HITL bookmark) | Already narrow | Unchanged; header only |
| Delete (retention, GDPR) | Delete document | Delete the header, then `deleteMany` steps by `conversationId`. A `deleteMany` by `userId` first resolves the ids, as GDPR already does |

Nothing rewrites the step history any more. `_histRev` keeps its meaning: an append whose loaded revision predates a history rewrite is refused, exactly as now.

### 11.7 Digests for All-History Consumers (later)

The lazy history keeps every consumer correct, but a few scans run on most turns, so for them it only moves the cost elsewhere. The cheaper fix is a small summary in the header, maintained on append. It is **derived** and can be rebuilt from the step records at any time.

| Digest | Replaces |
|---|---|
| `actionCounts` (action → count) | `OutputGenerationTask` `timesOccurred`; `ActionMatcher` `anyStep`/`never` for actions |
| `ruleSuccessCounts` (rule → count) | `occurrence` condition |
| `dynamicAgents` (created, torn down, retained), `delegationDepth`, `groupIds` | `DynamicAgentToolsProvider` scans, `ConversationGroups.resolveGroupIds` |
| `attachmentSteps` (step indexes with attachments) | `attachmentsFromPreviousTurns` |
| `vaultRefs` (vault references seen in `properties:changes`) | `everyPropertyVersion` for vault cleanup and GDPR erasure |

Only the digests that `eddi.conversation.steps.lazy_loaded` shows to matter in production are built. Expression matching on `anyStep` (`InputMatcher`) stays a scan: the set of possible expressions is open.

### 11.8 Migration

- **Layout flag:** `eddi.conversation.storage.layout = embedded | split`. The default is `embedded` for one release, while `split` is opt-in; then the default flips.
- **Reads understand both layouts, always.** A header without `layout` is layout 1 and loads as today.
- **Conversion on first write.** With `split` enabled, the first write to a layout-1 conversation converts it:
  1. insert all step records (idempotent by key);
  2. replace the document with the header (`layout: 2`, `stepCount`, `lastInteraction`), CAS on `_rev` and bumping `_histRev` as `V6RenameMigration.bumpRevision` does. A concurrent appender from the old layout is then refused rather than lost.
- **Background sweep:**
  - converts idle conversations in batches through `ScheduleFireExecutor` (cluster-safe claims);
  - oldest-active first, can be paused, and is idempotent;
  - logs progress to the migration log;
  - ended conversations convert last, or not at all: they are read rarely, and layout 1 stays readable.
- **Downgrade:** a release that only knows layout 1 cannot read layout 2. The same sweep runs in reverse (`layout` → 1), for an operator who must roll back. That is documented in the upgrade notes, and the default flip waits until it is tested.
- **Imports and exports** (agent ZIPs) carry no conversations, so they are unaffected. GDPR exports keep their shape.

### 11.9 What Changes for Operators and Agents

- **Agents:** nothing to configure. W derives from `conversationHistoryLimit`, `anchorFirstSteps` and `conversationSummary`, which agents already set.
- **Deployment properties (new):**
  - `eddi.conversation.storage.layout` (`embedded` | `split`);
  - `eddi.conversation.storage.window-min` (default 12);
  - `eddi.conversation.storage.page-size` (default 50).
- **16 MB:** the BSON ceiling stops applying to a conversation and applies only to a single step, which the existing payload caps already bound (`maxResponseSizeInBytes`, `PendingToolCallBatch` caps). The WS6 guard moves to the step record. The persistent-schedule and `/v1` step rollovers stay available but are no longer needed for size.
- **Metrics (new):** `eddi.conversation.steps.loaded` (summary per turn), `eddi.conversation.steps.lazy_loaded{consumer}`, and `eddi.conversation.storage.migrated` (counter), with dashboard panels per `MetricsDashboardCoverageTest`.

### 11.10 Measuring It

The design is accepted if the harness shows it on both backends. Pass criteria:

| Scenario | Today | Target |
|---|---|---|
| `long-conversation` p50, last quarter ÷ first quarter | 1.6–1.9× (MongoDB), 2.2–2.5× (PostgreSQL) | ≤ 1.2× on both |
| `long-conversation` turns/s, PostgreSQL vs MongoDB | 61–83 % | ≥ 90 % |
| PostgreSQL storage after 2000 turns of 10 conversations | 28 MB for 256 kB live | ≤ 2× live data |
| Bytes read from the database per turn (steady, 10-turn conversations) | the whole conversation, twice | header + W steps, once |
| `GET /agents/{id}` (default) at 200 steps | full document load | one header read + one step read |

Correctness: the memory-store contract tests run against both layouts and both backends:
- append, merge-on-conflict, fencing, undo/redo/rerun, HITL pause/resume, idle sweep, GDPR delete and export;
- a crash-between-writes test (records above `stepCount` stay invisible);
- a conversion test under a concurrent append;
- a two-node cluster IT, with a zombie writer fenced after step 1.

### 11.11 Implementation Order

1. **G1 — header counters** (small, independent, useful at once): `stepCount` and `lastInteraction` in the current single document, set on every write. Listings and the idle sweep read them instead of scanning arrays. Layout 1 gains them through the normal write path.
2. **G2 — split write and read path** behind `layout=split`, on both backends: the step store, the append protocol (§11.6), narrow history rewrites, full-history readers, conversion on first write.
3. **G3 — window plus `StepSource`:** eager window, lazy pages, the `lazy_loaded` metric, and `returnCurrentStepOnly` reading one record.
4. **G4 — background migration sweep** and its reverse.
5. **G5 — digests (§11.7)**, driven by `lazy_loaded` data.
6. **G6 — flip the default** to `split` after a release with it opt-in; remove the layout-1 write path one release later (reads stay).

Prerequisites: the throughput plan's WS3a (one full load per turn) lands first. It removes the duplicate load in the current layout and makes G3's gain measurable on its own.

### 11.12 Risks and Open Questions

- **More round trips for all-history agents** until G5: a rule set with `anyStep` on every turn pays one paged read per 50 steps. That is still less I/O than today's full load per turn, but it is more queries. The `lazy_loaded` metric makes it visible.
- **The MongoDB append without transactions** relies on the unique step key plus "visible below `stepCount`". This needs the crash and fencing tests above, and a review against the cluster lease (`docs/clustering.md`) before G2 merges.
- **Idempotency replay** returns "steps [0, idx]" for a duplicate key with `returnCurrentStepOnly=false`. That becomes a range read, fine, but its 50-step scan must read records, not the header.
- **Manager and Chat UI** rebuild transcripts with full-history reads on resume, undo and redo. That stays correct, but those calls become the heaviest reads a conversation sees. Paging them (`?fromStep=`) is a separate, compatible follow-up.
- **Open:** whether `conversationOutputs` should also be readable without steps (outputs only, for `/log` and the UI), or always travel with their step. The table above keeps them together until a measurement says otherwise.

---

*This plan should be revisited after Phase D (Auto-Compaction) is deployed and real-world token savings are measured. The consolidation engine (Phase E) parameters should be tuned based on production memory profiles.*
