/*
 * Copyright (c) 2016-2026 EDDI contributors
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
     * with a SHA-256 hash to preserve log integrity while removing personally
     * identifiable information.
     *
     * @param userId
     *            the original user identifier
     * @param pseudonym
     *            the pseudonymized replacement (SHA-256 hash)
     * @return number of entries pseudonymized
     */
    long pseudonymizeByUserId(String userId, String pseudonym);
}
