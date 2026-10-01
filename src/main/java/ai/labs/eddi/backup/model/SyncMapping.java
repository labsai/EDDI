/*
 * Copyright EDDI contributors
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
 *            local agent to upgrade. Null: the agent an earlier sync promoted
 *            from this source, found by its {@code originId}; a new agent when
 *            there is none
 * @param createNew
 *            true to create a new agent even when one was promoted from this
 *            source before; null and false both mean "find it first"
 * @since 6.0.0
 */
public record SyncMapping(
        String sourceAgentId,
        Integer sourceAgentVersion,
        String targetAgentId,
        Boolean createNew) {

    /** A mapping that finds an earlier promotion before creating one. */
    public SyncMapping(String sourceAgentId, Integer sourceAgentVersion, String targetAgentId) {
        this(sourceAgentId, sourceAgentVersion, targetAgentId, null);
    }
}
