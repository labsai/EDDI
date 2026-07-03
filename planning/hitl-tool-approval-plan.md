# Tool-Level HITL Approval Gating — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pause a conversation for human approval when the LLM invokes a *gated tool* (any source: built-in `@Tool`, MCP, A2A, httpcall, dynamic-agent, memory, recall), configured via allow/disallow pattern lists — co-existing with the behavior-rule `PAUSE_CONVERSATION` mechanism and reusing all its machinery (state model, `/resume`, timeout policies, audit, Slack, crash recovery). Also fixes the remaining HITL-critique findings (structured pauseDetails, per-call verdicts, approve-with-amendments, save-time lints).

**Architecture:** *Durable re-entry* (ToolGate-DR). A batch-level gate in `AgentOrchestrator.executeWithTools()` intercepts gated tool calls **before execution** (fail-safe), serializes the exact in-flight langchain4j message list via `ChatMessageSerializer` (langchain4j 1.17.0), persists it + pending-call metadata as typed fields on `ConversationMemorySnapshot`, and aborts the LLM loop with an unchecked `ToolApprovalRequiredException` that `LifecycleManager` converts into the existing `ConversationPauseException` (new `pauseOrigin=TOOL_CALL`). Resume re-enters the **same** task index; `LlmTask` detects the pending batch, deserializes the transcript, executes approved calls (write-ahead journal → no double execution), feeds rejected calls' reviewer notes back as structured tool results, and continues the loop until a final answer — the original turn then completes normally, including all downstream pipeline tasks.

**Tech Stack:** Java 25, Quarkus 3.37, MongoDB (Jackson snapshot persistence), langchain4j 1.17.0 (`ChatMessageSerializer`/`ChatMessageDeserializer` in `langchain4j-core`), Micrometer, JUnit 5 + Mockito.

## Why this architecture (decision record)

Three architectures were designed independently and adversarially judged (2 judges, 7-attack failure-mode matrix + product-fit lens):

| Angle | Core idea | Verdict |
|---|---|---|
| **A — Durable re-entry** (CHOSEN) | Abort loop with pause exception, persist exact transcript, re-enter LlmTask on resume | Provider-safe resume (replays the exact message list the provider already accepted, appending `ToolExecutionResultMessage`s bound by callId — the native tool-loop shape). Completes the original turn → downstream output/templating tasks run → no pipeline bypass. Single-document persistence → no orphan states. Weakness (double execution after crash-mid-resume) closed by grafting B's journal. |
| B — Turn-completing pending result | Gated tool returns `PENDING_APPROVAL` to the LLM, turn completes, approval fires a continuation turn | Rejected: continuation step bypasses pipeline tasks (semantic gap for a config-driven engine), produces consecutive AiMessages that poison every later turn's history, hallucination risk ("action completed" when nothing ran), 2× LLM cost per gated action, and its **core** resume path needs a provider-fragility fallback. Its write-ahead journal is grafted into A. |
| C — Checkpointed + park-and-handoff | Like A but separate checkpoint collection + bounded synchronous wait | Rejected: two-document consistency needs janitor/orphan sweeps A doesn't; park-lease protocol is v2-sized risk. Its pattern-language spec, GateClearance defense-in-depth, iteration/cost continuity, and no-progress demotion are grafted into A. |

**Product decisions baked into this plan (flag to the owner if any is wrong):**
1. **`AUTO_APPROVE` never applies to tool pauses implicitly.** If `toolApprovals` omits `timeoutPolicy` and the agent-level policy is `AUTO_APPROVE`, the effective tool policy is `WAIT_INDEFINITELY` (+ save-time warning). Machine-executed side-effecting tools require an explicit opt-in per gate.
2. **Crash-during-tool-execution yields honest "outcome unknown".** If a pod dies *inside* an approved tool's execution, re-approval does NOT silently re-execute: the LLM receives `{"status":"EXECUTION_OUTCOME_UNKNOWN", ...}`, approval-status flags it, audit records `hitl.tool.outcome_unknown`. At-most-once semantics, surfaced rather than hidden.
3. **Group-member tool pauses are auto-rejected** (`system:group`, graceful — the member LLM gets rejection tool-results and answers coherently). `inGroupTurns: "INBOX"` is a reserved enum value → 400 in v1 (forward-compat for a group-inbox route).
4. **Ungated calls in a mixed batch execute before the human sees the pause** (they were going to run anyway; freezing them would strand a half-executed batch). The approver sees which ungated calls already ran (`executedUngatedCalls` in pauseDetails).
5. **The frozen transcript resumes against pause-time prompt state** (system prompt/snippets/vars as of the pause). A multi-day approval resumes the turn the model was actually mid-way through — by design.

## Global Constraints

- Java 25 / Quarkus CDI (`@ApplicationScoped`, constructor injection), JBoss `Logger` (never `System.out`).
- **Backward compatibility is absolute:** stored agent configs without `toolApprovals` → byte-identical behavior; stored conversation snapshots without new fields → `null` pauseType treated as `RULE` on every branch; existing `/resume` bodies (`{"verdict":"APPROVED"}`) stay valid on both pause types.
- Conventional commits (`feat(hitl): …`, `fix(hitl): …`). **No `Co-Authored-By` trailer. Never push without explicit user approval. Never commit to `main` — work on `feat/hitl-framework`.**
- Every commit must build: `./mvnw compile -q` before committing. Stage files individually (never `git add .`).
- Unit tests (JUnit 5 + Mockito) must run locally: `./mvnw test -Dtest=<Class>`. Integration tests (`*IT`) are CI-only — write them, don't try to run them locally.
- Update `docs/changelog.md` in the same commit as the work it documents (emoji-prefixed section conventions).
- All size caps centralized as constants on `PendingToolCallBatch`. All approver-facing strings pass `SecretRedactionFilter.redact()`.
- Config property namespace: `eddi.hitl.tool.*`. Metrics: `eddi_hitl_tool_*` (new meters, never re-tag existing registered meters).
- Verify exact line numbers before editing — they drift; anchor on the quoted code, not the number.

## Verified codebase anchors (read these before starting)

| What | Where (verified) |
|---|---|
| Tool loop: `chatModel.chat()` → `aiMessage.hasToolExecutionRequests()` → per-request dispatch | `AgentOrchestrator.executeWithTools()`, loop at `AgentOrchestrator.java:337-443`; dispatch `toolExecutors.get(toolRequest.name())` :405; `executeToolWrapped` :412 |
| The loop runs inside `AgentExecutionHelper.executeWithRetry(() -> {...})` | `AgentOrchestrator.java:321`; **`catch (Exception e)` at `AgentExecutionHelper.java:53` wraps non-retryable exceptions into `LifecycleException` at :71 — pause signal must be rethrown before this** |
| Cascade path swallows exceptions | `CascadingModelExecutor.java:196` `catch (Exception e)` — needs rethrow guard; future-unwrap at :253-259 rethrows cause, so the guard at :196 suffices |
| Task-loop catch that would wrap the signal | `LifecycleManager.executeTaskRange`, `catch (LifecycleException | RuntimeException e)` at `LifecycleManager.java:342` — conversion point |
| Absolute index formula | `indexOffset + index` (`LifecycleManager.java:340`); `executeLifecycleFromIndex` bounds-check :229-236 |
| Pause commit | `Conversation.executeConversationStep` catch :309-318 → `pauseConversation(e)` :451-457 (sets state + 4 bookmark fields); cancel-wins check :310-314 |
| Resume | `Conversation.resume()` :460-566; REJECTED short-circuit :487-503; **`resumeFromIndex = …+1` hardcoded :507**; re-pause catch :537-542; finally :546-565; `clearHitlBookmark()` :568-575 |
| Bookmark fields (6) | `ConversationMemory.java:234-300` + mirrored `ConversationMemorySnapshot.java:19-36`: `hitlPausedWorkflowId`, `hitlPausedAbsoluteTaskIndex`, `hitlPausedAt`, `hitlPauseReason`, `hitlTimeoutPolicy`, `hitlApprovalTimeout` |
| Resume service | `ConversationService.resumeConversation` starts :1154; **CAS `compareAndSetState` AWAITING_HUMAN→IN_PROGRESS at :1164** (guard block :1164-1172); agent-deployed `agent==null` check + `restorePauseAfterFailedResume` call :1213-1223 (restore method defined :1509); schedule delete `deleteHitlTimeoutSchedule` :1233; state-guarded store `storeConversationMemoryIfState(...)` call :1302 (method def :1045); `auditHitlDecision` :1395; `HitlResumeCompletedEvent` fireAsync inside `fireHitlResumeCompleted` :1433 |
| Timeout bookmark/schedule | `populateHitlTimeoutBookmark` :1539-1561 (reads `hitlConfig` via `readAgentConfigPinned()`; **currently applies the `pauseReason` override unconditionally — Task 5 Step 7 gates it on pauseType**), `scheduleHitlTimeout` :1629, `HitlSchedules.regularTimeoutScheduleName(conversationId)` = `"hitl-timeout-"+id` (used :1647/:1673) |
| say() 409 while paused | `ConversationService.say()` throws `ConversationAwaitingApprovalException` at :398 (guard :397); narrow-window skip :862-873 |
| HitlConfig | `AgentConfiguration.java:854-892` (`approvalTimeout`, `timeoutPolicy` default `WAIT_INDEFINITELY` null-safe setter, `pauseReason`); field on AgentConfiguration :258 |
| HitlDecision | `engine/lifecycle/model/HitlDecision.java` — `verdict` (case-insensitive `@JsonCreator`), `note` (`MAX_NOTE_LENGTH=4096`), `decidedBy` (server-set) |
| `HitlTimeoutPolicy` (enum) | **`ai.labs.eddi.configs.hitl.HitlTimeoutPolicy`** — `{ WAIT_INDEFINITELY, AUTO_APPROVE, AUTO_REJECT, ABORT }` (NOT in `engine.lifecycle.model`) |
| `SecretRedactionFilter` | `ai.labs.eddi.secrets.sanitize.SecretRedactionFilter` — `public static String redact(String message)`. **Only masks known secret patterns** (`sk-`, `Bearer`, `api_key`/`token`/`secret`/`password`, `${vault:…}`); it does NOT truncate or blanket-mask non-secret content — so approver-facing capping/truncation is a SEPARATE step the plan does explicitly (the `ARGS_REDACTED_MAX_BYTES` cap, the Slack 300-char truncation). |
| `HitlConfigValidation` | `configs/hitl/HitlConfigValidation.java` — throws **`java.lang.IllegalArgumentException`** (not a custom type) at :39/:58/:68/:73. Invoked from `AgentStore.create` :48 / `update` :57, `AgentGroupStore` :35/:44, `RestImportService` :425. LLM store seam: `configs/llm/mongo/LlmStore.java` + `configs/llm/rest/RestLlmStore.java`. |
| `PendingApprovalSummary` | `engine/model/PendingApprovalSummary.java` — fields `conversationId, agentId, groupId, userId, pausedAt (Instant), pauseReason, timeoutPolicy, approvalTimeout`. Constructed in `ConversationMemoryStore.collectPendingSummaries()` :242-246 (6-arg ctor + `setApprovalTimeout`). |
| Snapshot copy sites (Task 4) | **`ConversationMemoryUtilities.java`** — memory→snapshot at :69-74, snapshot→memory at :118-123 (THIS is the both-directions copy the executor must edit; plus the setters on `ConversationMemory.java:255` and `ConversationMemorySnapshot.java:292`). Also check `PostgresConversationMemoryStore` for an independent copy path. |
| Config-carrier precedent | `IConversationMemory` carries transient `userMemoryConfig` (`ConversationMemory.java:45` field, :200 getter, :205 setter); `setUserMemoryConfig` called in `Conversation.init` :92 from `propertiesHandler.getUserMemoryConfig()`; `AgentStoreClientLibrary.java:55-56` copies it off `AgentConfiguration`. `ConversationService.readAgentConfigPinned()` (used at :1542) reaches `hitlConfig` service-side. |
| Store pattern (Task 6) | Mirror **constructor (`@Inject`) index creation** — `AgentTriggerStore.java:50` (unique index in ctor) or `MongoScheduleStore.java:98-113` (named `IndexOptions` in ctor). **No store uses `@PostConstruct` for indexes.** |
| Rules store / deployment (Task 15) | `configs/rules/mongo/RuleSetStore.java` (+ `IRestRuleSetStore`); deployment hook `engine/runtime/internal/AgentDeploymentManagement.java`. |
| Testcontainers ITs (CI-only) | `MongoConversationMemoryStoreTest` / `PostgresConversationMemoryStoreTest` are `@Testcontainers` (need Docker) — **do NOT run locally**. Locally-runnable HITL unit tests (Mockito, no `@QuarkusTest`): `ConversationServiceResumeTest`, `LifecycleManagerHitlTest`, `ConversationHitlTest`, `ConversationStateHitlTest`, `HitlConfigValidationWiringTest`. |
| LlmTask task loop | `LlmTask.execute` :175-215 — loops `llmConfig.tasks()` :206-210; typed catch :212 (no RuntimeException — unchecked signals pass); cascade branch :375-429 (orchestrator call :415), standard :433; pre-LLM side effects: `runTemplateEngineOnParams` :223, httpCall RAG :240-254, vector RAG :256-267, identity masking :273, counterweight :281, history build :324-335, `MultimodalMessageEnhancer` :339, `executePreRequestPropertyInstructions` :346 |
| Tool sources (8) & names | built-in `@Tool` method names (`AgentOrchestrator:236-242`); MCP: server-provided names unchanged (`McpToolProviderManager:117`); A2A: `sanitizeToolName(agentName+"_"+skillId)` (`A2AToolProviderManager:173,340`); httpcall: `ApiCall.name` (:783); mcpcall (:823); UserMemoryTool (:649, names `rememberFact`/`recallMemories`/`searchMemory`/`forgetMemory`); ConversationRecallTool (:671, `recallConversationDetail`); dynamic tools whitelist-gated (:575-594, `createSubAgent`/`converseWithAgent`/`findAgentsByCapability`/`teardownAgent`) |
| Auto-checkpoint before each tool | `AgentOrchestrator:353-361` (`memorySnapshotService.createCheckpoint`) — must NOT re-fire for journal-replayed calls |
| Group precedent for same-index re-entry & per-task verdicts | `GroupConversationService` — taskApprovals validation-before-mutation :2609-2640, approve-all shortcut :2660-2674, human-vs-system budget :2684-2692, fingerprint :619-631 |
| Delegated-pause envelopes | `ConverseWithAgentTool` :117-119/:146/:169 (`pausedForApprovalMessage`), `CreateSubAgentTool` :193-203, `McpConversationTools.pausedForApprovalJson()` :568 |
| Config validation seam | `configs/hitl/HitlConfigValidation.java`, invoked from `AgentStore` create/update (see `HitlConfigValidationWiringTest`) |
| langchain4j | 1.17.0 (`pom.xml:21`); `ChatMessageSerializer`/`ChatMessageDeserializer` live in `langchain4j-core` (transitive via `dev.langchain4j:langchain4j`), **zero current usages in repo — Task 1 proves the round-trip first** |

