# Human-in-the-Loop (HITL) Framework — Phase 9b (v6, Implementation-Ready)

> **Status:** Code-verified, implementation-ready. This is the build spec. Every API name, signature,
> field, exception type, and line reference below was verified against the EDDI source across five
> review iterations. Where a snippet reproduces an existing 13-arg constructor or a cancel-plan detail,
> **re-confirm the exact field order against the real class before compiling** — line numbers drift.

EDDI needs a **config-driven Human-in-the-Loop** capability: the pipeline pauses for human approval at
config-marked points, then resumes on an explicit decision. Applies to **both regular (1:1) conversations
and group discussions**. Built on top of the full conversation-cancel plan (Wave 0).

---

## 0. How To Read This Document

- **Waves** are implementation order. Each wave compiles and is independently testable.
- **"Mirror X"** means: copy the structure of the cited existing method exactly; do not invent a new pattern.
- **Invariants (§3)** are non-negotiable correctness rules distilled from five rounds of review. Re-read them
  before touching the group-discussion or persistence code — every prior failed draft violated one of them.
- Line numbers are anchors, not contracts. Locate by method/field name; the line is a hint.

---

## 1. Resolved Decisions

| Decision | Resolution |
|----------|-----------|
| Scope | Both surfaces (regular + group), one pass |
| Cancel | Full cancel plan is **Wave 0** (shared foundation) |
| Pause opt-in (regular) | **Presence of a `PAUSE_CONVERSATION` behavior-rule action** is the opt-in. No separate `enabled` flag (it would be dead config — `PAUSE_CONVERSATION` is a brand-new reserved action no existing config emits). Agent-level `HitlConfig` carries only timeout/policy, consulted at pause time. |
| Pause opt-in (group) | Existing `DiscussionPhase.requiresApproval` (already on the model). |
| Timeout default | `WAIT_INDEFINITELY`; designers opt into `AUTO_REJECT` / `AUTO_APPROVE` / `ABORT`. |
| Approval granularity | `PHASE` and `TASK` from the start (group). Regular conversations are turn-level only. |
| Who may approve | Conversation owner via `validateConversationOwnership()` / `requireOwnerOrAdmin`. |
| Group resume granularity | **Phase boundary only.** PHASE pauses fire after a phase's repeats fully complete and resume at `pausedPhaseIndex + 1`. TASK pauses fire at a wave-join boundary and resume at `pausedPhaseIndex` (re-enter same EXECUTE phase). No mid-repeat resume. |
| Group concurrency | **State-CAS** on the indexed `state` field via a new `storeIfFieldEquals` storage primitive. No POJO `version` field; `_version` stays pinned at `SINGLE_VERSION=1`. |
| Regular concurrency | `compareAndSetState` on `conversationState`, plus per-conversation `submitInOrder` (in-JVM ordering) — correctness comes from the DB CAS. |
| Tool-level HITL | Deferred. |
| Manager approvals UI | Deferred to the EDDI-Manager repo (separate PR). |

---

## 2. Architecture Overview

```
Regular conversation pause/resume (turn-level):
  behavior rule emits PAUSE_CONVERSATION
    └─ LifecycleManager: after a task, sees the action → throws ConversationPauseException(workflowId, absIndex, reason)
        └─ Conversation.executeConversationStep catches it → pauseConversation():
              setState(AWAITING_HUMAN); write bookmark to ConversationMemory (first-class fields)
              SKIP postConversationLifecycleTasks
        └─ Callable returns → ConversationService.processConversationStep saves memory (state=AWAITING_HUMAN)
              if a finite timeout policy → create one-shot HITL timeout ScheduleConfiguration
  POST /agents/{conversationId}/resume {verdict, note}
    └─ ConversationService.resumeConversation:
          compareAndSetState(id, AWAITING_HUMAN, IN_PROGRESS) → false ⇒ 409
          load agent from memory's agentId/version/environment; build Conversation; submitInOrder(resume callable)
          deleteSchedule(hitl timeout)
    └─ Conversation.resume: skip-before / resume-paused-workflow-from-absIndex+1 / run-after-from-0

Group discussion pause/resume (phase- or task-level):
  executeDiscussion(public) → increment metric + onGroupStart → runDiscussionLeg(..., startPhaseIndex=0)
  runDiscussionLeg → try { runPhaseLoop(...) } finally { timer; cleanup UNLESS state==AWAITING_APPROVAL }
  runPhaseLoop:
     PHASE pause:  after a phase's repeats complete, if phase.requiresApproval() → commitPause(PHASE) → return gc
     TASK pause:   executeTaskExecutionPhase, after allOf join, if taskList.hasAwaitingApproval() → commitPause(TASK) → return (void)
                   runPhaseLoop sees state==AWAITING_APPROVAL after the handler → return gc (before COMPLETED)
  POST /groups/{groupId}/conversations/{gcId}/approve  (or /approve/stream)
    └─ resumeDiscussion: read gc; apply task approvals; set IN_PROGRESS + clear pause fields;
          updateIfState(gc, AWAITING_APPROVAL)  ← state-CAS, ResourceModifiedException ⇒ 409
          runDiscussionLeg(..., startPhaseIndex = TASK ? pausedPhaseIndex : pausedPhaseIndex+1, turnCounter=savedTurnCount)
```

---

## 3. Correctness Invariants (DO NOT VIOLATE)

These are the rules that every prior failed draft broke. Treat them as acceptance criteria.

1. **Never persist a typed POJO/record as conversation step data.** `ConversationMemorySnapshot.ResultSnapshot.result`
   is `Object` and the Mongo/Postgres Jackson mappers have **no default typing** — a record round-trips as a
   `LinkedHashMap`, and any cast throws `ClassCastException`. The resume bookmark MUST be **first-class typed
   fields** on `ConversationMemorySnapshot` (regular) and `GroupConversation` (group).
2. **Resume task index is ABSOLUTE.** The component-cache key is `workflowId:version:absoluteIndex`. Iterating a
   re-based sublist from 0 makes `components.getOrDefault(key, null)` return null → config-driven tasks NPE.
   `executeLifecycleFromIndex` iterates the same `lifecycleTasks` list from `startIndex` with the absolute index.
3. **Group halt is `set state + (void) return` + a caller guard.** `executeTaskExecutionPhase`/`executeTaskPhase`
   return `void` — `return gc;` will not compile, and a bare `return;` falls through to `setState(COMPLETED)`.
   The pause must be detected in `runPhaseLoop` (which returns `GroupConversation`) **before** the COMPLETED
   transition, and that method must `return gc`.
4. **Per-task approval detection is a post-join scan of `gc.getTaskList()`,** not a future return value — the
   wave futures are `CompletableFuture<Void>` and carry nothing. The `submitForApproval` transition happens
   *inside* the runAsync body (replacing `completeTask`); the `hasAwaitingApproval()` check happens *after*
   `allOf().get()`.
5. **Group CAS is on the `state` field, never on `_version`.** `update(gc)` stays unconditional (the discussion
   loop is single-threaded). Only resume/cancel use `updateIfState(gc, expectedState)` → `storeIfFieldEquals`.
   `_version` stays `SINGLE_VERSION=1` so `read(id, version)` keeps working.
6. **Pause only at safe boundaries.** PHASE: after all repeats of the phase complete. TASK: after the wave's
   `allOf().get()` join. Never mid-repeat, never mid-wave (no partial-wave pause — `allOf` drains all siblings).
7. **Flush before returning on pause.** `conversationStore.update(gc)` (group) / memory save (regular) must run
   at the pause point so task statuses + transcript survive a pod restart.
8. **`findByState` uses an anchored regex** (`^STATE$`) — portable across Mongo (`$regex`) and Postgres (POSIX `~`).
9. **Skip `postConversationLifecycleTasks()` on the pause path** (it purges `scope==step` props and commits
   `longTerm` props mid-turn). Run it only on non-pause completion / resume completion / stop.
10. **Gate `say()` during `AWAITING_HUMAN`** and **never overwrite `AWAITING_HUMAN`** from the timeout handler.

---

## 4. Verified Codebase Seams (reference)

