/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

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
 *            guardrail that needs context such as retrieved documents
 */
public record GuardrailContext(GuardrailPhase phase, String taskId, String conversationId, String agentId, Map<String, String> parameters,
        List<ChatMessage> messages) {

    public GuardrailContext {
        parameters = parameters != null ? Map.copyOf(withoutNulls(parameters)) : Map.of();
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    /** This context for another phase, parameter set and message list. */
    public GuardrailContext with(GuardrailPhase newPhase, Map<String, String> newParameters, List<ChatMessage> newMessages) {
        return new GuardrailContext(newPhase, taskId, conversationId, agentId, newParameters, newMessages);
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