## File Structure (final state)

```
CREATE src/main/java/ai/labs/eddi/configs/hitl/model/ToolApprovalsConfig.java     — shared config POJO (agent-level + task-level)
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatterns.java     — glob compile/validate (ReDoS-safe), source prefixes
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGate.java         — batch classification, caps, fail-closed clearance
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalRequiredException.java — unchecked signal (reason + batch)
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/ChatTranscriptCodec.java      — serializer wrapper + size cap + failure flag
CREATE src/main/java/ai/labs/eddi/engine/memory/model/PendingToolCallBatch.java   — persisted batch + PendingToolCall + size constants
CREATE src/main/java/ai/labs/eddi/engine/lifecycle/model/ToolCallDecision.java    — per-call verdict {verdict, note, amendedArguments}
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/IHitlToolJournalStore.java    — write-ahead journal interface
CREATE src/main/java/ai/labs/eddi/engine/hitl/tools/mongo/HitlToolJournalStore.java — Mongo impl (unique conversationId+pauseEpoch+callId, TTL)
CREATE src/main/java/ai/labs/eddi/engine/hitl/lint/ReservedActionLint.java        — PAUSE_CONVERSATION near-miss lint
MODIFY src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java         — toolSources map, gate hook, executeSingleToolCall() extraction, resumeToolLoop()
MODIFY src/main/java/ai/labs/eddi/modules/llm/impl/AgentExecutionHelper.java      — rethrow guard (line ~53)
MODIFY src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java    — rethrow guard (line ~196)
MODIFY src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java                   — resume-mode branch
MODIFY src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java — signal→ConversationPauseException conversion
MODIFY src/main/java/ai/labs/eddi/engine/lifecycle/exceptions/ConversationPauseException.java — pauseOrigin (RULE|TOOL_CALL)
MODIFY src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java (+IConversationMemory, +ConversationMemorySnapshot) — hitlPauseType, hitlPendingToolCalls (+ transient hitlResumeDecision)
MODIFY src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java       — pauseConversation sets pauseType + pending output; resume() TOOL_CALL branch (+0, no REJECTED short-circuit)
MODIFY src/main/java/ai/labs/eddi/engine/internal/ConversationService.java        — toolDecisions validation, pauseReason RULE-only override, effective tool timeout policy, no-progress guards, metrics, audit detail, stale-batch clearing
MODIFY src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java            — pauseDetails (TOOL_CALL + RULE)
MODIFY src/main/java/ai/labs/eddi/engine/lifecycle/model/HitlDecision.java        — optional toolDecisions map
MODIFY src/main/java/ai/labs/eddi/engine/model/PendingApprovalSummary.java        — pauseType, toolNames
MODIFY src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java    — HitlConfig.toolApprovals
MODIFY src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java         — Task.toolApprovals (override)
MODIFY src/main/java/ai/labs/eddi/configs/hitl/HitlConfigValidation.java (+ LlmStore seam) — toolApprovals validation
MODIFY src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java (+SlackEventHandler) — per-tool redacted preview lines
MODIFY src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java (+ConverseWithAgentTool, CreateSubAgentTool) — pauseType/tools in PAUSED_FOR_APPROVAL
MODIFY src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java   — member tool-pause auto-reject
MODIFY behavior-rule store save path + agent deployment path                       — lints
MODIFY docs/hitl.md, docs/changelog.md, AGENTS.md (§5.3 HITL note)
```

Test classes are listed per task. Naming convention for the new engine package: `ai.labs.eddi.engine.hitl.tools`.

---

### Task 1: Prove the transcript round-trip (`ChatTranscriptCodec`) — GATE FOR THE WHOLE PLAN

The entire resume design depends on `ChatMessageSerializer.messagesToJson()` / `ChatMessageDeserializer.messagesFromJson()` faithfully round-tripping the message shapes EDDI produces. **Zero current usages in the repo.** If this task's tests cannot be made to pass, STOP and escalate — the fallback-only design (rebuild via `ConversationHistoryBuilder` + reconstructed `AiMessage`) becomes the primary path and the plan owner must re-approve.

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/ChatTranscriptCodec.java`
- Test: `src/test/java/ai/labs/eddi/engine/hitl/tools/ChatTranscriptCodecTest.java`

**Interfaces:**
- Produces: `ChatTranscriptCodec.serialize(List<ChatMessage> messages, int maxBytes)` → `CodecResult(String json, boolean omitted)`; `ChatTranscriptCodec.deserialize(String json)` → `List<ChatMessage>` (throws `TranscriptCodecException` on failure — callers fall back).

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatTranscriptCodecTest {

    // Task 4 places this constant on PendingToolCallBatch. For THIS task it is
    // inlined; Task 4 Step 4 replaces this field with a static import of
    // PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT.
    private static final int TRANSCRIPT_MAX_BYTES_DEFAULT = 2_000_000;

    private List<ChatMessage> fullConversation() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_abc").name("transfer_funds").arguments("{\"amount\":250,\"to\":\"iban-x\"}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_def").name("getCurrentDateTime").arguments("{}").build();
        return List.of(
                SystemMessage.from("You are a banking assistant."),
                UserMessage.from("send 250 to my landlord"),
                AiMessage.from(req1, req2),
                ToolExecutionResultMessage.from(req2, "2026-07-03T10:00:00Z"),
                AiMessage.from("intermediate reasoning text"),
                UserMessage.from(TextContent.from("what about this attachment?")));
    }

    @Test
    void roundTrip_preservesToolExecutionRequests_idsNamesArgs() {
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(fullConversation(), TRANSCRIPT_MAX_BYTES_DEFAULT);
        assertFalse(result.omitted());

        List<ChatMessage> restored = codec.deserialize(result.json());
        assertEquals(6, restored.size());
        AiMessage ai = (AiMessage) restored.get(2);
        assertTrue(ai.hasToolExecutionRequests());
        assertEquals("call_abc", ai.toolExecutionRequests().get(0).id());
        assertEquals("transfer_funds", ai.toolExecutionRequests().get(0).name());
        assertEquals("{\"amount\":250,\"to\":\"iban-x\"}", ai.toolExecutionRequests().get(0).arguments());
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) restored.get(3);
        assertEquals("call_def", toolResult.id());
        assertEquals("2026-07-03T10:00:00Z", toolResult.text());
    }

    @Test
    void roundTrip_preservesMultimodalUserContent() {
        var msg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from("aGVsbG8=", "image/png"));
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(List.of(msg), TRANSCRIPT_MAX_BYTES_DEFAULT);
        List<ChatMessage> restored = codec.deserialize(result.json());
        UserMessage restoredMsg = (UserMessage) restored.get(0);
        assertEquals(2, restoredMsg.contents().size());
        assertInstanceOf(ImageContent.class, restoredMsg.contents().get(1));
    }

    @Test
    void overCap_returnsOmittedWithoutJson() {
        var codec = new ChatTranscriptCodec();
        // serialize with a 16-byte cap — any real conversation exceeds it
        var result = codec.serialize(fullConversation(), 16);
        assertTrue(result.omitted());
        assertNull(result.json());
    }

    @Test
    void deserialize_garbage_throwsTranscriptCodecException() {
        var codec = new ChatTranscriptCodec();
        assertThrows(ChatTranscriptCodec.TranscriptCodecException.class,
                () -> codec.deserialize("{not json at all"));
    }

    @Test
    void byteCap_measuredInUtf8Bytes_notChars() {
        var codec = new ChatTranscriptCodec();
        var msg = UserMessage.from("ü".repeat(100)); // 200 UTF-8 bytes of payload
        var ok = codec.serialize(List.of(msg), 100_000);
        assertFalse(ok.omitted());
        assertTrue(ok.json().getBytes(StandardCharsets.UTF_8).length > 200);
    }
}
```

Note: the size constant lives on `PendingToolCallBatch` (created in Task 4). For THIS task it is inlined as the `private static final int TRANSCRIPT_MAX_BYTES_DEFAULT` field shown above. Task 4 Step 4 deletes that field and adds `import static ai.labs.eddi.engine.memory.model.PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT;`. There is no separate `…Limits` class — the constants live directly on `PendingToolCallBatch`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ChatTranscriptCodecTest -q`
Expected: COMPILATION ERROR — `ChatTranscriptCodec` does not exist.

- [ ] **Step 3: Implement `ChatTranscriptCodec`**

```java
/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes the in-flight LLM tool-loop transcript for a durable HITL tool
 * pause, and restores it on resume.
 * <p>
 * Wraps langchain4j's {@link ChatMessageSerializer}/{@link ChatMessageDeserializer}
 * (Jackson codec with mixins for AiMessage + ToolExecutionRequest,
 * ToolExecutionResultMessage and multimodal contents) behind a size cap and a
 * typed failure so callers can fall back to history reconstruction when a
 * stored transcript cannot be parsed (e.g. after a langchain4j upgrade
 * mid-pause).
 */
public class ChatTranscriptCodec {
    private static final Logger LOGGER = Logger.getLogger(ChatTranscriptCodec.class);

    /** Serialization outcome — {@code omitted=true} means the cap was exceeded. */
    public record CodecResult(String json, boolean omitted) {
    }

