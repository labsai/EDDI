# Conversation Cancellation & Lifecycle Control

> **Scope**: Cancel/stop for group discussions and regular conversations.  
> **HITL prerequisite**: This plan designs the detection mechanism (safe-point checking) to be reusable for HITL. However, HITL pause/resume requires significant additional work beyond what cancel provides — this is documented honestly in §6.

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

A shared, thread-safe control object that the execution loop checks at **safe points**.

### 2.1 Control Signal Enum

```java
public enum ControlSignal {
    /** Normal execution — continue to next speaker/phase */
    CONTINUE,
    
    /** Graceful stop — finish current speaker's turn, then stop before the next speaker */
    CANCEL_GRACEFUL,
    
    /** Immediate stop — best-effort attempt to interrupt the current in-flight call (see §3.4) */
    CANCEL_IMMEDIATE,
}
```

> **Why not include HITL signals here?** See §6 — PAUSE requires fundamentally different handling (state serialization + re-entry) that would complicate the cancel-only implementation. Better to add HITL signals when that feature is built.

### 2.2 Token Class

```java
public class DiscussionControlToken {
    private final AtomicReference<ControlSignal> signal = new AtomicReference<>(ControlSignal.CONTINUE);
    
    /** Set the control signal. Thread-safe, idempotent. */
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
- If token not found (conversation already finished), return gracefully

---

## 3. Safe Points

### 3.1 Group Discussions — Graceful Cancel

The execution loop has clear phase/speaker boundaries:

```
executeDiscussion()
  for each phase:                          ← ✅ CHECK: between phases
    for each repeat:
      for each speaker (sequential):       ← ✅ CHECK: before each speaker turn
        executeAgentTurn()                 
      for each speaker (parallel):
        futures.get(timeout)               ← ✅ CHECK: after each resolved future — cancel remaining
```

**Check locations** (3 insertion points):

1. **Top of phase loop** (line ~208): `if (token.isCancelled()) break;`
2. **Top of speaker loop** in `executeSequentialPhase()` (line ~385): `if (token.isCancelled()) break;`
3. **After each future** in `executeParallelPhase()` (line ~440): cancel remaining futures if signal received

When the check triggers:
- Set `gc.setState(CANCELLED)`
- Persist the conversation with partial transcript
- Emit `onGroupError(new GroupErrorEvent("Discussion cancelled by user"))`
- Return from `executeDiscussion()`

### 3.2 Group Discussions — Immediate Cancel

Adds a 4th check:

4. **During `executeAgentTurn()`**: The blocking `responseFuture.get(timeout)` can be interrupted. Store the future on the token so `cancelDiscussion()` can call `future.cancel(true)`.

```java
// In DiscussionControlToken — for IMMEDIATE only:
private volatile CompletableFuture<?> activeFuture;

public void setActiveFuture(CompletableFuture<?> f) { this.activeFuture = f; }

