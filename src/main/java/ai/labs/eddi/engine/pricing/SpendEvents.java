/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;

import java.time.Instant;
import java.util.Map;

/**
 * Builds the {@link SpendEvent} for a settled conversation turn, attributed
 * from the conversation and — for a group member turn — the group context the
 * orchestrator put on the current step ({@code context:groupId},
 * {@code context:groupConversationId}). Exact-key reads only: a prefix lookup
 * would also return a client-sent {@code context:groupIdSuffix}.
 */
public final class SpendEvents {

    private SpendEvents() {
    }

    /** The event for {@code usd} just charged for the current step. */
    public static SpendEvent forTurn(IConversationMemory memory, String tenantId, double usd) {
        var step = memory.getCurrentStep();
        String groupId = step != null ? contextValue(step.getData(MemoryKeys.CONTEXT_PREFIX + ":groupId")) : null;
        String groupConversationId = step != null ? contextValue(step.getData(MemoryKeys.CONTEXT_PREFIX + ":groupConversationId")) : null;
        int unpriced = 0;
        long input = 0;
        long output = 0;
        if (step != null) {
            if (step.getData(MemoryKeys.AUDIT_PRICING) != null && step.getData(MemoryKeys.AUDIT_PRICING).getResult() instanceof Map<?, ?> pricing) {
                unpriced = (int) asLong(pricing.get("unpricedCalls"));
            }
            if (step.getData(MemoryKeys.AUDIT_TOKEN_USAGE) != null
                    && step.getData(MemoryKeys.AUDIT_TOKEN_USAGE).getResult() instanceof Map<?, ?> tokens) {
                input = asLong(tokens.get("inputTokens"));
                output = asLong(tokens.get("outputTokens"));
            }
        }
        return new SpendEvent(tenantId, memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(), memory.getUserId(), groupId,
                groupConversationId, SpendEvent.Kind.TURN, null, usd, unpriced, input, output, Instant.now());
    }

    private static String contextValue(IData<?> data) {
        if (data == null || data.getResult() == null) {
            return null;
        }
        Object result = data.getResult();
        Object value = result instanceof Context ctx ? ctx.getValue() : result;
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
