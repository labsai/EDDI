/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Resumes a turn that paused on a gated tool call (R2 step 5): replay the
 * transcript, apply the human's verdicts call by call, then hand back to the
 * same loop the live path uses. Extracted from {@code AgentOrchestrator} as a
 * pure move — no behavior change.
 * <p>
 * <b>Shares one pipeline with the live path, deliberately.</b> Every approved
 * call goes through {@link ToolLoopRunner#executeSingleToolCallResult} and the
 * continuation through {@link ToolLoopRunner#runToolCallLoop}, so rate limits,
 * cache hits, cost charges, tenant budgets and LAZY activation behave
 * identically whether a call was requested by the model or approved by a
 * person. Reimplementing any of that here is the single most likely way to make
 * post-approval execution quietly diverge from normal execution, which is
 * exactly the kind of drift nobody notices until an audit.
 * <p>
 * Holds a reference back to {@link AgentOrchestrator} for the two things resume
 * genuinely needs from the facade: {@code buildToolSetup}, so the resumed turn
 * rebuilds tooling through the same provider assembly the live path used, and
 * {@code collectEnabledTools} for the history-rebuild fallback. Same pattern
 * and same safety argument as {@code MemberTurnExecutor}'s self-reference in R1
 * — the constructor only stores it.
 * <p>
 * The cost baseline is snapshotted before the verdict loop, not after: that
 * loop charges the conversation's cost tracker for every approved call, so a
 * later baseline would exclude from {@code toolCostUsd} precisely the calls a
 * human signed off on.
 */
class ToolLoopResumer {

    private static final Logger LOGGER = Logger.getLogger(ToolLoopResumer.class);

    /**
     * Serializes the rejection / amended-result envelopes handed back to the model.
     * Moved here with its only two callers; nothing else ever used it.
     */
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();

    private final AgentOrchestrator orchestrator;
    private final ToolLoopRunner toolLoopRunner;
    private final ToolApprovalGateSupport gateSupport;
    private final ChatTranscriptCodec chatTranscriptCodec;
    private final IHitlToolJournalStore journalStore;

    ToolLoopResumer(AgentOrchestrator orchestrator, ToolLoopRunner toolLoopRunner,
            ToolApprovalGateSupport gateSupport, ChatTranscriptCodec chatTranscriptCodec,
            IHitlToolJournalStore journalStore) {
        this.orchestrator = orchestrator;
        this.toolLoopRunner = toolLoopRunner;
        this.gateSupport = gateSupport;
        this.chatTranscriptCodec = chatTranscriptCodec;
        this.journalStore = journalStore;
    }

    /**
     * As
     * {@link #resumeToolLoop(ChatModel, LlmConfiguration.Task, IConversationMemory, PendingToolCallBatch, HitlDecision, boolean)},
     * but carrying the JSON response-format policy so the continuation's requests
     * match what the live loop would have sent.
     */
    AgentOrchestrator.ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory,
                                                     PendingToolCallBatch batch,
                                                     HitlDecision decision, boolean toolHitlEnabled,
                                                     JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        String conversationId = memory.getConversationId();
        String pauseEpoch = batch.getPauseEpoch();
        List<Map<String, Object>> trace = new ArrayList<>();

        // Tool-cost baseline, snapshotted BEFORE the verdict loop below — that loop
        // runs every human-approved gated call through executeToolWrapped, which
        // charges the conversation's cost tracker. A baseline taken after it would
        // already contain those charges, so the delta reported as toolCostUsd (and
        // hence the audit ledger's dollar figure) would exclude exactly the calls a
        // human explicitly approved. The live path (executeWithTools) snapshots
        // before any tool runs; this is the same contract.
        double toolCostBefore = toolLoopRunner.conversationToolCost(conversationId);

        // ── Step 2: rebuild tooling via the shared setup (SAME as the live path) ──
        AgentOrchestrator.ToolSetup setup = orchestrator.buildToolSetup(task, memory);
        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;

        // Restore the active-spec surface. For LAZY, reactivate exactly the specs that
        // were active at pause time (activatedToolNames); otherwise the full set.
        List<ToolSpecification> activeSpecs = restoreActiveSpecs(setup, isLazy, batch.getActivatedToolNames());

        // ── Step 1: reconstitute the transcript (primary) or fall back to rebuild ──
        List<ChatMessage> currentMessages = new ArrayList<>();
        boolean transcriptRestored = false;
        if (!batch.isTranscriptOmitted() && batch.getChatTranscriptJson() != null) {
            try {
                currentMessages.addAll(chatTranscriptCodec.deserialize(batch.getChatTranscriptJson()));
                transcriptRestored = true;
            } catch (ChatTranscriptCodec.TranscriptCodecException e) {
                LOGGER.warnf("HITL resume: transcript restore failed (%s) — falling back to history rebuild for conversation '%s'",
                        e.getMessage(), sanitize(conversationId));
            }
        }
        if (!transcriptRestored) {
            currentMessages.addAll(fallbackRebuildMessages(task, memory, batch));
        }
        trace.add(Map.of("type", "hitl_resume", "transcriptRestored", transcriptRestored));

        // ── Step 3: apply verdicts in batch order ──
        Set<String> clearedCallIds = new HashSet<>();
        Map<String, ToolExecutor> toolExecutors = setup.toolExecutors();
        HitlDecision.HitlVerdict topVerdict = decision.getVerdict();
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null ? decision.getToolDecisions() : Map.of();

        // Per-request execution controls (mirrors the live loop).
        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Map<String, String> toolCanonicalNames = setup.toolCanonicalNames();
        Double maxBudget = task.getMaxBudgetPerConversation();
        List<ToolSpecification> builtInSpecs = setup.builtInSpecs();

        for (PendingToolCallBatch.PendingToolCall c : batch.getCalls()) {
            ToolCallDecision cd = perCall.get(c.getCallId());
            HitlDecision.HitlVerdict verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : topVerdict;
            String note = cd != null ? cd.getNote() : decision.getNote();
            String amended = cd != null ? cd.getAmendedArguments() : null;

            if (verdict == HitlDecision.HitlVerdict.REJECTED) {
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), rejectionEnvelope(c.getToolName(), note)));
                trace.add(Map.of("type", "hitl_rejected", "tool", c.getToolName(), "callId", c.getCallId()));
                continue;
            }

            // APPROVED (top-level default or per-call) below.

            // Truncated raw args can't be honestly executed (the approver never saw the
            // full args and the raw bytes are incomplete). Validation already blocks
            // amendments on these — approving yields an honest non-execution.
            if (c.isArgsTruncated()) {
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c),
                        "{\"status\":\"NOT_EXECUTED\",\"reason\":\"arguments exceeded the persistable size cap\"}"));
                trace.add(Map.of("type", "hitl_not_executed", "tool", c.getToolName(), "callId", c.getCallId()));
                continue;
            }

            // Journal protocol — at-most-once across crashes/re-approvals.
            if (journalStore.tryClaim(conversationId, pauseEpoch, c.getCallId(), c.getToolName(), decision.getDecidedBy())) {
                String args = amended != null ? amended : c.getArgumentsRaw();
                ToolExecutionRequest req = rebuiltRequest(c, args);
                // Full per-request pipeline (checkpoint, budget, executeToolWrapped,
                // truncation, trace). Its own auto-checkpoint fires ONLY here.
                String result = toolLoopRunner.executeSingleToolCallResult(req, memory, trace, toolExecutors, toolRateLimits,
                        toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                        enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                journalStore.markExecuted(conversationId, pauseEpoch, c.getCallId(),
                        ToolApprovalGateSupport.capUtf8(result, AgentOrchestrator.JOURNAL_RESULT_MAX_BYTES));
                String envelope = amended != null ? amendedEnvelope(result) : result;
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), envelope));
                clearedCallIds.add(c.getCallId());
            } else {
                // Duplicate claim — a prior attempt already ran (or crashed mid-tool).
                var prior = journalStore.find(conversationId, pauseEpoch, c.getCallId());
                if (prior.isPresent() && prior.get().status() == IHitlToolJournalStore.Status.EXECUTED) {
                    // Replay the stored result — NEVER re-execute (no checkpoint re-fire).
                    currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), prior.get().resultCapped()));
                    trace.add(Map.of("type", "hitl_replayed", "tool", c.getToolName(), "callId", c.getCallId()));
                    clearedCallIds.add(c.getCallId());
                } else {
                    // EXECUTING (crash inside the tool) — honest at-most-once outcome.
                    currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c),
                            "{\"status\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"a previous execution attempt was interrupted; "
                                    + "it may or may not have taken effect — verify externally before retrying\"}"));
                    auditOutcomeUnknown(memory, c);
                    trace.add(Map.of("type", "hitl_outcome_unknown", "tool", c.getToolName(), "callId", c.getCallId()));
                    clearedCallIds.add(c.getCallId());
                }
            }
        }

        // ── Step 4: continue the SAME loop from the next iteration (budget-continuous)
        // ──
        // The gate resolves the SAME way the live path did (LlmTask.executeTask): the
        // cluster-wide eddi.hitl.tool.enabled kill-switch nulls the config (gate
        // inert), otherwise task override else agent default, so NEW calls in the
        // continuation re-gate → re-pause. Approved ids are pre-cleared so they are
        // never re-gated if the model reissues them. Threading toolHitlEnabled here
        // keeps the resume path from re-arming an approval flow an operator disabled.
        ToolApprovalsConfig effectiveToolApprovals = null;
        if (toolHitlEnabled) {
            effectiveToolApprovals = task.getToolApprovals() != null
                    ? task.getToolApprovals()
                    : memory.getAgentToolApprovalsConfig();
        }
        int llmTaskIndex = batch.getLlmTaskIndex();

        // Transcript cap: resumeToolLoop replays the ALREADY-serialized transcript
        // via ChatTranscriptCodec#deserialize (no cap involved) and never threads the
        // configured value in (LlmTask does not resolve it for the resume path). If
        // this continuation re-gates a fresh call and re-pauses, the resulting batch
        // is capped at the constant default here — a defensible, rare-path fallback
        // rather than widening resumeToolLoop's signature for the primary knob, which
        // governs the initial pause.
        TokenUsage[] tokenHolder = new TokenUsage[1];
        String response = toolLoopRunner.runToolCallLoop(chatModel, currentMessages, activeSpecs, trace, batch.getIterationIndex() + 1,
                setup, isLazy, task, memory, effectiveToolApprovals, llmTaskIndex, clearedCallIds, AgentOrchestrator.DEFAULT_TRANSCRIPT_MAX_BYTES,
                tokenHolder,
                jsonPolicy);

        // ── Step 5: merge the pre-pause trace with the resume trace ──
        List<Map<String, Object>> mergedTrace = new ArrayList<>();
        if (batch.getTraceSoFar() != null) {
            mergedTrace.addAll(batch.getTraceSoFar());
        }
        mergedTrace.addAll(trace);

        Map<String, Object> responseMetadata = new HashMap<>();
        if (tokenHolder[0] != null) {
            responseMetadata.put("tokenUsage", ToolContextBudget.tokenUsageMap(tokenHolder[0]));
        }
        responseMetadata.put("toolCostUsd", toolLoopRunner.toolCostDelta(conversationId, toolCostBefore));
        return new AgentOrchestrator.ExecutionResult(response, mergedTrace, responseMetadata);
    }

    /**
     * Restores the active-spec surface on resume. For EAGER, every registered spec.
     * For LAZY, exactly the specs that were active at pause time (by name), falling
     * back to the LAZY-initial surface when the recorded names are absent.
     */
    static List<ToolSpecification> restoreActiveSpecs(AgentOrchestrator.ToolSetup setup, boolean isLazy, List<String> activatedToolNames) {
        if (!isLazy) {
            return setup.toolSpecs();
        }
        if (activatedToolNames == null || activatedToolNames.isEmpty()) {
            return ToolLoopRunner.computeInitialActiveSpecs(setup, true);
        }
        Set<String> names = new HashSet<>(activatedToolNames);
        List<ToolSpecification> restored = new ArrayList<>();
        for (ToolSpecification spec : setup.toolSpecs()) {
            if (names.contains(spec.name())) {
                restored.add(spec);
            }
        }
        return restored.isEmpty() ? ToolLoopRunner.computeInitialActiveSpecs(setup, true) : restored;
    }

    /**
     * Rebuilds the base history exactly as a fresh turn would (the ONE sanctioned
     * history rebuild on resume) and appends a reconstructed {@link AiMessage}
     * carrying the batch's gated calls, so the appended results bind by call id.
     * Intra-turn prior iterations are lost — accepted trade-off when the transcript
     * cannot be restored. Uses the task's own (untemplated) system/prompt params;
     * the orchestrator has no templating engine, and this degraded path only needs
     * to carry the gated AiMessage so the appended results bind by call id.
     */
    List<ChatMessage> fallbackRebuildMessages(LlmConfiguration.Task task, IConversationMemory memory,
                                              PendingToolCallBatch batch) {
        String systemMessage = "";
        String prompt = null;
        boolean includeFirstAgentMessage = true;
        int logSizeLimit = task.getConversationHistoryLimit() != null ? task.getConversationHistoryLimit() : -1;
        if (task.getParameters() != null) {
            Object sys = task.getParameters().get("systemMessage");
            if (sys != null) {
                systemMessage = sys.toString();
            }
            Object p = task.getParameters().get("prompt");
            if (p != null) {
                prompt = p.toString();
            }
            Object inc = task.getParameters().get("includeFirstAgentMessage");
            if (inc != null) {
                includeFirstAgentMessage = Boolean.parseBoolean(inc.toString());
            }
        }

        List<ChatMessage> base = orchestrator.conversationHistoryBuilder.buildMessages(memory, systemMessage, prompt, logSizeLimit,
                includeFirstAgentMessage, null, 0);

        List<ChatMessage> messages = new ArrayList<>(base);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (PendingToolCallBatch.PendingToolCall c : batch.getCalls()) {
            requests.add(rebuiltRequest(c));
        }
        messages.add(AiMessage.from(requests));
        return messages;
    }

    static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c) {
        return rebuiltRequest(c, c.getArgumentsRaw());
    }

    /**
     * Rebuilds a request binding by the original call id with the given arguments.
     */
    static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c, String args) {
        return ToolExecutionRequest.builder()
                .id(c.getCallId())
                .name(c.getToolName())
                .arguments(args != null ? args : "")
                .build();
    }

    /**
     * Builds the reviewer-rejection envelope with the tool name and free-text note
     * JSON-escaped via Jackson — NEVER string-concatenated with raw reviewer text.
     */
    String rejectionEnvelope(String toolName, String note) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "REJECTED_BY_REVIEWER");
        envelope.put("tool", toolName);
        envelope.put("note", note != null ? note : "");
        envelope.put("instruction", "The reviewer declined this action. Do not retry this exact call; "
                + "explain the situation to the user and offer alternatives.");
        return toJson(envelope);
    }

    /**
     * Wraps an executed amended-args result so the model knows why it may differ.
     */
    String amendedEnvelope(String result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "EXECUTED");
        envelope.put("argsAmendedByReviewer", true);
        envelope.put("result", result);
        return toJson(envelope);
    }

    /**
     * Jackson serialization for approver/model-facing JSON envelopes. All embedded
     * reviewer/tool text is escaped by Jackson — NEVER string-concatenated.
     */
    static String toJson(Object value) {
        try {
            return ENVELOPE_MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Fall back to a minimal, safe envelope rather than propagating — the
            // resume must still complete. Effectively unreachable for the small maps
            // serialized here.
            LOGGER.warnf("HITL resume: JSON envelope serialization failed: %s", e.getMessage());
            return "{\"status\":\"ERROR\",\"reason\":\"could not serialize result envelope\"}";
        }
    }

    /**
     * Records an at-most-once outcome-unknown event. No lightweight
     * {@code hitl.tool.*} audit collector is reachable from this task (the
     * {@link ai.labs.eddi.engine.audit.model.AuditEntry} record is built by the
     * LifecycleManager per-task with HMAC context we do not have here), so —
     * exactly as the config-drift path does — this WARN-logs with a distinctive
     * marker that operators can alert on. Package-private + overridable so tests
     * can assert it fired.
     */
    void auditOutcomeUnknown(IConversationMemory memory, PendingToolCallBatch.PendingToolCall c) {
        LOGGER.warnf("hitl.tool.outcome_unknown: approved tool '%s' (callId '%s') for conversation '%s' had an interrupted prior execution; "
                + "outcome is unknown — verify externally before retrying.",
                sanitize(c.getToolName()), sanitize(c.getCallId()), sanitize(memory.getConversationId()));
    }
}
