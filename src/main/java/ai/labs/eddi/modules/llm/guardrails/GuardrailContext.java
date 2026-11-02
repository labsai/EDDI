/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.configs.shared.TurnDeadline;
import dev.langchain4j.data.message.ChatMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a guardrail may know about one evaluation. Built per call, so the
 * guardrail beans themselves stay stateless singletons.
 *
 * @param phase
 *            input or output
 * @param taskId
 *            the LLM task's id
 * @param conversationId
 *            for logging only
 * @param agentId
 *            for logging only
 * @param parameters
 *            the guardrail's configured {@code parameters}; never null, never
 *            templated
 * @param messages
 *            what the model sees (input) or saw (output), read-only — for a
 *            guardrail that needs context such as the user's question
 * @param retrievedContext
 *            the RAG context retrieved for this turn (output phase), or null —
 *            for a groundedness check by a model-backed guardrail
 * @param deadline
 *            the turn's deadline, or null when the turn has none — a guardrail
 *            that makes a network call must not outlive it
 * @param spend
 *            asked before a guardrail makes a paid call of its own; never null
 *            ({@link PaidCallGate#UNGATED} when none is given)
 */
public record GuardrailContext(GuardrailPhase phase, String taskId, String conversationId, String agentId, Map<String, String> parameters,
        List<ChatMessage> messages, String retrievedContext, TurnDeadline deadline, PaidCallGate spend) {

    public GuardrailContext {
        parameters = parameters != null ? Map.copyOf(withoutNulls(parameters)) : Map.of();
        messages = messages != null ? List.copyOf(messages) : List.of();
        spend = spend != null ? spend : PaidCallGate.UNGATED;
    }

    /** A context without spend checks. */
    public GuardrailContext(GuardrailPhase phase, String taskId, String conversationId, String agentId, Map<String, String> parameters,
            List<ChatMessage> messages, String retrievedContext, TurnDeadline deadline) {
        this(phase, taskId, conversationId, agentId, parameters, messages, retrievedContext, deadline, null);
    }

    /** A context without retrieved context or deadline. */
    public GuardrailContext(GuardrailPhase phase, String taskId, String conversationId, String agentId, Map<String, String> parameters,
            List<ChatMessage> messages) {
        this(phase, taskId, conversationId, agentId, parameters, messages, null, null, null);
    }

    /**
     * This context for another phase, parameter set and message list; the retrieved
     * context, the deadline and the spend checks are kept.
     */
    public GuardrailContext with(GuardrailPhase newPhase, Map<String, String> newParameters, List<ChatMessage> newMessages) {
        return new GuardrailContext(newPhase, taskId, conversationId, agentId, newParameters, newMessages, retrievedContext, deadline, spend);
    }

    /** This context with other spend checks. */
    public GuardrailContext withSpend(PaidCallGate newSpend) {
        return new GuardrailContext(phase, taskId, conversationId, agentId, parameters, messages, retrievedContext, deadline, newSpend);
    }

    /** A parameter, or {@code fallback} when it is unset or blank. */
    public String parameter(String name, String fallback) {
        String value = parameters.get(name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * An integer parameter, or {@code fallback} when it is unset or not a number.
     */
    public int intParameter(String name, int fallback) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> withoutNulls(Map<String, String> in) {
        Map<String, String> copy = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }
}
