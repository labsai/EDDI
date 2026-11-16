/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.llm.guardrails.ArgumentFields;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailOutcome;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener;
import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener.Decision;
import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener.Mode;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Runs a task's {@code toolCallGuardrails} over the tool calls the model asks
 * for — the orchestration around {@link ToolCallGuardrailScreener}: which
 * entries match a call, which of its fields they screen, what a refusal tells
 * the model, and what is recorded.
 * <h3>Where it runs</h3>
 * <ul>
 * <li><b>Live</b> ({@link #screenBatch}), in the tool loop, after the call caps
 * and <em>before</em> the tool-approval gate: a call certain to be refused is
 * never put in front of a human, and the approver sees the arguments as
 * redacted. A {@code redact} rewrites the call in the model's own message, so
 * the transcript, the trace, the pending approval and the tool all see the same
 * request.</li>
 * <li><b>After approval</b> ({@link #screenApproved}), block-only, before the
 * journal claim: nothing is rewritten after a human approved a request, so any
 * finding that would change or stop it refuses it — and a refusal consumes
 * nothing and stays replayable. A call the human did not amend already passed
 * the live screen, so only the cheap, deterministic types run on it again; an
 * amended call is new text and gets every type.</li>
 * </ul>
 * <h3>What a refused call returns</h3> A {@code NOT_EXECUTED} result built only
 * from configuration and EDDI vocabulary — the author's block message or fix
 * text, author-declared issue codes, EDDI reason codes passed through a
 * token-shape filter. The screened arguments are never echoed: not to the
 * model, the audit ledger, the SSE stream or the log.
 * <h3>What is recorded</h3> Per finding: an audit record under
 * {@code audit:guardrail:tool_call:<type>} (with {@code tool}, {@code field}
 * and {@code stage}), a {@code guardrail_triggered} SSE event, the
 * {@code eddi.guardrail.triggered} counter, and a {@code tool_call_guardrail}
 * trace step. Per screened call: {@code eddi.guardrail.toolcall.decisions}
 * tagged {@code stage} and {@code outcome} (no tool tag — tool names are
 * unbounded).
 * <p>
 * Stateless: every per-run figure is on the caller's stack. Inert — never
 * called past its first check — for a task without the field.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ToolCallGuardrails {

    private static final Logger LOGGER = Logger.getLogger(ToolCallGuardrails.class);

    /**
     * The dispatch name a model-backed argument check is charged under in the tool
     * cost tracker.
     */
    static final String DISPATCH_NAME = "toolCallGuardrail";

    /** The per-call decision counter; tags {@code stage, outcome}. */
    static final String METRIC_DECISIONS = "eddi.guardrail.toolcall.decisions";

    /** The trace step type of every recorded finding. */
    static final String TRACE_TYPE = "tool_call_guardrail";

    /** The {@code reason} of every refusal envelope. */
    static final String REFUSAL_REASON = "tool_call_guardrail";

    static final String STAGE_LIVE = "live";
    static final String STAGE_RESUME = "resume";

    /** At most this many model-backed checks per tool-loop run. */
    static final int MAX_MODEL_CHECKS_PER_RUN = 20;

    /** Bound on the compiled-pattern cache. */
    private static final int MAX_CACHED_PATTERNS = 2000;

    private static final Pattern SAFE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_.:/{}\\-]{1,120}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallGuardrailScreener screener;
    private final MeterRegistry meterRegistry;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    @Inject
    public ToolCallGuardrails(GuardrailService guardrailService, MeterRegistry meterRegistry) {
        this.screener = new ToolCallGuardrailScreener(guardrailService);
        this.meterRegistry = meterRegistry;
    }

    /** The model-backed checks one tool-loop run may still make. */
    static final class RunBudget implements ToolCallGuardrailScreener.ModelCheckBudget {
        private int used;

        @Override
        public boolean tryAcquire() {
            if (used >= MAX_MODEL_CHECKS_PER_RUN) {
                return false;
            }
            used++;
            return true;
        }
    }

    /** A fresh budget for one tool-loop run. */
    static RunBudget newRunBudget() {
        return new RunBudget();
    }

    /** A refused call and the {@code NOT_EXECUTED} result it is answered with. */
    record Refusal(ToolExecutionRequest request, String result) {
    }

    /**
     * The batch after screening.
     *
     * @param message
     *            the model's message with every rewritten call replaced (the same
     *            instance when nothing changed); already put in place of the
     *            original in the transcript
     * @param admitted
     *            the calls that go on to the approval gate, rewritten where a
     *            {@code redact} applied, in the model's order
     * @param refused
     *            the calls that do not run, each with its result
     */
    record BatchResult(AiMessage message, List<ToolExecutionRequest> admitted, List<Refusal> refused) {
    }

    /** One call after screening: its arguments, and the refusal or null. */
    private record CallOutcome(String arguments, boolean changed, String refusal) {
    }

    /**
     * Screens the admitted calls of one model response (the live path). Returns
     * null when the task has no {@code toolCallGuardrails} — the loop then runs
     * exactly as without this feature.
     */
    BatchResult screenBatch(LlmConfiguration.Task task, IConversationMemory memory, AiMessage aiMessage, List<ToolExecutionRequest> admitted,
                            Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ChatMessage> currentMessages,
                            List<Map<String, Object>> trace, RunBudget budget, PaidCallGate spend) {
        List<ToolCallGuardrailConfig> entries = task.getToolCallGuardrails();
        if (entries == null || entries.isEmpty() || admitted.isEmpty()) {
            return null;
        }
        GuardrailContext context = context(task, memory, currentMessages, spend);
        Map<ToolExecutionRequest, ToolExecutionRequest> rewritten = new IdentityHashMap<>();
        List<ToolExecutionRequest> stillAdmitted = new ArrayList<>();
        List<Refusal> refused = new ArrayList<>();
        for (ToolExecutionRequest request : admitted) {
            CallOutcome outcome = screenCall(task, entries, memory, request.name(), request.arguments(), toolSources, toolEndpoints, context, trace,
                    budget, Mode.LIVE, false, STAGE_LIVE);
            ToolExecutionRequest effective = request;
            if (outcome.changed()) {
                effective = ToolExecutionRequest.builder().id(request.id()).name(request.name()).arguments(outcome.arguments()).build();
                rewritten.put(request, effective);
            }
            if (outcome.refusal() != null) {
                refused.add(new Refusal(effective, outcome.refusal()));
            } else {
                stillAdmitted.add(effective);
            }
        }
        AiMessage message = aiMessage;
        if (!rewritten.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                requests.add(rewritten.getOrDefault(request, request));
            }
            message = aiMessage.toBuilder().toolExecutionRequests(requests).build();
            replaceInTranscript(currentMessages, aiMessage, message);
        }
        return new BatchResult(message, stillAdmitted, refused);
    }

    /**
     * Screens a call a human approved, before it is claimed and executed:
     * block-only. Returns the {@code NOT_EXECUTED} result when it may not run, or
     * null.
     *
     * @param amended
     *            whether the human changed the arguments; an unchanged call is
     *            re-screened with the deterministic types only
     * @param alreadyClaimed
     *            whether the journal already holds this call (executed, or
     *            interrupted mid-tool): it never executes again, so it is not
     *            screened. Asked only when the task has entries
     */
    String screenApproved(LlmConfiguration.Task task, IConversationMemory memory, String toolName, String arguments, boolean amended,
                          Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ChatMessage> messages,
                          List<Map<String, Object>> trace, PaidCallGate spend, BooleanSupplier alreadyClaimed) {
        List<ToolCallGuardrailConfig> entries = task.getToolCallGuardrails();
        if (entries == null || entries.isEmpty() || alreadyClaimed.getAsBoolean()) {
            return null;
        }
        GuardrailContext context = context(task, memory, messages, spend);
        CallOutcome outcome = screenCall(task, entries, memory, toolName, arguments, toolSources, toolEndpoints, context, trace, newRunBudget(),
                Mode.BLOCK_ONLY, !amended, STAGE_RESUME);
        return outcome.refusal();
    }

    private static GuardrailContext context(LlmConfiguration.Task task, IConversationMemory memory, List<ChatMessage> messages, PaidCallGate spend) {
        return new GuardrailContext(GuardrailPhase.TOOL_CALL, task.getId(), memory.getConversationId(), memory.getAgentId(), null, messages, null,
                memory.getTurnDeadline(), spend);
    }

    private CallOutcome screenCall(LlmConfiguration.Task task, List<ToolCallGuardrailConfig> entries, IConversationMemory memory, String toolName,
                                   String arguments, Map<String, String> toolSources, Map<String, String> toolEndpoints, GuardrailContext context,
                                   List<Map<String, Object>> trace, RunBudget budget, Mode mode, boolean deterministicOnly, String stage) {
        List<String> addresses = ToolApprovalGate.addressesOf(toolName, toolSources != null ? toolSources : Map.of(),
                toolEndpoints != null ? toolEndpoints : Map.of());
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ToolCallGuardrailConfig entry = entries.get(i);
            if (entry != null && matches(entry.getTools(), addresses)) {
                matching.add(i);
            }
        }
        if (matching.isEmpty()) {
            return new CallOutcome(arguments, false, null);
        }
        String shownTool = toolName != null && SAFE_TOOL_NAME.matcher(toolName).matches() ? toolName : "unknown";
        ArgumentFields.Document document;
        try {
            document = ArgumentFields.parse(arguments);
        } catch (ArgumentFields.UnparseableException e) {
            for (int index : matching) {
                ToolCallGuardrailConfig entry = entries.get(index);
                var result = screener.undecided(entry, ToolCallGuardrailScreener.REASON_UNPARSEABLE,
                        recorder(task, memory, trace, shownTool, fieldLabel(entry, null), index, stage));
                if (result.refuses()) {
                    return refused(arguments, false, entry, result, null, stage);
                }
            }
            count(stage, "allowed");
            return new CallOutcome(arguments, false, null);
        }
        for (int index : matching) {
            ToolCallGuardrailConfig entry = entries.get(index);
            if (deterministicOnly && screener.isModelBacked(entry)) {
                continue;
            }
            List<ArgumentFields.Field> fields;
            try {
                fields = document.select(entry.getFields());
            } catch (ArgumentFields.TooLargeException e) {
                var result = screener.undecided(entry, ToolCallGuardrailScreener.REASON_TOO_LARGE,
                        recorder(task, memory, trace, shownTool, fieldLabel(entry, null), index, stage));
                if (result.refuses()) {
                    return refused(document.arguments(), document.changed(), entry, result, null, stage);
                }
                continue;
            }
            int reprompts = mode == Mode.LIVE ? repromptsSoFar(trace, index) : 0;
            for (ArgumentFields.Field field : fields) {
                String label = fieldLabel(entry, field);
                Consumer<GuardrailOutcome> record = recorder(task, memory, trace, shownTool, label, index, stage);
                var result = screener.screen(entry, field.text(), context, reprompts, mode, budget, record);
                if (result.decision() == Decision.REWRITE) {
                    if (!field.replace(result.text())) {
                        var refusal = screener.refuse(entry, ToolCallGuardrailScreener.REASON_REWRITE_NOT_APPLICABLE, record);
                        return refused(document.arguments(), document.changed(), entry, refusal, label, stage);
                    }
                } else if (result.refuses()) {
                    if (result.text() != null) {
                        // What the guardrail found does not stay in the transcript of a
                        // call that does not run.
                        field.replace(result.text());
                    }
                    return refused(document.arguments(), document.changed(), entry, result, label, stage);
                }
            }
        }
        count(stage, document.changed() ? "rewritten" : "allowed");
        return new CallOutcome(document.arguments(), document.changed(), null);
    }

    private CallOutcome refused(String arguments, boolean changed, ToolCallGuardrailConfig entry, ToolCallGuardrailScreener.Result result,
                                String field, String stage) {
        count(stage, result.undecided() ? "undecided" : result.decision() == Decision.REPROMPT ? "reprompted" : "blocked");
        return new CallOutcome(arguments, changed, envelope(entry, result, field != null ? field : fieldLabel(entry, null)));
    }

    /**
     * The {@code NOT_EXECUTED} result of a refused call — closed vocabulary only.
     */
    static String envelope(ToolCallGuardrailConfig entry, ToolCallGuardrailScreener.Result result, String field) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "NOT_EXECUTED");
        envelope.put("reason", REFUSAL_REASON);
        envelope.put("guardrail", ToolCallGuardrailScreener.safe(entry.getType(), "guardrail"));
        envelope.put("field", field);
        envelope.put("issues", result.issues());
        envelope.put("message", result.message() != null ? result.message() : ToolCallGuardrailScreener.blockMessage(entry));
        envelope.put("retryAllowed", result.decision() == Decision.REPROMPT);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"NOT_EXECUTED\",\"reason\":\"" + REFUSAL_REASON + "\"}";
        }
    }

    /**
     * What a finding names as its field: the configured pointer — author text — or
     * {@code *} for "every string", never a key the model wrote.
     */
    private static String fieldLabel(ToolCallGuardrailConfig entry, ArgumentFields.Field field) {
        if (entry.getFields() == null || entry.getFields().isEmpty()) {
            return ArgumentFields.ALL_STRING_FIELDS;
        }
        if (field == null) {
            return String.join(",", entry.getFields());
        }
        return field.pointer();
    }

    /**
     * Reprompts this entry already sent in the turn, read from the trace — so a
     * HITL resume, whose trace is seeded with the turn's, continues the count.
     */
    static int repromptsSoFar(List<Map<String, Object>> trace, int entryIndex) {
        int count = 0;
        for (Map<String, Object> step : trace) {
            if (TRACE_TYPE.equals(step.get("type")) && "reprompt".equals(step.get("action"))
                    && Integer.valueOf(entryIndex).equals(step.get("entry"))) {
                count++;
            }
        }
        return count;
    }

    private boolean matches(List<String> tools, List<String> addresses) {
        if (tools == null || addresses.isEmpty()) {
            return false;
        }
        for (String glob : tools) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            Pattern pattern = patterns.get(glob);
            if (pattern == null) {
                pattern = ToolApprovalPatterns.compile(glob);
                if (patterns.size() >= MAX_CACHED_PATTERNS) {
                    patterns.clear();
                }
                patterns.put(glob, pattern);
            }
            for (String address : addresses) {
                if (pattern.matcher(address).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records each finding: audit record, SSE event, trace step. The step writes
     * are skipped on an interrupted (abandoned) thread, so a cascade step that was
     * given up on never writes to the shared memory.
     */
    private Consumer<GuardrailOutcome> recorder(LlmConfiguration.Task task, IConversationMemory memory, List<Map<String, Object>> trace,
                                                String tool, String field, int entryIndex, String stage) {
        return outcome -> {
            Map<String, Object> step = new HashMap<>();
            step.put("type", TRACE_TYPE);
            step.put("tool", tool);
            step.put("guardrail", ToolCallGuardrailScreener.safe(outcome.type(), "guardrail"));
            step.put("action", outcome.action().label());
            step.put("field", field);
            step.put("entry", entryIndex);
            step.put("stage", stage);
            trace.add(step);
            LOGGER.debugf("Tool-call guardrail '%s' on '%s' (%s): %s", sanitize(outcome.type()), sanitize(tool), stage, outcome.action().label());
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String taskId = task.getId() != null ? task.getId() : "";
            var currentStep = memory.getCurrentStep();
            if (currentStep != null) {
                String key = MemoryKeys.AUDIT_GUARDRAIL_PREFIX + outcome.phase().label() + ":" + outcome.type();
                List<Object> records = new ArrayList<>();
                IData<List<Object>> existing = currentStep.getLatestData(key);
                if (existing != null && existing.getResult() != null) {
                    records.addAll(existing.getResult());
                }
                Map<String, Object> record = outcome.toAuditRecord(taskId);
                record.put("tool", tool);
                record.put("field", field);
                record.put("stage", stage);
                records.add(record);
                currentStep.storeData(new Data<>(key, records));
            }
            var sink = memory.getEventSink();
            if (sink != null) {
                sink.onGuardrailTriggered(outcome.type(), outcome.phase().label(), outcome.action().label(), taskId);
            }
        };
    }

    private void count(String stage, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_DECISIONS, "stage", stage, "outcome", outcome).increment();
        }
    }

    private static void replaceInTranscript(List<ChatMessage> messages, AiMessage original, AiMessage replacement) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == original) {
                messages.set(i, replacement);
                return;
            }
        }
    }
}
