# Conversation Cancellation & Lifecycle Control

> **Scope**: Cancel/stop for group discussions and regular conversations.  
> **HITL prerequisite**: This plan explicitly designs the control mechanism to be extensible for HITL pause/resume/approve without refactoring.

---

## 1. Problem Statement

Currently, neither group discussions nor regular agent conversations can be stopped mid-execution.

- **Group discussions**: The `executeDiscussion()` loop runs all phases to completion. SSE client disconnect silently drops events but the backend keeps calling LLM agents — wasting tokens and compute.
- **Regular conversations**: The `Conversation.say()` method submits work via `ConversationCoordinator.submitInOrder()` which runs the full lifecycle pipeline (parser → rules → LLM → output → property setter). There is no way to externally cancel an in-flight turn.
- **Nested groups**: A GROUP-type member triggers a recursive `discuss()` call. Cancelling the parent must cascade to all child discussions.

### What already exists

| Mechanism | Where | Purpose |
|-----------|-------|---------|
| `ConversationStopException` | `ILifecycleManager` | Stops a regular conversation's lifecycle — thrown by specific tasks, caught in `Conversation.executeConversationStep()` |
| `STOP_CONVERSATION` action | `IConversation` | String constant that triggers `endConversation()` via the actions system |
| `ConversationState.EXECUTION_INTERRUPTED` | `ConversationState` enum | Already exists as a state for interrupted conversations |
| `CompletableFuture.cancel(true)` | `executeParallelPhase()` | Used for timeout — cancels parallel agent calls |
| `eventSink.isClosed()` check | `RestGroupConversation.sendEvent()` | Detects SSE client disconnect but does **not** propagate to execution loop |

---

## 2. Design: `DiscussionControlToken`

A shared, thread-safe control object that the execution loop checks at **safe points**. Designed from the start to support HITL operations beyond simple cancel.

### 2.1 Control Signal Enum

```java
public enum ControlSignal {
    /** Normal execution — continue to next speaker/phase */
    CONTINUE,
    
    /** Graceful stop — finish current speaker's turn, then stop before the next speaker */
    CANCEL_GRACEFUL,
    
    /** Immediate stop — attempt to interrupt the current LLM call */
    CANCEL_IMMEDIATE,
    
    // --- HITL extensions (Phase 9b, not implemented now) ---
    // PAUSE,           // Stop after current speaker, mark as PAUSED (resumable)
    // AWAIT_APPROVAL,  // Block until human approves/rejects the last response
}
```

### 2.2 Token Class

```java
public class DiscussionControlToken {
    private final AtomicReference<ControlSignal> signal = new AtomicReference<>(ControlSignal.CONTINUE);
    
    /** Set the control signal. Thread-safe. */
    public void setSignal(ControlSignal signal) {
        this.signal.set(signal);
    }
    
    /** Read the current signal. Thread-safe. */
    public ControlSignal getSignal() {
        return signal.get();
    }
    
    /** Convenience: is any cancel variant active? */
    public boolean isCancelled() {
        var s = signal.get();
        return s == ControlSignal.CANCEL_GRACEFUL || s == ControlSignal.CANCEL_IMMEDIATE;
    }
    
    // --- HITL extension point (Phase 9b) ---
    // public boolean isPaused() { return signal.get() == ControlSignal.PAUSE; }
    // public CompletableFuture<HitlDecision> awaitApproval() { ... }
}
```

### 2.3 Token Registry

```java
// In GroupConversationService
private final ConcurrentMap<String, DiscussionControlToken> activeTokens = new ConcurrentHashMap<>();
```

- Token is created when `startAndDiscussAsync()` starts, keyed by `conversationId`
- Token is removed in the `finally` block of `executeDiscussion()`
- `cancelDiscussion(convId, mode)` looks up the token and sets the signal

---

## 3. Safe Points

### 3.1 Group Discussions

The execution loop has clear phase/speaker boundaries. Safe points are:

```
executeDiscussion()
  for each phase:                          ← ✅ Safe point: between phases
    for each repeat:
      for each speaker (sequential):       ← ✅ Safe point: between speakers (GRACEFUL)
        executeSpeakerTurn()               ← ⚠️  IMMEDIATE: needs to interrupt the blocking call
      for each speaker (parallel):
        futures.get(timeout)               ← ✅ Safe point: cancel remaining futures
```

**Graceful cancel check locations** (checked at each `✅`):

1. **Top of phase loop** (line ~208) — before `executeSequentialPhase()` / `executeParallelPhase()`
2. **Top of speaker loop** in `executeSequentialPhase()` (line ~385) — before each `executeAgentTurn()`
3. **After each future** in `executeParallelPhase()` (line ~440) — cancel remaining futures if signal received