| Seam | Location | Note |
|------|----------|------|
| Step data is untyped `Object` | `ConversationMemorySnapshot.java:131` | No default typing (`PersistenceModule.java:64-67`, `SECURITY.md`) |
| Memory↔snapshot copy (3 sites) | `ConversationMemoryUtilities.java` `getMemorySnapshot()`~:55-70, `convertConversationMemorySnapshot()`~:107-142, `getSimpleMemorySnapshot()`~:197-213 | All manual; no reflection |
| Workflows loop | `Conversation.executeWorkflows()` :422-429 | Each workflow has its own `LifecycleManager`/task list |
| Step-execute catch | `Conversation.executeConversationStep()` ~:296-308 | Add pause catch + skip-post-tasks |
| `say()` gate | `Conversation.checkIfConversationInProgress()` :210-214 | Only blocks `IN_PROGRESS` today |
| Lifecycle loop / component key | `LifecycleManager` loop ~:214, key build ~:259-261, stop-check ~:467-478, config resolve precedent ~:210-211 | `ACTIONS` = `MemoryKeys.java:58` |
| `ILifecycleTask` exec method | `ILifecycleTask.java:179` | `execute(IConversationMemory, Object)` — **not** `executeTask` |
| ConversationState enum | `ConversationState.java:10-11` | `READY, IN_PROGRESS, ENDED, EXECUTION_INTERRUPTED, ERROR` |
| Active-count query | `ConversationMemoryStore.java:112` (`$ne ENDED`), `:146-151`; Postgres `:205-213` | Quota gate |
| Conversation-memory raw CAS seam | `ConversationMemoryStore.setConversationState()` :122-126 (raw `MongoCollection`, `KEY_CONVERSATION_STATE` :48); Postgres `:162-173` | Parallel-impl pattern — raw driver OK here |
| Timeout handler | `ConversationService.waitForExecutionFinishOrTimeout()` :724-736 | Writes `EXECUTION_INTERRUPTED` on timeout |
| Coordinator | `submitInOrder` (`InMemoryConversationCoordinator`); NATS gives in-JVM order only (`NatsConversationCoordinator.java:275` local fallback) | Correctness = DB CAS |
| Storage CAS primitive | `IResourceStorage.storeIfCurrentVersion` :58-61; Mongo override `MongoResourceStorage.java:97-111`; Postgres `PostgresResourceStorage.java:122-144` | Template for `storeIfFieldEquals` |
| Resource regex filter | Mongo `MongoResourceStorage.java:272-273` (`$regex`); Postgres `:335-338` (POSIX `~`) | Anchored regex portable |
| Postgres ignores index varargs | `PostgresResourceStorageFactory.java:50`, `initSchema` ~:78-86 | `findByState`/`listByGroupId` are seq scans on PG |
| Group store | `GroupConversationStore.java` `storage` field :36, `SINGLE_VERSION=1` :34, `update()` :71-79, `create(...,"groupId","state")` :40 | Single store over `IResourceStorage` — **no raw Mongo** |
| Group enum + task enum | `GroupConversation.GroupConversationState` :127-130 (`AWAITING_APPROVAL` exists); `SharedTaskList.TaskStatus` :89-95 (`AWAITING_APPROVAL`, `BLOCKED` exist) | Placeholders, currently inert |
| `executeDiscussion` | `GroupConversationService.java:227` `(gc, config, phases, question, listener)`; metric `:232`; `onGroupStart` :246-250; phase loop :254; repeat loop :257; `update` :302; `onPhaseComplete` :304-306; synthesis/COMPLETED/onGroupComplete :315-326; finally/timer/cleanup :342-349 | turnCounter created internally :244 |
| Phase dispatch | `:291-299`; EXECUTE → `executeTaskPhase` → `executeTaskExecutionPhase` (`:485`, `:662`, both `void`) | |
| EXECUTE internals | empty-list guard :667-670; wave loop :683; `findExecutableTasks` :684; `runAsync` :712; `completeTask` :734; `allOf().get()` ~:765-766 | |
| Original question | `GroupConversation.originalQuestion` :28, getter ~:167; set `createGroupConversation` :1625; also transcript[0] (`QUESTION`) :1631 | Recover on resume via `getOriginalQuestion()` |
| Phases resolve | `GroupConversationService.resolvePhases(config)` :428-431 | Deterministic from config |
| SharedTaskList | `completeTask` :228-239; `findExecutableTasks` :103-108; `all()` :143; private `requireTask`/`replaceTask` ~:288/:325; `failTask` template :262-277; `TaskItem` 13-arg record :57-70 | |
| Schedule infra | `ScheduleFireExecutor.fire()` ~:61-95 (only `say()` + latch); `ScheduleConfiguration` `oneTimeAt` :60, `persistentConversationId` :67, `metadata` :86; `IScheduleStore.createSchedule` :26, `deleteSchedule` :47; `tryClaim` (lease) | |
| Audit | `AuditEntry` 20-component record :74-77 (positional, `with*` copy-withers :83-104); `AuditLedgerService.submit()` :150 (async/void) | No builder |
| Auth | `RestAgentEngine.validateConversationOwnership()` :256-270; `OwnershipValidator.requireOwnerOrAdmin` (no-ops on null/blank owner ~:145) | |
| REST shapes | `IRestAgentEngine` `@Path("/agents")`, `say()` has no `environment` param :132-141; `IRestGroupConversation` `{groupId}`/`{groupConversationId}`, `discuss`/`discussStreaming` SSE | |
| Event sinks | `ConversationEventSink` = **interface** w/ default methods (safe to extend); `GroupConversationEventSink` = **final class** w/ constants + record payloads (add constants+records, not interface methods) | |
| Config classes | `AgentConfiguration` POJO, `SessionManagement` nested `public static class` ~:784; `AgentGroupConfiguration` POJO, `phases` :26/:241, `DiscussionPhase.requiresApproval` :96 | Add `HitlConfig` as nested static class |
| Config validation seam | `AgentStore.create/update` `checkCollectionNoNullElements(...)` ~:46/:54 | No `configure()` on these POJOs |

---

## 5. Wave 0 — Shared Storage Primitive + Cancel + Lifecycle Prerequisites

### 5.1 `storeIfFieldEquals` on `IResourceStorage` (the group state-CAS foundation)

**[MODIFY] `datastore/IResourceStorage.java`** — add a default method beside `storeIfCurrentVersion`:

```java
/**
 * Store a new version of a resource only if the JSON field {@code fieldName} currently equals
 * {@code expectedValue}. Atomic compare-and-swap on an arbitrary indexed field (not _version).
 * Default impl performs an unconditional store (NO locking) — backends override.
 */
default void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
        throws IResourceStore.ResourceModifiedException {
    store(newResource);
}
```

**[MODIFY] `datastore/mongo/MongoResourceStorage.java`** — override, mirroring `storeIfCurrentVersion` (:97-111),
swapping the `VERSION_FIELD` predicate for the field predicate:

```java
@Override
public void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
        throws IResourceStore.ResourceModifiedException {
    Resource resource = checkInternalResource(newResource);
    var result = currentCollection.updateOne(
            Filters.and(
                    Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                    Filters.eq(fieldName, expectedValue)),
            new Document("$set", resource.getMongoDocument()));
    if (result.getMatchedCount() == 0) {
        throw new IResourceStore.ResourceModifiedException(
                String.format("Resource field '%s' was not '%s' (id=%s)", fieldName, expectedValue, resource.getId()));
    }
}
```

**[MODIFY] `datastore/postgres/PostgresResourceStorage.java`** — override, mirroring its `storeIfCurrentVersion`
(:122-144), swapping the `version = ?` predicate for `data->>? = ?`:

```java
@Override
public void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
        throws IResourceStore.ResourceModifiedException {
    Resource pgResource = checkInternalResource(newResource);
    String sql = """
            UPDATE resources SET version = ?, data = ?::jsonb
            WHERE id = ?::uuid AND collection_name = ? AND data->>? = ?
            """;
    try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, pgResource.getVersion());     // stays SINGLE_VERSION
        ps.setString(2, pgResource.getJson());
        ps.setString(3, pgResource.getId());
        ps.setString(4, collectionName);
        ps.setString(5, fieldName);
        ps.setString(6, expectedValue);
        if (ps.executeUpdate() == 0) {
            throw new IResourceStore.ResourceModifiedException(
                    String.format("Resource field '%s' was not '%s' (id=%s)", fieldName, expectedValue, pgResource.getId()));
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to store resource with field check", e);
    }
}
```

> **Why this is correct (verified):** `_version` is never advanced, so `read(id, SINGLE_VERSION)` (Mongo
> `:120-122` / Postgres `:161-163`) keeps matching. The CAS is on the indexed `state` field. The default
> method makes every other `IResourceStorage` user unaffected.

### 5.2 Cancel infrastructure (the cancel plan) + lifecycle types

