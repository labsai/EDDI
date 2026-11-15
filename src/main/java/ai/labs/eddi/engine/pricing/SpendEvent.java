/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import java.time.Instant;

/**
 * One charge to the tenant's spend, as {@link ISpendSink}s see it: who it is
 * attributed to, what incurred it, and the dollars just charged — the
 * <em>delta</em>, never a running total, delivered exactly once per charge.
 * <p>
 * Stable contract for consumers (per-team budgets, cost analytics): fields are
 * only ever added.
 *
 * @param tenantId
 *            the tenant charged
 * @param conversationId
 *            the conversation that spent, or null for group machinery and Dream
 * @param agentId
 *            the agent, or null
 * @param agentVersion
 *            the agent version, or null
 * @param userId
 *            the user, or null
 * @param groupId
 *            the group the spend belongs to — a member turn's group, or the
 *            group whose machinery spent; null outside a group
 * @param groupConversationId
 *            the group conversation, or null
 * @param kind
 *            what incurred the spend
 * @param detail
 *            a short machine-readable label ({@code summarizer:full:12},
 *            {@code stance:agent-1}, ...), or null
 * @param usd
 *            the dollars just charged; finite and positive
 * @param unpricedCalls
 *            model calls in this charge that had no price ($0, counted)
 * @param inputTokens
 *            input tokens in this charge, 0 when unknown
 * @param outputTokens
 *            output tokens in this charge, 0 when unknown
 * @param at
 *            when it was charged
 */
public record SpendEvent(String tenantId, String conversationId, String agentId, Integer agentVersion, String userId, String groupId,
        String groupConversationId, Kind kind, String detail, double usd, int unpricedCalls, long inputTokens, long outputTokens, Instant at) {

    /** What incurred a charge. */
    public enum Kind {
        /** A conversation turn, settled by {@code TurnCostMeter}. */
        TURN,
        /** A Dream consolidation run. */
        DREAM,
        /** A group conversation's own machinery (window and stance summaries). */
        GROUP_SYSTEM
    }
}
