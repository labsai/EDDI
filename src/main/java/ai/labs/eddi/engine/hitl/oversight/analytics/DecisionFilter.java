/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import java.time.Instant;

/**
 * Which approval-decision rows a query reads. Every field is optional; null
 * means "any". {@code from} is inclusive, {@code to} exclusive, both on
 * {@code decidedAt}.
 *
 * @since 6.7.0
 */
public record DecisionFilter(String agentId, String groupId, String category, Instant from, Instant to, String surface, String pauseType,
        String channel, Integer agentVersion) {

    /** The rows of one agent's category in a window. */
    public static DecisionFilter ofCategory(String agentId, String category, Instant from, Instant to) {
        return new DecisionFilter(agentId, null, category, from, to, null, null, null, null);
    }

    /** Whether a row passes the filter (the in-memory twin of the store query). */
    public boolean test(ApprovalDecision d) {
        return eq(agentId, d.agentId()) && eq(groupId, d.groupId()) && eq(category, d.category())
                && (from == null || d.decidedAt() != null && !d.decidedAt().isBefore(from))
                && (to == null || d.decidedAt() != null && d.decidedAt().isBefore(to)) && eq(surface, d.surface())
                && eq(pauseType, d.pauseType()) && eq(channel, d.channel())
                && (agentVersion == null || agentVersion.equals(d.agentVersion()));
    }

    private static boolean eq(String wanted, String actual) {
        return wanted == null || wanted.equals(actual);
    }
}