Implement `planning/conversation-cancel-plan.md` in full, with `PAUSE` added to `ControlSignal` now.

| File | Action | Notes |
|------|--------|-------|
| `engine/lifecycle/model/ControlSignal.java` | NEW | `enum { CONTINUE, CANCEL_GRACEFUL, CANCEL_IMMEDIATE, PAUSE }` |
| `engine/lifecycle/model/DiscussionControlToken.java` | NEW | `AtomicReference<ControlSignal>`; `isCancelled/isPaused/shouldStop`. **In-flight scope only** — created at exec start, removed in `finally`. Does NOT survive a pause gap (cancel-of-paused uses persisted state, §7.x). |
| `engine/lifecycle/exceptions/ConversationPauseException.java` | NEW | **checked** `extends Exception`, mirroring `ConversationStopException`. Fields: `String pausedWorkflowId`, `int pausedAbsoluteTaskIndex`, `String pauseReason` + getters. |
| `engine/lifecycle/ILifecycleManager.java` | MODIFY | Add `throws ConversationPauseException` to `executeLifecycle(...)`; add `executeLifecycleFromIndex(IConversationMemory, int) throws LifecycleException, ConversationStopException, ConversationPauseException`. |
| `engine/lifecycle/internal/LifecycleManager.java` | MODIFY | §5.3 |
| `configs/groups/model/GroupConversation.java` | MODIFY | Add `CANCELLED` to `GroupConversationState`. **No `version` field** (state-CAS handles the race). |
| `engine/internal/GroupConversationService.java` | MODIFY | Cancel: `ConcurrentMap<String,DiscussionControlToken> activeTokens`; token created in `startAndDiscussAsync`, removed in `runDiscussionLeg` finally; 3 safe-point `shouldStop()` checks; `cancelDiscussion(id, mode)`; nested-group cascade; SSE auto-cancel wrapper. |
| `engine/api/IGroupConversationService.java` | MODIFY | `+ void cancelDiscussion(String, ControlSignal)` |
| `configs/groups/IGroupConversationStore.java` | MODIFY | `+ void updateIfState(GroupConversation, GroupConversationState expectedState) throws ResourceStoreException, ResourceModifiedException`; `+ List<GroupConversation> findByState(GroupConversationState)` |
| `configs/groups/mongo/GroupConversationStore.java` | MODIFY | §5.4 |
| `engine/api/IRestGroupConversation.java` + `engine/internal/RestGroupConversation.java` | MODIFY | `POST /{groupConversationId}/cancel` + `sendOrCancel` SSE wrapper |
| `engine/lifecycle/GroupConversationEventSink.java` | MODIFY | `+ String EVENT_CANCELLED`; `+ record CancelledEvent(String reason, String cancelledBy)` (final class — add constants/records) |
| `engine/memory/IConversationMemory.java` + `ConversationMemory.java` | MODIFY | `+ setCancelled(boolean)/isCancelled()` with `volatile boolean` |
| `engine/internal/ConversationService.java` | MODIFY | `activeMemories` registry + `cancelConversation(id, mode)`; cancel-of-paused writes state directly via `compareAndSetState` |
| `engine/api/IRestAgentEngine.java` + `engine/internal/RestAgentEngine.java` | MODIFY | `POST /{conversationId}/cancel` |

### 5.3 `LifecycleManager` — pause detection + index resume

1. **Extract the per-task body.** The existing main loop (~:214) does: null `TaskId` guard, interruption check,
   write-discipline snapshot, OpenTelemetry span, component lookup by **absolute index**, `task.execute(memory, component)`,
   `checkIfStopConversationAction(memory)`. **Refactor that body into a private helper** so both loops share it:

```java
private void runTaskAt(IConversationMemory memory, int absoluteIndex)
        throws LifecycleException, ConversationStopException, ConversationPauseException {
    if (memory.isCancelled()) throw new ConversationStopException();          // cancel (Wave 0)
    ILifecycleTask task = lifecycleTasks.get(absoluteIndex);
    // ... existing: null-id guard, interruption check, write-discipline snapshot, span ...
    var components  = componentCache.getComponentMap(task.getId().name());
    var componentKey = createComponentKey(workflowId.getId(), workflowId.getVersion(), absoluteIndex); // ABSOLUTE
    var component    = components.getOrDefault(componentKey, null);
    task.execute(memory, component);                                          // ILifecycleTask.execute (:179)
    checkIfStopConversationAction(memory);                                    // existing
    checkIfPauseConversationAction(memory, absoluteIndex);                    // NEW
}
```

2. **`checkIfPauseConversationAction`** — placed beside `checkIfStopConversationAction` (~:467-478):

```java
private void checkIfPauseConversationAction(IConversationMemory memory, int absoluteTaskIndex)
        throws ConversationPauseException {
    IData<List<String>> actionData = memory.getCurrentStep().getLatestData(ACTIONS); // MemoryKeys:58
    if (actionData == null) return;
    List<String> actions = actionData.getResult();
    if (actions != null && actions.contains(IConversation.PAUSE_CONVERSATION)) {
        throw new ConversationPauseException(workflowId.getId(), absoluteTaskIndex, "PAUSE_CONVERSATION action");
    }
}
```

> No `enabled` gate here — opt-in is the presence of the behavior-rule action. (See §1.)

3. **`executeLifecycleFromIndex`**:

```java
@Override
public void executeLifecycleFromIndex(IConversationMemory memory, int startFromAbsoluteIndex)
        throws LifecycleException, ConversationStopException, ConversationPauseException {
    for (int i = startFromAbsoluteIndex; i < lifecycleTasks.size(); i++) {
        runTaskAt(memory, i);
    }
}
```

4. Make the existing main loop call `runTaskAt(memory, i)` too, so behavior is identical.

### 5.4 `GroupConversationStore` — `updateIfState` + `findByState`

```java
public void updateIfState(GroupConversation gc, GroupConversationState expectedState)
        throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException {
    try {
        IResourceStorage.IResource<GroupConversation> resource = storage.newResource(gc.getId(), SINGLE_VERSION, gc);
        storage.storeIfFieldEquals(resource, "state", expectedState.name());   // CAS on state, NOT _version
    } catch (IOException e) {
        throw new IResourceStore.ResourceStoreException("Failed conditional update: " + e.getMessage(), e);
    }
}

public List<GroupConversation> findByState(GroupConversationState state)
        throws IResourceStore.ResourceStoreException {
    var filters = new IResourceFilter.QueryFilters[]{
        new IResourceFilter.QueryFilters(List.of(
            new IResourceFilter.QueryFilter("state", "^" + state.name() + "$")))};  // anchored regex — portable
    List<IResourceStore.IResourceId> ids = storage.findResources(filters, "lastModified", 0, 1000);
    List<GroupConversation> out = new ArrayList<>();
    for (var id : ids) { GroupConversation gc = read(id.getId()); if (gc != null) out.add(gc); }
    return out;
}
```

> `update(gc)` (:71-79) stays **unconditional** — used by the single-threaded discussion loop. Only resume/cancel
> use `updateIfState`. No new index needed (state index exists at :40 on Mongo; on Postgres this is a seq scan —
> acceptable, see §11 M-5 follow-up).

---

## 6. Wave 1 — Core HITL State Machine (Regular Conversations)

### 6.1 New value & types

- **[MODIFY] `engine/memory/model/ConversationState.java`** → add `AWAITING_HUMAN`.
- **[NEW] `engine/lifecycle/model/HitlTimeoutPolicy.java`** → `enum { AUTO_REJECT, AUTO_APPROVE, ABORT, WAIT_INDEFINITELY }`.
- **[NEW] `engine/lifecycle/model/HitlDecision.java`** — getter/setter class (Jackson-deserialized from REST body):

```java
public class HitlDecision {
    public enum HitlVerdict { APPROVED, REJECTED }
    private HitlVerdict verdict;
    private String note;
    private String decidedBy;   // userId, set server-side from SecurityIdentity (not trusted from body)
    // getters + setters
}
```

### 6.2 Resume bookmark — first-class fields (Invariant 1)

**[MODIFY] `engine/memory/model/ConversationMemorySnapshot.java`** — add fields + getters/setters:

```java
private String  hitlPausedWorkflowId;
private int     hitlPausedAbsoluteTaskIndex = -1;
private Instant hitlPausedAt;            // authoritative "is paused" check: null == not paused
private String  hitlPauseReason;
private String  hitlTimeoutPolicy;       // HitlTimeoutPolicy name, nullable
private String  hitlApprovalTimeout;     // ISO-8601 duration, null == indefinite
```

