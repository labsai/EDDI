/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import java.time.Instant;

/**
 * Result of a GDPR cascading user data deletion.
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
        Instant completedAt) {
}
