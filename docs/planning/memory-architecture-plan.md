# EDDI v6.0.0 — Memory Architecture Plan

> **Scope**: WISC Framework integration, Skeptical Memory (Strict Write Discipline), AutoDream Property Consolidation, and proactive memory curation.
>
> **Focus**: This document deals **exclusively** with memory management — how EDDI stores, validates, curates, and consolidates conversational and cross-conversation state.
>
> **Governing Principles**: All changes **must** conform to the [Seven Pillars](../project-philosophy.md). Memory machinery is Java infrastructure; memory *policy* is JSON configuration.

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
5. The summary **replaces** the raw history in `IConversationMemory` (the old turns are archived, not deleted — audit trail preserved)
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
       auditLedger.logTaskFailure(task.getId(), e, conversationId);
       // Inject error action, NOT the exception, into the pipeline
       memory.getCurrentStep().storeData(new Data<>("actions", List.of("task_failed_" + task.getId())));
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
| **Backwards compatibility** | All existing integration tests pass without modification after each phase | All |

---

*This plan should be revisited after Phase D (Auto-Compaction) is deployed and real-world token savings are measured. The consolidation engine (Phase E) parameters should be tuned based on production memory profiles.*
