# Conversation Window Management & Context Optimization

## Overview

This document defines EDDI's strategy for managing long conversations: how the LLM context is assembled, how conversation history is compressed without losing recoverability, and how conversations can chain across session boundaries.

**Prerequisite**: Phase 11a (Persistent User Memory) should be implemented first. The conversation window strategies build on top of persistent properties, the shared summarization infrastructure, and the ScheduleFireExecutor. Token-aware windowing (Strategy 1) is the sole exception — it has no dependencies and can be implemented at any time as a quick win.

**Core principle**: The full conversation is NEVER deleted. All strategies are about how to *present* the conversation to the LLM, not how to *store* it. The original is always recoverable.

---

## Current State

### What exists today

- `LlmConfiguration.Task.conversationHistoryLimit` — integer, default `10`
- `ConversationHistoryBuilder.buildMessages()` calls `ConversationLogGenerator.generate(logSizeLimit, includeFirstAgentMessage)`
- `ConversationLogGenerator` calculates `startIndex = conversationOutputs.size() > logSize ? conversationOutputs.size() - logSize : 0` then iterates `startIndex → end`
- This is a **fixed step-count sliding window** — last N steps verbatim, everything before silently dropped from LLM context
- Full conversation always persisted in MongoDB regardless of window

### What's wrong with it

1. **Step count ≠ token count** — a tool-calling step with 5 API results may be 10x the tokens of a simple "ok, thanks" step
2. **Oldest = most important** — the first user message often contains the core requirements, goals, and constraints, but it's the first thing truncated
3. **No middle ground** — a step is either included verbatim or completely invisible to the LLM
4. **No compression** — unlike properties (which extract discrete facts), there's no mechanism to compress conversational reasoning, exploration, or narrative context

### What properties already handle

The Property system (`PropertySetter` + longTerm scope) already extracts **discrete facts**:
- User preferences, slot-filled data, session state
- Available via `{{properties.key}}` in system prompts
- Carried across the window boundary automatically

The **gap** that remains after properties:

| Properties capture | Properties miss |
|---|---|
| Discrete facts/decisions | The reasoning behind decisions |
| User preferences | The sequence of exploration (why was option A rejected?) |
| Named entities, data points | Conversational tone and rapport |
| Explicit state | Implicit accumulated context |

---

## Strategy 1: Token-Aware Window with Anchored Opening

### Concept

Replace the step-count window with a **token-budget window** that also **anchors the first N steps** (configurable) to preserve the opening context that typically contains the most critical information.

```
[System prompt]
[Turn 1: user's opening message]        ← anchored (always included)
[Turn 1: agent's opening response]      ← anchored (always included)
...gap (summarized or dropped)...
[Turn 42: user message]                  ← recent window (fills remaining budget)
[Turn 43: agent response]               ← recent window
[Turn 44: user message]                  ← current
```

### Configuration

New fields on `LlmConfiguration.Task`:

```java
/**
 * Maximum token budget for conversation history (excluding system prompt).
 * When set, replaces step-count-based conversationHistoryLimit with
 * token-aware packing. -1 = unlimited (use conversationHistoryLimit instead).
 * Default: -1 (backward compatible — uses step count).
 */
private Integer maxContextTokens = -1;

/**
 * Number of opening conversation steps to always include regardless of
 * window position. These steps typically contain the user's initial
 * requirements and goals. 0 = no anchoring. Default: 2.
 * Only applies when maxContextTokens is set (token-aware mode).
 */
private Integer anchorFirstSteps = 2;
```

When `maxContextTokens` is set (> 0), token-aware mode activates:
1. Reserve budget for anchored steps (first N conversationOutputs)
2. Fill remaining budget from most recent steps backward
3. If conversation is short enough, everything fits — no special handling

When `maxContextTokens` is -1 (default), the existing `conversationHistoryLimit` step-count behavior applies. Full backward compatibility.

### Token Counting

Use langchain4j's `Tokenizer` implementations — already a transitive dependency:
- `OpenAiTokenizer` for OpenAI/Azure OpenAI models
- Fallback: approximate tokenizer (characters / 4) for providers without native tokenizers

The tokenizer is resolved from the `task.type` field (same model registry used for building chat models).

### Implementation

Modify `ConversationHistoryBuilder.buildMessages()`:

```java
List<ChatMessage> buildMessages(IConversationMemory memory, String systemMessage, 
                                 String prompt, int logSizeLimit, 
                                 boolean includeFirstAgentMessage,
                                 Integer maxContextTokens, int anchorFirstSteps) {
    
    if (maxContextTokens != null && maxContextTokens > 0) {
        return buildTokenAwareMessages(memory, systemMessage, prompt, 
                                        maxContextTokens, anchorFirstSteps,
                                        includeFirstAgentMessage);
    }
    
    // Existing step-count logic (unchanged)
    return buildStepCountMessages(memory, systemMessage, prompt, 
                                   logSizeLimit, includeFirstAgentMessage);
}

private List<ChatMessage> buildTokenAwareMessages(...) {
    // 1. Convert + count tokens for anchored steps
    // 2. Fill remaining budget from recent steps backward
    // 3. If gap exists between anchor and recent, insert a marker:
    //    "[... turns X-Y omitted — use recallConversationDetail 
    //     tool if you need details from this range ...]"
    // 4. Assemble: system + anchored + gap marker + recent + current
}
```

### File Impact

| File | Change |
|---|---|
| `LlmConfiguration.java` | ADD `maxContextTokens`, `anchorFirstSteps` fields |
| `ConversationHistoryBuilder.java` | ADD `buildTokenAwareMessages()` method |
| `LlmTask.java` | MODIFY to pass new params through |
| `TokenCounterFactory.java` | NEW — resolve tokenizer from model type |

**Estimated effort**: ~150 lines new, ~20 lines modified

---

## Strategy 2: Rolling Summary with Conversation Recall Tool

### Concept

Maintain a **running conversation summary** that compresses older turns into a concise representation. The LLM sees:

```
[System prompt]
[CONVERSATION SUMMARY — turns 1-35 condensed]
"User wants to plan a 2-week trip to Japan. Budget: $5000. 
 Decided on Tokyo + Kyoto itinerary. Rejected Osaka due to time.
 Key requirements: vegetarian dining, temple visits, hiking..."

[You can use the recallConversationDetail tool to access full 
 details from these summarized turns.]

[Turn 36 onward — verbatim]
[Turn 36: user message]
[Turn 36: agent response]
...
[Turn 42: user message]  ← current
```

**Key principle**: The summary is a **derived view**, not a replacement. The full conversation is always in MongoDB. The summary is what the LLM *sees*, but the agent can drill back into the original via a tool.

### The Conversation Recall Tool

When rolling summary is enabled, the agent gets a built-in tool:

```java
@Tool("Look back at earlier parts of this conversation that have been " +
      "summarized. Use when the conversation summary mentions something " +
      "you need more detail about, or when the user refers to something " +
      "from earlier in the conversation.")
public String recallConversationDetail(
    @P("What to look for — describe the topic or question, " +
       "or specify a turn range like '1-10'") String query
) {
    // Returns verbatim turns from the summarized section
    // Bounded to prevent dumping the entire conversation
}
```

This is registered as a built-in tool (`builtInTools: ["conversationRecall"]`), only available when rolling summary is enabled. The LLM context explicitly tells the agent which turns are summarized and that the tool exists.

This ensures:
- No information is permanently lost from the LLM's perspective
- The agent can self-serve when it detects context gaps
- The user experience is seamless — the agent behaves as if it remembers everything

### When to Summarize — Configurable Triggers

```java
public enum SummarizationTrigger {
    /** Summarize asynchronously after each response is sent.
     *  Zero user-facing latency. Summary lags by 1 turn (acceptable
     *  since it covers *older* turns, not the just-completed one). */
    ASYNC_AFTER_RESPONSE,
    
    /** Summarize every N turns synchronously. Adds latency on 
     *  every Nth turn but summary is always up-to-date. */
    EVERY_N_TURNS,
    
    /** Summarize only when the conversation exceeds the context 
     *  window. Lazy — no cost until needed. */
    ON_WINDOW_OVERFLOW
}
```

Default: `ASYNC_AFTER_RESPONSE` — best balance of quality and UX.

### Configuration

New section on `LlmConfiguration.Task`:

```json
{
  "conversationSummary": {
    "enabled": false,
    "trigger": "ASYNC_AFTER_RESPONSE",
    "triggerValue": 10,
    "llmProvider": "openai",
    "llmModel": "gpt-4o-mini",
    "maxSummaryTokens": 800,
    "excludePropertiesFromSummary": true,
    "recentWindowSteps": 5,
    "summarizationPrompt": null
  }
}
```