**[MODIFY] `engine/memory/IConversationMemory.java` + `ConversationMemory.java`** — matching getters/setters
(plain mutable scalars; not final).

**[MODIFY] `engine/memory/ConversationMemoryUtilities.java`** — copy in **all three** converters:
- `getMemorySnapshot()` (~:55-70, after `setConversationState`): copy all 6 fields memory→snapshot.
- `convertConversationMemorySnapshot()` (~:107-142, after `setConversationState`): copy all 6 fields snapshot→memory.
- `getSimpleMemorySnapshot()` (~:197-213): copy **`hitlPausedAt` only** (for the Manager "paused" badge) — add the
  field to `SimpleConversationMemorySnapshot` too.

> Invariant: a passing *group* persistence test does NOT prove the regular path — the converter is the regular
> path's only bridge. Test it independently (`HitlBookmarkRoundTripTest`).

### 6.3 `IConversation` + `Conversation`

**[MODIFY] `engine/lifecycle/IConversation.java`**: `+ String PAUSE_CONVERSATION = "PAUSE_CONVERSATION";` and
`void resume(HitlDecision decision, Map<String, Context> contexts) throws LifecycleException, ConversationNotReadyException;`

**[MODIFY] `engine/runtime/internal/Conversation.java`:**

1. `executeConversationStep()` — catch pause, skip post-tasks (Invariant 9):

```java
private void executeConversationStep(List<IData<?>> lifecycleData, List<String> lifecycleTaskTypes)
        throws LifecycleException {
    boolean paused = false;
    try {
        executeWorkflows(lifecycleData, lifecycleTaskTypes);   // now also throws ConversationPauseException
    } catch (ConversationStopException unused) {
        endConversation();
    } catch (ConversationPauseException e) {
        pauseConversation(e);
        paused = true;
    }
    if (!paused) {
        try { postConversationLifecycleTasks(); }
        catch (IResourceStore.ResourceStoreException e) { throw new LifecycleException(e.getLocalizedMessage(), e); }
    }
}
```

2. `pauseConversation()`:

```java
private void pauseConversation(ConversationPauseException e) {
    setConversationState(ConversationState.AWAITING_HUMAN);
    conversationMemory.setHitlPausedWorkflowId(e.getPausedWorkflowId());
    conversationMemory.setHitlPausedAbsoluteTaskIndex(e.getPausedAbsoluteTaskIndex());
    conversationMemory.setHitlPausedAt(Instant.now());
    conversationMemory.setHitlPauseReason(e.getPauseReason());
    // hitlTimeoutPolicy/hitlApprovalTimeout are written by ConversationService post-pause (it has the agent config)
}
```

3. `checkIfConversationInProgress()` — add (Invariant 10):

```java
if (getConversationState() == ConversationState.AWAITING_HUMAN) {
    throw new ConversationNotReadyException("Conversation is AWAITING_HUMAN approval. Use the /resume endpoint.");
}
```

4. `executeWorkflows()` — add `throws ConversationPauseException`.

5. `resume()` — skip-before / resume-paused / run-after (Invariant 2):

```java
@Override
public void resume(HitlDecision decision, Map<String, Context> contexts)
        throws LifecycleException, ConversationNotReadyException {
    if (getConversationState() != ConversationState.AWAITING_HUMAN) {
        throw new ConversationNotReadyException("Not in AWAITING_HUMAN state");
    }
    try {
        setConversationState(ConversationState.IN_PROGRESS);
        // Decision recorded as STRING step data (round-trips safely)
        conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_verdict", decision.getVerdict().name()));
        if (decision.getNote() != null)
            conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_note", decision.getNote()));
        if (decision.getDecidedBy() != null)
            conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_by", decision.getDecidedBy()));

        if (decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
            clearHitlBookmark();
            return;   // do not resume pipeline; finally transitions to READY
        }

        String pausedWorkflowId = conversationMemory.getHitlPausedWorkflowId();
        int resumeFromIndex = conversationMemory.getHitlPausedAbsoluteTaskIndex() + 1;
        clearHitlBookmark();

        boolean foundPaused = false;
        for (IExecutableWorkflow workflow : executableWorkflows) {
            String wfId = workflow.getWorkflowId();
            conversationMemory.getCurrentStep().setCurrentWorkflowId(wfId);
            if (!foundPaused) {
                if (wfId.equals(pausedWorkflowId)) {                 // resume the paused workflow from index+1
                    foundPaused = true;
                    workflow.getLifecycleManager().executeLifecycleFromIndex(conversationMemory, resumeFromIndex);
                }
                // else: skip workflows ordered before the paused one (already executed)
            } else {                                                 // run later workflows in full
                workflow.getLifecycleManager().executeLifecycle(conversationMemory, null);
            }
        }
    } catch (ConversationStopException unused) {
        endConversation();
    } catch (ConversationPauseException e) {
        pauseConversation(e);                                        // a second pause in the same turn
    } catch (Exception e) {
        setConversationState(ConversationState.ERROR);
        throw new LifecycleException(e.getLocalizedMessage(), e);
    } finally {
        checkActionsForConversationEnd();
        ConversationState finalState = getConversationState();
        if (finalState == ConversationState.IN_PROGRESS) setConversationState(ConversationState.READY);
        if (finalState != ConversationState.AWAITING_HUMAN) {       // skip post-tasks if it re-paused
            try { postConversationLifecycleTasks(); }
            catch (IResourceStore.ResourceStoreException ex) { LOGGER.error("post-conversation tasks on resume failed", ex); }
        }
        if (outputProvider != null) outputProvider.renderOutput(conversationMemory);
    }
}

private void clearHitlBookmark() {
    conversationMemory.setHitlPausedWorkflowId(null);
    conversationMemory.setHitlPausedAbsoluteTaskIndex(-1);
    conversationMemory.setHitlPausedAt(null);
    conversationMemory.setHitlPauseReason(null);
    conversationMemory.setHitlTimeoutPolicy(null);
    conversationMemory.setHitlApprovalTimeout(null);
}
```

### 6.4 `ConversationService` — timeout guard + store CAS

1. **Guard the timeout handler** (Invariant 10) at `waitForExecutionFinishOrTimeout()` :724-736:

```java
} catch (TimeoutException | InterruptedException e) {
    if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
        return; // pause already committed — do not overwrite with EXECUTION_INTERRUPTED
    }
    setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
    // ...
}
```

2. Store an output marker so the UI does not render an empty bubble: when a turn ends in `AWAITING_HUMAN`,
   add a `hitl:status = "awaiting_approval"` public output item to the step.

**[MODIFY] `engine/memory/IConversationMemoryStore.java` (+ Mongo + Postgres impls — three files):**

```java
List<String> findConversationIdsByState(ConversationState state) throws ResourceStoreException;
boolean compareAndSetState(String conversationId, ConversationState expected, ConversationState next)
        throws ResourceStoreException;
```

Mongo `compareAndSetState` (raw driver is correct here — parallel-impl store):

```java
var result = conversationCollectionDocument.updateOne(
        Filters.and(Filters.eq(OBJECT_ID, new ObjectId(conversationId)),
                    Filters.eq(KEY_CONVERSATION_STATE, expected.name())),
        Updates.set(KEY_CONVERSATION_STATE, next.name()));
return result.getMatchedCount() == 1;
```

Postgres `compareAndSetState`: `UPDATE conversation_memories SET conversation_state = ? WHERE id = ?::uuid
AND conversation_state = ?` → `return executeUpdate() == 1;`. `findConversationIdsByState`: mirror
`getEndedConversationIds` swapping the predicate. Add a Mongo index `{ conversationState: 1 }` on
`conversationmemories` (Postgres `conversation_state` is already a real column).

**[MODIFY] `engine/lifecycle/GroupConversationEventSink.java`** (final class):

```java
public static final String EVENT_AWAITING_APPROVAL = "awaiting_approval";
public static final String EVENT_HITL_RESUME      = "hitl_resume";
public record HitlPauseEvent(int phaseIndex, String phaseName, String reason, String granularity) {}
public record HitlResumeEvent(String verdict, String note, String decidedBy) {}
```

---

## 7. Wave 2 — REST API & Resume Flow (Regular)

**[MODIFY] `engine/api/IRestAgentEngine.java`** (base `@Path("/agents")`; conversationId-scoped endpoints carry
**no** `environment` param, matching `say()` :132-141):