**Immediate cancel** — additionally:

4. **In `executeAgentTurn()`** (line ~565) — the `CompletableFuture<String> responseFuture` has a blocking `.get(timeout)`. Interrupt the thread to abort the LLM HTTP call. The `cancel(true)` mechanism already exists for parallel timeout.

### 3.2 Regular Conversations

The lifecycle pipeline runs tasks sequentially via `ILifecycleManager.executeLifecycle()`. The existing `ConversationStopException` already provides the interrupt mechanism — we just need to trigger it externally.

**Approach**: Add a `volatile boolean cancelled` flag to `IConversationMemory`.  The `LifecycleManager` checks this **between lifecycle tasks** (between parser, rules, LLM, output steps). If cancelled, throw `ConversationStopException`.

```
executeLifecycle()
  for each task:         ← ✅ Safe point: between lifecycle tasks
    if (memory.isCancelled()) throw new ConversationStopException();
    task.executeTask(memory);
```

This reuses the existing `ConversationStopException` handling in `Conversation.executeConversationStep()` (line ~295) which already catches this exception and completes gracefully.

### 3.3 Cascading: Group → Agent → Sub-Group

When a group is cancelled:

1. The `DiscussionControlToken` signal is set
2. The phase loop breaks at the next safe point
3. **Currently-executing agent turns** need cascading:
   - `executeAgentTurn()` calls `conversationService.say()` which runs the lifecycle
   - The agent's `ConversationMemory` needs a cancel flag too
   - The group service sets `memory.setCancelled(true)` before checking the token
4. **Nested sub-groups** (`executeGroupMemberTurn()` calls `discuss()` recursively):
   - Pass the parent's `DiscussionControlToken` to child discussions
   - Child discussions inherit the cancel signal automatically

---

## 4. API Design

### 4.1 Group Discussion Cancel

```
POST /groups/{groupId}/conversations/{groupConversationId}/cancel
Content-Type: application/json

{
  "mode": "GRACEFUL"    // or "IMMEDIATE"
}
```

- **`GRACEFUL`** (default): Finish the current speaker's turn, then stop. Transcript is consistent.
- **`IMMEDIATE`**: Interrupt the current LLM call. The speaking agent's turn gets a SKIPPED entry.

**Response**: `200 OK` if found and cancelled, `404` if conversation not found or already completed.

**New state**: `GroupConversationState.CANCELLED`

### 4.2 Regular Conversation Cancel

```
POST /agents/{agentId}/conversations/{conversationId}/cancel
```

No body needed — regular conversations have a single in-flight turn, so there's no graceful/immediate distinction. The lifecycle is interrupted between tasks via `ConversationStopException`.

**Existing state**: `ConversationState.EXECUTION_INTERRUPTED` (already exists!)

### 4.3 SSE Auto-Cancel (Group only)

When the SSE client disconnects (`eventSink.isClosed()`), the REST layer automatically calls `cancelDiscussion(convId, GRACEFUL)`. This is the "user closed the browser tab" scenario.

```java
// In RestGroupConversation.discussStreaming(), modify the listener:
@Override
public void onSpeakerStart(SpeakerStartEvent event) {
    if (eventSink.isClosed()) {
        groupConversationService.cancelDiscussion(gc.getId(), ControlSignal.CANCEL_GRACEFUL);
        return;
    }
    sendEvent(eventSink, sse, EVENT_SPEAKER_START, toJson(event));
}
```

---

## 5. State Transitions

### Group Conversation

```
CREATED → IN_PROGRESS → SYNTHESIZING → COMPLETED
                     ↘ CANCELLED (via cancel)
                     ↘ FAILED (via error)
               // HITL extensions (Phase 9b):
               // → PAUSED → IN_PROGRESS (resume)
               // → AWAITING_APPROVAL → IN_PROGRESS (approved) | CANCELLED (rejected)
```

### Regular Conversation

```
READY → IN_PROGRESS → READY (completed turn)
                   ↘ EXECUTION_INTERRUPTED (via cancel — already exists)
                   ↘ ERROR
```

---

## 6. HITL Extension Points

The `DiscussionControlToken` is explicitly designed so HITL can be added **without modifying** the cancel implementation:

| Cancel (this plan) | HITL (Phase 9b) |
|---|---|
| `CANCEL_GRACEFUL` signal | `PAUSE` signal — same check location, but saves state as PAUSED instead of CANCELLED |
| Token removed after discussion ends | Token kept alive for PAUSED conversations — allows resume |
| No human interaction needed | `AWAIT_APPROVAL` signal — blocks the execution thread until a human endpoint is called |
| `POST .../cancel` | `POST .../pause`, `POST .../resume`, `POST .../approve`, `POST .../reject` |

