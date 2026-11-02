/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;

import java.util.List;

/**
 * Persistence for {@link AgentTestRun} records. Not versioned configuration: a
 * run is written once when it starts, replaced once when it finishes, and
 * pruned to a bounded number per agent.
 *
 * @since 6.5.0
 */
public interface IAgentTestRunStore {

    /** Stores a new run and returns its id. */
    String create(AgentTestRun run) throws IResourceStore.ResourceStoreException;

    /** Replaces a stored run (the id must be set). */
    void update(AgentTestRun run) throws IResourceStore.ResourceStoreException;

    /**
     * Replaces the run only while its stored {@code heartbeatAt} still equals
     * {@code expectedHeartbeatAt} — one atomic compare-and-swap, so a heartbeat and
     * an abort by another node cannot both land.
     *
     * @throws IResourceStore.ResourceModifiedException
     *             the stored heartbeat is another one (or none)
     * @throws IResourceStore.ResourceNotFoundException
     *             the run is gone
     */
    void updateIfHeartbeat(AgentTestRun run, long expectedHeartbeatAt)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    AgentTestRun read(String runId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /** Runs of one agent, newest first. */
    List<AgentTestRun> listByAgent(String agentId, int index, int limit) throws IResourceStore.ResourceStoreException;

    /** Runs in {@code status}, newest first, at most {@code limit}. */
    List<AgentTestRun> listByStatus(AgentTestRun.Status status, int limit) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes all but the {@code keep} newest runs of {@code agentId}.
     *
     * @return how many were deleted
     */
    int pruneOlderRuns(String agentId, int keep) throws IResourceStore.ResourceStoreException;
}
