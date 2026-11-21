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
 *            local agent to upgrade. Null: the agent an earlier sync promoted
 *            from this source, found by its {@code originId}; a new agent when
 *            there is none
 * @param selectedResources
 *            source resource IDs to sync (null = all). An empty list is
 *            refused: it names nothing, and reading it as "all" overwrote
 *            everything for a caller that meant "none"
 * @param workflowOrder
 *            desired workflow order after sync (null = append new ones at end)
 * @param createNew
 *            true to create a new agent even when one was promoted from this
 *            source before; null and false both mean "find it first"
 * @param compatible
 *            true to declare the agent version this writes compatible with the
 *            one it replaces, so running conversations follow it; null and
 *            false both mean a breaking version, which is the default. No
 *            effect when a new agent is created
 * @param allowIncomplete
 *            first promotion only: true creates the agent although its archive
 *            names configurations it does not contain (the steps are dropped
 *            and listed in the warnings); null and false both refuse it with
 *            422, the default
 * @since 6.0.0
 */
public record SyncRequest(
        String sourceAgentId,
        Integer sourceAgentVersion,
        String targetAgentId,
        Set<String> selectedResources,
        List<String> workflowOrder,
        Boolean createNew,
        Boolean compatible,
        Boolean allowIncomplete) {

    /** A request that refuses an incomplete first promotion. */
    public SyncRequest(String sourceAgentId, Integer sourceAgentVersion, String targetAgentId,
            Set<String> selectedResources, List<String> workflowOrder, Boolean createNew, Boolean compatible) {
        this(sourceAgentId, sourceAgentVersion, targetAgentId, selectedResources, workflowOrder, createNew, compatible, null);
    }

    /** A breaking request. */
    public SyncRequest(String sourceAgentId, Integer sourceAgentVersion, String targetAgentId,
            Set<String> selectedResources, List<String> workflowOrder, Boolean createNew) {
        this(sourceAgentId, sourceAgentVersion, targetAgentId, selectedResources, workflowOrder, createNew, null);
    }

    /** A request that finds an earlier promotion before creating one. */
    public SyncRequest(String sourceAgentId, Integer sourceAgentVersion, String targetAgentId,
            Set<String> selectedResources, List<String> workflowOrder) {
        this(sourceAgentId, sourceAgentVersion, targetAgentId, selectedResources, workflowOrder, null, null);
    }
}
