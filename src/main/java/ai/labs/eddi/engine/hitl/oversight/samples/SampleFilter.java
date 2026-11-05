/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import java.time.Instant;

/**
 * Which samples a query reads; null fields mean "any". {@code from} inclusive,
 * {@code to} exclusive, on {@code sampledAt}.
 *
 * @since 6.7.0
 */
public record SampleFilter(String agentId, String category, AuditSample.Status status, Instant from, Instant to) {

    /** The pending samples of an agent (null = all agents). */
    public static SampleFilter pending(String agentId) {
        return new SampleFilter(agentId, null, AuditSample.Status.PENDING, null, null);
    }
}
