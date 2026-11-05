/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import java.time.Instant;

/**
 * One sampled tool call that ran without approval, waiting for (or having had)
 * a human's after-the-fact review.
 * <p>
 * The arguments are stored only in their redacted, capped form — the same
 * redaction an approval card applies — and {@code argsDigest} binds the sample
 * to the exact call. The result is stored only when {@code includeResult} is
 * on.
 *
 * @param category
 *            the sampling rule's category
 * @param rulePattern
 *            the {@code match} of the sampling rule that picked the call — the
 *            pattern a suspension re-gates
 * @param exemptPattern
 *            the exempt pattern that let the call through, or null when it
 *            matched no gating pattern at all
 * @param version
 *            compare-and-set counter; a review only succeeds on the version
 *            read
 * @since 6.7.0
 */
public record AuditSample(String id, String agentId, Integer agentVersion, String conversationId, String userId, String callId,
        String toolName, String source, String endpoint, String category, String rulePattern, String exemptPattern,
        String argumentsRedacted, String argsDigest, String resultRedacted, Instant sampledAt, Instant reviewDueAt, Status status,
        String reasonCode, String note, String reviewedBy, Instant reviewedAt, long version) {

    /** Upper bound of a review note, in characters. */
    public static final int MAX_NOTE_LENGTH = 2000;

    /** Where a sample is in review. */
    public enum Status {
        PENDING, OK, FLAGGED, EXPIRED
    }

    /** The sample with its column-held fields replaced (store read path). */
    public AuditSample withColumns(String newUserId, String newReviewedBy, Status newStatus, long newVersion) {
        return new AuditSample(id, agentId, agentVersion, conversationId, newUserId, callId, toolName, source, endpoint, category,
                rulePattern, exemptPattern, argumentsRedacted, argsDigest, resultRedacted, sampledAt, reviewDueAt, newStatus, reasonCode,
                note, newReviewedBy, reviewedAt, newVersion);
    }

    /** The JSON payload: the sample without its column-held fields. */
    public AuditSample payload() {
        return withColumns(null, null, null, 0L);
    }

    /** The sample as reviewed. */
    public AuditSample reviewed(Status verdict, String newReasonCode, String newNote, String reviewer, Instant at) {
        return new AuditSample(id, agentId, agentVersion, conversationId, userId, callId, toolName, source, endpoint, category, rulePattern,
                exemptPattern, argumentsRedacted, argsDigest, resultRedacted, sampledAt, reviewDueAt, verdict, newReasonCode, newNote,
                reviewer, at, version + 1);
    }
}