    /** Thrown when a stored transcript cannot be restored; callers must fall back. */
    public static class TranscriptCodecException extends Exception {
        public TranscriptCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public CodecResult serialize(List<ChatMessage> messages, int maxBytes) {
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                LOGGER.warnf("HITL tool-pause transcript exceeds cap (%d bytes) — omitting; resume will use fallback reconstruction",
                        maxBytes);
                return new CodecResult(null, true);
            }
            return new CodecResult(json, false);
        } catch (Exception e) {
            LOGGER.errorf(e, "HITL tool-pause transcript serialization failed — omitting; resume will use fallback reconstruction");
            return new CodecResult(null, true);
        }
    }

    public List<ChatMessage> deserialize(String json) throws TranscriptCodecException {
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            if (messages == null || messages.isEmpty()) {
                throw new TranscriptCodecException("transcript deserialized to empty list", null);
            }
            return messages;
        } catch (TranscriptCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new TranscriptCodecException("failed to restore HITL tool-pause transcript: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest=ChatTranscriptCodecTest -q`
Expected: PASS (5 tests). **If `roundTrip_preservesToolExecutionRequests_idsNamesArgs` or the multimodal test fails with the real langchain4j 1.17.0 codec (e.g. a content type has no mixin), STOP — report exactly which message shape fails and wait for a design decision before continuing.** Do not silently work around it.

- [ ] **Step 5: Also add a provider-shape smoke test for the resume message sequence**

Append to `ChatTranscriptCodecTest`:

```java
    @Test
    void resumeShape_transcriptPlusAppendedToolResults_isWellFormed() {
        // The resume path appends ToolExecutionResultMessages for the GATED calls
        // to the restored transcript. Assert the combined list still alternates
        // legally: ...AiMessage(with requests) -> results for every request id.
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(fullConversation(), 2_000_000);
        List<ChatMessage> restored = new java.util.ArrayList<>(codec.deserialize(result.json()));
        AiMessage pending = (AiMessage) restored.get(2);
        // simulate resume: answer the still-unanswered request (call_abc)
        restored.add(3 + 1, ToolExecutionResultMessage.from(pending.toolExecutionRequests().get(0),
                "{\"status\":\"REJECTED_BY_REVIEWER\",\"note\":\"amount too high\"}"));
        long requestCount = pending.toolExecutionRequests().size();
        long resultCount = restored.stream().filter(m -> m instanceof ToolExecutionResultMessage).count();
        assertEquals(requestCount, resultCount);
    }
```

Run: `./mvnw test -Dtest=ChatTranscriptCodecTest -q` — Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/labs/eddi/engine/hitl/tools/ChatTranscriptCodec.java src/test/java/ai/labs/eddi/engine/hitl/tools/ChatTranscriptCodecTest.java
git commit -m "feat(hitl): prove langchain4j transcript round-trip for tool-pause resume (ChatTranscriptCodec)"
```

---

### Task 2: Pattern engine (`ToolApprovalPatterns`) + gate classifier (`ToolApprovalGate`)

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatterns.java`
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGate.java`
- Create: `src/main/java/ai/labs/eddi/configs/hitl/model/ToolApprovalsConfig.java`
- Test: `src/test/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatternsTest.java`, `src/test/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGateTest.java`

**Interfaces:**
- Produces: `ToolApprovalsConfig` (Jackson POJO; fields below — used by Task 3 in both AgentConfiguration and LlmConfiguration).
- Produces: `ToolApprovalPatterns.compile(String glob)` → `java.util.regex.Pattern` (anchored, `Pattern.quote`d segments, `*`→`.*` — ReDoS-safe); `ToolApprovalPatterns.validate(String pattern)` → `Optional<String>` error message; `ToolApprovalPatterns.KNOWN_SOURCES = List.of("builtin","http","mcp","a2a","dynamic","memory","recall")`.
- Produces: `ToolApprovalGate.classify(List<ToolExecutionRequest> batch, Map<String,String> toolSources, ToolApprovalsConfig cfg, Set<String> clearedCallIds)` → `GateResult(List<ToolExecutionRequest> gated, List<ToolExecutionRequest> allowed, Map<String,String> gateReasonByCallId)`.

**`ToolApprovalsConfig` fields (Jackson, all nullable → defaults applied by readers):**

```java
/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy; // verified package

import java.util.List;

/**
 * Config-driven tool approval gating (tool-level HITL). Used in two homes:
 * agent-level default ({@code AgentConfiguration.HitlConfig.toolApprovals})
 * and per-task override ({@code LlmConfiguration.Task.toolApprovals} —
 * full-replace, no merging).
 */
public class ToolApprovalsConfig {
    /** Glob patterns of tools requiring approval, e.g. "mcp:*", "delete_*". */
    private List<String> requireApproval;
    /** Exemptions — always beat requireApproval. */
    private List<String> exempt;
    /** Max tool pauses per turn (default 3, valid 1..10). Fail-closed at cap. */
    private Integer maxPausesPerTurn;
    /** Max consecutive system (timeout) auto-approvals per turn (default 2, 0..10). */
    private Integer maxAutoApprovalsPerTurn;
    /** WAIT_FOR_HUMAN (default) | AUTO_REJECT | ABORT — fires on identical-fingerprint re-pause after a system decision. */
    private String onNoProgress;
    /** Tool-pause timeout overrides; see effective-policy rule in Task 10. */
    private String approvalTimeout;
    private HitlTimeoutPolicy timeoutPolicy;
    /** Approver-facing reason; "{toolNames}" placeholder is substituted. ≤500 chars. */
    private String pauseReason;
    /** End-user-facing message stored as public output at pause commit. "{toolNames}" substituted. ≤500 chars. */
    private String pendingMessage;
    /** REJECT (default) | INBOX (reserved, 400 in v1) — behavior inside group turns. */
    private String inGroupTurns;

    // plain getters/setters for every field (Jackson) — generate all 10 pairs
}
```

- [ ] **Step 1: Write the failing tests**

`ToolApprovalPatternsTest`:

```java
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalPatternsTest {

    @Test
    void star_matchesAnyRun_includingEmpty() {
        assertTrue(ToolApprovalPatterns.compile("delete_*").matcher("delete_account").matches());
        assertTrue(ToolApprovalPatterns.compile("delete_*").matcher("delete_").matches());
        assertFalse(ToolApprovalPatterns.compile("delete_*").matcher("undelete_x").matches());
        assertTrue(ToolApprovalPatterns.compile("*").matcher("anything").matches());
    }

    @Test
    void literalDotsAreQuoted_notRegexMeta() {
        assertFalse(ToolApprovalPatterns.compile("a.b").matcher("axb").matches());
        assertTrue(ToolApprovalPatterns.compile("a.b").matcher("a.b").matches());
    }

    @Test
    void matchingIsCaseSensitive() {
        assertFalse(ToolApprovalPatterns.compile("Delete_*").matcher("delete_account").matches());
    }

    @Test
    void validate_rejectsBlank_illegalChars_overlong_unknownSource() {
        assertTrue(ToolApprovalPatterns.validate("").isPresent());
        assertTrue(ToolApprovalPatterns.validate("has space").isPresent());
        assertTrue(ToolApprovalPatterns.validate("x".repeat(257)).isPresent());
        var err = ToolApprovalPatterns.validate("mpc:read_*");
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("mcp"), "should suggest 'mcp' for typo 'mpc': " + err.get());
        assertTrue(ToolApprovalPatterns.validate("mcp:read_*").isEmpty());
        assertTrue(ToolApprovalPatterns.validate("plainName").isEmpty());
    }
}
```

`ToolApprovalGateTest`:

```java
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalGateTest {

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ToolApprovalsConfig cfg(List<String> require, List<String> exempt) {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(require);
        c.setExempt(exempt);
        return c;
    }

    @Test
    void nullOrEmptyConfig_gatesNothing() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        assertTrue(gate.classify(batch, Map.of(), null, Set.of()).gated().isEmpty());
        assertTrue(gate.classify(batch, Map.of(), cfg(null, null), Set.of()).gated().isEmpty());
        assertTrue(gate.classify(batch, Map.of(), cfg(List.of(), List.of("x")), Set.of()).gated().isEmpty());
    }

    @Test
    void sourceQualifiedAndBareName_bothMatch() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "read_file"), req("2", "getCurrentDateTime"));
        var sources = Map.of("read_file", "mcp", "getCurrentDateTime", "builtin");
        var result = gate.classify(batch, sources, cfg(List.of("mcp:*"), null), Set.of());
        assertEquals(1, result.gated().size());
        assertEquals("read_file", result.gated().get(0).name());
        assertEquals("mcp:*", result.gateReasonByCallId().get("1"));
    }

    @Test
    void exemptBeatsRequire() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "read_file"), req("2", "write_file"));
        var sources = Map.of("read_file", "mcp", "write_file", "mcp");
        var result = gate.classify(batch, sources, cfg(List.of("mcp:*"), List.of("mcp:read_*")), Set.of());
        assertEquals(List.of("write_file"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void clearedCallIds_neverReGated() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        var result = gate.classify(batch, Map.of("delete_account", "http"), cfg(List.of("delete_*"), null), Set.of("1"));
        assertTrue(result.gated().isEmpty());
    }

    @Test
    void unknownSourceForTool_stillMatchesBareName_failSafe() {
        var gate = new ToolApprovalGate();
        var batch = List.of(req("1", "delete_account"));
        // tool missing from the sources map entirely — bare-name match must still gate
        var result = gate.classify(batch, Map.of(), cfg(List.of("delete_*"), null), Set.of());
        assertEquals(1, result.gated().size());
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./mvnw test -Dtest='ToolApprovalPatternsTest,ToolApprovalGateTest' -q` → COMPILATION ERROR.

- [ ] **Step 3: Implement**

`ToolApprovalPatterns`:

```java
package ai.labs.eddi.engine.hitl.tools;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Glob compilation + save-time validation for tool approval patterns. */
public final class ToolApprovalPatterns {
    public static final List<String> KNOWN_SOURCES = List.of("builtin", "http", "mcp", "a2a", "dynamic", "memory", "recall");
    private static final Pattern LEGAL_CHARS = Pattern.compile("[A-Za-z0-9_\\-.:*]+");
    private static final int MAX_LENGTH = 256;

    private ToolApprovalPatterns() {
    }

    /** '*' is the only wildcard; every other char is a quoted literal (ReDoS-safe). */
    public static Pattern compile(String glob) {
        String[] parts = glob.split("\\*", -1);
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".*");
            if (!parts[i].isEmpty()) sb.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(sb.append("$").toString());
    }

    /** Returns an actionable error message, or empty if the pattern is valid. */
    public static Optional<String> validate(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return Optional.of("pattern must not be blank");
        }
        if (pattern.length() > MAX_LENGTH) {
            return Optional.of("pattern exceeds " + MAX_LENGTH + " characters");
        }
        if (!LEGAL_CHARS.matcher(pattern).matches()) {
            return Optional.of("pattern '" + pattern + "' contains illegal characters — allowed: A-Za-z0-9_-.:* (tool names never contain spaces)");
        }
        int colon = pattern.indexOf(':');
        if (colon > 0) {
            String prefix = pattern.substring(0, colon);
            if (!prefix.contains("*") && !KNOWN_SOURCES.contains(prefix)) {
                return Optional.of("unknown tool source prefix '" + prefix + ":' in pattern '" + pattern + "'"
                        + suggestionFor(prefix) + " — known sources: " + String.join(", ", KNOWN_SOURCES));
            }
        }
        return Optional.empty();
    }

    private static String suggestionFor(String prefix) {
        for (String known : KNOWN_SOURCES) {
            if (levenshtein(prefix, known) <= 2) {
                return " — did you mean '" + known + ":'?";
            }
        }
        return "";
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }
}
```

`ToolApprovalGate`:

```java
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Splits an LLM tool-call batch into gated (requires human approval) and
 * allowed calls. Precedence: (P1) exempt always beats requireApproval;
 * (P2) any pattern match suffices; (P3) empty/absent requireApproval = gate
 * fully inactive. Patterns are tested against "source:name" first, then the
 * bare dispatch name (fail-safe: a tool with unknown source still matches
 * bare-name patterns).
 */
public class ToolApprovalGate {

    public record GateResult(List<ToolExecutionRequest> gated,
                             List<ToolExecutionRequest> allowed,
                             Map<String, String> gateReasonByCallId) {
    }

    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               ToolApprovalsConfig cfg, Set<String> clearedCallIds) {
        if (cfg == null || cfg.getRequireApproval() == null || cfg.getRequireApproval().isEmpty()) {
            return new GateResult(List.of(), List.copyOf(batch), Map.of());
        }
        List<CompiledPattern> require = compileAll(cfg.getRequireApproval());
        List<CompiledPattern> exempt = compileAll(cfg.getExempt());

        List<ToolExecutionRequest> gated = new ArrayList<>();
        List<ToolExecutionRequest> allowed = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>();
        for (ToolExecutionRequest request : batch) {
            if (request.id() != null && clearedCallIds.contains(request.id())) {
                allowed.add(request); // already approved by a human — never re-gate
                continue;
            }
            String source = toolSources.get(request.name());
            String qualified = source != null ? source + ":" + request.name() : null;
            if (firstMatch(exempt, qualified, request.name()) != null) {
                allowed.add(request);
                continue;
            }
            CompiledPattern match = firstMatch(require, qualified, request.name());
            if (match != null) {
                gated.add(request);
                if (request.id() != null) {
                    reasons.put(request.id(), match.raw());
                }
            } else {
                allowed.add(request);
            }
        }
        return new GateResult(gated, allowed, reasons);
    }

    private record CompiledPattern(String raw, Pattern pattern) {
    }

    private static List<CompiledPattern> compileAll(List<String> globs) {
        if (globs == null) return List.of();
        return globs.stream().map(g -> new CompiledPattern(g, ToolApprovalPatterns.compile(g))).toList();
    }

    private static CompiledPattern firstMatch(List<CompiledPattern> patterns, String qualified, String bare) {
        for (CompiledPattern cp : patterns) {
            if ((qualified != null && cp.pattern().matcher(qualified).matches()) || cp.pattern().matcher(bare).matches()) {
                return cp;
            }
        }
        return null;
    }
}
```

Also create `ToolApprovalsConfig` exactly as specified in the Interfaces block above (all 10 getter/setter pairs; `HitlTimeoutPolicy` is `ai.labs.eddi.configs.hitl.HitlTimeoutPolicy` — verified).

Performance note: `classify` compiles per call. Acceptable for v1 (a handful of patterns, tool batches ≤ ~10); if the executor wants, cache `List<CompiledPattern>` keyed by the config object identity inside `ToolApprovalGate` — NOT static state.

- [ ] **Step 4: Run** — `./mvnw test -Dtest='ToolApprovalPatternsTest,ToolApprovalGateTest' -q` → PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatterns.java src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGate.java src/main/java/ai/labs/eddi/configs/hitl/model/ToolApprovalsConfig.java src/test/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatternsTest.java src/test/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGateTest.java
git commit -m "feat(hitl): tool approval pattern engine and batch gate classifier"
```

---

### Task 3: Config homes + save-time validation

**Files:**
- Modify: `src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java` (HitlConfig, ~line 854)
- Modify: `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` (Task class, ~line 64)
- Modify: `src/main/java/ai/labs/eddi/configs/hitl/HitlConfigValidation.java`
- Modify: the LLM config store create/update seam — **`configs/llm/mongo/LlmStore.java`** (and `configs/llm/rest/RestLlmStore.java`), mirroring how `AgentStore.create` :48 / `update` :57 invoke `HitlConfigValidation` (see `HitlConfigValidationWiringTest`)
- Test: `src/test/java/ai/labs/eddi/configs/hitl/HitlConfigValidationToolApprovalsTest.java`

