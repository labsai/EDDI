/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A real-world result attached to work a team did: a reply arrived, a pull
 * request merged, a listing went live, a metric was read. The approval gate
 * records that a human said yes; an outcome records what came of it.
 * <p>
 * Team-scoped on purpose: an outcome belongs to {@code groupId}, and only that
 * team's RETRO phase and the analytics scoped to that team read it.
 *
 * @param id
 *            unique; derived from {@code (groupId, idempotencyKey)} when the
 *            caller gave a key, so a webhook that retries stores once
 * @param groupId
 *            the owning team
 * @param subjectType
 *            what the outcome is about
 * @param subjectId
 *            the backlog task id or the approval decision id
 * @param kind
 *            what happened, a short token such as {@code reply_received}
 * @param metric
 *            the metric name for a reading, or {@code null}
 * @param value
 *            the reading, or {@code null} for an event without a number
 * @param unit
 *            unit of {@code value}, or {@code null}
 * @param note
 *            a short free-text remark; bounded, never a payload dump
 * @param source
 *            the system that reported it ("github", "crm"), as the caller
 *            declared it
 * @param recordedBy
 *            the authenticated principal that posted it; pseudonymized on a
 *            GDPR erasure
 * @param occurredAt
 *            when it happened in the world
 * @param recordedAt
 *            when EDDI stored it
 * @param idempotencyKey
 *            the caller's retry key, or {@code null}
 * @since 6.8.0
 */
public record OutcomeEvent(String id, String groupId, SubjectType subjectType, String subjectId, String kind, String metric, Double value,
        String unit, String note, String source, String recordedBy, Instant occurredAt, Instant recordedAt, String idempotencyKey) {

    /** What an outcome is attached to. */
    public enum SubjectType {
        /** A task on the team's workspace backlog. */
        TASK,
        /** An approval decision (an audit-ledger entry's decision id). */
        DECISION;

        /** Parses {@code TASK|DECISION}, case-insensitive. */
        public static SubjectType parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("subjectType is required (TASK or DECISION)");
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("subjectType must be TASK or DECISION");
            }
        }
    }

    public static final int MAX_KIND = 64;
    public static final int MAX_METRIC = 100;
    public static final int MAX_UNIT = 32;
    public static final int MAX_NOTE = 1000;
    public static final int MAX_SOURCE = 100;
    public static final int MAX_KEY = 128;
    public static final int MAX_SUBJECT_ID = 200;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:/-]+");

    /** Checks the length and shape of a token field; {@code null} passes. */
    public static void requireToken(String field, String value, int max) {
        if (value != null && (value.isBlank() || value.length() > max || !TOKEN.matcher(value).matches())) {
            throw new IllegalArgumentException(field + " must be 1-" + max + " characters of letters, digits and _ . : / -");
        }
    }

    /** Checks only the length of a free-text field; {@code null} passes. */
    public static void requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
    }

    /** The row with the personal field replaced. */
    public OutcomeEvent withRecordedBy(String newRecordedBy) {
        return new OutcomeEvent(id, groupId, subjectType, subjectId, kind, metric, value, unit, note, source, newRecordedBy, occurredAt,
                recordedAt, idempotencyKey);
    }
}
