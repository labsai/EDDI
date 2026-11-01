/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * The rule with every target holding a {@code targetId}: a missing one gets
     * {@link DeliveryTarget#derivedTargetId()}, or a random id when that is taken
     * in this rule. Existing ids are kept.
     */
    public AlertRule withTargetIds() {
        if (delivery == null || delivery.isEmpty()) {
            return this;
        }
        Set<String> used = new HashSet<>();
        for (DeliveryTarget target : delivery) {
            if (target != null && target.targetId() != null && !target.targetId().isBlank()) {
                used.add(target.targetId());
            }
        }
        List<DeliveryTarget> targets = new ArrayList<>(delivery.size());
        for (DeliveryTarget target : delivery) {
            if (target == null || (target.targetId() != null && !target.targetId().isBlank())) {
                targets.add(target);
                continue;
            }
            String id = target.derivedTargetId();
            while (!used.add(id)) {
                id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            targets.add(target.withTargetId(id));
        }
        return withDelivery(targets);
    }

    public AlertRule withDelivery(List<DeliveryTarget> newDelivery) {
        return new AlertRule(id, name, enabled, scope, source, condition, newDelivery, throttle, digest, createdBy, createdAt, updatedBy,
                updatedAt);
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
