/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;

import java.util.List;

public interface IDatabaseLogs {

    List<LogEntry> getLogs(Deployment.Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
                           String instanceId, Integer skip, Integer limit);

    /**
     * Batch insert log entries. Used by the async DB writer in
     * {@link BoundedLogStore}.
     */
    void addLogsBatch(List<LogEntry> entries);

    // === GDPR ===

    /**
     * Pseudonymize all log entries for a user (GDPR Art. 17). Replaces the userId
     * with the caller's pseudonym to preserve log integrity while removing
     * personally identifiable information. Erasure passes a keyed one (see
     * {@code GdprPseudonymizer}); rows erased by earlier releases carry an unsalted
     * SHA-256.
     *
     * @param userId
     *            the original user identifier
     * @param pseudonym
     *            the pseudonymized replacement
     * @return number of entries pseudonymized
     */
    long pseudonymizeByUserId(String userId, String pseudonym);

    // === Retention ===

    /**
     * Delete persisted log entries older than {@code olderThanDays} days. Backs the
     * {@code eddi.logs.db-retention-days} sweep in {@link DatabaseLogRetention}.
     *
     * @param olderThanDays
     *            entries whose timestamp is before now minus this many days are
     *            deleted; must be positive
     * @return number of entries deleted
     */
    long deleteOlderThan(int olderThanDays);
}
