/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import java.security.NoSuchAlgorithmException;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The tool-approval gate's supporting cast (R2 step 4): per-turn pause
 * accounting, the approver-facing pause reason, the durable
 * {@link PendingToolCallBatch} snapshot and its size caps, the batch
 * fingerprint, tool-call-id normalisation, and the pause-cap guard's
 * metric/audit emission. Extracted from {@code AgentOrchestrator} as a pure
 * move — no behavior change.
 * <p>
 * Accompanies {@link ToolApprovalGate}, which decides <em>whether</em> a batch
 * pauses; everything here is about what happens once it does. The two were
 * always a pair — the gate is self-instantiated by {@code AgentOrchestrator}
 * and these helpers sat in a labelled block right beside its call sites — but
 * they lived in the orchestrator only because that is where the loop is.
 * <p>
 * Almost every method is static, which is why the cluster was mechanical to
 * move: the two that are not ({@link #recordRuleMatches},
 * {@link #recordPauseCapGuard}) are instance methods purely so a test can
 * observe them, and both are best-effort emitters that swallow their own
 * exceptions — guard bookkeeping must never break the LLM loop.
 * <p>
 * {@code AgentOrchestrator} keeps a thin declared delegator for each of these.
 * That is not tidiness: several characterization tests reach them through
 * {@code AgentOrchestrator.class.getDeclaredMethod(...)}, which resolves only
 * methods declared on that exact class, and {@code buildPendingBatch} is called
 * directly as an instance method in eight places.
 */
public class ToolApprovalGateSupport {

    private static final Logger LOGGER = Logger.getLogger(ToolApprovalGateSupport.class);

    /** Step-data key holding this turn's cumulative gated-pause count. */
    public static final String KEY_TOOL_PAUSE_COUNT = "hitl:tool_pause_count";

    private static final int DEFAULT_MAX_PAUSES_PER_TURN = 3;

    private static final int PAUSE_REASON_MAX_CHARS = 500;

    /**
     * Cap on the model's interim narration carried with a pause. A sentence or two
     * by nature; the cap only guards against a runaway preamble bloating the
     * persisted batch and the paused step's public output.
     */
    static final int INTERIM_TEXT_MAX_CHARS = 2_000;

    private final ChatTranscriptCodec chatTranscriptCodec;

    public ToolApprovalGateSupport(ChatTranscriptCodec chatTranscriptCodec) {
        this.chatTranscriptCodec = chatTranscriptCodec;
    }

    // ─── Per-turn pause accounting ───

    /** Reads this turn's cumulative gated-pause count (0 when absent). */
    public static int readToolPauseCount(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        if (step == null) {
            return 0;
        }
        IData<Integer> data = step.getLatestData(KEY_TOOL_PAUSE_COUNT);
        if (data == null || data.getResult() == null) {
            return 0;
        }
        return data.getResult();
    }

    /** Writes the incremented gated-pause count for this turn. */
    public static void incrementToolPauseCount(IConversationMemory memory, int pausesSoFar) {
        var step = memory.getCurrentStep();
        if (step != null) {
            step.storeData(new Data<>(KEY_TOOL_PAUSE_COUNT, pausesSoFar + 1));
        }
    }

    /** Effective max pauses per turn (default 3, clamped 1..10). */
    public static int maxPausesPerTurn(ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getMaxPausesPerTurn() == null) {
            return DEFAULT_MAX_PAUSES_PER_TURN;
        }
        return Math.max(1, Math.min(10, cfg.getMaxPausesPerTurn()));
    }

    // ─── Approver-facing strings ───

    /**
     * The redacted, capped text of the trailing {@link AiMessage} — the narration
     * the model emitted in the same message as its gated tool calls — or null when
     * the transcript does not end in an AiMessage or that message carries no text.
     */
    static String interimTextOf(List<ChatMessage> currentMessages) {
        if (currentMessages == null || currentMessages.isEmpty()) {
            return null;
        }
        if (!(currentMessages.getLast() instanceof AiMessage ai)) {
            return null;
        }
        String text = ai.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        String redacted = SecretRedactionFilter.redact(text.strip());
        // The ellipsis counts against the cap, so the persisted value never exceeds it.
        return redacted.length() > INTERIM_TEXT_MAX_CHARS
                ? redacted.substring(0, INTERIM_TEXT_MAX_CHARS - 1) + "…"
                : redacted;
    }

    /** First non-blank of the two, or null. */
    public static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    /**
     * Builds the approver-facing pause reason: the governing rule's
     * {@code pauseReason} if it set one, else {@code cfg.pauseReason}, with
     * {@code {toolNames}} replaced by the comma-joined gated names and defaulting
     * to "Tool call requires approval: names". Redacted and capped at 500 chars.
     */
    public static String buildPauseReason(ToolApprovalsConfig cfg, ToolApprovalGate.GateResult gateResult,
                                          ToolApprovalsConfig.ApprovalRule rule) {
        String names = gateResult.gated().stream().map(ToolExecutionRequest::name).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
        String template = firstNonBlank(rule != null ? rule.getPauseReason() : null,
                cfg != null ? cfg.getPauseReason() : null);
        if (template == null) {
            template = "Tool call requires approval: {toolNames}";
        }
        String reason = template.replace("{toolNames}", names);
        reason = SecretRedactionFilter.redact(reason);
        if (reason.length() > PAUSE_REASON_MAX_CHARS) {
            reason = reason.substring(0, PAUSE_REASON_MAX_CHARS);
        }
        return reason;
    }

    // ─── LAZY / request shaping ───

    /** Names activated in LAZY mode (for resume reactivation); empty otherwise. */
    public static List<String> activatedToolNames(boolean isLazy, List<ToolSpecification> activeSpecs) {
        if (!isLazy) {
            return List.of();
        }
        return activeSpecs.stream().map(ToolSpecification::name).toList();
    }

    /**
     * Gives every tool-execution request an id when the gate is active, because a
     * gated batch has to be addressable per call: an approver decides on one call
     * at a time, a null-id request in the transcript paired with an invented id on
     * resume breaks providers that match tool results by {@code tool_call_id}, and
     * the gate itself only records a reason per non-null id. Returns the message
     * unchanged when the gate is inert (pre-HITL byte-identical) or no id is
     * missing.
     */
    public static AiMessage normalizeToolCallIds(AiMessage aiMessage, ToolApprovalsConfig effectiveToolApprovals) {
        boolean gateActive = effectiveToolApprovals != null
                && effectiveToolApprovals.getRequireApproval() != null
                && !effectiveToolApprovals.getRequireApproval().isEmpty();
        if (!gateActive || !aiMessage.hasToolExecutionRequests()) {
            return aiMessage;
        }
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        if (requests.stream().allMatch(r -> r.id() != null)) {
            return aiMessage;
        }
        List<ToolExecutionRequest> normalized = new ArrayList<>(requests.size());
        for (ToolExecutionRequest r : requests) {
            if (r.id() == null) {
                normalized.add(ToolExecutionRequest.builder()
                        .id("gen-" + UUID.randomUUID())
                        .name(r.name())
                        .arguments(r.arguments() != null ? r.arguments() : "")
                        .build());
            } else {
                normalized.add(r);
            }
        }
        String text = aiMessage.text();
        return text != null && !text.isBlank()
                ? AiMessage.from(text, normalized)
                : AiMessage.from(normalized);
    }

    // ─── The durable pause snapshot ───

    /**
     * Snapshots the interrupted tool-call batch into a durable
     * {@link PendingToolCallBatch}. All approver-facing strings are secret-redacted
     * and size-capped.
     *
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for serializing {@code currentMessages}
     *            — resolved by {@code LlmTask} from
     *            {@code eddi.hitl.tool.transcript-max-bytes} (default
     *            {@link PendingToolCallBatch#TRANSCRIPT_MAX_BYTES_DEFAULT}).
     * @param ruleByCallId
     *            per gated call, the {@code toolApprovals.rules} entry tuning it —
     *            recorded for approver visibility only
     * @param governingRule
     *            the single rule governing this pause (strictest of the above), or
     *            null; persisted so the post-pause resolvers read the same answer
     *            this gate computed
     * @param resolvers
     *            per httpcall tool name, how to resolve what it would send —
     *            {@code ToolSetup#toolRequestResolvers}. Absent entries (every
     *            non-http tool) leave the call unpinned, which is the pre-pinning
     *            behaviour.
     */
    public PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages,
                                                  ToolApprovalGate.GateResult gateResult, LlmConfiguration.Task task, IConversationMemory memory,
                                                  int iterationIndex, List<String> activatedToolNames, List<Map<String, Object>> trace,
                                                  int pauseCountThisTurn, int llmTaskIndex, Map<String, String> toolSources,
                                                  ToolApprovalsConfig effectiveToolApprovals, int transcriptMaxBytes,
                                                  Map<String, ToolApprovalsConfig.ApprovalRule> ruleByCallId,
                                                  ToolApprovalsConfig.ApprovalRule governingRule,
                                                  Map<String, ToolRequestResolver> resolvers) {
        PendingToolCallBatch batch = new PendingToolCallBatch();
        batch.setPauseEpoch(UUID.randomUUID().toString());
        batch.setLlmTaskId(task.getId());
        batch.setLlmTaskIndex(llmTaskIndex);
        // Fix #1: persist the EXACT effective tool-approval config that gated this
        // batch (task-level override when the task set one, else the agent-level
        // default) so the post-pause resolvers in ConversationService and
        // Conversation.resolvePendingMessage read the task-scoped config that produced
        // the pause instead of re-deriving from the agent level only.
        batch.setEffectiveToolApprovals(effectiveToolApprovals);
        batch.setEffectiveRule(governingRule);
        // workflowId is informational; the authoritative pause bookmark carries the
        // paused workflow id (set by the LifecycleManager → Conversation pause commit).
        batch.setIterationIndex(iterationIndex);
        batch.setActivatedToolNames(activatedToolNames);
        batch.setPauseCountThisTurn(pauseCountThisTurn);
        batch.setAutoApproveCount(0);

        // Serialize the transcript (capped); omitted flag drives fallback on resume.
        ChatTranscriptCodec.CodecResult codecResult = chatTranscriptCodec.serialize(currentMessages, transcriptMaxBytes);
        batch.setChatTranscriptJson(codecResult.json());
        batch.setTranscriptOmitted(codecResult.omitted());
        // What the model said alongside the gated calls — see
        // PendingToolCallBatch#interimText. The gating AiMessage is the last
        // message the runner appended before classifying its tool requests.
        batch.setInterimText(interimTextOf(currentMessages));

        // Per gated call: cap raw args, redact + cap redacted args, carry gate reason.
        List<PendingToolCallBatch.PendingToolCall> calls = new ArrayList<>();
        for (ToolExecutionRequest req : gateResult.gated()) {
            var call = new PendingToolCallBatch.PendingToolCall();
            String callId = req.id() != null ? req.id() : "gen-" + UUID.randomUUID();
            call.setCallId(callId);
            call.setToolName(req.name());
            String source = toolSources.getOrDefault(req.name(), "unknown");
            call.setSource(source);

            String rawArgs = req.arguments() != null ? req.arguments() : "";
            byte[] rawBytes = rawArgs.getBytes(StandardCharsets.UTF_8);
            if (rawBytes.length > PendingToolCallBatch.ARGS_RAW_MAX_BYTES) {
                call.setArgumentsRaw(capUtf8(rawArgs, PendingToolCallBatch.ARGS_RAW_MAX_BYTES));
                call.setArgsTruncated(true);
            } else {
                call.setArgumentsRaw(rawArgs);
                call.setArgsTruncated(false);
            }

            String redacted = SecretRedactionFilter.redact(rawArgs);
            call.setArgumentsRedacted(capUtf8(redacted, PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES));

            // gateReason: the matched pattern (by call id), fall back to bare name.
            String reason = req.id() != null ? gateResult.gateReasonByCallId().get(req.id()) : null;
            call.setGateReason(reason);
            // matchedRule: which friction rule tuned THIS call, which is not necessarily
            // the one governing the pause — an approver seeing a five-minute auto-reject
            // on a batch should be able to tell which call brought it.
            var callRule = req.id() != null ? ruleByCallId.get(req.id()) : null;
            call.setMatchedRule(callRule != null ? callRule.getMatch() : null);
            pinResolvedRequest(call, req, resolvers);
            calls.add(call);
        }
        batch.setCalls(calls);

        // Ungated calls of this batch that already executed (approver visibility).
        batch.setExecutedUngatedCallNames(gateResult.allowed().stream().map(ToolExecutionRequest::name).toList());

        // Deep copy of trace with each entry's "result" string capped.
        batch.setTraceSoFar(capTrace(trace));

        // Fingerprint over sorted gated (name + "|" + arguments).
        batch.setFingerprint(fingerprint(gateResult.gated()));

        return batch;
    }

    /**
     * Resolve what this gated call would send, and pin it to the pause.
     * <p>
     * Records both the redacted preview (so the approver sees the actual request
     * rather than a tool name) and its fingerprint (so the request can be
     * re-checked immediately before execution).
     * <p>
     * <b>Never fails the pause.</b> A tool with no resolver — every non-http source
     * — and a resolve that throws both leave the call simply unpinned, which is
     * exactly the behaviour that existed before pinning: approval on name and
     * arguments. Letting a template error here abort the batch would turn a display
     * feature into a way to kill a turn, and the honest failure mode for "we could
     * not determine the request" is to say so, not to guess.
     */
    private void pinResolvedRequest(PendingToolCallBatch.PendingToolCall call, ToolExecutionRequest req,
                                    Map<String, ToolRequestResolver> resolvers) {

        var resolver = resolvers != null ? resolvers.get(req.name()) : null;
        if (resolver == null) {
            return;
        }
        try {
            ResolvedRequest resolved = resolver.resolve(req);
            call.setRequestFingerprint(resolved.fingerprint());
            call.setRequestPreview(toPreview(resolved));
        } catch (Exception e) {
            // sanitize: the tool name is model-chosen, so it can carry newlines or
            // control characters and forge log records. The throwable is omitted
            // because this failure comes out of request building, whose message
            // routinely quotes the request being built.
            LOGGER.warnf("Could not resolve the request for gated tool '%s' (%s); it will be approved unpinned.", sanitize(req.name()),
                    e.getClass().getSimpleName());
        }
    }

    /** The persisted, display-shaped view of a resolved request. */
    private static PendingToolCallBatch.ResolvedRequestPreview toPreview(ResolvedRequest resolved) {
        var preview = new PendingToolCallBatch.ResolvedRequestPreview();
        preview.setMethod(resolved.method());
        preview.setUri(resolved.uri());
        preview.setQueryParams(resolved.queryParams());
        preview.setHeaders(resolved.headers());

        String body = resolved.body();
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > PendingToolCallBatch.PREVIEW_BODY_MAX_BYTES) {
            // Capped for display only — the fingerprint above was computed over the
            // whole body, so truncating here cannot weaken the pre-execution check.
            preview.setBody(capUtf8(body, PendingToolCallBatch.PREVIEW_BODY_MAX_BYTES));
            preview.setBodyTruncated(true);
        } else {
            preview.setBody(body);
            preview.setBodyTruncated(false);
        }
        return preview;
    }

    /**
     * {@code eddi.operator.write.approval} — one per gated call the moment its
     * verdict is resolved, regardless of what happens to it afterwards (truncated
     * args, a changed-request refusal, and a successful execution are all still an
     * instance of a human's — or the timeout policy's — decision).
     * <p>
     * "write" describes the mechanism, not the source: any call reaching this loop
     * was gated by {@code toolApprovals.requireApproval}, whether it dispatches
     * over http, mcp, or a2a. Restricting the tag to http-sourced calls would
     * silently exclude a gated MCP tool that writes to an external system, which is
     * exactly the rubber-stamping risk this counter exists to surface.
     * <p>
     * {@code decidedBy} distinguishes a real decision from one the timeout policy
     * made ({@code HitlTimeoutHandler}, {@code decidedBy = "system:timeout"}) —
     * folding those into {@code approved}/{@code rejected} would count an operator
     * walking away from their desk as an approval, which is the opposite of what
     * "approvals ≫ rejections is a rubber-stamping signal" is trying to detect.
     * <p>
     * Tagged only with the decision outcome — never a tool name, argument, or
     * conversation id.
     */
    public void recordWriteApprovalDecision(HitlDecision.HitlVerdict verdict, String decidedBy) {
        try {
            String decisionTag = "system:timeout".equals(decidedBy)
                    ? "timeout"
                    : verdict == HitlDecision.HitlVerdict.APPROVED ? "approved" : "rejected";
            Metrics.globalRegistry.counter("eddi.operator.write.approval", "decision", decisionTag).increment();
        } catch (Exception e) {
            LOGGER.debugf("write.approval metric emit failed: %s", e.getMessage());
        }
    }

    /** Caps a string to at most maxBytes UTF-8 bytes without splitting a char. */
    public static String capUtf8(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        // Back off to a char boundary (avoid splitting a multi-byte sequence).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** Deep-copies the trace, capping each entry's "result" string. */
    public static List<Map<String, Object>> capTrace(List<Map<String, Object>> trace) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> entry : trace) {
            Map<String, Object> e = new HashMap<>(entry);
            Object result = e.get("result");
            if (result instanceof String rs && rs.getBytes(StandardCharsets.UTF_8).length > PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES) {
                e.put("result", capUtf8(rs, PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES));
            }
            copy.add(e);
        }
        return copy;
    }

    /** sha256Hex of sorted gated (name + "|" + arguments) joined by newline. */
    public static String fingerprint(List<ToolExecutionRequest> gated) {
        List<String> parts = new ArrayList<>();
        for (ToolExecutionRequest req : gated) {
            parts.add(req.name() + "|" + (req.arguments() != null ? req.arguments() : ""));
        }
        Collections.sort(parts);
        String joined = String.join("\n", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; treat as unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ─── Guard bookkeeping (best-effort emitters) ───

    /**
     * Counts which friction rules actually fire, tagged by the CONFIGURED pattern —
     * never a URL, credential, tool argument or user id, so cardinality is bounded
     * by the size of the agent's {@code rules} list. Deduplicated per pause: a
     * batch where three calls match {@code http.delete:*} increments once, so the
     * counter reads "how often did this rule govern a review", not "how many calls
     * did the model happen to bundle". Best-effort, like the other guard metrics: a
     * failure here must never break the LLM loop.
     */
    public void recordRuleMatches(Collection<ToolApprovalsConfig.ApprovalRule> matched) {
        try {
            matched.stream().filter(Objects::nonNull).map(ToolApprovalsConfig.ApprovalRule::getMatch)
                    .filter(Objects::nonNull).distinct()
                    .forEach(match -> Metrics.globalRegistry.counter("eddi.hitl.rule.matched", "match", match).increment());
        } catch (Exception e) {
            LOGGER.debugf("hitl rule metric emit failed: %s", e.getMessage());
        }
    }

    /**
     * Task 10 — records the pause-cap guard activation as BOTH a metric
     * ({@code eddi_hitl_tool_guard_count{guard=pause_cap}}) and an audit-ledger
     * entry ({@code hitl.tool.pause_cap}). The metric uses the Micrometer global
     * registry (this class, like {@code AgentOrchestrator} before it, is not CDI
     * and has no injected registry — same idiom as {@code LifecycleManager}); the
     * audit is submitted through the live memory's audit collector (the seam that
     * carries the audit context, wired by {@code ConversationService} on the
     * say/resume paths). Best-effort: any failure is swallowed so guard bookkeeping
     * never breaks the LLM loop.
     */
    public void recordPauseCapGuard(IConversationMemory memory, String fingerprint) {
        try {
            Metrics.globalRegistry.counter("eddi_hitl_tool_guard_count", "guard", "pause_cap").increment();
        } catch (Exception e) {
            LOGGER.debugf("pause_cap metric emit failed: %s", e.getMessage());
        }
        try {
            var collector = memory.getAuditCollector();
            if (collector == null) {
                return;
            }
            var detail = new LinkedHashMap<String, Object>();
            detail.put("guard", "pause_cap");
            detail.put("decidedBy", "system:pause-cap");
            detail.put("automated", true);
            if (fingerprint != null) {
                detail.put("fingerprint", fingerprint);
            }
            collector.collect(new AuditEntry(
                    UUID.randomUUID().toString(), memory.getConversationId(), null, null, memory.getUserId(),
                    null, -1, "hitl.tool.pause_cap", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.debugf("pause_cap audit emit failed: %s", e.getMessage());
        }
    }
}
