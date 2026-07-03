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
     * @return true if this call claimed execution; false if an entry already exists
     *         (crashed or completed attempt).
     */
    boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy);

    void markExecuted(String conversationId, String pauseEpoch, String callId, String resultCapped);

    Optional<JournalEntry> find(String conversationId, String pauseEpoch, String callId);
}
