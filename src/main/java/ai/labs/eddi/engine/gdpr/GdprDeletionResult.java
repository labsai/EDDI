/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import java.time.Instant;
import java.util.List;

/**
 * Result of a GDPR cascading user data deletion.
 * <p>
 * The counters describe every store the cascade touches, not just the five it
 * used to report. Attachments, HITL journal entries, checkpoints, group
 * transcripts, shared artifacts and schedules were computed and written to the
 * audit ledger but never returned, so the DPO producing an Art. 17 confirmation
 * could not see whether they had been touched without reading the server log.
 * <p>
 * {@link #complete()} and {@link #failedSteps()} exist because the cascade
 * deliberately continues past a failing step: without them a run in which
 * {@code deleteConversationsByUserId} threw was indistinguishable from a user
 * who simply had no conversations, and the request was filed as fulfilled while
 * the data was still there.
 *
 * @param userId
 *            the user whose data was deleted
 * @param memoriesDeleted
 *            number of user memory entries removed
 * @param conversationsDeleted
 *            number of conversation snapshots removed
 * @param conversationMappingsDeleted
 *            number of managed conversation mappings removed
 * @param logsPseudonymized
 *            number of database log entries pseudonymized
 * @param auditEntriesPseudonymized
 *            number of audit ledger entries pseudonymized
 * @param attachmentsDeleted
 *            number of binary attachments removed
 * @param journalEntriesDeleted
 *            number of HITL tool journal entries removed
 * @param checkpointsDeleted
 *            number of conversation memory checkpoints removed
 * @param groupConversationsDeleted
 *            number of group discussion transcripts removed
 * @param sharedArtifactsDeleted
 *            number of shared artifacts removed
 * @param schedulesDeleted
 *            number of schedules removed
 * @param failedSteps
 *            names of the cascade steps that threw; empty on a clean run
 * @param completedAt
 *            timestamp of completion
 *
 * @author ginccc
 * @since 6.0.0
 */
public record GdprDeletionResult(
        String userId,
        long memoriesDeleted,
        long conversationsDeleted,
        long conversationMappingsDeleted,
        long logsPseudonymized,
        long auditEntriesPseudonymized,
        long attachmentsDeleted,
        long journalEntriesDeleted,
        long checkpointsDeleted,
        long groupConversationsDeleted,
        long sharedArtifactsDeleted,
        long schedulesDeleted,
        List<String> failedSteps,
        Instant completedAt) {

    /**
     * Compatibility constructor for the original seven-component shape, so existing
     * callers and tests keep compiling. Reports the six added counters as 0 and the
     * run as clean.
     */
    public GdprDeletionResult(String userId, long memoriesDeleted, long conversationsDeleted, long conversationMappingsDeleted,
            long logsPseudonymized, long auditEntriesPseudonymized, Instant completedAt) {
        this(userId, memoriesDeleted, conversationsDeleted, conversationMappingsDeleted, logsPseudonymized, auditEntriesPseudonymized,
                0, 0, 0, 0, 0, 0, List.of(), completedAt);
    }

    public GdprDeletionResult {
        failedSteps = failedSteps == null ? List.of() : List.copyOf(failedSteps);
    }

    /**
     * Whether every step of the cascade succeeded. False means some of the user's
     * data may still exist — the caller must not report the erasure as fulfilled.
     */
    public boolean complete() {
        return failedSteps.isEmpty();
    }
}
