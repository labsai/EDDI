/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;

import java.util.List;

public interface IAgentTriggerStore {

    List<AgentTriggerConfiguration> readAllAgentTriggers() throws IResourceStore.ResourceStoreException;

    AgentTriggerConfiguration readAgentTrigger(String intent) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteAgentTrigger(String intent) throws IResourceStore.ResourceStoreException;
}