**Key architectural guarantee**: The safe-point checking logic in the phase loop and lifecycle manager is **identical** for cancel and HITL. Adding HITL means:
1. Add new `ControlSignal` variants (`PAUSE`, `AWAIT_APPROVAL`)
2. Add corresponding state enum values (`PAUSED`, `AWAITING_APPROVAL`)
3. Add REST endpoints for human actions
4. In the safe-point check, handle new signals alongside the cancel ones

No refactoring of the execution loop is needed.

### HITL for Regular Conversations

The `volatile boolean cancelled` on `IConversationMemory` would evolve to a `ControlSignal` field (or a dedicated `ConversationControlToken`). The lifecycle manager checks between tasks. For HITL, instead of throwing `ConversationStopException`, it would block on a `CompletableFuture<HitlDecision>` that resolves when the human approves/rejects.

---

## 7. Implementation Plan

### Phase A: Backend Core (EDDI repo)

| # | File | Change |
|---|------|--------|
| A1 | `DiscussionControlToken.java` [NEW] | Token class with `AtomicReference<ControlSignal>` |
| A2 | `ControlSignal.java` [NEW] | Enum: `CONTINUE`, `CANCEL_GRACEFUL`, `CANCEL_IMMEDIATE` |
| A3 | `GroupConversation.java` | Add `CANCELLED` to `GroupConversationState` enum |
| A4 | `GroupConversationService.java` | Add `activeTokens` map, create/remove token around execution, check signal at safe points |
| A5 | `IGroupConversationService.java` | Add `cancelDiscussion(String convId, ControlSignal mode)` |
| A6 | `IRestGroupConversation.java` | Add `POST /{convId}/cancel` endpoint |
| A7 | `RestGroupConversation.java` | Implement cancel endpoint + SSE auto-cancel on `eventSink.isClosed()` |

### Phase B: Regular Conversation Cancel (EDDI repo)

| # | File | Change |
|---|------|--------|
| B1 | `IConversationMemory.java` | Add `setCancelled(boolean)` / `isCancelled()` |
| B2 | `ConversationMemory.java` | Implement with `volatile boolean` |
| B3 | `LifecycleManager.java` (impl) | Check `memory.isCancelled()` between task executions, throw `ConversationStopException` |
| B4 | `IConversationService.java` | Add `cancelConversation(String convId)` |
| B5 | `ConversationService.java` | Implement: find active conversation memory, set cancelled |
| B6 | REST endpoint | Add `POST /agents/{agentId}/conversations/{convId}/cancel` |

### Phase C: Frontend (EDDI-Manager repo)

| # | File | Change |
|---|------|--------|
| C1 | `groups.ts` | Add `cancelGroupDiscussion(groupId, convId, mode)` API function |
| C2 | `use-group-discussion-stream.ts` | Wire `abortStream()` to call the cancel API before disconnecting SSE |
| C3 | `group-detail.tsx` | Add Stop/Cancel button (re-introduce, now backed by real backend cancel) |
| C4 | Chat components | Add stop button to regular conversation chat during streaming |

### Phase D: Tests

| # | Test |
|---|------|
| D1 | Unit test: `DiscussionControlToken` thread safety |
| D2 | Unit test: `GroupConversationService` cancel at phase boundary |
| D3 | Unit test: `GroupConversationService` cancel cascading to sub-group |
| D4 | Integration test: SSE auto-cancel on client disconnect |
| D5 | Unit test: Regular conversation cancel between lifecycle tasks |

---

## 8. Open Questions

1. **Cancel response body**: Should `POST .../cancel` return the partial `GroupConversation` (with transcript so far) or just `200 OK`?
   - *Recommendation*: Return the saved `GroupConversation` — the client can display the partial transcript.

2. **Idempotency**: What if cancel is called on an already-completed conversation?
   - *Recommendation*: Return `200 OK` with the existing conversation — no error.

3. **Metrics**: Should cancelled discussions count as failures?
   - *Recommendation*: New counter `eddi_group_discussion_cancelled_count` (separate from failure).

4. **Nested group cancel**: When a parent group is cancelled, should the child sub-group's conversation also be persisted with `CANCELLED` state?
   - *Recommendation*: Yes — the parent's token cascades to the child, so the child also gets `CANCELLED`.

---

## 9. Relationship to Other Plans

- **Agentic Improvements Phase 9b (HITL)**: This plan provides the prerequisite infrastructure. HITL adds `PAUSE`/`AWAIT_APPROVAL` signals to the same token mechanism.
- **Multi-tenancy**: Cancel endpoints inherit the same auth/tenant context as other conversation endpoints — no special handling needed.
- **Observability**: New metrics (`cancelled_count`) and trace spans for cancel events.
