/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One fired alert — the outbox row and the inbox entry in one. Written before
 * anything is delivered; each delivery target keeps its own status.
 * <p>
 * <b>What it holds, and what it never holds.</b> No user id and no conversation
 * text: the rule, the agent and version, the conversation id, and
 * {@link AlertMatch what matched}. The one piece of user-written text is a
 * rating comment, cut to {@link AlertMatch#MAX_COMMENT_EXCERPT} characters.
 *
 * @param kind
 *            an alert, or a digest that lists alerts held back by the throttle
 * @param dedupeKey
 *            {@code ruleId|agentId|conversationId|windowStart} — unique in the
 *            store, which is what makes the dedupe hold across replicas
 * @param duplicates
 *            how many later alerts with the same key were folded into this one
 * @param nextAttemptAt
 *            when a delivery is next due (also the claim lease of the node
 *            delivering it); {@code null} when nothing is pending
 * @param digestCount
 *            DIGEST: how many alerts it lists
 * @param digestItems
 *            DIGEST: one line per listed alert (at most
 *            {@link #MAX_DIGEST_ITEMS})
 * @param version
 *            optimistic-concurrency counter, bumped by every delivery update
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertEvent(String id, String ruleId, String ruleName, AlertSource source, Kind kind, String agentId, Integer agentVersion,
        String conversationId, Instant firedAt, String summary, AlertMatch match, List<Delivery> deliveries, State state,
        Instant nextAttemptAt, String dedupeKey, int duplicates, Integer digestCount, List<String> digestItems, String acknowledgedBy,
        Instant acknowledgedAt, long version) {

    /** The most alerts a digest lists by line; the count covers all of them. */
    public static final int MAX_DIGEST_ITEMS = 50;

    public AlertEvent {
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        digestItems = digestItems == null ? null : List.copyOf(digestItems);
    }

    public enum Kind {
        ALERT, DIGEST
    }

    public enum State {
        /** Delivery not finished: being delivered, or a target waits for a retry. */
        PENDING,
        /** Every target took it. */
        DELIVERED,
        /** A target gave up after the last retry. */
        FAILED,
        /** Recorded in the inbox only: no targets, or held back with no digest. */
        INBOX_ONLY,
        /** Held back by the throttle; goes out in the rule's next digest. */
        DIGEST_PENDING,
        /** Went out in a digest. */
        DIGESTED
    }

    /** One target's delivery. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String targetKey, DeliveryTarget.DeliveryType type, String label, DeliveryStatus status, int attempts,
            Instant lastAttemptAt, String lastError, Instant nextAttemptAt) {

        public static Delivery pending(DeliveryTarget target) {
            return new Delivery(target.key(), target.type(), target.label(), DeliveryStatus.PENDING, 0, null, null, null);
        }
    }

    public enum DeliveryStatus {
        PENDING, DELIVERED, FAILED
    }

    public boolean acknowledged() {
        return acknowledgedAt != null;
    }

    public AlertEvent withDelivery(List<Delivery> newDeliveries, State newState, Instant newNextAttemptAt) {
        return new AlertEvent(id, ruleId, ruleName, source, kind, agentId, agentVersion, conversationId, firedAt, summary, match,
                newDeliveries, newState, newNextAttemptAt, dedupeKey, duplicates, digestCount, digestItems, acknowledgedBy, acknowledgedAt,
                version);
    }

    public AlertEvent withId(String newId) {
        return new AlertEvent(newId, ruleId, ruleName, source, kind, agentId, agentVersion, conversationId, firedAt, summary, match,
                deliveries, state, nextAttemptAt, dedupeKey, duplicates, digestCount, digestItems, acknowledgedBy, acknowledgedAt, version);
    }

    public AlertEvent withVersion(long newVersion) {
        return new AlertEvent(id, ruleId, ruleName, source, kind, agentId, agentVersion, conversationId, firedAt, summary, match,
                deliveries, state, nextAttemptAt, dedupeKey, duplicates, digestCount, digestItems, acknowledgedBy, acknowledgedAt,
                newVersion);
    }
}
