/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * OpenAI {@code usage} block for a completion.
 * <p>
 * Sourced from EDDI's per-turn {@code audit:token_usage} entry, which
 * {@code LlmTask} accumulates across every model call the turn made — cascade
 * steps and tool round-trips included. So a turn that escalated through three
 * models reports the sum, not the last leg.
 * <p>
 * Rule-based agents make no model calls and therefore have no usage at all;
 * {@link #from} returns {@code null} for them rather than zeros, because "0
 * tokens" reads in a client as a measurement rather than as an absence.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenUsage(@JsonProperty("prompt_tokens") Long promptTokens,
        @JsonProperty("completion_tokens") Long completionTokens,
        @JsonProperty("total_tokens") Long totalTokens) {

    /**
     * Build a usage block from EDDI's {@code audit:token_usage} map, whose fields
     * are {@code inputTokens} / {@code outputTokens} / {@code totalTokens}.
     *
     * @param auditTokenUsage
     *            the audit map, or {@code null}
     * @return the usage block, or {@code null} if no count could be read — an agent
     *         that never called a model has no usage to report
     */
    public static TokenUsage from(Map<String, Object> auditTokenUsage) {
        if (auditTokenUsage == null || auditTokenUsage.isEmpty()) {
            return null;
        }
        Long input = asLong(auditTokenUsage.get("inputTokens"));
        Long output = asLong(auditTokenUsage.get("outputTokens"));
        Long total = asLong(auditTokenUsage.get("totalTokens"));

        if (input == null && output == null && total == null) {
            return null;
        }
        // Providers are inconsistent about totalTokens; derive it when both parts
        // are known rather than emitting a usage block that fails to add up.
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        return new TokenUsage(input, output, total);
    }

    /**
     * Providers and the datastore round-trip disagree on numeric type — Integer,
     * Long and Double all occur for the same field.
     */
    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