public void cancelActiveFuture() {
    var f = activeFuture;
    if (f != null) f.cancel(true);
}
```

> **⚠️ Limitation**: `cancel(true)` sets the interrupt flag on the thread. Whether the underlying LLM HTTP call actually responds to interruption depends on the HTTP client implementation. Java's `HttpClient` does respect interruption, but if the call goes through `ConversationService.say()` → `ConversationCoordinator.submitInOrder()` → `BaseRuntime`, the interrupt may not reach the actual HTTP call. **IMMEDIATE is best-effort** — it will reliably prevent the next speaker from starting, but may not abort the current LLM call mid-stream.

### 3.3 Cascading: Group → Nested Sub-Group

When the parent group checks `token.isCancelled()` and breaks, any currently-executing child `discuss()` call is already running in the same virtual thread. The simplest cascade:

- **Pass the token to child discussions**: `executeDiscussion(gc, config, phases, question, listener, token)` — child checks the same token at its own safe points.
- This works because `executeGroupMemberTurn()` calls `discuss()` synchronously (not async) — so the parent's token is visible to the child.

### 3.4 Cascading: Group → In-Flight Agent Turn  

This is the hardest part. When `executeAgentTurn()` calls `conversationService.say()`:

```java
conversationService.say(DEFAULT_ENV, member.agentId(), convId, false, true, null, inputData, false, snapshot -> {
    String response = extractResponse(snapshot);
    responseFuture.complete(response);
});
```

The `say()` call submits work to `ConversationCoordinator.submitInOrder()` which dispatches to `BaseRuntime`. The group service does NOT have a reference to the `IConversationMemory` being used inside.

**For GRACEFUL cancel**: No cascading needed — the agent finishes its turn, and the cancel check fires at the next safe point (before the next speaker).

**For IMMEDIATE cancel**: Two options:

a) **Cancel the future** (`responseFuture.cancel(true)`): The group service thread unblocks with `CancellationException`. But the agent's lifecycle continues running in the coordinator's thread — it just has no listener waiting for the result. The result is discarded. _Wasted compute but functional._

b) **Track active memories in ConversationService** (more complex): Add a `ConcurrentMap<String, IConversationMemory>` to `ConversationService` so external callers can set a cancel flag. This requires modifying `ConversationService.say()` to register/unregister the memory around execution.

**Recommendation**: Start with option (a) for IMMEDIATE. It's pragmatic — the worst case is one wasted LLM call, but the discussion stops immediately from the group's perspective. Option (b) can be added later if the wasted compute becomes a real concern, and it's needed anyway for Phase B (regular conversation cancel).

### 3.5 Regular Conversations

The lifecycle pipeline runs tasks sequentially via `ILifecycleManager.executeLifecycle()`. The existing `ConversationStopException` provides the interruption mechanism.

**Approach**: Add a `volatile boolean cancelled` flag to `IConversationMemory`. The `LifecycleManager` checks **between lifecycle tasks** (between parser, rules, LLM, output). If cancelled, throw `ConversationStopException`.

```
executeLifecycle()
  for each task:         ← ✅ Safe point: between lifecycle tasks
    if (memory.isCancelled()) throw new ConversationStopException();
    task.executeTask(memory);
```

**Prerequisite**: `ConversationService` must track active `IConversationMemory` instances so external callers can find and cancel them:

```java
// In ConversationService
private final ConcurrentMap<String, IConversationMemory> activeMemories = new ConcurrentHashMap<>();

public void cancelConversation(String conversationId) {
    var memory = activeMemories.get(conversationId);
    if (memory != null) memory.setCancelled(true);
}
```

This reuses the existing `ConversationStopException` handling in `Conversation.executeConversationStep()` (line ~295).

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

- **`GRACEFUL`** (default if body omitted): Finish the current speaker's turn, then stop. Transcript is consistent and complete up to the cancellation point.
- **`IMMEDIATE`**: Best-effort interrupt of the current LLM call (see §3.4 limitations). The speaking agent's turn may get a SKIPPED entry or may complete if the interrupt doesn't reach the HTTP call.

**Responses**:
- `200 OK` + partial `GroupConversation` body — if actively running and cancelled
- `200 OK` + existing `GroupConversation` — if already completed/failed (idempotent, no error)
- `404 Not Found` — conversation ID doesn't exist

**New state**: `GroupConversationState.CANCELLED`

### 4.2 Regular Conversation Cancel

```
POST /agents/{agentId}/conversations/{conversationId}/cancel
```

No body needed — regular conversations have a single in-flight turn, so the distinction is always "interrupt between lifecycle tasks."

**Existing state**: `ConversationState.EXECUTION_INTERRUPTED` (already exists)

### 4.3 SSE Auto-Cancel

When the SSE client disconnects, the REST layer should auto-cancel the discussion to prevent wasted compute. The most reliable detection point is in the listener callbacks — checked at every event emission:

```java
// In RestGroupConversation — wrap every sendEvent call:
private boolean sendOrCancel(SseEventSink eventSink, Sse sse, String eventName, String data, String convId) {
    if (eventSink.isClosed()) {
        groupConversationService.cancelDiscussion(convId, ControlSignal.CANCEL_GRACEFUL);
        return true; // cancelled
    }
    sendEvent(eventSink, sse, eventName, data);
    return false;
}
```

> **Limitation**: This only detects disconnect when the next SSE event fires. If one agent takes 60 seconds to respond, the disconnect isn't detected until that agent completes. This is acceptable — it's a safety net, not the primary cancel mechanism.

---

## 5. State Transitions

### Group Conversation

```
CREATED → IN_PROGRESS → SYNTHESIZING → COMPLETED
                     ↘ CANCELLED (via user cancel or SSE disconnect)
                     ↘ FAILED (via error)