```java
@POST @Path("/{conversationId}/resume") @Consumes(MediaType.APPLICATION_JSON)
Response resumeConversation(@PathParam("conversationId") String conversationId, HitlDecision decision);

@GET @Path("/{conversationId}/approval-status") @Produces(MediaType.APPLICATION_JSON)
Response getApprovalStatus(@PathParam("conversationId") String conversationId,
                           @QueryParam("detail") @DefaultValue("summary") String detail);

@GET @Path("/pending-approvals") @Produces(MediaType.APPLICATION_JSON)
List<PendingApprovalSummary> listPendingApprovals();   // backed by findConversationIdsByState(AWAITING_HUMAN)
```

`PendingApprovalSummary` = `(conversationId, agentId, pausedAt, pauseReason, timeoutPolicy)`.

**[MODIFY] `engine/internal/RestAgentEngine.java`:**
- `resumeConversation`: `validateConversationOwnership(conversationId)` (:256-270, fail-closed) → set
  `decision.setDecidedBy(resolvedUserId)` server-side → `conversationService.resumeConversation(...)`.
- `getApprovalStatus`: owner-checked; `summary` = last step output + properties + bookmark; `detail=full` = whole snapshot.

**[MODIFY] `engine/api/IConversationService.java` + `ConversationService.java`:**

```java
void resumeConversation(String conversationId, HitlDecision decision, ConversationResponseHandler handler)
        throws ResourceStoreException, ResourceNotFoundException;
```

Implementation:
1. `if (!memoryStore.compareAndSetState(conversationId, AWAITING_HUMAN, IN_PROGRESS)) → 409` (Invariant: DB CAS is the real lock).
2. Load memory; derive agent from memory's `agentId/agentVersion/environment` (same as `say()`); `agent.continueConversation(memory, propertiesHandler, outputRenderer)`.
3. `Callable` wrapping `conversation.resume(decision, contexts)`; wrap with `processConversationStep()` (save-on-complete).
4. `conversationCoordinator.submitInOrder(conversationId, callable)`.
5. On success, `scheduleStore.deleteSchedule(hitlTimeoutScheduleId(conversationId))` if present.

---

## 8. Wave 3 — Group Discussion HITL

### 8.1 `GroupConversation` — HITL fields

```java
public enum HitlPauseType { PHASE, TASK }

private int           pausedAtPhaseIndex = -1;
private int           pausedTurnCount    = 0;
private String        pausedPhaseName;
private Instant       pausedAt;            // null == not paused (authoritative)
private HitlPauseType hitlPauseType;       // resume index: TASK → same phase, PHASE → +1
// getters/setters

@JsonIgnore   // NOT @BsonIgnore — serialization is Jackson via IDocumentBuilder
public boolean isPaused() { return pausedAt != null; }
```

### 8.2 `SharedTaskList` — transitions (all inside the class; use private `requireTask`/`replaceTask`)

Add `synchronized` methods mirroring `completeTask` (:228-239) and `failTask` (:262-277). **Confirm the 13-field
`TaskItem` constructor order against the record (:57-70) before compiling.**

```java
/** IN_PROGRESS → AWAITING_APPROVAL, preserving the agent's answer in result. */
public synchronized TaskItem submitForApproval(String taskId, String result) {
    TaskItem t = requireTask(taskId);
    if (t.status() != TaskStatus.IN_PROGRESS)
        throw new IllegalStateException("submitForApproval '%s': expected IN_PROGRESS but was %s".formatted(taskId, t.status()));
    TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.AWAITING_APPROVAL,
            t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), result,
            t.verificationNote(), t.verified(), t.priority(), t.createdAt(), t.completedAt());
    replaceTask(taskId, u); return u;
}

/** AWAITING_APPROVAL → COMPLETED (result already stored by submitForApproval). */
public synchronized TaskItem approveTask(String taskId) {
    TaskItem t = requireTask(taskId);
    if (t.status() != TaskStatus.AWAITING_APPROVAL)
        throw new IllegalStateException("approveTask '%s': expected AWAITING_APPROVAL but was %s".formatted(taskId, t.status()));
    TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.COMPLETED,
            t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
            t.verificationNote(), t.verified(), t.priority(), t.createdAt(), Instant.now());
    replaceTask(taskId, u); return u;
}

/** AWAITING_APPROVAL → FAILED, preserving result; rejection note in verificationNote (failTask pattern). */
public synchronized TaskItem rejectTask(String taskId, String rejectionNote) {
    TaskItem t = requireTask(taskId);
    if (t.status() != TaskStatus.AWAITING_APPROVAL)
        throw new IllegalStateException("rejectTask '%s': expected AWAITING_APPROVAL but was %s".formatted(taskId, t.status()));
    TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.FAILED,
            t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
            rejectionNote, false, t.priority(), t.createdAt(), Instant.now());
    replaceTask(taskId, u); return u;
}

/** IN_PROGRESS → ASSIGNED (un-strand a started-but-unfinished task before a phase-level pause). */
public synchronized TaskItem resetToAssigned(String taskId) {
    TaskItem t = requireTask(taskId);
    if (t.status() != TaskStatus.IN_PROGRESS)
        throw new IllegalStateException("resetToAssigned '%s': expected IN_PROGRESS but was %s".formatted(taskId, t.status()));
    TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.ASSIGNED,
            t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
            t.verificationNote(), t.verified(), t.priority(), t.createdAt(), t.completedAt());
    replaceTask(taskId, u); return u;
}

/** Post-join detection query for the per-task gate (Invariant 4). */
public synchronized boolean hasAwaitingApproval() {
    return all().stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_APPROVAL);
}
```

### 8.3 `GroupConversationService` — refactor + gates

**(A) Extract `runPhaseLoop` + `runDiscussionLeg` (M-2 + the resume overload).** `executeDiscussion` currently
does start-side-effects then the phase loop then COMPLETED. Split it:

```java
// PUBLIC entry (unchanged signature :227) — fresh start
private GroupConversation executeDiscussion(GroupConversation gc, AgentGroupConfiguration config,
        List<DiscussionPhase> phases, String question, GroupDiscussionEventListener listener)
        throws GroupDiscussionException, IResourceStore.ResourceStoreException {
    counterGroupDiscussion.increment();                                   // start-only (:232)
    gc.setDynamicAgentConfig(config.getDynamicAgents());
    if (listener != null) listener.onGroupStart(/* :246-250 */);          // start-only
    return runDiscussionLeg(gc, config, phases, question, listener, new AtomicInteger(0), 0);
}

// Shared try/finally wrapper (cleanup guard — Invariant: skip cleanup when paused)
private GroupConversation runDiscussionLeg(GroupConversation gc, AgentGroupConfiguration config,
        List<DiscussionPhase> phases, String question, GroupDiscussionEventListener listener,
        AtomicInteger turnCounter, int startPhaseIndex)
        throws GroupDiscussionException, IResourceStore.ResourceStoreException {
    long startTime = System.nanoTime();
    try {
        return runPhaseLoop(gc, config, phases, question, listener, turnCounter, startPhaseIndex);
    } catch (GroupDiscussionException e) {
        failConversation(gc);
        if (listener != null) listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
        throw e;
    } finally {
        timerGroupDiscussion.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        if (gc.getState() != GroupConversationState.AWAITING_APPROVAL) {   // B7: keep agents/cursor on pause
            lastVerifiedIndex.remove(gc.getId());
            cleanupEphemeralAgents(gc, config);
        }
    }
}

// The phase loop + synthesis/COMPLETED (was :254-326), now re-enterable + pause-aware
private GroupConversation runPhaseLoop(GroupConversation gc, AgentGroupConfiguration config,
        List<DiscussionPhase> phases, String question, GroupDiscussionEventListener listener,
        AtomicInteger turnCounter, int startPhaseIndex) throws GroupDiscussionException, ... {
    int maxTurns = resolveMaxTurns(config);
    for (int phaseIdx = startPhaseIndex; phaseIdx < phases.size(); phaseIdx++) {
        DiscussionPhase phase = phases.get(phaseIdx);
        for (int repeat = 0; repeat < Math.max(phase.repeats(), 1); repeat++) {
            if (turnCounter.get() >= maxTurns) { /* existing skip + break */ }
            gc.setCurrentPhaseIndex(phaseIdx); gc.setCurrentPhaseName(phase.name());
            if (listener != null) listener.onPhaseStart(/* :276-279 */);
            if (phase.type() == PhaseType.SYNTHESIS) { gc.setState(SYNTHESIZING); /* onSynthesisStart */ }
            List<GroupMember> speakers = resolveParticipants(phase, config.getMembers(), config.getModeratorAgentId());

            dispatchPhase(gc, config, speakers, phase, /*protocol*/, question, phaseIdx, listener, turnCounter, maxTurns);

            // TASK pause was committed inside executeTaskExecutionPhase → short-circuit BEFORE COMPLETED (Invariant 3)
            if (gc.getState() == GroupConversationState.AWAITING_APPROVAL) return gc;

            gc.setLastModified(Instant.now()); conversationStore.update(gc);      // :302 unconditional flush
            if (listener != null) listener.onPhaseComplete(/* :304-306 */);
        }
        if (turnCounter.get() >= maxTurns) break;

        // PHASE pause fires only AFTER all repeats complete (M-1)
        if (phase.requiresApproval()) { commitPause(gc, phaseIdx, phase.name(), HitlPauseType.PHASE, turnCounter, listener);
                                        return gc; }
    }
    // reached only when never paused:
    gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null)
            .reduce((a, b) -> b).ifPresent(e -> gc.setSynthesizedAnswer(e.content()));
    gc.setState(GroupConversationState.COMPLETED); gc.setLastModified(Instant.now()); conversationStore.update(gc);
    if (listener != null) listener.onGroupComplete(/* :324-325 */);
    return gc;
}
```

