/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.ClientContextGuard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the {@link HealthTurn} of a finished turn from its live memory — on
 * the turn's thread, reading only the current step, and copying plain values so
 * the live memory never reaches the health worker (the
 * {@code OutputMatchHook.snapshot} pattern).
 * <p>
 * Where each value comes from:
 * <ul>
 * <li>cost — {@code audit:cost} and {@code audit:discarded_cost}, the keys
 * {@code ConversationMemoryUtilities#conversationCost} sums; tokens —
 * {@code audit:token_usage}. They are written with auditing on or off;</li>
 * <li>failures — {@code health:llm_failures}; fallback —
 * {@code llm:fallback:*}; guardrail trips — the {@code audit:guardrail:*}
 * records;</li>
 * <li>tools — the {@code langchain:trace:*} traces (not
 * {@code audit:tool_calls}, which exists only with auditing on). A
 * {@code tool_result} entry is a call that ran — marked {@code failed} when it
 * failed — or, marked {@code notExecuted}, one that never ran; a
 * {@code tool_error} entry is a call refused before it ran. {@code tool_call}
 * entries are requests and are not counted: some refusals write one, some do
 * not;</li>
 * <li>{@code userInput} — {@code input:initial} is not blank and the turn is
 * not a self-continuation ({@code context:selfContinuation});
 * {@code agentOutput} and {@code lastOutputWasQuestion} — the step's output
 * text; the question test is a heuristic: the trimmed text ends with {@code ?}
 * (or the full-width {@code U+FF1F}). It cannot see a question phrased without
 * one, nor tell a rhetorical one; it is only ever a hint for outcome
 * inference.</li>
 * </ul>
 * No text leaves the step: neither input, output nor property values.
 */
public final class HealthTurnFactory {

    /** At most this many actions are kept on a row. */
    static final int MAX_ACTIONS = 32;
    /** Longer action names are cut. */
    static final int MAX_ACTION_LENGTH = 128;
    /** At most this many failure entries are kept on a row. */
    static final int MAX_FAILURES = 20;

    static final String TRACE_TYPE = "type";
    static final String TRACE_TOOL_RESULT = "tool_result";
    static final String TRACE_TOOL_ERROR = "tool_error";
    /** Flags {@code ToolLoopRunner} sets on a {@code tool_result} entry. */
    static final String TRACE_FAILED = "failed";
    static final String TRACE_NOT_EXECUTED = "notExecuted";
    /**
     * Trace entries of a call that was answered with a refusal instead of running,
     * each written <em>instead of</em> a {@code tool_result}: the repeat guard, the
     * per-response call caps, a call to a lazy tool not activated yet, a call into
     * the agent's own conversation, and the HITL resume's refusals of an approved
     * (or declined) call. {@code tool_repeat_stopped}, which ends the loop rather
     * than answering a call, and the guardrail findings, counted as
     * {@code guardrails}, are not among them.
     */
    static final Set<String> TRACE_REFUSALS = Set.of("tool_repeat_refused", "tool_call_capped", "tool_not_activated",
            "hitl_self_conversation", "hitl_self_ungating", "hitl_request_changed", "hitl_not_executed", "hitl_rejected");

    private static final String FULL_WIDTH_QUESTION_MARK = "？";

    /**
     * What a finished turn produced: its row and the executions it discarded.
     *
     * @param discardedExecutionIds
     *            the rows of the same conversation to mark discarded — always empty
     *            for an unsettled turn
     * @param settled
     *            the turn's outcome is what the conversation keeps: not abandoned
     *            by the watchdog, not cancelled, not interrupted. An unsettled
     *            turn's row is only <em>inserted</em>, never replaces one, and
     *            discards nothing: its snapshot may be thrown away (a cancelled say
     *            after an undo leaves the undone step in the redo cache), and an
     *            abandoned rerun or resume leaves the persisted step as it was
     */
    public record Capture(HealthTurn turn, List<String> discardedExecutionIds, boolean settled) {
        public Capture {
            discardedExecutionIds = discardedExecutionIds == null || !settled ? List.of() : List.copyOf(discardedExecutionIds);
        }
    }

    private HealthTurnFactory() {
    }

    /**
     * @param conversationId
     *            the conversation's id, when the memory does not carry it yet (the
     *            start turn); {@code null} to take the memory's
     * @param kind
     *            how the turn ended, {@code null} for a clean (or paused) turn
     * @param durationMs
     *            how long the hook saw the turn run, {@code null} when unknown
     * @param abandoned
     *            the watchdog gave up waiting (timeout, interrupted wait): the turn
     *            may still be running
     * @return the capture, or {@code null} when the memory names no conversation or
     *         agent
     */
    public static Capture capture(IConversationMemory memory, String conversationId, ErrorKind kind, Long durationMs, Instant now,
                                  boolean abandoned) {
        if (memory == null) {
            return null;
        }
        String id = conversationId != null ? conversationId : memory.getConversationId();
        String agentId = memory.getAgentId();
        if (id == null || agentId == null) {
            return null;
        }
        int stepIndex = Math.max(0, memory.size() - 1);
        IConversationStep step = memory.getCurrentStep();
        String userId = memory.getUserId();
        boolean paused = memory.getConversationState() == ConversationState.AWAITING_HUMAN;
        Instant timestamp = now != null ? now : Instant.now();

        Integer version = memory.getAgentVersion();
        Integer previousVersion = null;
        Double cost = null;
        Double discardedCost = null;
        Long inputTokens = null;
        Long outputTokens = null;
        Long totalTokens = null;
        List<HealthFailure> failures = List.of();
        boolean fallback = false;
        int[] tools = {0, 0, 0};
        int guardrails = 0;
        boolean userInput = false;
        boolean agentOutput = false;
        boolean question = false;
        List<String> actions = List.of();
        List<String> discarded = List.of();
        String executionId = null;
        String origin = HealthOrigins.classify(userId, step);
        if (step != null) {
            executionId = TurnExecutions.executionId(step);
            if (executionId == null && predatesExecutionIds(memory, step, stepIndex)) {
                executionId = HealthTurn.LEGACY_EXECUTION_PREFIX + stepIndex;
            }
            discarded = TurnExecutions.discardedBy(step);
            Integer stepVersion = integer(value(step, MemoryKeys.AGENT_VERSION.key()));
            if (stepVersion != null) {
                version = stepVersion;
            }
            if (value(step, MemoryKeys.AGENT_VERSION_CHANGE.key()) instanceof Map<?, ?> change) {
                previousVersion = integer(change.get("from"));
            }
            cost = spend(value(step, MemoryKeys.AUDIT_COST));
            discardedCost = spend(value(step, MemoryKeys.AUDIT_DISCARDED_COST));
            if (value(step, MemoryKeys.AUDIT_TOKEN_USAGE) instanceof Map<?, ?> usage) {
                inputTokens = count(usage.get("inputTokens"));
                outputTokens = count(usage.get("outputTokens"));
                totalTokens = count(usage.get("totalTokens"));
            }
            failures = failures(value(step, MemoryKeys.HEALTH_LLM_FAILURES));
            fallback = anyTrue(step.getAllData(MemoryKeys.LLM_FALLBACK_PREFIX));
            countTools(step.getAllData(MemoryKeys.LANGCHAIN_TRACE_PREFIX), tools);
            guardrails = countEntries(step.getAllData(MemoryKeys.AUDIT_GUARDRAIL_PREFIX));
            userInput = notBlank(value(step, MemoryKeys.INPUT_INITIAL.key()))
                    && step.getData(MemoryKeys.CONTEXT_PREFIX + ":" + ClientContextGuard.KEY_SELF_CONTINUATION) == null;
            String text = ConversationOutputExtractor.extractText(step.getConversationOutput());
            String trimmed = text == null ? "" : text.strip();
            agentOutput = !trimmed.isEmpty();
            question = trimmed.endsWith("?") || trimmed.endsWith(FULL_WIDTH_QUESTION_MARK);
            actions = actions(value(step, MemoryKeys.ACTIONS.key()));
        }
        if (executionId == null) {
            // A step not stamped yet: a turn the watchdog abandoned before its end. Its
            // own id is unknown, and legacy-<index> would let every timeout at this step
            // overwrite the last one — a fresh id records each.
            executionId = UUID.randomUUID().toString();
        }
        boolean settled = !abandoned && !memory.isCancelled() && kind != ErrorKind.TURN_INTERRUPTED && kind != ErrorKind.TURN_DEADLINE;
        HealthTurn turn = new HealthTurn(id, stepIndex, executionId, agentId, version, previousVersion, userId, origin,
                LocalDate.ofInstant(timestamp, ZoneOffset.UTC), timestamp, timestamp, cost, discardedCost, inputTokens, outputTokens,
                totalTokens, durationMs == null || durationMs < 0 ? null : durationMs, kind == null ? null : kind.name(), paused, failures,
                fallback, tools[0], tools[1], tools[2], guardrails, userInput, agentOutput, question, actions, false);
        return new Capture(turn, discarded, settled);
    }

    /**
     * A step without an execution id predates Agent Health when it already finished
     * an execution — {@code agent:version} is written at the end of every
     * execution, right after the id would have been — or when it was persisted
     * before this turn loaded it.
     */
    static boolean predatesExecutionIds(IConversationMemory memory, IConversationStep step, int stepIndex) {
        return step.getData(MemoryKeys.AGENT_VERSION.key()) != null || stepIndex < memory.getPersistedStepCount();
    }

    private static Object value(IConversationStep step, String key) {
        IData<Object> data = step.getData(key);
        return data == null ? null : data.getResult();
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Long count(Object value) {
        return value instanceof Number n && n.longValue() >= 0 ? n.longValue() : null;
    }

    /** A finite, non-negative amount, as {@code conversationCost} accepts it. */
    private static Double spend(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0.0 ? n.doubleValue() : null;
    }

    private static boolean notBlank(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private static List<HealthFailure> failures(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<HealthFailure> out = new ArrayList<>(Math.min(list.size(), MAX_FAILURES));
        for (Object entry : list) {
            if (out.size() >= MAX_FAILURES) {
                break;
            }
            HealthFailure failure = HealthFailure.fromRecord(entry);
            if (failure != null) {
                out.add(failure);
            }
        }
        return out;
    }

    private static boolean anyTrue(List<IData<Object>> flags) {
        if (flags == null) {
            return false;
        }
        for (IData<Object> flag : flags) {
            if (flag != null && Boolean.TRUE.equals(flag.getResult())) {
                return true;
            }
        }
        return false;
    }

    /** Counts into {@code tools}: {ran, ran and failed, never ran}. */
    private static void countTools(List<IData<Object>> traces, int[] tools) {
        if (traces == null) {
            return;
        }
        for (IData<Object> trace : traces) {
            if (trace == null || !(trace.getResult() instanceof List<?> entries)) {
                continue;
            }
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                Object type = map.get(TRACE_TYPE);
                if (TRACE_TOOL_RESULT.equals(type)) {
                    if (Boolean.TRUE.equals(map.get(TRACE_NOT_EXECUTED))) {
                        tools[2]++;
                    } else {
                        tools[0]++;
                        if (Boolean.TRUE.equals(map.get(TRACE_FAILED))) {
                            tools[1]++;
                        }
                    }
                } else if (TRACE_TOOL_ERROR.equals(type) || type instanceof String name && TRACE_REFUSALS.contains(name)) {
                    tools[2]++;
                }
            }
        }
    }

    private static int countEntries(List<IData<Object>> records) {
        int total = 0;
        if (records == null) {
            return 0;
        }
        for (IData<Object> record : records) {
            if (record != null && record.getResult() instanceof List<?> list) {
                total += list.size();
            }
        }
        return total;
    }

    private static List<String> actions(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.min(list.size(), MAX_ACTIONS));
        for (Object action : list) {
            if (out.size() >= MAX_ACTIONS) {
                break;
            }
            if (action != null) {
                String name = String.valueOf(action);
                out.add(name.length() > MAX_ACTION_LENGTH ? name.substring(0, MAX_ACTION_LENGTH) : name);
            }
        }
        return out;
    }
}
