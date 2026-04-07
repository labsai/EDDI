# Multi-Agent UX Improvements

> **Based on**: [research-1.md](research-1.md) — *"Architecting Multi-Agent User Experiences in Enterprise Workspaces: Integrating EDDI v6.0.0-RC1 into Slack and Microsoft Teams"*
>
> **Status**: Planning (pre-approval)

---

## 1. Context

The research paper analyzes deploying EDDI's multi-agent group conversations into enterprise chat platforms (Slack & Microsoft Teams). It validates EDDI's existing architecture as a strong fit — the 5 discussion topologies, phase-based orchestration, SSE event streaming, and A2A protocol are all well-suited for this purpose.

This document distills the **actionable improvements** from the research, filtered through critical assessment of what EDDI already has and what it genuinely needs.

### What EDDI Already Has (No Changes Needed)

| Capability | Implementation | Status |
|---|---|---|
| 5 Discussion Styles + CUSTOM | `DiscussionStyle` enum in `AgentGroupConfiguration` | ✅ Complete |
| Phase-based orchestration | `GroupConversationService.executeDiscussion()` with 8 `PhaseType` values | ✅ Complete |
| Parallel agent execution | `TurnOrder.PARALLEL` with `CompletableFuture` + virtual threads | ✅ Complete |
| SSE event streaming | `GroupConversationEventSink` — 7 event types (group_start, phase_start, speaker_start, speaker_complete, phase_complete, synthesis_start, group_complete, group_error) | ✅ Complete |
| A2A protocol | `A2ATaskHandler` + `AgentCardService` with JSON-RPC | ✅ Complete |
| Nested groups (group-of-groups) | `MemberType.GROUP` with recursive depth control (`eddi.groups.max-depth`) | ✅ Complete |
| Timeout / retry handling | `ProtocolConfig` with `agentTimeoutSeconds`, `maxRetries`, `MemberFailurePolicy` | ✅ Complete |
| Execution hash dedup | `ToolExecutionService` pipeline | ✅ Complete |
| Rate limiting | `ToolRateLimiter` | ✅ Complete |
| Cost tracking | `ToolCostTracker` | ✅ Complete |

### Enterprise Use Cases — Configuration, Not Code

The research identifies 5 enterprise use case categories. All are supported by existing discussion styles — they require **agent JSON configs** (system prompts, tools, HTTP calls), not engine code changes:

| Use Case | Discussion Style | Config Needed |
|---|---|---|
| Brainstorming / ideation | ROUND_TABLE | Agents with creative/exploratory system prompts |
| Code review / document review | PEER_REVIEW | Agents with code analysis tools (via `httpCall` or MCP) |
| Risk assessment / stress-testing | DEVIL_ADVOCATE | Adversarial agent with `ROLE:DEVIL_ADVOCATE` |
| Forecasting / unbiased estimates | DELPHI | Domain-specific forecasting agents; `ContextScope.ANONYMOUS` prevents groupthink |
| Policy debate / pro-con analysis | DEBATE | Agents assigned `ROLE:PRO` / `ROLE:CON`; moderator as judge |

### What We Explicitly Will NOT Build

| Suggestion from Research | Why Not |
|---|---|
| **DAG Pipeline** | Agents already run in parallel via `TurnOrder.PARALLEL`. The sequential lifecycle pipeline is **by design** — it provides deterministic governance (Pillar 2). |
| **Dynamic temperature escalation** | Configuration concern, not an architectural gap. Temperature is already configurable per-agent in `LlmConfiguration`. If needed, add a phase-aware override in `DiscussionPhase` — no engine changes required. |
| **Distributed bot identity** (separate app per agent) | Research itself recommends against this. Single orchestrator bot with text prefixes is the right approach. |
| **Cryptographic message signing** (within EDDI core) | Within EDDI's orchestration, agents only execute when explicitly called by the orchestrator — no webhook loop risk internally. Nonces are only relevant at the **adapter layer** for external platforms. |

---

## 2. Improvements

### 2.1 Add `maxTurns` to `ProtocolConfig`

**Priority**: 🔴 High — safety-critical quick win

**Problem**: `ProtocolConfig` controls per-agent failure handling (`maxRetries`, `agentTimeoutSeconds`) but has no **global hard cap** on total agent turns within a discussion. A misconfigured ROUND_TABLE with high `maxRounds` could run indefinitely.

