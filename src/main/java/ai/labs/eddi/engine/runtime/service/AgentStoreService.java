/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Loads an agent's configuration for the engine.
 * <p>
 * Reads {@link IAgentStore} directly rather than {@code IRestAgentStore}. The
 * REST facade is the authoring surface and carries {@code ResourceAccessGuard};
 * the caller here is a conversation turn, whose identity is whoever is chatting
 * — someone who may legitimately have no access to the agent's
 * <em>configuration</em> while being perfectly entitled to talk to the agent.
 * Routing this through the facade would fail every conversation on a shared
 * agent. Whether the chatting user may use the agent at all is decided once, at
 * {@code /agents/{agentId}/start}, not on every config read.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentStoreService implements IAgentStoreService {

    private final IAgentStore agentStore;

    @Inject
    public AgentStoreService(IAgentStore agentStore) {
        this.agentStore = agentStore;
    }

    @Override
    public AgentConfiguration getAgentConfiguration(String agentId, Integer version) throws ServiceException {
        try {
            return agentStore.read(agentId, version);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