**Interfaces:**
- Consumes: `ToolApprovalsConfig`, `ToolApprovalPatterns.validate` (Task 2).
- Produces: `AgentConfiguration.HitlConfig.getToolApprovals()` / `setToolApprovals(ToolApprovalsConfig)`; `LlmConfiguration.Task.getToolApprovals()` / `setToolApprovals(ToolApprovalsConfig)`; `HitlConfigValidation.validateToolApprovals(ToolApprovalsConfig cfg, String fieldPath)` → throws **`java.lang.IllegalArgumentException`** (the exact type the existing `HitlConfigValidation` rules throw at :39/:58/:68/:73 — reuse it, do not invent a custom exception). Save-time **warnings** (non-fatal, e.g. the AUTO_APPROVE-inheritance case) are **WARN-logged only** in v1 — the existing stores do not carry a warnings channel in their responses, so do NOT invent a new response envelope; a WARN log is the contract.

**Validation rules (each with the exact message shown):**

| Rule | Response |
|---|---|
| any pattern fails `ToolApprovalPatterns.validate` | 400: `"hitlConfig.toolApprovals.requireApproval[2]: <validate() message>"` |
| identical string in both `requireApproval` and `exempt` | 400: `"pattern 'delete_*' appears in both requireApproval and exempt; exempt would win — remove one"` |
| exact duplicate within one list | 400: `"duplicate pattern 'mcp:*' in requireApproval"` |
| `exempt` non-empty while `requireApproval` empty/absent | 400: `"exempt has no effect without requireApproval patterns"` |
| `maxPausesPerTurn` outside 1..10 | 400 naming the valid range |
| `maxAutoApprovalsPerTurn` outside 0..10 | 400 naming the valid range |
| `onNoProgress` not in `WAIT_FOR_HUMAN`/`AUTO_REJECT`/`ABORT` | 400 listing valid values |
| `inGroupTurns` == `"INBOX"` | 400: `"inGroupTurns=INBOX is reserved for a future version; use REJECT"` |
| `inGroupTurns` not in `REJECT`/`INBOX` | 400 listing valid values |
| `approvalTimeout` present but not a positive ISO-8601 duration | 400 (reuse the existing duration validation the outer hitlConfig already has) |
| `pauseReason`/`pendingMessage` > 500 chars | 400 (mirror the existing pauseReason length rule) |
| `toolApprovals.timeoutPolicy` absent AND outer `hitlConfig.timeoutPolicy == AUTO_APPROVE` | **WARNING** (not 400): `"agent-level AUTO_APPROVE does not apply to tool approvals; tool pauses will WAIT_INDEFINITELY unless toolApprovals.timeoutPolicy is set explicitly"` |

- [ ] **Step 1:** Read `HitlConfigValidation.java` and `HitlConfigValidationWiringTest.java` fully. Note the exception type, message prefixing style, and exactly where `AgentStore` invokes it.
- [ ] **Step 2:** Write the failing test `HitlConfigValidationToolApprovalsTest` — one `@Test` per table row above, each building a `ToolApprovalsConfig`, calling `validateToolApprovals`, and asserting the thrown message contains the quoted phrase (use the real exception type discovered in Step 1). Plus one happy-path test: a full valid config passes.
- [ ] **Step 3:** Run: `./mvnw test -Dtest=HitlConfigValidationToolApprovalsTest -q` → COMPILATION ERROR / failures.
- [ ] **Step 4:** Implement: add the `toolApprovals` field + getter/setter to `AgentConfiguration.HitlConfig` (after `pauseReason`, keeping the null-safe-setter style of `timeoutPolicy`); add the same to `LlmConfiguration.Task`; implement `validateToolApprovals` in `HitlConfigValidation` per the table; call it from the existing agent-store validation path (where the outer hitlConfig is validated) and add the equivalent invocation to the LLM store create/update path found in Step 1's grep.
- [ ] **Step 5:** Run: `./mvnw test -Dtest='HitlConfigValidationToolApprovalsTest,HitlConfigValidationTest,HitlConfigValidationWiringTest' -q` → PASS, including all pre-existing tests.
- [ ] **Step 6:** ZIP import: confirm `RestImportService` funnels agent configs through the same store create/update path (it does for the outer hitlConfig per docs/hitl.md:83) — if the LLM configs imported via ZIP bypass the LlmStore validation seam, add the `validateToolApprovals` call to the import path too, with a test.
- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java src/main/java/ai/labs/eddi/configs/hitl/HitlConfigValidation.java src/test/java/ai/labs/eddi/configs/hitl/HitlConfigValidationToolApprovalsTest.java
# plus the LlmStore/import files actually touched in steps 4/6
git commit -m "feat(hitl): toolApprovals config on agent hitlConfig and llm task, save-time validation"
```

---

### Task 4: Memory model — pause type, pending batch, snapshot round-trip

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/memory/model/PendingToolCallBatch.java`
- Modify: `src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java` (+ its interface `IConversationMemory`), `src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java`, and **`src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java`** — the both-directions copy lives here: memory→snapshot at **:69-74**, snapshot→memory at **:118-123** (the six bookmark fields are copied there; add the two new fields to both). Also confirm `PostgresConversationMemoryStore` has no independent snapshot-field copy path that needs them (grep `setHitlPausedWorkflowId` to be sure; if it does, patch it too with a test).
- Modify: `src/main/java/ai/labs/eddi/engine/lifecycle/exceptions/ConversationPauseException.java`
- Test: `src/test/java/ai/labs/eddi/engine/memory/model/PendingToolCallBatchSnapshotTest.java`

**Interfaces (produces — used by every later task):**

```java
package ai.labs.eddi.engine.memory.model;

import java.util.List;
import java.util.Map;

/**
 * Durable record of an LLM tool-call batch interrupted by a HITL tool pause.
 * Persisted as a first-class typed field on ConversationMemorySnapshot
 * (planning Invariant 1 — never Object-typed step data).
 */
public class PendingToolCallBatch {
    // Size caps (bytes unless noted) — single source of truth for every writer.
    public static final int TRANSCRIPT_MAX_BYTES_DEFAULT = 2_000_000;
    public static final int ARGS_RAW_MAX_BYTES = 262_144;
    public static final int ARGS_REDACTED_MAX_BYTES = 32_768;
    public static final int AMENDED_ARGS_MAX_BYTES = 32_768;
    public static final int TRACE_ENTRY_MAX_BYTES = 65_536;

    public static class PendingToolCall {
        private String callId;          // provider id, or "gen-" + UUID when absent
        private String toolName;
        private String source;          // builtin|http|mcp|a2a|dynamic|memory|recall|unknown
        private String argumentsRaw;    // capped; needed for execution + fallback rebuild
        private boolean argsTruncated;  // true => call may NOT be executed on resume (auto-error result)
        private String argumentsRedacted; // SecretRedactionFilter'd, capped — the ONLY field approver surfaces read
        private String gateReason;      // the matched pattern, e.g. "mcp:*"
        // getters/setters for all 7
    }

    private String pauseEpoch;              // UUID per pause — journal key component
    private String llmTaskId;               // LlmConfiguration.Task.getId() — identity binding
    private int llmTaskIndex;               // index within llmConfig.tasks() at pause time
    private String workflowId;              // informational
    private String chatTranscriptJson;      // ChatTranscriptCodec output; null when omitted
    private boolean transcriptOmitted;
    private List<PendingToolCall> calls;
    private List<String> executedUngatedCallNames; // approver visibility: side effects that already ran
    private int iterationIndex;             // loop iteration at pause — budget continuity
    private List<String> activatedToolNames;   // LAZY-mode reactivation
    private List<Map<String, Object>> traceSoFar; // per-entry result capped
    private String fingerprint;             // sha256(sorted toolName + "|" + arguments)
    private int autoApproveCount;           // consecutive system approvals, carried across re-pauses
    private int pauseCountThisTurn;         // enforced against maxPausesPerTurn
    // getters/setters for all 14
}
```

- `IConversationMemory`/`ConversationMemory` gain: `String getHitlPauseType()`/`setHitlPauseType(String)` (values `null`→RULE, `"RULE"`, `"TOOL_CALL"`); `PendingToolCallBatch getHitlPendingToolCalls()`/`setHitlPendingToolCalls(...)`; and a **transient, never-snapshotted** `HitlDecision getHitlResumeDecision()`/`setHitlResumeDecision(...)` (used only during the same-JVM resume call-chain; mark it clearly and exclude from snapshot conversion).
- `ConversationMemorySnapshot` gains persisted mirrors: `hitlPauseType` (String), `hitlPendingToolCalls` (PendingToolCallBatch). Jackson handles nested POJOs — no custom codec.

**Clearing-method contract (defined ONCE here; Tasks 5/8/9/14 use these exact names — no overloads, no other variants):**
- `Conversation.clearHitlBookmark()` — **unchanged from today**: clears ONLY the six bookmark fields (`hitlPausedWorkflowId`, `hitlPausedAbsoluteTaskIndex`, `hitlPausedAt`, `hitlPauseReason`, `hitlTimeoutPolicy`, `hitlApprovalTimeout`). It does **NOT** touch `hitlPauseType`, `hitlPendingToolCalls`, or `hitlResumeDecision`.
- `Conversation.clearToolPauseState()` — **new**: nulls `hitlPauseType`, `hitlPendingToolCalls`, and the transient `hitlResumeDecision`.
- Rule-pause resume calls `clearHitlBookmark()` (existing behavior) and, at pause-commit for a rule pause, `clearToolPauseState()` is also called defensively (Task 5 Step 5 RULE branch). Tool-pause resume calls `clearHitlBookmark()` immediately but **defers `clearToolPauseState()` until `LlmTask` consumes the batch** (Task 9) and on every terminal resume exit (`resume()` `finally`, Task 8) and from cancel/retention (Task 14).
- `ConversationPauseException` gains `PauseOrigin` enum:

```java
public enum PauseOrigin { RULE, TOOL_CALL }
```

with a new 4-arg constructor `(String pausedWorkflowId, int pausedAbsoluteTaskIndex, String pauseReason, PauseOrigin origin)`; the existing 3-arg constructor delegates with `PauseOrigin.RULE` — zero call-site changes.

- [ ] **Step 1: Write the failing test** — `PendingToolCallBatchSnapshotTest`: build a `ConversationMemorySnapshot`, set `hitlPauseType="TOOL_CALL"` and a fully-populated batch (2 calls, one `argsTruncated`), serialize with the same `ObjectMapper` configuration the Mongo store uses (find it: `grep -rn "ObjectMapper" src/main/java/ai/labs/eddi/engine/memory/ src/main/java/ai/labs/eddi/datastore/serialization/` and reuse `IJsonSerialization` if that is the snapshot path), deserialize, assert field-for-field equality. Second test: deserializing a snapshot JSON **without** the new fields yields `null` pauseType and `null` batch (backward compat). Third test: `new ConversationPauseException("wf", 3, "r")` has `getPauseOrigin() == RULE`.
- [ ] **Step 2:** Run → COMPILATION ERROR.
- [ ] **Step 3:** Implement all model changes. In the snapshot conversion code, copy `hitlPauseType` and `hitlPendingToolCalls` in BOTH directions everywhere the existing six bookmark fields are copied (there are at least two directions: memory→snapshot on store, snapshot→memory on load — `grep "setHitlPausedWorkflowId"` finds every site). Do NOT copy `hitlResumeDecision`.
- [ ] **Step 4:** Update `ChatTranscriptCodecTest` from Task 1 to import `PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT` and delete its inlined constant.
- [ ] **Step 5:** Run: `./mvnw test -Dtest='PendingToolCallBatchSnapshotTest,ChatTranscriptCodecTest,ConversationStateHitlTest' -q` → PASS. The locally-runnable round-trip check is `PendingToolCallBatchSnapshotTest` (pure Jackson). **`MongoConversationMemoryStoreTest` / `PostgresConversationMemoryStoreTest` are `@Testcontainers` ITs (need Docker) — do NOT run them locally; they run in CI.** The new nullable fields must not break them — CI will confirm.
- [ ] **Step 6: Commit** — `feat(hitl): pause-type discriminator and pending tool-call batch on conversation memory`

---

### Task 5: The pause path — gate hook in AgentOrchestrator, signal plumbing, pause commit