**Solution**:
- Add `int maxTurns` field to `ProtocolConfig` record (default: 50)
- Add turn counter in `GroupConversationService.executeDiscussion()` loop
- When exceeded: force-synthesize with whatever transcript exists, emit `group_error` event with "max turns exceeded" warning, set state to `COMPLETED`
- Expose in `McpGroupTools.create_group()` and REST API

**Files to modify**:
- `AgentGroupConfiguration.java` — add field to `ProtocolConfig` record
- `GroupConversationService.java` — add counter in phase loop, check before each agent turn
- `McpGroupTools.java` — expose parameter in `create_group`
- `DiscussionStylePresets.java` — document reasonable defaults per style

**Effort**: < 1 day

---

### 2.2 Multi-Channel Adapter Layer (Phase 11b)

**Priority**: 🔴 High — biggest force-multiplier

**Problem**: EDDI's backend is ready for multi-agent debates. What's missing is the "last mile" — translating SSE events into platform-specific chat messages (Slack threads, Teams channel posts).

**Solution**: Pluggable `IChannelAdapter` SPI that bridges `GroupDiscussionEventListener` events to platform APIs.

**Architecture**:

```
GroupConversationService (SSE Events)
       │
       ▼
┌──────────────────────────────────────┐
│  GroupDiscussionEventListener (SPI)  │  ← Already exists
│  ─ onGroupStart(event)              │
│  ─ onPhaseStart(event)              │
│  ─ onSpeakerStart(event)            │
│  ─ onSpeakerComplete(event)         │
│  ─ onSynthesisStart(event)          │
│  ─ onPhaseComplete(event)           │
│  ─ onGroupComplete(event)           │
│  ─ onGroupError(event)              │
└──────┬───────────────────┬───────────┘
       │                   │
  SlackAdapter         TeamsAdapter
  (Bolt SDK)           (Bot Framework)
```

**Key design decisions** (from research):
- **Identity**: Single bot app. Per-agent identity via structured text prefix: `**AgentName** [Role]: message`
- **Threading**: Anchor message in main channel → all debate in thread → synthesis broadcast back
- **Message size**: 4000-char safe limit for Slack (16 KB hard disconnect limit includes JSON overhead). Chunk larger outputs.
- **Rate limiting**: Async queue for outgoing Slack messages at ~1 req/sec (Tier 4)
- **Teams constraint**: Must mandate Teams Channels. Teams Group Chats have no threading — incompatible.
- **Adaptive Cards**: Teams rendering strictly limited to schema v1.5

**Adapter responsibilities**:
1. `postAnchor(channel, question, style, members)` — initial channel message
2. `postToThread(threadId, agentName, role, content)` — individual agent contributions
3. `sendEphemeral(userId, status)` — intermediate status visible only to triggering user
4. `broadcastSynthesis(channel, threadId, synthesis)` — final answer back to main channel
5. `collectFeedback(messageId)` — embed reaction/button hooks
6. `validateNonce(incomingMessage)` — adapter-level loop prevention for shared channels

**Effort**: 2–4 weeks (abstraction + Slack adapter). Teams adapter comparable effort, defer to later.

**Start with Slack**: Simpler integration model (Bolt SDK, universal threading, `chat.postEphemeral` for quiet orchestration).

---

### 2.3 HITL Approval Framework (Phase 9b)

**Priority**: 🔴 High — prerequisite for enterprise deployment

**Problem**: No mechanism to pause a pipeline or group discussion at defined checkpoints for human approval. The research warns that "unbounded autonomy" is the #1 deployment risk for multi-agent systems.

**Solution**: Add `AWAITING_APPROVAL` state to conversation lifecycle.

**Design** (per AGENTS.md guidance — this is a **Conversation.java init/teardown concern**, not a new `ILifecycleTask`):

```
Pipeline running
  └─→ BehaviorRules emits action: "pause_for_approval"
      └─→ Pipeline halts, state → AWAITING_APPROVAL
      └─→ Approval event emitted (SSE / adapter)
      └─→ State serialized to memory

Human reviews proposed action
  └─→ POST /conversations/{conversationId}/approve
      └─→ State → IN_PROGRESS, pipeline resumes
  └─→ POST /conversations/{conversationId}/reject
      └─→ State → ENDED, rejection reason logged

In Slack/Teams (adapter concern):
  └─→ Render approval card (Block Kit / Adaptive Card)
  └─→ "Approve" / "Reject" buttons trigger REST endpoint
```