```

### Regular Conversation

```
READY → IN_PROGRESS → READY (completed turn)
                   ↘ EXECUTION_INTERRUPTED (via cancel — state already exists)
                   ↘ ERROR
```

---

## 6. HITL Relationship — Honest Assessment

### What cancel shares with HITL

The **detection mechanism** is identical: checking a control signal at safe points in the execution loop. Both cancel and HITL need:
- Thread-safe signal propagation
- Defined safe points between speakers/phases/lifecycle tasks
- State persistence of partial results

### What HITL needs beyond cancel

HITL PAUSE is **not** "cancel but save state." It requires fundamentally different handling:

| Concern | Cancel | HITL Pause |
|---------|--------|------------|
| Thread lifecycle | Break loop, return, GC thread | Must release thread — can't hold a virtual thread blocked for hours |
| State | Save partial transcript with CANCELLED | Save full execution context (phase index, speaker index, transcript, repeat count) so loop can resume |
| Re-entry | None | Resume `executeDiscussion()` from saved checkpoint — need to reconstruct the loop mid-iteration |
| Token lifetime | Removed after execution ends | Kept alive across pause/resume — potentially hours |
| Concurrent safety | Simple: signal is set, loop exits | Complex: resume may race with a second cancel or another pause |

### What cancel provides as HITL groundwork

1. ✅ **Safe-point locations identified and proven** — these exact locations are where HITL checks go
2. ✅ **`DiscussionControlToken` pattern** — HITL extends this with `PAUSE` signal
3. ✅ **`CANCELLED` state in persistence** — `PAUSED` follows the same pattern
4. ✅ **Partial transcript persistence** — cancel proves that saving mid-discussion works
5. ✅ **SSE event model** — cancel events prove the client can handle non-COMPLETED endings

### What HITL still requires (Phase 9b)

1. ❌ **Execution checkpoint serialization**: The phase loop's iteration state (current phase, current speaker, current repeat) must be persistable
2. ❌ **Resume from checkpoint**: `executeDiscussion()` needs a code path that starts from a saved state instead of phase 0
3. ❌ **Human approval webhook/polling**: REST endpoints + possibly WebSocket for real-time human interaction
4. ❌ **Timeout for human response**: What happens if the human never responds? Auto-cancel? Auto-approve?
5. ❌ **Thread management**: Can't hold threads for HITL; need event-driven resume

**Bottom line**: Cancel provides ~30% of the HITL infrastructure (detection, safe points, partial-state persistence). The remaining ~70% (checkpoint serialization, resume from checkpoint, human interaction model, thread management) is new work for Phase 9b. This is honest and by design — we build the foundation now, not a half-baked HITL.

---

## 7. Implementation Plan

### Phase A: Group Discussion Cancel (EDDI repo) — Core feature

| # | File | Change |
|---|------|--------|
| A1 | `DiscussionControlToken.java` [NEW] | Token class with `AtomicReference<ControlSignal>` + optional `activeFuture` for IMMEDIATE |
| A2 | `ControlSignal.java` [NEW] | Enum: `CONTINUE`, `CANCEL_GRACEFUL`, `CANCEL_IMMEDIATE` |
| A3 | `GroupConversation.java` | Add `CANCELLED` to `GroupConversationState` enum |
| A4 | `GroupConversationService.java` | Add `activeTokens` map; create/remove token in `executeDiscussion()`; check at 3 safe points; pass token to nested `discuss()` calls |
| A5 | `IGroupConversationService.java` | Add `cancelDiscussion(String convId, ControlSignal mode)` |
| A6 | `IRestGroupConversation.java` | Add `POST /{convId}/cancel` endpoint definition |
| A7 | `RestGroupConversation.java` | Implement cancel endpoint + SSE auto-cancel via `sendOrCancel()` wrapper |

### Phase B: Regular Conversation Cancel (EDDI repo) — Can be independent

| # | File | Change |
|---|------|--------|
| B1 | `IConversationMemory.java` | Add `setCancelled(boolean)` / `isCancelled()` |
| B2 | `ConversationMemory.java` | Implement with `volatile boolean` |
| B3 | `LifecycleManager.java` (impl) | Check `memory.isCancelled()` between task executions, throw `ConversationStopException` |
| B4 | `ConversationService.java` | Add `activeMemories` tracking map + `cancelConversation(String convId)` |
| B5 | REST endpoint | Add `POST /agents/{agentId}/conversations/{convId}/cancel` |

### Phase C: Frontend (EDDI-Manager repo)

| # | File | Change |
|---|------|--------|
| C1 | `groups.ts` | Add `cancelGroupDiscussion(groupId, convId, mode)` API function |
| C2 | `use-group-discussion-stream.ts` | Wire `abortStream()` to call cancel API before disconnecting SSE |
| C3 | `group-detail.tsx` | Re-introduce Stop button — now backed by real backend cancel |
| C4 | Chat components (Phase B) | Add stop button to regular conversation chat during streaming |

### Phase D: Tests

| # | Test |
|---|------|
| D1 | Unit: `DiscussionControlToken` signal concurrency |
| D2 | Unit: `GroupConversationService` — graceful cancel at phase boundary |
| D3 | Unit: `GroupConversationService` — cancel cascading to nested sub-group |
| D4 | Integration: SSE auto-cancel on client disconnect |
| D5 | Unit: Regular conversation cancel between lifecycle tasks (Phase B) |
| D6 | Unit: Idempotent cancel on already-completed conversation |

---

## 8. Open Questions

1. **Cancel response body**: Should `POST .../cancel` return the partial `GroupConversation` or just `200 OK`?
   - *Recommendation*: Return the saved `GroupConversation` — the client can display the partial transcript.

2. **Metrics**: Should cancelled discussions count as failures?
   - *Recommendation*: Separate counter `eddi_group_discussion_cancelled_count`.

3. **Nested group cancel persistence**: When a parent is cancelled, should the child sub-group also be persisted as `CANCELLED`?
   - *Recommendation*: Yes — the shared token ensures consistency.

4. **SSE disconnect = auto-cancel?** Should disconnecting the browser tab always trigger GRACEFUL cancel? Or should the discussion continue (current behavior)?
   - *Recommendation*: Auto-cancel. If no client is listening, continuing wastes tokens. If the user navigates back, they can start a new discussion.

---

## 9. Relationship to Other Plans

- **Agentic Improvements Phase 9b (HITL)**: Cancel provides safe-point infrastructure + partial-state persistence as groundwork. HITL adds checkpoint serialization, resume-from-checkpoint, and human interaction model. ~30% reuse, ~70% new work.
- **Multi-tenancy**: Cancel endpoints inherit auth/tenant context — no special handling.
- **Observability**: New metric `eddi_group_discussion_cancelled_count` + span for cancel events.
