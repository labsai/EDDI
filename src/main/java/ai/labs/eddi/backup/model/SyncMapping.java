/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

/**
 * Maps a source agent to a target agent for batch sync preview. Used in POST
 * /backup/import/sync/preview/batch.
 *
 * @param sourceAgentId
 *            agent ID on the remote instance
 * @param sourceAgentVersion
 *            version to sync (null = latest)
 * @param targetAgentId
 *            local agent to upgrade (null = create new)
 * @since 6.0.0
 */
public record SyncMapping(
        String sourceAgentId,
        Integer sourceAgentVersion,
        String targetAgentId) {
}