**(B) `commitPause` helper** (single source of pause-commit — Invariant 7):

```java
private void commitPause(GroupConversation gc, int phaseIdx, String phaseName, HitlPauseType type,
                         AtomicInteger turnCounter, GroupDiscussionEventListener listener)
        throws IResourceStore.ResourceStoreException {
    gc.setState(GroupConversationState.AWAITING_APPROVAL);
    gc.setPausedAtPhaseIndex(phaseIdx);
    gc.setPausedPhaseName(phaseName);
    gc.setHitlPauseType(type);
    gc.setPausedTurnCount(turnCounter.get());
    gc.setPausedAt(Instant.now());
    gc.setLastModified(Instant.now());
    conversationStore.update(gc);                       // flush task statuses + transcript (Invariant 7)
    if (listener != null) listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(
            phaseIdx, phaseName, type == HitlPauseType.TASK ? "task_approval" : "phase_approval", type.name()));
}
```

**(C) Per-task gate inside `executeTaskExecutionPhase`** (Invariants 3, 4, 6):
- Replace the unconditional `completeTask(task.id(), entry.content())` at :734:
  ```java
  if (perTaskApproval) gc.getTaskList().submitForApproval(task.id(), entry.content());
  else                 gc.getTaskList().completeTask(task.id(), entry.content());
  ```
  where `perTaskApproval = phase.requiresApproval() && granularity == HitlGranularity.TASK` (granularity plumbed
  via a new method param resolved from `config.getHitlConfig()`).
- **After** the wave's `allOf().get()` join (~:765-766) and after the error checks, add the gate (guard against a
  timeout/exception path with a `timedOut` flag set in the catch):
  ```java
  if (!timedOut && gc.getTaskList().hasAwaitingApproval()) {
      commitPause(gc, phaseIdx, phase.name(), HitlPauseType.TASK, turnCounter, listener);
      return;   // void — runPhaseLoop sees AWAITING_APPROVAL after dispatch and returns gc (Invariant 3)
  }
  ```
- **Phase-level pause in EXECUTE** (a `requiresApproval` EXECUTE phase, not per-task): before `commitPause(PHASE)`,
  un-strand any started-but-unfinished tasks: `all().stream().filter(IN_PROGRESS).forEach(t -> resetToAssigned(t.id()))`.

**(D) `resumeDiscussion`** (state-CAS via `updateIfState`; Invariant 5):

```java
GroupConversation resumeDiscussion(String gcId, GroupApprovalRequest request, GroupDiscussionEventListener listener)
        throws GroupDiscussionException, IResourceStore.ResourceStoreException,
               IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
    GroupConversation gc = conversationStore.read(gcId);
    if (gc.getState() != GroupConversationState.AWAITING_APPROVAL)
        throw new IResourceStore.ResourceModifiedException("Not in AWAITING_APPROVAL state");

    if (request.getDecision().getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
        gc.setState(GroupConversationState.FAILED); gc.setPausedAt(null);
        conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);   // CAS → 409 if lost
        cleanupEphemeralAgents(gc, loadConfig(gc));
        return gc;
    }
    // Apply per-task decisions FIRST so re-entry's findExecutableTasks sees them COMPLETED and unblocks dependents
    if (request.getTaskApprovals() != null) {
        request.getTaskApprovals().forEach((taskId, verdict) -> {
            if ("APPROVED".equals(verdict)) gc.getTaskList().approveTask(taskId);
            else                            gc.getTaskList().rejectTask(taskId, "Human rejected");
        });
    }
    int resumeFromPhase = (gc.getHitlPauseType() == HitlPauseType.TASK)
            ? gc.getPausedAtPhaseIndex()        // TASK: re-enter same EXECUTE phase
            : gc.getPausedAtPhaseIndex() + 1;   // PHASE: next phase
    int savedTurnCount = gc.getPausedTurnCount();
    gc.setState(GroupConversationState.IN_PROGRESS);
    gc.setPausedAt(null); gc.setPausedAtPhaseIndex(-1); gc.setPausedPhaseName(null); gc.setHitlPauseType(null);
    conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);       // CAS → 409 if lost

    AgentGroupConfiguration config = loadConfig(gc);
    List<DiscussionPhase> phases   = resolvePhases(config);                              // deterministic (:428)
    String question                = gc.getOriginalQuestion();                            // recovered (:28)
    return runDiscussionLeg(gc, config, phases, question, listener,
                            new AtomicInteger(savedTurnCount), resumeFromPhase);          // fresh listener, no onGroupStart
}
```

> **TASK-resume idempotency (verified):** re-entering the EXECUTE phase re-runs the wave loop; the empty-list
> guard (:667-670) means no re-decomposition, and `findExecutableTasks` excludes COMPLETED/VERIFIED — so approved
> tasks are skipped and only their now-unblocked dependents run. Constrain `granularity=TASK` to EXECUTE phases
> (which conventionally have `repeats=1`); document this.

### 8.4 REST (group)

**[MODIFY] `IRestGroupConversation.java` + `RestGroupConversation.java`:**

```java
@POST @Path("/{groupConversationId}/approve") @Consumes(APPLICATION_JSON)
Response approveGroupPhase(@PathParam("groupId") String groupId,
        @PathParam("groupConversationId") String gcId, GroupApprovalRequest request);

@POST @Path("/{groupConversationId}/approve/stream") @Consumes(APPLICATION_JSON) @Produces(SERVER_SENT_EVENTS)
void approveGroupPhaseStreaming(@PathParam("groupId") String groupId,
        @PathParam("groupConversationId") String gcId, GroupApprovalRequest request,
        @Context SseEventSink eventSink, @Context Sse sse);

@GET @Path("/{groupConversationId}/approval-status") @Produces(APPLICATION_JSON)
Response getGroupApprovalStatus(@PathParam("groupId") String groupId,
        @PathParam("groupConversationId") String gcId, @QueryParam("detail") @DefaultValue("summary") String detail);
```

