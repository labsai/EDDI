/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One business event an external system reported about an agent's work — the
 * row of {@code healthevents} / {@code health_events} (plan §5, H5). It arrives
 * through a {@code health-outcomes} target of a signed webhook integration, is
 * stored {@link #PENDING}, and is matched by the outcome pass to the session it
 * is about — by the conversation id it names, or by a business reference the
 * session set ({@code orderId=A-123}). A matched event carries that session's
 * conversation, step, version, origin and user; an event that stays unmatched
 * for {@code eddi.health.event-match-window} (72 h) is counted and deleted.
 * <p>
 * Holds no conversation content: ids, the event type, an optional number and
 * the reference in its stored form ({@code HealthRefs} — hashed when the agent
 * hashes references).
 *
 * @param id
 *            the event's key: the sender's item id, else a hash of the item — a
 *            redelivered event is stored once
 * @param integrationId
 *            the webhook integration it arrived through
 * @param eventType
 *            {@code order.placed}, {@code ticket.reopened}, …
 * @param ref
 *            the business reference in stored form, or {@code null} for an
 *            event that names a conversation
 * @param refValue
 *            the plain reference value, kept only while the event is pending
 *            and the agent does not hash references — so a late match can also
 *            try the hashed form; {@code null} once matched
 * @param conversationId
 *            the conversation the event names, or — once matched — the one it
 *            was matched to
 * @param value
 *            an optional amount ({@code 49.90}), summed in the business measure
 * @param at
 *            when the event happened (the sender's {@code at}, else receipt)
 * @param day
 *            the UTC day of {@code at} — the day it counts on
 * @param state
 *            {@link #PENDING} or {@link #MATCHED}
 * @param matchedStep
 *            the first step of the session it was matched to
 * @param evidence
 *            {@code external_event} (by reference) or
 *            {@code conversation_id_event} once matched
 * @param userId
 *            the matched session's user — for GDPR erasure and export only
 */
public record HealthEvent(String id, String integrationId, String agentId, String eventType, String ref, String refValue, String conversationId,
        Double value, Instant at, Instant receivedAt, LocalDate day, String state, Integer matchedStep, Integer agentVersion, String origin,
        String userId, String evidence, Instant matchedAt) {

    public static final String PENDING = "PENDING";
    public static final String MATCHED = "MATCHED";

    /** Whether the event names a conversation rather than a reference. */
    public boolean byConversation() {
        return ref == null;
    }

    /** This event matched to a session. */
    public HealthEvent matched(String conversationId, int step, Integer version, String origin, String userId, String evidence, Instant now) {
        return new HealthEvent(id, integrationId, agentId, eventType, ref, null, conversationId, value, at, receivedAt, day, MATCHED, step, version,
                origin, userId, evidence, now);
    }
}