This is the heart of the feature's first half. After this task, a gated tool call durably pauses the conversation; resume is Task 8/9.

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalRequiredException.java`
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java`
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/AgentExecutionHelper.java`
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java`
- Modify: `src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java`
- Modify: `src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java`
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java` (pass effective config + stale-batch clear)
- Test: `src/test/java/ai/labs/eddi/modules/llm/impl/AgentOrchestratorToolPauseTest.java`, `src/test/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManagerToolPauseTest.java`

**Interfaces:**
- Produces: `ToolApprovalRequiredException extends RuntimeException` with fields `String pauseReason`, `PendingToolCallBatch batch` (+getters).
- Produces (on AgentOrchestrator, package-private): `Map<String,String> toolSources` built during tool registration; `executeSingleToolCall(ToolExecutionRequest, ...)` extraction of the existing per-request body (`AgentOrchestrator.java:352-434`: checkpoint, trace, budget check, tenant check, executor dispatch, truncation, trace-result — extracted verbatim so the live loop and Task 9's resume path share it).
- Consumes: `ToolApprovalGate`, `ChatTranscriptCodec`, `PendingToolCallBatch`, `ToolApprovalsConfig`.

**Sub-steps:**

- [ ] **Step 1: Effective config resolution.** In `LlmTask.executeTask`, resolve `ToolApprovalsConfig effectiveToolApprovals = task.getToolApprovals() != null ? task.getToolApprovals() : memory.getAgentToolApprovalsConfig()`. The agent-level config reaches memory via a **transient, non-snapshotted carrier** on `IConversationMemory`/`ConversationMemory` — `getAgentToolApprovalsConfig()`/`setAgentToolApprovalsConfig(ToolApprovalsConfig)` — added in this task, **exactly mirroring the proven `userMemoryConfig` precedent** (`ConversationMemory.java:45` transient field, :200 getter, :205 setter; set in `Conversation.init` :92 from `propertiesHandler`). ⚠️ **Do NOT claim `Conversation` has the `AgentConfiguration` — it does not** (its ctor takes only `executableWorkflows`, memory, `IPropertiesHandler`, renderer). Pick one carrier wiring:
  - **(a) service-side (recommended, least surface):** in `ConversationService`, wherever the pinned agent config is already read for a turn (the same `readAgentConfigPinned()` that `populateHitlTimeoutBookmark` uses at :1542), call `memory.setAgentToolApprovalsConfig(agentCfg.getHitlConfig() != null ? agentCfg.getHitlConfig().getToolApprovals() : null)` at conversation start / say-time so it is present before `LlmTask` runs; OR
  - **(b) properties-handler route:** extend `IPropertiesHandler` with a `getToolApprovalsConfig()` accessor and populate it in `ConversationService.createPropertiesHandler` (4 call sites: :218, :427, :584, :1243), then set it in `Conversation.init` alongside `setUserMemoryConfig` — this is the exact shape the `userMemoryConfig` carrier already uses (`AgentStoreClientLibrary.java:55-56` copies `userMemoryConfig` off `AgentConfiguration`; do the same for `toolApprovals`).

  Feature flag: also check `eddi.hitl.tool.enabled` (`@ConfigProperty`, default `true`, injected into `LlmTask`) — when false, effective config is `null` (gate inert; rolling-upgrade control).
  Pass `effectiveToolApprovals` into `agentOrchestrator.executeIfToolsEnabled(...)` as a new parameter (both call sites: `LlmTask:415` cascade-disabled branch and `:433` standard branch; also thread through `CascadingModelExecutor.execute` → its internal `executeIfToolsEnabled` call).
- [ ] **Step 2: Build `toolSources` during registration.** In `executeWithTools`, alongside every `toolExecutors.put(...)`: built-in loop (:236-242) → `"builtin"`; httpcall merge (:254-257) → `"http"`; mcpcall merge (:260-263) → `"mcp"`; A2A merge (:266-269) → `"a2a"`; and inside `collectEnabledTools`' registration of UserMemoryTool → `"memory"`, ConversationRecallTool → `"recall"`, dynamic tools (createSubAgent/converseWithAgent/findAgentsByCapability/teardownAgent) → `"dynamic"`. (If those latter registrations happen via the same `@Tool`-reflection loop, tag by tool-name membership instead: after the loop, overwrite sources for the known dynamic/memory/recall names. MCP-server tools configured on the task (`McpServerConfig`) also → `"mcp"`.) Missing entries are tolerated — the gate falls back to bare-name matching (fail-safe, tested in Task 2).
- [ ] **Step 3: Gate hook.** In `executeWithTools`, immediately after `if (aiMessage.hasToolExecutionRequests()) {` (:350), insert the batch classification:

```java
if (aiMessage.hasToolExecutionRequests()) {
    var gateResult = toolApprovalGate.classify(aiMessage.toolExecutionRequests(), toolSources,
            effectiveToolApprovals, clearedCallIds);
    if (!gateResult.gated().isEmpty()) {
        int pausesSoFar = readToolPauseCount(memory); // step data "hitl:tool_pause_count", 0 when absent
        if (pausesSoFar >= maxPausesPerTurn(effectiveToolApprovals)) {
            // Fail-closed: no pause, no execution — the model is told to stop asking.
            for (ToolExecutionRequest gatedReq : gateResult.gated()) {
                currentMessages.add(ToolExecutionResultMessage.from(gatedReq,
                        "{\"status\":\"DENIED\",\"reason\":\"approval-pause limit for this turn reached; do not retry\"}"));
                traceError(trace, gatedReq, "hitl_pause_cap");
            }
            // ungated calls still execute below — restructure so the per-request loop
            // iterates gateResult.allowed() instead of aiMessage.toolExecutionRequests()
        } else {
            // 1) execute the ungated calls of this batch normally (they were going to run anyway)
            for (ToolExecutionRequest allowedReq : gateResult.allowed()) {
                executeSingleToolCall(allowedReq, /* existing per-request params */ ...);
            }
            // 2) snapshot + persist the pending batch, then abort the loop
            PendingToolCallBatch batch = buildPendingBatch(currentMessages, gateResult, task, memory,
                    iterationIndex, activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1);
            memory.setHitlPendingToolCalls(batch);
            incrementToolPauseCount(memory);
            throw new ToolApprovalRequiredException(buildPauseReason(effectiveToolApprovals, gateResult), batch);
        }
    }
    for (ToolExecutionRequest toolRequest : gateResult.allowed()) { // was: aiMessage.toolExecutionRequests()
        ... existing per-request body, now extracted as executeSingleToolCall(...) ...
    }
}
```

`buildPendingBatch` responsibilities (all in AgentOrchestrator, package-private for testability): `pauseEpoch = UUID.randomUUID().toString()`; `llmTaskId = task.getId()`, `llmTaskIndex` = position of `task` in the llmConfig task list (thread it in from LlmTask as a parameter); serialize `currentMessages` via `ChatTranscriptCodec` (cap from `eddi.hitl.tool.transcript-max-bytes`, default constant); per gated call build `PendingToolCall` with `callId = toolRequest.id() != null ? toolRequest.id() : "gen-" + UUID.randomUUID()`, raw args capped at `ARGS_RAW_MAX_BYTES` (over → `argsTruncated=true`), `argumentsRedacted = SecretRedactionFilter.redact(args)` capped at `ARGS_REDACTED_MAX_BYTES` (find the redaction utility: `grep -rn "class SecretRedactionFilter" src/main/java` and use its actual API), `gateReason` from the gate result; `executedUngatedCallNames` = names of this batch's allowed calls (executed in step 1 above); `iterationIndex = i`; `traceSoFar` = deep copy of `trace` with each entry's `result` string capped at `TRACE_ENTRY_MAX_BYTES`; `fingerprint = sha256Hex(sortedJoin(gated: name + "|" + arguments))`; `autoApproveCount` carried from a prior batch if this pause follows a resume in the same turn (thread through — Task 10 wires it), else 0; `pauseCountThisTurn = pausesSoFar + 1`.
`buildPauseReason`: `cfg.getPauseReason()` with `{toolNames}` replaced by the comma-joined gated names, falling back to `"Tool call requires approval: " + names`. Redact + cap at 500 chars.
`clearedCallIds`: a `Set<String>` parameter of `executeWithTools`, empty for live turns (Task 9 passes approved ids on resume).

- [ ] **Step 4: Signal plumbing — the three swallow-traps.**
  1. `AgentExecutionHelper.executeWithRetry` — at the very top of `catch (Exception e)` (line 53): `if (e instanceof ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException tare) { throw tare; }` (unchecked → compiles without signature change).
  2. `CascadingModelExecutor` — same guard at the top of `catch (Exception e)` (line ~196). The `ExecutionException` unwrap at :253-259 already rethrows the cause, which then reaches :196.
  3. `LifecycleManager.executeTaskRange` — at the top of `catch (LifecycleException | RuntimeException e)` (line 342), BEFORE strict-write failure handling and error counters:

```java
} catch (LifecycleException | RuntimeException e) {
    if (e instanceof ToolApprovalRequiredException tare) {
        // A HITL tool pause is not a task failure: no error counter, no
        // strict-write rollback — the partially-executed step data (incl. the
        // pending batch) must survive into the pause snapshot.
        taskSpan.setAttribute("eddi.hitl.pause", "tool_call");
        throw new ConversationPauseException(workflowId.getId(), indexOffset + index,
                tare.getPauseReason(), ConversationPauseException.PauseOrigin.TOOL_CALL);
    }
    ... existing body unchanged ...
```

Also audit every other `catch (Exception` / `catch (RuntimeException` / `catch (Throwable` between the gate throw-site and `LifecycleManager` and add the same one-line guard where reachable: run `grep -n "catch (Exception\|catch (RuntimeException\|catch (Throwable" src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java src/main/java/ai/labs/eddi/modules/llm/impl/AgentExecutionHelper.java` and inspect each hit: guards are needed ONLY where the catch encloses the tool loop or a call chain that reaches it (e.g. NOT the RAG catches at LlmTask:251/264 — they run pre-loop). Document each decision in a code comment at the catch site you modify.

- [ ] **Step 5: Pause commit.** `Conversation.pauseConversation` (:451-457) gains:

```java
private void pauseConversation(ConversationPauseException e) {
    setConversationState(ConversationState.AWAITING_HUMAN);
    conversationMemory.setHitlPausedWorkflowId(e.getPausedWorkflowId());
    conversationMemory.setHitlPausedAbsoluteTaskIndex(e.getPausedAbsoluteTaskIndex());
    conversationMemory.setHitlPausedAt(Instant.now());
    conversationMemory.setHitlPauseReason(e.getPauseReason());
    conversationMemory.setHitlPauseType(e.getPauseOrigin().name());
    if (e.getPauseOrigin() == ConversationPauseException.PauseOrigin.TOOL_CALL) {
        // End-user visibility: the turn aborted before output/templating tasks ran,
        // so without this the chat client renders NOTHING for the paused turn.
        String pending = resolvePendingMessage(conversationMemory); // toolApprovals.pendingMessage
                // with {toolNames} substituted; default:
                // "This action requires human approval before it can proceed. You will
                //  receive the result once a reviewer decides."
        var pendingData = new Data<>(MemoryKeys.OUTPUT_PREFIX, List.of(pending));
        pendingData.setPublic(true);
        conversationMemory.getCurrentStep().storeData(pendingData);
        conversationMemory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(pending));
    } else {
        // A RULE pause must never carry a stale tool batch (e.g. gate tripped
        // earlier in the same turn on a path that recovered) — belt and braces.
        conversationMemory.setHitlPendingToolCalls(null);
    }
}
```

(Mirror the exact `Data`/output pattern of the REJECTED path at :493-496.) The RULE branch above calls `clearToolPauseState()` (defined in Task 4) to defensively null any stray tool batch. **Do NOT change `clearHitlBookmark()`'s contract** — it keeps clearing only the six bookmark fields (Task 4 contract). Add the new `clearToolPauseState()` method to `Conversation` in this task.

- [ ] **Step 6: Stale-batch hygiene.** At the start of each turn (find where `startNextStep`/say-path turn setup runs in `Conversation`), clear any leftover `hitlPendingToolCalls`/`hitlPauseType` when state is not AWAITING_HUMAN (defends against a crash that persisted a batch without the pause committing, and against future code paths that error after the gate trips). One line + WARN log when a stale batch is actually cleared.
- [ ] **Step 7: pauseReason override scope.** In `ConversationService.populateHitlTimeoutBookmark` (:1534-1556): apply the agent-level `hitlConfig.pauseReason` override **only when** `snapshot.hitlPauseType` is null/`RULE` — a TOOL_CALL pause keeps its tool-specific reason (with tool names) built at gate time.
- [ ] **Step 8: Tests.**
  `AgentOrchestratorToolPauseTest` (Mockito; mock `ChatModel` returning an `AiMessage` with two tool requests, one matching `requireApproval`):
  - gated call never reaches its executor; ungated call in the same batch executes exactly once (verify mock executor invocations)
  - thrown `ToolApprovalRequiredException` carries a batch with: correct callIds/names/sources/gateReason, serialized transcript containing the AiMessage, `executedUngatedCallNames` listing the ungated call, correct `llmTaskId`
  - `argsTruncated` set when arguments exceed the raw cap; `argumentsRedacted` never exceeds its cap
  - pause-cap reached → no throw, gated calls answered with DENIED envelope, loop continues
  - null/absent config → zero behavior change (model returns text → same result as before the change; use a spy to assert `classify` short-circuits)
  `LifecycleManagerToolPauseTest`:
  - a task throwing `ToolApprovalRequiredException` → `ConversationPauseException` with `PauseOrigin.TOOL_CALL` and absolute index `indexOffset + i`; error counter NOT incremented; strict-write rollback NOT invoked
  - `AgentExecutionHelper.executeWithRetry(() -> { throw new ToolApprovalRequiredException(...); }, task, "x")` rethrows the SAME instance, no retry sleep, no LifecycleException wrap
- [ ] **Step 9:** Run: `./mvnw test -Dtest='AgentOrchestratorToolPauseTest,LifecycleManagerToolPauseTest,LifecycleManagerHitlTest,ConversationHitlTest' -q` → PASS including the pre-existing HITL suites.
- [ ] **Step 10:** `./mvnw compile -q` then commit: `feat(hitl): tool-approval gate pauses the LLM loop via the conversation pause machinery`

---

### Task 6: Write-ahead journal store (double-execution guard)

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/IHitlToolJournalStore.java`
- Create: `src/main/java/ai/labs/eddi/engine/hitl/tools/mongo/HitlToolJournalStore.java`
- Test: `src/test/java/ai/labs/eddi/engine/hitl/tools/mongo/HitlToolJournalStoreTest.java`

**Interfaces:**

```java
package ai.labs.eddi.engine.hitl.tools;

import java.util.Optional;

/**
 * Write-ahead journal for HITL-approved tool executions. Guarantees a human
 * approval is executed at most once, across pod crashes and re-approvals:
 * insert EXECUTING before running the tool, mark EXECUTED with the capped
 * result after. On resume, EXECUTED entries replay their stored result;
 * EXECUTING entries (crash mid-tool) yield an honest outcome-unknown.
 * Key includes pauseEpoch because providers may reuse tool-call ids across
 * different pauses in one conversation.
 */
public interface IHitlToolJournalStore {
    enum Status { EXECUTING, EXECUTED }

    record JournalEntry(String conversationId, String pauseEpoch, String callId,
                        String toolName, Status status, String resultCapped,
                        java.time.Instant executedAt, String decidedBy) {
    }

    /** @return true if this call claimed execution; false if an entry already exists (crashed or completed attempt). */
    boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy);

    void markExecuted(String conversationId, String pauseEpoch, String callId, String resultCapped);

    Optional<JournalEntry> find(String conversationId, String pauseEpoch, String callId);
}
```

Mongo impl: collection `hitltoolexecutionjournal`; unique compound index `(conversationId, pauseEpoch, callId)`; TTL index on `executedAt` with `eddi.hitl.tool.journal-retention` (default 30 days); `tryClaim` = insert with `status=EXECUTING`, return false on duplicate-key (`MongoWriteException`/`DuplicateKeyException` — catch and return false, don't leak it); `markExecuted` = update set status/result/executedAt. **Result cap 32 KB.** Follow the existing store pattern exactly: **create the indexes in the `@Inject` constructor** (mirror `AgentTriggerStore.java:50` for the unique index, or `MongoScheduleStore.java:98-113` for named `IndexOptions` + TTL — **no store in this repo uses `@PostConstruct` for index creation**); use the same Mongo client injection idiom (`MongoDatabase`/`MongoCollection` via CDI, as those stores do).

- [ ] **Step 1:** Write failing test (use the same test harness the existing Mongo store tests use — check `MongoConversationMemoryStoreTest` for whether it uses an embedded/fake Mongo or is mock-based; mirror it): claim → find returns EXECUTING; markExecuted → find returns EXECUTED with result; second `tryClaim` same key → false; same callId different pauseEpoch → true (independent claim).
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit: `feat(hitl): write-ahead journal for approved tool executions`

---

### Task 7: REST decision model — per-call verdicts + amendments

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/lifecycle/model/ToolCallDecision.java`
- Modify: `src/main/java/ai/labs/eddi/engine/lifecycle/model/HitlDecision.java`
- Modify: `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java` (pre-CAS validation in `resumeConversation`, ~line 1150)
- Test: `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceToolDecisionValidationTest.java`

**Interfaces:**

```java
package ai.labs.eddi.engine.lifecycle.model;

/** Per-tool-call verdict inside a HITL resume decision (TOOL_CALL pauses only). */
public class ToolCallDecision {
    private HitlDecision.HitlVerdict verdict; // required; case-insensitive via the existing @JsonCreator
    private String note;                      // ≤1024 chars
    private String amendedArguments;          // optional JSON object string, ≤32 KB; APPROVED only
    // getters/setters
}
```

`HitlDecision` gains `private Map<String, ToolCallDecision> toolDecisions;` (+getter/setter). Body examples:

```json
{ "verdict": "APPROVED" }                                     // approve ALL pending calls (works for both pause types)
{ "verdict": "REJECTED", "note": "not during quarter close" } // reject all
{ "verdict": "APPROVED", "note": "capped the amount",
  "toolDecisions": {
    "call_abc": { "verdict": "APPROVED", "amendedArguments": "{\"amount\":100}" },
    "call_def": { "verdict": "REJECTED", "note": "wrong account" } } }
```

**Validation (in `ConversationService.resumeConversation`, BEFORE the `compareAndSetState` CAS at :1164 — a bad request must never consume the pause; mirror the group precedent of validate-before-mutate):**

| Condition | Response |
|---|---|
| `toolDecisions` present but snapshot `hitlPauseType != "TOOL_CALL"` | 400 `"toolDecisions is only valid for tool-call pauses"` |
| unknown callId | 400 `"no pending tool call 'call_xyz'; pending: [call_abc, call_def]"` |
| per-call verdict missing | 400 |
| per-call note > 1024 | 400 |
| `amendedArguments` on a REJECTED call | 400 |
| `amendedArguments` not parseable as a JSON **object** or > `AMENDED_ARGS_MAX_BYTES` | 400 |
| `amendedArguments` for a call with `argsTruncated=true` | 400 `"call 'call_abc' was truncated at pause time and cannot be amended; approve or reject it as-is"` |
| per-call mixing requires top-level `verdict=APPROVED` (top-level REJECTED + any per-call APPROVED) | 400 (mirrors group taskApprovals semantics) |

Semantics: calls not listed in `toolDecisions` inherit the top-level verdict. NOTE: the pre-CAS read needs the snapshot — `resumeConversation` already loads state pre-CAS (:1154-1157); load the snapshot's pauseType/batch for validation the same way the 409-state-hint body does (read the existing code first; reuse its snapshot access, do not add a second load if one exists).

- [ ] **Step 1:** Failing test — one `@Test` per table row (Mockito on the store returning a snapshot with a TOOL_CALL batch of two pending calls), asserting 400-mapped exception type + message fragment AND that the CAS/state was never touched (verify no `compareAndSetState` interaction). Plus: valid mixed body passes validation.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Run + `./mvnw test -Dtest='ConversationServiceResumeTest,ConversationServiceHitlTest' -q` → all PASS (existing bodies unaffected).
- [ ] **Step 5:** Commit: `feat(hitl): per-tool-call verdicts and amended arguments on the resume decision`

---

### Task 8: Same-index re-entry — Conversation.resume branch + LlmTask resume mode

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java` (resume(), ~:460-566)
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java`
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java` (**add the `resumeToolLoop` signature STUB in this task** — body in Task 9)
- Test: `src/test/java/ai/labs/eddi/engine/runtime/internal/ConversationToolResumeTest.java`, extend `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceResumeTest.java` patterns into `ConversationServiceToolResumeTest`

> **Ordering note (build-green invariant):** Task 8's `LlmTask.executeResume` calls `agentOrchestrator.resumeToolLoop(...)`, whose BODY is written in Task 9. So Task 8 **first adds a package-private stub** to `AgentOrchestrator`: `ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch, HitlDecision decision, Map<String,Object> templateDataObjects) throws LifecycleException { throw new UnsupportedOperationException("implemented in Task 9"); }`. This keeps Task 8 compiling and its `LlmTaskResumeModeTest` green (the test mocks the orchestrator, so the stub is never actually hit). Task 9 replaces the stub body. The signature is defined identically in both tasks.

**Conversation.resume changes (surgical — read :460-566 before editing):**

1. After the decision-visibility writes (:469-485) and **before** the REJECTED short-circuit (:487): 

```java
boolean toolPause = "TOOL_CALL".equals(conversationMemory.getHitlPauseType());
if (decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED && !toolPause) {
    ... existing short-circuit body unchanged ...
}
```

(TOOL_CALL REJECTED falls through to re-entry — the model must answer the user gracefully; Task 9 turns the rejection into tool results.)

2. Resume index (:507):

```java
int resumeFromIndex = conversationMemory.getHitlPausedAbsoluteTaskIndex() + (toolPause ? 0 : 1);
```

3. For tool pauses, stash the decision for LlmTask **before** `clearHitlBookmark()`:

```java
if (toolPause) {
    conversationMemory.setHitlResumeDecision(decision);
}
```

Use the clearing-method contract from Task 4 (no overloads). On the tool-pause path, `clearHitlBookmark()` clears the six bookmark fields as usual, but the batch (`hitlPendingToolCalls`/`hitlPauseType`/`hitlResumeDecision`) MUST survive until `LlmTask` consumes it — so **do NOT call `clearToolPauseState()` here**. `clearToolPauseState()` is called: by `LlmTask` after successful batch consumption (Task 9 Step 4), and in `resume()`'s `finally` (:546-565) as a safety net — if the batch is still non-null and the final state is not `AWAITING_HUMAN` (i.e. not a fresh re-pause), clear it (covers the config-drift/degraded/error exits).

**LlmTask resume mode (`execute`, :175-215):**

```java
@Override
public void execute(IConversationMemory memory, Object component) throws LifecycleException {
    final var llmConfig = (LlmConfiguration) component;
    PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
    HitlDecision resumeDecision = memory.getHitlResumeDecision();
    boolean resumeMode = batch != null && resumeDecision != null;
    ...
    if (resumeMode) {
        executeResume(memory, llmConfig, batch, resumeDecision, currentStep, templateDataObjects);
        return;
    }
    ... existing body unchanged ...
}
```

`executeResume` (task-identity binding — judge mustFix):
1. Locate the task: `llmConfig.tasks().get(batch.getLlmTaskIndex())` guarded by bounds AND `Objects.equals(task.getId(), batch.getLlmTaskId())`. On mismatch (workflow/llm-config redeployed): **config-drift degradation** — clear tool-pause state, store a public output `"The pending approval could not be applied because the agent's configuration changed. No gated action was executed."`, audit `hitl.tool.config_drift` (via the audit collector if present), WARN log, `return` (pipeline continues with the tasks after LlmTask; the gated tools never ran — fail-safe).
2. Rebuild the chat model exactly as the normal path does for THAT task only (`runTemplateEngineOnParams` → `chatModelRegistry.getOrCreate(resolvedType, processedParams)`). **Explicit bypass checklist — the following normal-path steps MUST NOT run in resume mode (each is a double-execution or state-mutation bug if it re-runs), and the test asserts each:**
   - httpCall RAG (`:240-254`) and vector RAG (`:256-267`) — external calls
   - `prePostUtils.executePreRequestPropertyInstructions` (`:346`) — property mutations
   - history rebuild (`:324-335`) + `MultimodalMessageEnhancer` (`:339`) — replaced by the persisted transcript (fallback path in Task 9 is the ONE place history rebuild is allowed on resume)
   - cascade branch (`:375-429`) — resume always uses the single resolved model
   - `identityMaskingService`/`counterweightService` re-application — baked into the frozen transcript already
3. Call `agentOrchestrator.resumeToolLoop(chatModel, task, memory, batch, resumeDecision, templateDataObjects)` (Task 9) → returns `ExecutionResult`.
4. Store the result EXACTLY like the normal path stores it (raw response data `:484-485`, trace `:518-521`, output `:527-535`, `prePostUtils.runPostResponse` `:537` — postResponse DOES run: it reacts to the final response, which only now exists). Then `memory.clearToolPauseState()` (nulls batch + resume decision + pauseType).
5. **Multi-task note:** tasks in `llmConfig.tasks()` before `batch.llmTaskIndex` already ran pre-pause (their step data is committed); tasks after it never ran. After `executeResume` returns the resumed task's result, iterate the remaining matching tasks (`index > batch.llmTaskIndex`) through the NORMAL `executeTask` path so the turn completes fully. Implement by restructuring the task loop: in resume mode, skip tasks with index < batch index, resume at ==, normal-execute >.

- [ ] **Step 1:** Failing tests. `ConversationToolResumeTest` (Mockito on workflows/lifecycle manager): TOOL_CALL pause + APPROVED → `executeLifecycleFromIndex` called with the SAME index (not +1); RULE pause (null pauseType) → +1 unchanged (backward compat); TOOL_CALL + REJECTED → NO short-circuit, same-index re-entry, hitlDecision outputs still written; legacy snapshot without pauseType behaves as RULE. LlmTask side (`LlmTaskResumeModeTest`): resume mode skips RAG/preRequest (verify zero interactions on their mocks), consumes the batch via `resumeToolLoop` (mock orchestrator), stores result + runs postResponse, clears tool-pause state; index/id mismatch → config-drift degradation (public output stored, batch cleared, orchestrator never called); tasks after the resumed index run normally.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Run new + `ConversationServiceResumeTest` + `ConversationHitlTest` + `RestAgentEngineHitlTest` → all PASS.
- [ ] **Step 5:** Commit: `feat(hitl): same-index re-entry into LlmTask for tool-call pauses`

---

### Task 9: `resumeToolLoop` — verdict application, journal protocol, loop continuation

The riskiest task. Everything here lives in `AgentOrchestrator` so it shares `executeSingleToolCall`, tool discovery, and the loop body with the live path.

**Files:**
- Modify: `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java`
- Test: `src/test/java/ai/labs/eddi/modules/llm/impl/AgentOrchestratorResumeToolLoopTest.java`

**Interfaces:**
- Produces: `ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch, HitlDecision decision, Map<String,Object> templateDataObjects) throws LifecycleException` (package-private).
- Consumes: `IHitlToolJournalStore` (Task 6), `ChatTranscriptCodec` (Task 1), `executeSingleToolCall` (Task 5).

**Algorithm (implement in this order):**

1. **Reconstitute messages.** Primary: `chatTranscriptCodec.deserialize(batch.getChatTranscriptJson())` when `!batch.isTranscriptOmitted()`. On `TranscriptCodecException` OR omitted: **fallback rebuild** — build base history exactly as a fresh turn would (`conversationHistoryBuilder.buildMessages/buildTokenAwareMessages` with the task's params — this is the one sanctioned use of history rebuild on resume; accept that intra-turn prior iterations are lost) + append a reconstructed `AiMessage.from(requests)` where requests are rebuilt from `batch.getCalls()` (`ToolExecutionRequest.builder().id(callId).name(toolName).arguments(argumentsRaw).build()`). Record `trace` entry `{"type":"hitl_resume","transcriptRestored":bool}`.
2. **Rebuild tooling.** Re-run the same tool discovery/registration the live path uses for this task (extract the registration prologue of `executeWithTools` into a private `ToolSetup buildToolSetup(task, memory)` record `(toolSpecs, toolExecutors, toolSources, builtInSpecs)` so both paths share it — do this extraction as the first commit-worthy step of this task). Restore LAZY `activeSpecs` from `batch.getActivatedToolNames()`.
3. **Apply verdicts in batch order.** For each `PendingToolCall c` in `batch.getCalls()`, resolve the effective verdict (per-call entry or top-level default):
   - **REJECTED** → append `ToolExecutionResultMessage.from(rebuiltRequest(c), "{\"status\":\"REJECTED_BY_REVIEWER\",\"tool\":\"" + c.getToolName() + "\",\"note\":" + jsonString(note) + ",\"instruction\":\"The reviewer declined this action. Do not retry this exact call; explain the situation to the user and offer alternatives.\"}")`. (Build the JSON with the existing `IJsonSerialization`/Jackson — never string-concatenate unescaped user text.)
   - **APPROVED with `argsTruncated`** → cannot execute what the approver didn't fully see and the raw args are incomplete anyway: append error result `{"status":"NOT_EXECUTED","reason":"arguments exceeded the persistable size cap"}` (validation already blocks amendments on these; approving them is allowed but yields this honest non-execution — document in the result envelope).
   - **APPROVED** → journal protocol:
     ```java
     if (journalStore.tryClaim(conversationId, batch.getPauseEpoch(), c.getCallId(), c.getToolName(), decision.getDecidedBy())) {
         String args = amended != null ? amended : c.getArgumentsRaw();
         String result = executeSingleToolCall(rebuiltRequest(c, args), ...); // full pipeline: checkpoint, budget, executeToolWrapped, truncation, trace
         journalStore.markExecuted(conversationId, batch.getPauseEpoch(), c.getCallId(), cap(result, 32_768));
         String envelope = amended != null
                 ? jsonEnvelope("status", "EXECUTED", "argsAmendedByReviewer", true, "result", result)
                 : result;
         currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), envelope));
     } else {
         var prior = journalStore.find(conversationId, batch.getPauseEpoch(), c.getCallId());
         if (prior.isPresent() && prior.get().status() == Status.EXECUTED) {
             currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), prior.get().resultCapped())); // replay, never re-execute
         } else {
             // EXECUTING: a previous attempt crashed inside the tool — honest at-most-once
             currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c),
                     "{\"status\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"a previous execution attempt was interrupted; it may or may not have taken effect — verify externally before retrying\"}"));
             auditOutcomeUnknown(memory, c); // audit type hitl.tool.outcome_unknown (via memory's audit collector)
             trace.add(Map.of("type", "hitl_outcome_unknown", "tool", c.getToolName(), "callId", c.getCallId()));
         }
     }
     ```
     The amended-args execution keeps the transcript's original `AiMessage` untouched (immutable, provider-safe — results bind by callId); the `argsAmendedByReviewer` envelope tells the model why the result may not match its issued args.
   - Tool vanished after redeploy (`toolExecutors.get(name) == null`) → the existing `"Error: Tool 'x' not found"` result (:415 behavior) — resume still completes.
   - **NO auto-checkpoint re-fire for replayed/unknown calls** — `executeSingleToolCall` runs (with its checkpoint) only inside the `tryClaim` success branch.
4. **Continue the loop.** Enter the same iteration loop as `executeWithTools` starting at `i = batch.getIterationIndex() + 1` up to the task's `maxToolIterations` (budget continuity — the pause does not launder fresh iterations), with `clearedCallIds` = the approved callIds, gate ACTIVE for new calls (a new gated batch throws `ToolApprovalRequiredException` again → re-pause; `pauseCountThisTurn` carries from `batch.getPauseCountThisTurn()`, `autoApproveCount` per Task 10). Merge `batch.getTraceSoFar()` + new trace entries into the returned `ExecutionResult`. Wrap the loop in `AgentExecutionHelper.executeWithRetry` exactly like the live path (the rethrow guard from Task 5 lets re-pauses escape).
5. Return `new ExecutionResult(finalText, mergedTrace)`.

- [ ] **Step 1:** Failing tests (mock ChatModel scripted per scenario; mock journal store; mock executors):
  - approve-all: both tools execute once, journal claim+markExecuted per call, results appended by callId, model called again, final text returned, trace merged
  - reject-all: NO executor invocation, rejection envelopes contain the note, model produces final text (verify the messages list passed to the final `chat()` contains the envelopes)
  - mixed + amendment: approved call executes with amended args; envelope has `argsAmendedByReviewer:true`; rejected call gets note
  - journal says EXECUTED → replay stored result, executor NOT invoked
  - journal says EXECUTING → outcome-unknown envelope, executor NOT invoked, audit called
  - transcript codec failure → fallback rebuild path: history builder invoked, reconstructed AiMessage carries the batch's callIds, gated approved call still executes
  - re-pause: model's next response contains a NEW gated call → `ToolApprovalRequiredException` with a fresh pauseEpoch and `pauseCountThisTurn = old + 1`
  - iteration budget: `batch.iterationIndex = maxToolIterations - 1` → after verdicts, loop makes at most one more model call
- [ ] **Step 2:** Run → fails. **Step 3:** Implement (extraction refactor first, then resumeToolLoop; run `AgentOrchestratorToolPauseTest` after the extraction to prove the live path is unbroken). **Step 4:** All orchestrator tests PASS.
- [ ] **Step 5:** Commit: `feat(hitl): journal-protected resume of gated tool calls with transcript replay`

---

### Task 10: Timeout policies, effective-policy rule, no-progress guards

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java` (`populateHitlTimeoutBookmark` :1534-1556, resume path, timeout handler wiring — find `HitlTimeoutHandler` usage: `grep -rn "system:timeout" src/main/java`)
- Test: `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceToolTimeoutTest.java`

**Rules to implement:**

1. **Effective tool timeout policy** (in `populateHitlTimeoutBookmark`, only when `hitlPauseType == "TOOL_CALL"`): use `toolApprovals.approvalTimeout`/`timeoutPolicy` when set; else inherit the outer `hitlConfig` values EXCEPT: inherited `AUTO_APPROVE` becomes `WAIT_INDEFINITELY` (the save-time warning from Task 3 told the designer). Explicit `toolApprovals.timeoutPolicy=AUTO_APPROVE` is honored.
2. **Timeout decisions route through the normal resume path** — `HitlTimeoutHandler` already builds `HitlDecision(verdict, decidedBy="system:timeout")` and calls `resumeConversation`; the TOOL_CALL branch (Tasks 8/9) then handles it — verify no shortcut path exists that bypasses the journal (test).
3. **No-progress guard**: when `resumeConversation` processes a decision whose `decidedBy` starts with `"system:"` on a TOOL_CALL pause, and the conversation later re-pauses (same turn) with an identical `fingerprint`: increment `autoApproveCount` (carried into the new batch); when `autoApproveCount >= 2` OR the count of system approvals this turn reaches `maxAutoApprovalsPerTurn`, apply `onNoProgress`:
   - `WAIT_FOR_HUMAN` (default): re-pause but **demote** the timeout policy of the new pause to `WAIT_INDEFINITELY` (delete/skip the schedule), audit `hitl.tool.no_progress` — a human must break the loop
   - `AUTO_REJECT`: resume immediately with reject-all (`system:no-progress`), graceful completion
   - `ABORT`: cancel the conversation via the existing cancel path
   Implementation note: fingerprint comparison happens where the re-pause commits (the service-side pause-commit path already inspects the snapshot; compare old batch fingerprint stashed pre-resume vs. new batch fingerprint). Human decisions (`decidedBy` not `system:*`) reset `autoApproveCount` to 0 — mirroring the group fresh-budget convention.
4. **Metrics** (new meters, registered like the existing HITL counters — find `counterHitlPause` registration): `eddi_hitl_tool_pause_count`, `eddi_hitl_tool_resume_count` tagged `verdict=approved|rejected|mixed`, `eddi_hitl_tool_guard_count` tagged `guard=pause_cap|no_progress|auto_approve_cap`.
   **Audit per guard:** every guard activation writes an audit-ledger entry, not just a metric — `hitl.tool.pause_cap` (from Task 5's cap branch — thread the audit collector so the DENIED path can audit), `hitl.tool.auto_approve_cap`, and `hitl.tool.no_progress`. Each entry records the guard name, the fingerprint (for no-progress), and `decidedBy`. This satisfies the "audit per guard" requirement literally.
5. **Audit detail extension** in `auditHitlDecision` (:1390-1413): when TOOL_CALL, add `pauseType`, and a `toolDecisions` summary list `[{callId, verdict, amended:bool, toolName}]` + per-call SHA-256 `argsDigest` (never raw args in the ledger).

- [ ] **Step 1:** Failing tests: inherited AUTO_APPROVE demoted to WAIT_INDEFINITELY (no schedule armed); explicit tool-level AUTO_APPROVE honored (schedule armed, timeout fires → resume path invoked with system:timeout); identical-fingerprint re-pause after system approval with default policy → new pause has WAIT_INDEFINITELY + audit entry; AUTO_REJECT no-progress → reject-all resume; human decision resets the counter; audit detail contains argsDigest not raw args.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Run + full existing HITL service suites (`ConversationServiceHitlTest`, `ConversationServiceSayHitlTest`, `ConversationServiceResumeTest`) → PASS.
- [ ] **Step 5:** Commit: `feat(hitl): tool-pause timeout policy scoping and no-progress guards`

---

### Task 11: Approver surfaces — pauseDetails, pending-approvals badges

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java` (approval-status, ~:354-394) — **inject `IHitlToolJournalStore` (Task 6) to read EXECUTING entries for `outcomeUnknown`**
- Modify: `src/main/java/ai/labs/eddi/engine/model/PendingApprovalSummary.java` (constructed in `ConversationMemoryStore.collectPendingSummaries()` :242-246 — add `pauseType`/`toolNames` there too)
- Test: `src/test/java/ai/labs/eddi/engine/internal/RestAgentEngineToolPauseDetailsTest.java`

**Depends on Task 6** (`IHitlToolJournalStore`) — the `outcomeUnknown` field lists callIds that have an `EXECUTING` journal entry (a crashed-mid-execution approved call). Without the journal store injected, that field cannot be populated.

**Behavior:**
- `GET /agents/{id}/approval-status` (summary view) gains `pauseDetails`:
  - TOOL_CALL: `{"type":"TOOL_CALL","calls":[{"callId","toolName","source","arguments":<argumentsRedacted>,"argsTruncated","gateReason"}],"executedUngatedCalls":["getCurrentDateTime"],"outcomeUnknown":[<callIds with EXECUTING journal entries, populated only after a crashed resume>]}`
  - RULE (finding R10 — computed **at read time** from the snapshot, no new persistence): `{"type":"RULE","reason":<pauseReason>,"actions":[<ACTIONS data of the paused step>]}`
- `PendingApprovalSummary` gains `pauseType` (String) and `toolNames` (List<String> — names only, no args) so inbox lists can badge tool pauses. Populate in **`ConversationMemoryStore.collectPendingSummaries()` :242-246** (the 6-arg ctor + `setApprovalTimeout` site — add the two new fields there).
- The `detail=full` authz behavior is untouched (approver-only-while-paused already implemented).

- [ ] **Step 1:** Failing tests: TOOL_CALL snapshot → pauseDetails carries redacted args only (assert the raw `argumentsRaw` value does NOT appear anywhere in the response), executedUngatedCalls present; **an `EXECUTING` journal entry for a callId → that callId appears in `outcomeUnknown`** (mock `IHitlToolJournalStore.find` returning EXECUTING); no journal entries → `outcomeUnknown` empty; RULE snapshot → type RULE with actions list; legacy snapshot (null pauseType) → type RULE; pending-approvals entries carry pauseType + toolNames.
- [ ] **Step 2-4:** Red → implement → green (+ `RestConversationStoreTest`, `RestAgentEngineHitlTest` still PASS).
- [ ] **Step 5:** Commit: `feat(hitl): structured pauseDetails for tool and rule pauses`

---

### Task 12: Slack integration parity

**Files:**
- Modify: `src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java`, `src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java`
- Test: extend `src/test/java/ai/labs/eddi/integrations/slack/SlackInteractivityHandlerTest.java` patterns → `SlackToolPauseNotificationTest`

**Behavior:**
- The approval-inbox Block Kit message, when the bookmark's snapshot has `pauseType=TOOL_CALL`, appends one context section per pending call (max 5, then `"+N more"`): `"<toolName> — <argumentsRedacted truncated to 300 chars>"`. `argumentsRedacted` is already redaction-filtered at batch build; the 300-char display truncation happens here. This intentionally relaxes the previous names-only data-minimization stance **for the approval channel only** (a reviewer cannot responsibly approve `transfer_funds` without seeing the amount) — the in-thread pause notice keeps showing only the pauseReason.
- Approve/Reject buttons keep their existing action ids and semantics: they submit a plain `{"verdict": ...}` decision = approve-all/reject-all (per-call verdicts are REST/Manager-only in v1 — document in docs/hitl.md).
- The existing state+bookmark-driven notification flow (`notifyApprovers`, `loadHitlBookmark` retry) requires NO dispatch changes — verify with a test that a TOOL_CALL pause triggers the notification exactly like a RULE pause.
- Continuation push: `HitlResumeCompletedEvent` observers already post the resumed output; the tool-resume path produces normal output (Task 8/9) → works unchanged; add one test asserting the resumed final text reaches the thread-post call.

- [ ] Steps: failing test → implement → green (`SlackInteractivityHandlerTest` untouched and passing). Commit: `feat(hitl): tool-pause detail blocks in Slack approval messages`

---

### Task 13: Delegated/MCP parity + group-member policy

**Files:**
- Modify: `src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java` (`pausedForApprovalJson`, ~:568), `src/main/java/ai/labs/eddi/modules/llm/tools/ConverseWithAgentTool.java` (:117-119/:146/:169), `src/main/java/ai/labs/eddi/modules/llm/tools/CreateSubAgentTool.java` (:193-203)
- Modify: `src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java`
- Test: extend `McpConversationToolsHitlTest`, `ConverseWithAgentToolHitlTest`; new `GroupConversationServiceToolPauseTest`

**Behavior:**
1. **Delegated parity (near-free):** a nested conversation's tool pause lands in `AWAITING_HUMAN` like any pause → the existing `PAUSED_FOR_APPROVAL` envelopes fire unchanged. Additively include `pauseType` and `tools` (names only, from the nested snapshot's batch) in `pausedForApprovalJson`'s map and in the delegated-tool text messages. Tests: nested TOOL_CALL pause → envelope contains `pauseType:"TOOL_CALL"` + tool names; nested RULE pause → unchanged fields (regression).
2. **Group members:** when a member conversation tool-pauses during a group turn (the group's `executeAgentTurn` awaits the member future and observes the pause), the group auto-resolves it with `HitlDecision(REJECTED, decidedBy="system:group", note="tool approval is not available during group discussions in this version")` — routed through the NORMAL resume path (so the member's LLM receives rejection tool-results, Task 9, and produces a coherent tool-less answer that becomes its turn contribution when the resume completes synchronously; if the resume cannot complete within the member-turn budget, fall back to the existing member-pause handling: turn recorded SKIPPED + `member_pause_skipped` SSE + audited auto-cancel). Read the existing member-pause auto-cancel code first (`grep -rn "member_pause_skipped\|system:group" src/main/java`) and extend it minimally: the DIFFERENCE for tool pauses is the graceful system-rejection attempt before falling back to cancel. Metric: reuse `eddi_group_member_pause_skipped_count`.
3. `inGroupTurns` config: v1 only supports `REJECT` (above). `INBOX` was already rejected at save time (Task 3).

- [ ] Steps: failing tests → implement → green (all existing group HITL tests still pass: `GroupConversationServiceHitlTest`). Commit: `feat(hitl): tool-pause parity for delegated conversations and group-member policy`

---

### Task 14: Crash recovery, retention, undeploy — verify + extend

**Files:**
- Modify (verify-mostly): `src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java`
- Test: extend `src/test/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserverTest.java`; extend `AgentDeploymentManagementTest`

**Verify (tests, no behavior change expected):**
- A TOOL_CALL pause sets `hitlPausedAt` (Task 5) → crash recovery re-arms finite schedules identically; `WAIT_INDEFINITELY` tool pauses skipped. Add one test with a TOOL_CALL-flavored snapshot.
- `IN_PROGRESS`-with-bookmark recovery (pod died mid-resume) restores `AWAITING_HUMAN` → the batch is still in the snapshot → re-approval works, and the journal (Task 6/9) prevents double execution. Test: recovery-restored pause + re-approve → journal `tryClaim` returns false for already-executed calls.
- Undeploy with a pending tool pause → allowed (paused conversations don't count active); resume then 409s + restores pause (existing `AgentNotDeployedForResumeException` path) — add a TOOL_CALL variant test.
- Retention sweep (`eddi.hitl.pending.max-age`) cancels tool pauses via the normal cancel path — the cancel must also clear `hitlPendingToolCalls` (verify `clearToolPauseState` is reached from cancel; fix if not) — test.

**Extend:** the recovery sweep's stale-state repair should clear a `hitlPendingToolCalls` batch found on a conversation that is NOT `AWAITING_HUMAN`/`IN_PROGRESS` (orphan from a crash between gate-trip and pause-commit — normally impossible since both persist in one document, but a defensive one-liner + WARN + test).

**Also (review follow-up — real-codec round-trip):** the Task 4 unit test `PendingToolCallBatchSnapshotTest` only proves the POJOs are bean-shaped via a plain Jackson `ObjectMapper`. Add a **Testcontainers** round-trip in `MongoConversationMemoryStoreTest` (CI-only) that stores a `ConversationMemorySnapshot` with `hitlPauseType="TOOL_CALL"` and a fully-populated `PendingToolCallBatch` (incl. `traceSoFar` with a nested `Map<String,Object>` and two calls) via `store.storeConversationMemorySnapshot`, loads it back, and asserts every batch field survives — this exercises the real `JacksonCodec` (BSON-backed) path the unit test cannot. Mirror it for Postgres (JSONB) if that store's test harness exists.

- [ ] Steps: tests → (minimal) fixes → green. Commit: `test(hitl): crash-recovery, retention and undeploy coverage for tool pauses`

---

### Task 15: Save-time lints (critique findings R10)

**Files:**
- Create: `src/main/java/ai/labs/eddi/engine/hitl/lint/ReservedActionLint.java`
- Modify: the behavior-rules store create/update path — **`configs/rules/mongo/RuleSetStore.java`** (+ `IRestRuleSetStore`), hook where rulesets are saved; and the agent deployment path — **`engine/runtime/internal/AgentDeploymentManagement.java`**
- Test: `src/test/java/ai/labs/eddi/engine/hitl/lint/ReservedActionLintTest.java`

**Behavior:**
1. **Near-miss lint (rules save):** for every action name in a saved ruleset, if it is a case-variant or Levenshtein-distance ≤ 2 of a reserved action (`PAUSE_CONVERSATION`, `STOP_CONVERSATION`, `CONVERSATION_START`, `CONVERSATION_END`) **and not exactly equal** → WARN log + warnings entry (non-fatal; a legitimate action may legally resemble the name). Reuse `ToolApprovalPatterns.levenshtein` (make it public or move to a shared util — executor's choice, keep it in one place).
2. **Nothing-can-pause lint (deployment):** when a deployed agent has `hitlConfig` set (any field) AND no referenced ruleset emits `PAUSE_CONVERSATION` AND `hitlConfig.toolApprovals.requireApproval` is empty/absent → WARN log `"agent <id>: hitlConfig is configured but nothing in this agent can trigger a pause"` (+ deployment-descriptor warning if the deployment response supports warnings — inspect first, WARN-log-only is acceptable).

- [ ] Steps: failing tests (exact-match not flagged; `PAUSE_CONVERSATON` flagged; `pause_conversation` flagged; unrelated names not flagged; deployment warn fires only in the configured-but-inert case) → implement → green. Commit: `feat(hitl): save-time lint for reserved-action near-misses and inert hitlConfig`

---

### Task 16: Integration tests (CI-only) + full regression pass

**Files:**
- Create: `src/test/java/ai/labs/eddi/integration/HitlToolPauseResumeIT.java` (mirror `HitlPauseResumeIT`'s harness)
- Test resources: a langchain.json config with a fake/scripted tool + `toolApprovals` (mirror how existing ITs stub LLM providers — inspect `src/test/resources/tests/hitl/` and the IT's model stubbing approach first; if the harness cannot stub a ChatModel, scope the IT to: config save validation 400s + approval-status/pending-approvals surface shapes on a synthetically-paused conversation)

**Scenarios (as far as the harness allows):** gated call pauses (409 on say, approval-status shows pauseDetails) → approve via REST → turn completes with tool executed once → journal has EXECUTED entry; reject-all → graceful answer, no execution; toolDecisions validation 400s end-to-end.

- [ ] Write ITs; run `./mvnw compile -q` and the full local unit suite `./mvnw test -q` (NOT the ITs — CI-only). Fix any regression. Commit: `test(hitl): tool-pause integration tests (CI)`

---

### Task 17: Documentation + changelog + upgrade notes

**Files:**
- Modify: `docs/hitl.md` — remove "Tool-level HITL … deferred" from Known Limitations (:253); **amend the Slack data-minimization sentence at :241 ("Only the pause reason is shared — no conversation content") to scope it to RULE pauses and note the TOOL_CALL exception (the approval channel shows redacted, size-capped tool arguments so a reviewer can see what they are approving)** — otherwise it directly contradicts Task 12; add a **Tool-Level Approval Gating** section: config schema + defaults table, pattern language (sources list, `*` semantics, case-sensitivity), precedence table (task-replaces-agent; exempt-beats-require; absent=off), effective-timeout-policy rule (AUTO_APPROVE explicit-only), per-call verdict/amendment REST bodies, pauseDetails shapes, journal semantics incl. the outcome-unknown contract, Slack behavior (approval channel shows redacted args; buttons = all-or-nothing), group-member REJECT behavior, rolling-upgrade note (`eddi.hitl.tool.enabled`, complete rollout before enabling gates; drain tool pauses before downgrade), frozen-transcript semantics (multi-day pause resumes pause-time prompt state).
- Modify: `AGENTS.md` §5.3 — extend the HITL note: behavior rules gate *turns*; `toolApprovals` gates *LLM tool calls*; link docs/hitl.md.
- Modify: `docs/changelog.md` — full entry per repo conventions (date, branch `feat/hitl-framework`, files + reasoning, the 5 product decisions from the decision record, what's next).
- Modify: `planning/hitl-framework-plan.md` — flip "Tool-level HITL: Deferred" to "Implemented — see planning/hitl-tool-approval-plan.md".

- [ ] Write docs; commit together with nothing else: `docs(hitl): tool-level approval gating reference and upgrade notes`

---

## Requirement → Task traceability

| Requirement / critique finding | Task(s) |
|---|---|
| R1 fail-safe gate at execution layer, all 8 tool sources | 2, 5 |
| R2 allow/disallow pattern config, save-time 400s | 2, 3 |
| R3 coexistence with PAUSE_CONVERSATION (shared state/endpoint/timeouts/audit/Slack/recovery) | 4, 5, 8, 10, 12, 14 |
| R4 durability: crash, multi-day, any-pod, undeploy | 1, 4, 6, 14 |
| R5 approver UX: pauseDetails, per-call verdicts, amendments, graceful rejection into the LLM | 7, 9, 11 |
| R6 loop/abuse protection: caps, fingerprint, redaction | 5, 10 |
| R7 Slack + delegated parity | 12, 13 |
| R8 group members | 13 |
| R9 backward compat | 3, 4, 8 (+ explicit tests in each) |
| Finding: static pauseReason → structured pauseDetails (both pause types) | 11 |
| Finding: all-or-nothing rejection → per-call verdicts | 7, 9 |
| Finding: magic-string footguns → near-miss lint + inert-config lint | 15 |
| Finding: approve-with-modifications → amendedArguments | 7, 9 |
| Double-execution (judge mustFix) | 6, 9, 14 |
| End-user visibility on pause (judge mustFix) | 5 |
| Transcript round-trip proof first (judge mustFix) | 1 |
| Task-identity binding + pre-LLM bypass checklist (judge mustFix) | 8 |
| AUTO_APPROVE inheritance guard (judge mustFix) | 3, 10 |
| Ungated-calls-executed visibility (judge mustFix) | 5, 11 |
| Rolling-upgrade flag (judge mustFix) | 5 (flag), 17 (docs) |
| Journal epoch key (judge mustFix) | 6 |
| Multi-task LlmConfiguration merge/ordering (judge mustFix) | 8 |
| Counter/fingerprint interplay + audit per guard (judge mustFix) | 10 |
| Outcome-unknown product contract (judge mustFix) | 9, 11, 17 |

## Execution order & independence

Tasks 1→5 are strictly sequential (each consumes the previous). 6 and 7 are independent of each other (both after 4). 8 needs 4+7 (and adds the `resumeToolLoop` stub so it compiles); 9 needs 5+6+8 (fills the stub body). 10 needs 9. 11 needs ≤10 **plus Task 6** (`IHitlToolJournalStore` injected into `RestAgentEngine` to populate `outcomeUnknown`). 12-15 are mutually independent (all need ≤10). 16-17 last. A reviewer can approve/reject each task independently — every task leaves the build green (the Task 8 stub is the one deliberate placeholder, replaced in Task 9) and existing HITL suites passing.