- Auth: owner/admin check (reuse the group's existing ownership resolution).
- `approveGroupPhase` runs `resumeDiscussion` on a virtual thread (like `discuss`); `ResourceModifiedException` → **409**.
- Streaming listener's `onHitlPause`: `sendEvent(EVENT_AWAITING_APPROVAL, payload)` **then `eventSink.close()`** (B8) — a
  re-pause during resume yields a fresh `AWAITING_APPROVAL` and a closed stream; the next approve opens a new one.

**[NEW] `GroupApprovalRequest.java`:** `{ HitlDecision decision; Map<String,String> taskApprovals; }` (taskId → `"APPROVED"`/`"REJECTED"`), getters/setters.

---

## 9. Wave 4 — Configuration, Timeout, Audit

### 9.1 Config (nested static classes — NOT records; match `SessionManagement`)

**[MODIFY] `AgentConfiguration.java`:**
```java
public static class HitlConfig {
    private String approvalTimeout;                    // ISO-8601, null = indefinite
    private String timeoutPolicy = "WAIT_INDEFINITELY";
    // getters + setters
}
private HitlConfig hitlConfig;   // + getter/setter
```

**[MODIFY] `AgentGroupConfiguration.java`:**
```java
public static class HitlConfig {
    private String approvalTimeout;
    private String timeoutPolicy = "WAIT_INDEFINITELY";
    private String granularity   = "PHASE";            // PHASE | TASK
    // getters + setters
}
private HitlConfig hitlConfig;   // + getter/setter; plumb granularity into executeTaskExecutionPhase
```

**Validation** (B5 — these POJOs have no `configure()`): validate in the resource store create/update path
(`AgentStore` / `AgentGroupStore`, mirroring `checkCollectionNoNullElements`), throwing
`IResourceStore.ResourceStoreException` (or `IllegalArgumentException`) — **not** `WorkflowConfigurationException`.
Reject unknown `timeoutPolicy`/`granularity` enum names with an actionable message.

### 9.2 Timeout via the schedule lease layer (no raw `@Scheduled`)

- **Schedule creation (owner = the orchestrating service, not `Conversation`):**
  - Regular: `ConversationService`, in the save-on-complete path, when a turn lands in `AWAITING_HUMAN` and the
    agent's `HitlConfig.timeoutPolicy != WAIT_INDEFINITELY`: also write `hitlTimeoutPolicy`/`hitlApprovalTimeout`
    into the memory bookmark, and `scheduleStore.createSchedule(cfg)` with
    `oneTimeAt = pausedAt + Duration.parse(approvalTimeout)`,
    `metadata = { hitlType:"hitl_timeout", surface:"regular", conversationId, agentId, agentVersion, environment, policy }`.
  - Group: `GroupConversationService`, in `commitPause`, same pattern keyed by `gcId` (`surface:"group"`).
  - `WAIT_INDEFINITELY` → no schedule.
- **Fire dispatch — [MODIFY] `ScheduleFireExecutor.fire()`** (it only does `say()` today): branch at the top:
  ```java
  Map<String,Object> md = schedule.getMetadata();
  if (md != null && "hitl_timeout".equals(md.get("hitlType"))) return fireHitlTimeout(schedule, instanceId, attempt);
  // ... existing say() path ...
  ```
- **[NEW] `engine/internal/HitlTimeoutHandler.java`** (`@ApplicationScoped`):
  ```java
  void handleTimeout(Map<String,Object> md) {
      HitlTimeoutPolicy policy = HitlTimeoutPolicy.valueOf((String) md.get("policy"));
      String surface = (String) md.get("surface");
      switch (policy) {
          case AUTO_REJECT, AUTO_APPROVE -> {
              var v = policy == HitlTimeoutPolicy.AUTO_APPROVE ? HitlVerdict.APPROVED : HitlVerdict.REJECTED;
              if ("group".equals(surface)) resumeGroup(md, v); else resumeRegular(md, v);   // decidedBy = "system:timeout"
          }
          case ABORT -> { if ("group".equals(surface)) cancelGroup(md); else cancelRegular(md); }
          case WAIT_INDEFINITELY -> { /* never scheduled */ }
      }
      // audit (§9.3)
  }
  ```
  Resume needs no HTTP scope: `resumeRegular` calls `conversationService.resumeConversation(conversationId, autoDecision, noop)`,
  which derives the agent from persisted memory; `resumeGroup` calls `resumeDiscussion(gcId, autoRequest, null)`.
- On resume/cancel (both surfaces), `scheduleStore.deleteSchedule(scheduleId)`.

### 9.3 Audit (20-arg positional record; `submit` async/void)

```java
auditLedgerService.submit(new AuditEntry(
    UUID.randomUUID().toString(), conversationId, agentId, agentVersion,
    decidedBy, environment, stepIndex,
    "hitl", "hitl.approval", 0,              // taskId, taskType (synthetic), taskIndex
    pauseDurationMs,
    Map.of("pauseReason", pauseReason, "surface", surface),
    Map.of("verdict", verdict, "automated", String.valueOf(automated)),
    null, null, List.of(), 0.0,              // llmDetail, toolCalls, actions, cost
    Instant.now(), null, null));             // timestamp, hmac, agentSignature (submit() fills last two)
```
> Confirm field order against `AuditEntry.java:74-77` before compiling; prefer the `with*` copy-withers for
> hmac/signature rather than hand-passing.

### 9.4 ExtensionDescriptor + changelog

- Add `hitlConfig` descriptors to the agent and group extension descriptors (lets the Manager render the fields).
- **[MODIFY] `docs/changelog.md`** — one entry per wave, in the same commit as that wave (AGENTS.md §2.8). No
  `Co-Authored-By` trailer.

---

## 10. Quota Disposition (M-6 — must decide, not document-and-ship)

Paused conversations are non-`ENDED`, so they count in `getActiveConversationCount` (gates undeploy at
`RestAgentAdministration.java:167` and old-version GC at `AgentDeploymentManagement.java:243`). A long
`WAIT_INDEFINITELY` pause blocks both indefinitely. **Choose one and implement it:**
- **(a, recommended)** Exclude `AWAITING_HUMAN` from `getActiveConversationCount` on **both** backends, and exclude
  `AWAITING_APPROVAL` group conversations from any analogous group gate; **and** add a forced-undeploy path that
  `deleteSchedule`s + ends pending HITL conversations.
- **(b)** Keep counting, but require a finite timeout in production (operator policy) and provide the forced-undeploy path.

Do **not** ship "counts forever, documented as policy."

---

## 11. File Summary

| Wave | File | Action |
|------|------|--------|
| 0 | `datastore/IResourceStorage.java` | MODIFY — `+ storeIfFieldEquals` default |
| 0 | `datastore/mongo/MongoResourceStorage.java` | MODIFY — override |
| 0 | `datastore/postgres/PostgresResourceStorage.java` | MODIFY — override |
| 0 | `engine/lifecycle/model/ControlSignal.java` | NEW |
| 0 | `engine/lifecycle/model/DiscussionControlToken.java` | NEW |
| 0 | `engine/lifecycle/exceptions/ConversationPauseException.java` | NEW |
| 0 | `engine/lifecycle/ILifecycleManager.java` | MODIFY |
| 0 | `engine/lifecycle/internal/LifecycleManager.java` | MODIFY (runTaskAt, pause check, executeLifecycleFromIndex, cancel check) |
| 0 | `configs/groups/model/GroupConversation.java` | MODIFY (`+CANCELLED`, no version field) |
| 0 | `engine/internal/GroupConversationService.java` | MODIFY (cancel: activeTokens, safe-points, cancelDiscussion) |
| 0 | `engine/api/IGroupConversationService.java` | MODIFY (`+cancelDiscussion`) |
| 0 | `configs/groups/IGroupConversationStore.java` | MODIFY (`+updateIfState`, `+findByState`) |
| 0 | `configs/groups/mongo/GroupConversationStore.java` | MODIFY |
| 0 | `engine/api/IRestGroupConversation.java` / `engine/internal/RestGroupConversation.java` | MODIFY (`+cancel`) |
| 0 | `engine/lifecycle/GroupConversationEventSink.java` | MODIFY (`+EVENT_CANCELLED`, `CancelledEvent`) |
| 0 | `engine/memory/IConversationMemory.java` / `ConversationMemory.java` | MODIFY (`+isCancelled/setCancelled`) |
| 0 | `engine/internal/ConversationService.java` | MODIFY (`+activeMemories`, `+cancelConversation`) |
| 0 | `engine/api/IRestAgentEngine.java` / `engine/internal/RestAgentEngine.java` | MODIFY (`+cancel`) |
| 1 | `engine/memory/model/ConversationState.java` | MODIFY (`+AWAITING_HUMAN`) |
| 1 | `engine/lifecycle/model/HitlTimeoutPolicy.java` | NEW |
| 1 | `engine/lifecycle/model/HitlDecision.java` | NEW |
| 1 | `engine/memory/model/ConversationMemorySnapshot.java` | MODIFY (`+6 bookmark fields`) |
| 1 | `engine/memory/model/SimpleConversationMemorySnapshot.java` | MODIFY (`+hitlPausedAt`) |
| 1 | `engine/memory/IConversationMemory.java` / `ConversationMemory.java` | MODIFY (`+bookmark getters/setters`) |
| 1 | **`engine/memory/ConversationMemoryUtilities.java`** | **MODIFY (3 converters)** |
| 1 | `engine/lifecycle/IConversation.java` | MODIFY (`+PAUSE_CONVERSATION`, `+resume`) |
| 1 | `engine/runtime/internal/Conversation.java` | MODIFY (pause/resume, skip post-tasks, gate say()) |
| 1 | `engine/internal/ConversationService.java` | MODIFY (timeout guard, awaiting marker) |
| 1 | `engine/lifecycle/GroupConversationEventSink.java` | MODIFY (`+EVENT_AWAITING_APPROVAL`, events) |
| 1 | `engine/memory/IConversationMemoryStore.java` | MODIFY (`+findConversationIdsByState`, `+compareAndSetState`) |
| 1 | `engine/memory/ConversationMemoryStore.java` | MODIFY (impl + state index) |
| 1 | `engine/memory/PostgresConversationMemoryStore.java` | MODIFY (impl) |
| 2 | `engine/api/IRestAgentEngine.java` / `engine/internal/RestAgentEngine.java` | MODIFY (`+resume/status/pending`) |
| 2 | `engine/api/IConversationService.java` / `engine/internal/ConversationService.java` | MODIFY (`+resumeConversation`) |
| 3 | `configs/groups/model/GroupConversation.java` | MODIFY (`+HITL fields`, `HitlPauseType`) |
| 3 | `configs/groups/model/SharedTaskList.java` | MODIFY (`+submitForApproval/approveTask/rejectTask/resetToAssigned/hasAwaitingApproval`) |
| 3 | `engine/internal/GroupConversationService.java` | MODIFY (runPhaseLoop/runDiscussionLeg split, commitPause, per-task gate, finally guard, resumeDiscussion) |
| 3 | `engine/api/IGroupConversationService.java` | MODIFY (`+resumeDiscussion`) |
| 3 | `engine/api/IRestGroupConversation.java` / `engine/internal/RestGroupConversation.java` | MODIFY (`+approve/stream/status`) |
| 3 | `engine/internal/GroupApprovalRequest.java` | NEW |
| 4 | `configs/agents/model/AgentConfiguration.java` | MODIFY (`+HitlConfig`) |
| 4 | `configs/groups/model/AgentGroupConfiguration.java` | MODIFY (`+HitlConfig`) |
| 4 | `configs/.../AgentStore.java` / `AgentGroupStore.java` | MODIFY (validate HitlConfig) |
| 4 | `engine/runtime/internal/ScheduleFireExecutor.java` | MODIFY (`+hitlType dispatch`) |
| 4 | `engine/internal/HitlTimeoutHandler.java` | NEW |
| 4 | (Manager) extension descriptors | MODIFY (`hitlConfig` fields) |
| 4 | `docs/changelog.md` | MODIFY (per wave) |
| 10 | `ConversationMemoryStore.java` / `PostgresConversationMemoryStore.java` | MODIFY (quota exclusion, per §10a) |

**Deferred:** Manager approvals UI (separate repo), tool-level HITL, multi-approver/role policies, Postgres index-varargs honoring (M-5 perf).

---

## 12. Test Plan

**Wave 0**
- `StoreIfFieldEqualsMongoTest` / `StoreIfFieldEqualsPostgresTest` — match writes, non-match throws `ResourceModifiedException`.
- `GroupConversationUpdateIfStateTest` — `AWAITING_APPROVAL`→`IN_PROGRESS` CAS; stale expected → throws.
- `MultiPhaseUpdateRegressionTest` — ≥2-phase discussion through all unconditional `update(gc)` calls (no CAS regression).
- `DiscussionControlTokenTest`, `GroupConversationCancelTest`, `ConversationCancelTest`, `LifecycleFromIndexTest` (absolute index → non-null component; task N not re-invoked, N+1 runs).

**Wave 1**
- `HitlBookmarkRoundTripTest` — save→load via `ConversationMemoryUtilities` (all 6 fields survive; **regular path**, not via group).
- `HitlSimpleSnapshotTest` — `getSimpleMemorySnapshot` carries `hitlPausedAt`.
- `ConversationPauseResumeTest`, `ConversationResumeRejectionTest` (bookmark cleared, READY), `ConversationMultiPauseTest`,
  `ConversationSayWhilePausedTest` (→ `ConversationNotReadyException`), `MultiWorkflowResumeTest` (skip/resume/run),
  `PostConversationSkipOnPauseTest`, `CompareAndSetStateTest` (Mongo + Postgres), `TimeoutDoesNotOverwriteAwaitingHumanTest`.

**Wave 2**
- `HitlRestEndpointTest` (resume/status/pending; 409 on wrong state), `HitlAuthorizationTest` (non-owner → 403).

**Wave 3**
- `SharedTaskListHitlTest` — submitForApproval (result preserved), approveTask (result preserved), rejectTask
  (verificationNote), resetToAssigned, hasAwaitingApproval.
- `GroupDiscussionHitlPhaseTest` — phase pause → `AWAITING_APPROVAL` (NOT COMPLETED) → approve → next phase.
- `GroupDiscussionHitlTaskTest` — per-task: submitForApproval inside wave; post-join gate; approve preserves result.
- `GroupDiscussionPerTaskResumeTest` — resume re-enters **same** phase; remaining waves + dependents run.
- `GroupDiscussionPhaseResumeTest` — resume at `pausedPhaseIndex + 1`.
- `GroupDiscussionPostLoopGuardTest` — `AWAITING_APPROVAL` returns before synthesis/COMPLETED.
- `GroupDiscussionCleanupTest` — agents/cursor NOT cleaned during pause.
- `GroupDiscussionMidPhaseFlushTest` — pod-restart-equivalent: task statuses survive after pause.
- `GroupDiscussionSseSinkTest` — `EVENT_AWAITING_APPROVAL` sent, sink closed.
- `GroupDiscussionDoubleResumeTest` — concurrent resume → `updateIfState` → one 200, one 409.
- `GroupDiscussionNoResumeStartEventTest` — resume emits no `onGroupStart`; metric not double-counted.

**Wave 4**
- `HitlTimeoutHandlerTest` (AUTO_REJECT/AUTO_APPROVE/ABORT; WAIT_INDEFINITELY makes no schedule),
  `HitlScheduleLifecycleTest` (created on pause, `deleteSchedule` on resume/cancel),
  `HitlConfigValidationTest` (bad policy/granularity → store exception), `HitlAuditEntryTest` (20-arg, async submit),
  `FindByStateAnchoredRegexTest` (`COMPLETED` ≠ `NOT_COMPLETED`; Mongo + Postgres).

**Regression:** `SharedTaskListTest`, `DynamicAgentTrackingPropagationTest`, `GroupConversationServiceTaskForceTest`, `./mvnw compile`.

---

## 13. Manual Verification

1. Regular: rule emits `PAUSE_CONVERSATION` → `AWAITING_HUMAN` → `/approval-status` → `/resume` APPROVED → continues.
2. Regular rejection → bookmark cleared, READY.
3. Pod restart while paused → `/approval-status` intact → resume works (regular AND group).
4. Group phase approval → `AWAITING_APPROVAL` (not COMPLETED) → approve → next phase.
5. Group per-task → results stored → approve/reject individually → approved result preserved → remaining waves run.
6. Per-task resume re-enters same phase; dependents unblock.
7. Timeout `PT30S` + `AUTO_REJECT` → schedule fires → auto-reject + audit; `WAIT_INDEFINITELY` → stays paused.
8. Cancel paused (both surfaces) → ENDED / CANCELLED via state-CAS.
9. Double resume → one 200, one 409.
10. Non-owner approve → 403.
11. Multi-phase discussion (no HITL) → unaffected (all `update(gc)` unconditional).
12. SSE: pause closes the stream; `/approve/stream` opens a fresh one for remaining phases.

---

## 14. Build Order Checklist (for the coding model)

1. Wave 0 storage primitive (`storeIfFieldEquals`) + tests — everything else leans on it.
2. Wave 0 lifecycle types + `LifecycleManager` `runTaskAt`/`executeLifecycleFromIndex` + cancel.
3. Wave 1 regular pause/resume (bookmark fields + **all three** converters + `Conversation.resume` + CAS).
4. Wave 2 regular REST + `ConversationService.resumeConversation`.
5. Wave 3 group: `SharedTaskList` methods → `runPhaseLoop`/`runDiscussionLeg` split → `commitPause` → per-task gate →
   `resumeDiscussion` → REST.
6. Wave 4 config + timeout schedule + audit + quota disposition (§10).
7. Changelog entry per wave; `./mvnw test` green per wave before moving on.

**Hard gates before declaring done:** Invariants §3 all hold; `HitlBookmarkRoundTripTest` (regular path) green;
`GroupDiscussionPostLoopGuardTest` green (no silent COMPLETED-overwrite); `GroupDiscussionDoubleResumeTest` green
(409 on the loser); `MultiPhaseUpdateRegressionTest` green (no group regression).