**Key design decisions**:
- `AWAITING_APPROVAL` is a new `ConversationState` — clean extension of existing state machine (READY/IN_PROGRESS/ENDED/ERROR)
- Approval timeout: configurable, defaults to 24h, auto-rejects after expiry
- Group discussions: approval pauses the entire discussion, not individual agent turns
- Audit: approval/rejection logged to audit ledger (already built for EU AI Act compliance)

**Files to modify**:
- `Conversation.java` — new state handling in lifecycle
- `ConversationState` enum — add `AWAITING_APPROVAL`
- New REST endpoints on `IRestConversation` — `approve`, `reject`
- `GroupConversationService` — integrate approval checkpoints

**Effort**: 1–2 weeks

---

### 2.4 Quiet Orchestration / Notification Triage

**Priority**: 🟡 Medium — part of adapter design

**Problem**: Multi-agent debates generating dozens of notifications per minute will trigger "alert fatigue" in enterprise users. Research cites ~30% attention drop per redundant alert.

**Solution**: Notification strategy built into the adapter layer (Priority 2.2).

**Rules**:
1. **Only 2 channel-level notifications per discussion**: start acknowledgment + final synthesis
2. **Intermediate updates via ephemeral messages**: `chat.postEphemeral` (Slack) — visible only to triggering user, no push notification, vanishes on refresh
3. **Agent debate contained in thread**: Platform defaults protect channel — users only get thread notifications if they explicitly follow
4. **Targeted @mentions only for HITL**: When human intervention is required (Priority 2.3), the adapter dynamically tags the responsible user
5. **Synthesis broadcast**: Use Slack's "Also send to channel" flag to push final answer from thread back to main channel

**Effort**: Built into adapter design, not a separate work item.

---

### 2.5 Feedback Collection Infrastructure

**Priority**: 🟡 Medium

**Problem**: No mechanism to capture user feedback on agent responses for continuous improvement. Teams mandates that AI outputs embed interactive feedback mechanisms.

**Solution**: New `IFeedbackStore` with REST API and adapter hooks.

**Data model**:
```java
record ConversationFeedback(
    String id,
    String conversationId,
    String groupConversationId,  // null for 1:1 conversations
    int stepIndex,
    FeedbackRating rating,       // POSITIVE, NEGATIVE, NEUTRAL
    String comment,              // optional free-text
    String userId,
    Instant timestamp
)

enum FeedbackRating { POSITIVE, NEGATIVE, NEUTRAL }
```

**API**:
- `POST /conversations/{conversationId}/feedback` — submit feedback
- `GET /conversations/{conversationId}/feedback` — read feedback for a conversation
- MCP tool: `submit_feedback(conversationId, rating, comment)`

**Adapter integration**:
- Slack: Observe `reaction_added` webhook (👍 = POSITIVE, 👎 = NEGATIVE) on synthesis messages
- Teams: `Action.Execute` buttons on Adaptive Cards appended to AI responses

**Files**:
- New `IFeedbackStore` interface + MongoDB/Postgres implementation
- New REST resource `IRestConversationFeedback`
- New MCP tool in `McpConversationTools`

**Effort**: ~1 week

---

## 3. Execution Order

| # | Item | Phase | Effort | Dependencies |
|---|---|---|---|---|
| 1 | `maxTurns` in `ProtocolConfig` | — | < 1 day | None |
| 2 | HITL approval framework | 9b | 1–2 weeks | None |
| 3 | `IChannelAdapter` interface design | 11b | 2–3 days | None |
| 4 | Slack adapter prototype | 11b | 2–3 weeks | #3 |
| 5 | `IFeedbackStore` + REST API | — | ~1 week | None |
| 6 | Teams adapter | 11b | 2–3 weeks | #3, #2 |

> **Note**: Items 1, 2, 3, and 5 can proceed in parallel. Item 4 depends on #3. Item 6 depends on #3 and benefits from #2 (HITL renders as Adaptive Cards in Teams).

---

## 4. References

- [research-1.md](research-1.md) — Source research paper
- [architecture.md](../architecture.md) — EDDI architecture overview
- [project-philosophy.md](../project-philosophy.md) — The Seven Pillars
- [GroupConversationService.java](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java) — Phase-based orchestrator
- [AgentGroupConfiguration.java](../../src/main/java/ai/labs/eddi/configs/groups/model/AgentGroupConfiguration.java) — Group configuration model
- [A2ATaskHandler.java](../../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskHandler.java) — A2A protocol handler
