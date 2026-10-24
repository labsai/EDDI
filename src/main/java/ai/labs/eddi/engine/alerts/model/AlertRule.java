/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import java.time.Instant;
import java.util.List;

/**
 * An alert rule: which agents, which source and condition, where to deliver,
 * and how much to send.
 *
 * @param throttle
 *            the spam control; {@code null} means the defaults
 * @param digest
 *            how overflow past the throttle cap is delivered; {@code null}
 *            means {@link DigestMode#HOURLY}
 */
public record AlertRule(String id, String name, boolean enabled, AlertScope scope, AlertSource source, AlertCondition condition,
        List<DeliveryTarget> delivery, Throttle throttle, DigestMode digest, String createdBy, Instant createdAt, String updatedBy,
        Instant updatedAt) {

    public AlertRule {
        delivery = delivery == null ? List.of() : List.copyOf(delivery);
    }

    /**
     * At most {@code maxPerWindow} alerts of a rule are delivered per window of
     * {@code windowMinutes}; an alert for the same rule, agent and conversation is
     * delivered once per window.
     */
    public record Throttle(int windowMinutes, int maxPerWindow) {
        public static final Throttle DEFAULT = new Throttle(60, 10);
    }

    public enum DigestMode {
        /** Overflow stays in the inbox and is never delivered elsewhere. */
        NONE, HOURLY, DAILY
    }

    public Throttle effectiveThrottle() {
        return throttle == null ? Throttle.DEFAULT : throttle;
    }

    public DigestMode effectiveDigest() {
        return digest == null ? DigestMode.HOURLY : digest;
    }

    public AlertRule withId(String newId) {
        return new AlertRule(newId, name, enabled, scope, source, condition, delivery, throttle, digest, createdBy, createdAt, updatedBy,
                updatedAt);
    }

    public AlertRule withAudit(String newCreatedBy, Instant newCreatedAt, String newUpdatedBy, Instant newUpdatedAt) {
        return new AlertRule(id, name, enabled, scope, source, condition, delivery, throttle, digest, newCreatedBy, newCreatedAt,
                newUpdatedBy, newUpdatedAt);
    }
}