| Field | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `trigger` | `ASYNC_AFTER_RESPONSE` | When to generate/update the summary |
| `triggerValue` | `10` | For `EVERY_N_TURNS`: N. Ignored for other triggers |
| `llmProvider` | `"openai"` | LLM provider for summarization (should be cheap/fast) |
| `llmModel` | `"gpt-4o-mini"` | Model for summarization |
| `maxSummaryTokens` | `800` | Max length of generated summary |
| `excludePropertiesFromSummary` | `true` | Tell summarizer to skip facts already in properties |
| `recentWindowSteps` | `5` | How many recent steps to keep verbatim alongside summary |
| `summarizationPrompt` | `null` | Custom prompt override (null = use default structured prompt) |

### Default Summarization Prompt

```
Summarize the conversation below. You MUST preserve:
1. The user's stated goals, requirements, and constraints
2. Decisions made and their reasoning (especially WHY alternatives were rejected)
3. The sequence of exploration — what was tried, in what order
4. Important corrections or clarifications the user made
5. Any agreements, action items, or commitments
6. The conversational tone and rapport established

{{#if propertiesContext}}
The following facts are ALREADY stored as persistent properties. 
Do NOT repeat these — focus only on context they don't capture:
{{propertiesContext}}
{{/if}}

Format as concise bullet points grouped by topic. 
Keep under {{maxSummaryTokens}} tokens.
```

This prompt is engineered to capture precisely what the property system misses: **reasoning, sequence, rejections, tone, implicit context**.

### Where the Summary Lives

Stored as a data key in the conversation memory:

```java
// After summarization
var summaryData = new Data<>("conversation:running_summary", summaryText);
currentStep.storeData(summaryData);

// Also store the metadata so the agent knows what's summarized
var summaryMeta = new Data<>("conversation:summary_range", 
    Map.of("fromStep", 1, "toStep", 35, "summarizedAt", Instant.now()));
currentStep.storeData(summaryMeta);
```

On next turn:
1. `ConversationHistoryBuilder` checks for `conversation:running_summary` in memory
2. If present → inject summary + gap marker + recent window
3. If absent → use normal window logic

### Incremental Updates

The summary is **rolling** — each update re-summarizes the previous summary + new unsummarized turns:

```
Turn 1-10:  Summary_v1 = summarize(turns 1-10)
Turn 11-20: Summary_v2 = summarize(Summary_v1 + turns 11-20)
Turn 21-30: Summary_v3 = summarize(Summary_v2 + turns 21-30)
```

Each version is stored as a new data key (immutable history). Only the latest is used for context injection. Previous versions are available for audit/debugger.

### Shared Summarization Infrastructure

Rolling summary and Dream consolidation (`dream.summarizeInteractions` from Phase 11a) both need an LLM summarization call. They MUST share infrastructure:

```java
@ApplicationScoped
public class SummarizationService {
    
    private final ChatModelRegistry chatModelRegistry;
    private final MeterRegistry meterRegistry;
    
    /**
     * Summarize content using a specified LLM.
     * Shared by: conversation rolling summary, Dream consolidation.
     * 
     * Automatically handles:
     * - Cost tracking via existing CostTracker
     * - Rate limiting
     * - Metrics (summarization.calls, summarization.tokens, summarization.cost)
     */
    public String summarize(String content, String instructions,
                            String llmProvider, String llmModel,
                            int maxOutputTokens) {
        // Build prompt, call LLM, track cost
    }
}
```

This is the single place where summarization prompts are engineered, cost is tracked, and metrics are recorded. Both Dream and conversation summary configure it with their own provider/model/prompt but share the execution path.

### File Impact

| File | Change |
|---|---|
| `LlmConfiguration.java` | ADD `ConversationSummaryConfig` inner class |
| `SummarizationService.java` | NEW — shared summarization infrastructure |
| `ConversationSummarizer.java` | NEW — rolling summary logic |
| `ConversationRecallTool.java` | NEW — built-in drill-down tool |
| `ConversationHistoryBuilder.java` | MODIFY — summary-aware context assembly |
| `Conversation.java` | MODIFY — async summary trigger in `postConversationLifecycleTasks()` |
| `AgentOrchestrator.java` | MODIFY — register conversationRecall tool |

**Estimated effort**: ~400 lines new, ~60 lines modified

---

## Strategy 3: Conversation Chaining

### Concept

When a conversation reaches a configured threshold or ends naturally, EDDI can **chain** into a new conversation that inherits context from the previous one. This is especially valuable for:

- **1:1 channel adapters** (Telegram, WhatsApp, Slack) where there's no natural "start new conversation" UX — the user just keeps typing
- **Multi-session advisory** agents where the user returns days later
- **Workflow completion** — the agent finishes its workflow, but the user wants to continue

### How It Works

```
Conversation A (100+ turns)
    → Threshold reached / CONVERSATION_END action / user requests
    → System generates summary of Conversation A
    → Creates Conversation B with:
        - All longTerm properties (automatic — Conversation.init() already loads these)
        - All persistent user memories (Phase 11a — automatic)
        - Summary of A injected as initial context
        - Link to A's conversationId (provenance chain)
    → User continues in Conversation B transparently
```

### Configuration

Agent-level config (not task-level — chaining affects the entire conversation lifecycle):

```json
{
  "conversationChaining": {
    "enabled": false,
    "triggerMode": "manual",
    "maxStepsThreshold": 100,
    "carryOverSummary": true,
    "carryOverProperties": true,
    "notifyUser": true,
    "summarizationConfig": {
      "llmProvider": "openai",
      "llmModel": "gpt-4o-mini",
      "maxSummaryTokens": 1000
    }
  }
}
```

| Field | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `triggerMode` | `"manual"` | `"manual"` (user/API triggers), `"on_end"` (auto on CONVERSATION_END), `"on_threshold"` (auto when maxSteps exceeded) |
| `maxStepsThreshold` | `100` | For `on_threshold` mode |
| `carryOverSummary` | `true` | Generate and inject a summary of the previous conversation |
| `carryOverProperties` | `true` | Explicitly copy longTerm properties (Note: already happens via `Conversation.init()` → `loadLongTermProperties()`, this flag controls whether to force reload from latest state) |
| `notifyUser` | `true` | Send a message like "Starting a fresh conversation. I still remember our previous discussion." |
| `summarizationConfig` | (uses agent default) | LLM config for generating the crossover summary. Uses `SummarizationService` |

### Trigger Modes

**`manual`**: The chaining endpoint is available via REST/MCP but never triggers automatically. The client app (Chat UI, Telegram adapter) or human operator decides when to chain.

**`on_end`**: When the `CONVERSATION_END` action fires (behavior rules signal completion), the system automatically creates a new conversation with context. The response to the user includes the final message from conversation A, plus the "fresh start" notification.

**`on_threshold`**: When step count exceeds `maxStepsThreshold`, the system chains before the next LLM call. This is transparent to the user — they just keep talking.

For all modes, the user should have clarity about what's happening:
- Fresh start vs. carried-over context should be visually distinguishable in the UI
- The agent's first message in the new conversation should acknowledge the handoff

### Provenance Chain

```java
public record ConversationChainLink(
    String previousConversationId,
    String summaryAtHandoff,
    Instant chainedAt
) {}
```

Stored as a property in the new conversation:
```java
conversationProperties.put("conversation:chain_link", 
    new Property("conversation:chain_link", chainLinkMap, Scope.conversation));
```

This enables:
- Debugger can follow the chain backward
- REST API can query conversation lineage
- Agent can reference "the previous conversation" in its context

### File Impact

| File | Change |
|---|---|
| `AgentConfiguration.java` | ADD `ConversationChainingConfig` |
| `ConversationChainService.java` | NEW — chaining logic, summary generation, new conversation creation |
| `Conversation.java` | MODIFY — chain trigger check in `postConversationLifecycleTasks()` |
| `ConversationService.java` | MODIFY — new `chainConversation()` method |
| `RestConversationStore.java` | MODIFY — REST endpoint for manual chaining |

**Estimated effort**: ~350 lines new, ~80 lines modified

---

## Updates to Persistent Memory Architecture (Phase 11a)

Based on critical review, the following changes should be incorporated into the persistent memory plan:

### Dream Consolidation — Cost Control & Batching

Dream MUST use `ScheduleFireExecutor` (and by extension, `SchedulePollerService`) for scheduling — the same infrastructure as all other scheduled tasks, inheriting cluster-aware execution, fire logging, and retry semantics.

The Dream task itself needs cost controls:

```json
"dream": {
  "enabled": false,
  "schedule": "0 3 * * *",
  "detectContradictions": true,
  "contradictionResolution": "keep_newest",
  "pruneStaleAfterDays": 90,
  "summarizeInteractions": false,
  "llmProvider": "openai",
  "llmModel": "gpt-4o-mini",
  "batchSize": 50,
  "maxCostPerRun": 5.00,
  "maxUsersPerRun": 1000
}
```

