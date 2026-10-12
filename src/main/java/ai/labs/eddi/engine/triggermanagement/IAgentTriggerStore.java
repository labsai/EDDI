/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;

import java.util.List;
import java.util.Objects;

public interface IAgentTriggerStore {

    List<AgentTriggerConfiguration> readAllAgentTriggers() throws IResourceStore.ResourceStoreException;

    AgentTriggerConfiguration readAgentTrigger(String intent) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteAgentTrigger(String intent) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /**
     * Replaces the trigger only if it still routes exactly as {@code expected} does
     * — the version an authorization decision was made against.
     * <p>
     * The REST layer authorizes a change against the agents a trigger routes to,
     * read before the write. Writing by intent alone let a concurrent re-point slip
     * between the check and the write, so the request overwrote a routing it had
     * never been checked against. The write is conditional on the stored document
     * being the one that was compared, so a change in between makes it fail rather
     * than land.
     *
     * @return {@code false} when the trigger no longer routes as {@code expected},
     *         or no longer exists — the caller re-reads and decides again
     */
    boolean updateAgentTriggerIfUnchanged(String intent, AgentTriggerConfiguration expected, AgentTriggerConfiguration update)
            throws IResourceStore.ResourceStoreException;

    /**
     * Deletes the trigger only if it still routes exactly as {@code expected} does
     * — see {@link #updateAgentTriggerIfUnchanged}.
     *
     * @return {@code false} when the trigger no longer routes as {@code expected},
     *         or no longer exists
     */
    boolean deleteAgentTriggerIfUnchanged(String intent, AgentTriggerConfiguration expected) throws IResourceStore.ResourceStoreException;

    /**
     * Whether two trigger configurations route to the same agents, in the same
     * environments, in the same order — what an authorization decision on a trigger
     * depends on.
     */
    static boolean routesIdentically(AgentTriggerConfiguration a, AgentTriggerConfiguration b) {
        List<AgentDeployment> left = a == null || a.getAgentDeployments() == null ? List.of() : a.getAgentDeployments();
        List<AgentDeployment> right = b == null || b.getAgentDeployments() == null ? List.of() : b.getAgentDeployments();
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            AgentDeployment l = left.get(i);
            AgentDeployment r = right.get(i);
            if (l == null || r == null) {
                if (l != r) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(l.getAgentId(), r.getAgentId()) || !Objects.equals(l.getEnvironment(), r.getEnvironment())) {
                return false;
            }
        }
        return true;
    }
}
