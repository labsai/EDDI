/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

import java.util.List;
import java.util.Set;

/**
 * Request for a single agent in a batch sync execution. Used in POST
 * /backup/import/sync/batch.
 *
 * @param sourceAgentId
 *            agent ID on the remote instance
 * @param sourceAgentVersion
 *            version to sync (null = latest)
 * @param targetAgentId
 *            local agent to upgrade (null = create new)
 * @param selectedResources
 *            source resource IDs to sync (null = all)
 * @param workflowOrder
 *            desired workflow order after sync (null = append new ones at end)
 * @since 6.0.0
 */
public record SyncRequest(
        String sourceAgentId,
        Integer sourceAgentVersion,
        String targetAgentId,
        Set<String> selectedResources,
        List<String> workflowOrder) {
}