**Incremental processing**: Track `lastDreamProcessedAt` per user (stored as a field on the user's memory document or a lightweight `dream_state` collection). Each Dream run queries only users where `updatedAt > lastDreamProcessedAt`. This means a deployment with 10,000 users but only 200 with new entries since last run processes only 200 users.

**Batch execution flow**:

```
1. Query users with entries updated since last Dream run
2. Sort by updatedAt ascending (oldest pending first — ensures fairness)
3. Process in batches of batchSize (default 50)
4. After each batch:
   a. Check accumulated cost against maxCostPerRun
   b. If exceeded → record watermark (last processed userId) → stop
   c. If maxUsersPerRun exceeded → stop
5. Log metrics:
   - dream.users.processed (counter)
   - dream.entries.pruned (counter)
   - dream.contradictions.found (counter)
   - dream.cost.total (gauge)
   - dream.duration.ms (timer)
6. Update lastDreamProcessedAt for all processed users
```

**Abort-on-budget**: If cost exceeds `maxCostPerRun` mid-batch, finish the current user (don't leave partial work), then stop. Next scheduled run picks up from the watermark.

### Recall Ordering — Configurable

```json
"userMemoryConfig": {
  "recallOrder": "most_recent",
  ...
}
```

| Value | Sort | Overhead | Description |
|---|---|---|---|
| `most_recent` | `updatedAt DESC` | Zero | Default — recent facts first |
| `most_accessed` | `accessCount DESC, updatedAt DESC` | Write on every recall (increment counter) | Prioritize frequently used memories |

For v1, implement both. Default to `most_recent`. The `most_accessed` mode adds a `db.usermemories.updateMany({accessCount: {$inc: 1}})` on each recall — acceptable overhead for deployments that opt in.

`relevance` (semantic similarity) is deferred to v2 when pgvector/semantic search is available.

### Category Validation

Enforce a closed enum on `UserMemoryEntry.category`:

```java
public enum MemoryCategory {
    preference, fact, context;
    
    public static MemoryCategory fromString(String value) {
        try {
            return valueOf(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warnf("Unknown memory category '%s', defaulting to 'fact'", value);
            return fact;
        }
    }
}
```

The `rememberFact` LLM tool and MCP tool both validate through this enum. Invalid categories gracefully default to `fact` with a logged warning.

### Write-Side Guardrails on LLM Memory Tools

```java
// In UserMemoryTool

private static final int MAX_VALUE_LENGTH = 2048;  // 2KB per entry
private static final int MAX_WRITES_PER_TURN = 10;  // prevent runaway loops

private int writesThisTurn = 0;  // reset per pipeline execution

@Tool("Remember a fact or preference about the current user")
public String rememberFact(@P("key") String key, @P("value") String value, 
                           @P("category") String category, 
                           @P("visibility") String visibility) {
    
    if (value != null && value.length() > MAX_VALUE_LENGTH) {
        return "Error: value too long (" + value.length() + " chars). " +
               "Maximum is " + MAX_VALUE_LENGTH + ". Summarize the value first.";
    }
    
    if (++writesThisTurn > MAX_WRITES_PER_TURN) {
        return "Error: too many memory writes this turn (" + writesThisTurn + "). " +
               "Maximum is " + MAX_WRITES_PER_TURN + " per conversation turn.";
    }
    
    // ... proceed with storage
}
```

### Contradiction Resolution

```json
"contradictionResolution": "keep_newest"
```

| Value | Behavior |
|---|---|
| `keep_newest` (default) | Newer entry overwrites the older one. The old value is logged in the Dream audit trail for recoverability. |
| `keep_both` | Both entries are kept. A `conflicted: true` flag is set on both. The agent sees both values on next recall and can decide. |

`ask_user` and `llm_decide` are deferred — both introduce complexity disproportionate to their value in v1.

### GroupId Lifecycle

**Source of groupIds**: Populated from `GroupConversation` context. When a conversation is part of a group discussion (via `GroupConversationService`), the active groupId is available from the group conversation's `AgentGroupConfiguration`. During `Conversation.init()`, if the conversation was created as part of a group, the groupId is stored as a conversation property and made available to `PropertySetterTask` and `UserMemoryTool`.

**Agent removal from group**: When an agent is removed from an `AgentGroupConfiguration`, its `group`-visibility entries become **naturally invisible** — on recall, the agent is no longer in the group's member list, so the `getVisibleEntries()` query's group filter excludes those entries. No data is deleted.

**Group deletion**: Orphaned entries remain in storage but are naturally invisible (no active group matches the deleted groupId). Options for cleanup:
- **Dream pruning**: Add a Dream sub-task that detects orphaned groupIds (entries referencing groups that no longer exist) and offers to either delete them or degrade to `self` visibility
- **Admin REST endpoint**: `POST /usermemory/cleanup/orphaned-groups` for manual cleanup
- **Default behavior**: Do nothing — orphaned entries cost storage but cause no harm. They become visible again if a group with the same ID is recreated

For v1, **do nothing** is the safest default. Dream can optionally flag orphaned entries for admin review.

---

## Sequencing & Dependencies

```
                    ┌──────────────────────────┐
                    │ Phase 11a: Persistent     │
                    │ User Memory               │
                    │ (prerequisite)            │
                    └─────────┬────────────────┘
                              │
              ┌───────────────┼──────────────────┐
              ▼               │                  ▼
┌─────────────────────┐       │    ┌──────────────────────────┐
│ Strategy 1:         │       │    │ SummarizationService     │
│ Token-Aware Window  │       │    │ (shared infrastructure)  │
│ (independent —      │       │    │ Used by Dream +          │
│  can run anytime)   │       │    │ conversation summary     │
└─────────────────────┘       │    └───────────┬──────────────┘
                              │                │
                              │    ┌───────────▼──────────────┐
                              │    │ Strategy 2:              │
                              │    │ Rolling Summary +        │
                              │    │ Conversation Recall Tool │
                              └───►│ (depends on Phase 11a    │
                                   │  + SummarizationService) │
                                   └───────────┬──────────────┘
                                               │
                                   ┌───────────▼──────────────┐
                                   │ Strategy 3:              │
                                   │ Conversation Chaining    │
                                   │ (depends on all above)   │
                                   └──────────────────────────┘
```

### Recommended build order

1. **Phase 11a: Persistent User Memory** — foundation for everything
2. **Strategy 1: Token-Aware Window** — quick win, zero LLM cost, no dependencies
3. **SummarizationService** — shared infrastructure (used by Dream + Strategy 2)
4. **Strategy 2: Rolling Summary + Conversation Recall** — the core value play
5. **Strategy 3: Conversation Chaining** — architectural, builds on everything above

---

## Verification Plan

### Strategy 1: Token-Aware Window

- Unit test: `ConversationHistoryBuilderTest` — verify token budget respected, anchored steps always present
- Unit test: token counting accuracy against known model tokenizers
- Integration test: 50-step conversation with variable step sizes → verify correct message assembly
- Backward compat: `maxContextTokens = -1` → behavior identical to current step-count

### Strategy 2: Rolling Summary

- Unit test: `ConversationSummarizerTest` — verify incremental summary updates
- Unit test: `ConversationRecallToolTest` — verify turn range retrieval
- Integration test: conversation exceeding summary threshold → summary generated → next LLM call includes summary
- Test: summary prompt excludes facts already in properties
- Test: `ASYNC_AFTER_RESPONSE` trigger fires after response without blocking

### Strategy 3: Conversation Chaining

- Unit test: `ConversationChainServiceTest` — new conversation created with correct context
- Integration test: `on_end` trigger → verify new conversation inherits properties + summary
- Integration test: `on_threshold` → verify automatic chaining at step limit
- Test: provenance chain queryable via REST API

### SummarizationService

- Unit test: cost tracking integration
- Unit test: rate limiting
- Test: shared by Dream and conversation summary with different prompts → both work correctly

### Build Verification

```bash
./mvnw compile && ./mvnw test
```

---

## What Was Considered and Not Included

| Strategy | Why not |
|---|---|
| **Importance-based retention** | The Property system IS the importance extractor. PropertySetter configs explicitly choose what to remember. Building a second scoring system duplicates this but with fragile heuristics instead of explicit configuration. |
| **Tiered compression** (3 levels: verbatim → condensed → summarized → one-liner) | Requires 3 maintained prompt tiers with unclear "condensed" definition. The marginal benefit over single summary + recent window is tiny, but triples testing surface. |
| **Per-turn importance scoring** | Requires either LLM calls (circular — spending tokens to decide which tokens to keep) or hardcoded heuristics (brittle, fights the config-driven philosophy). |
