/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import java.util.Optional;

/**
 * Write-ahead journal for HITL-approved tool executions. Guarantees a human
 * approval is executed at most once, across pod crashes and re-approvals:
 * insert EXECUTING before running the tool, mark EXECUTED with the capped
 * result after. On resume, EXECUTED entries replay their stored result;
 * EXECUTING entries (crash mid-tool) yield an honest outcome-unknown. Key
 * includes pauseEpoch because providers may reuse tool-call ids across
 * different pauses in one conversation.
 */
public interface IHitlToolJournalStore {
    enum Status {
        EXECUTING, EXECUTED
    }

    record JournalEntry(String conversationId, String pauseEpoch, String callId,
            String toolName, Status status, String resultCapped,
            java.time.Instant executedAt, String decidedBy) {
    }

    /**
     * @return true if this call claimed execution; false ONLY if an entry already
     *         exists (duplicate key — crashed or completed attempt). Any other
     *         write or connectivity failure is NOT a duplicate and is propagated as
     *         an unchecked exception rather than returned as {@code false}, so
     *         callers can distinguish "already claimed" from "claim attempt failed"
     *         and retry instead of silently skipping a human-approved tool.
     */
    boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy);

    void markExecuted(String conversationId, String pauseEpoch, String callId, String resultCapped);

    Optional<JournalEntry> find(String conversationId, String pauseEpoch, String callId);

    /**
     * Delete all journal entries for a conversation (GDPR erasure cascade).
     *
     * @return the number of entries deleted
     */
    long deleteByConversationId(String conversationId);
}
