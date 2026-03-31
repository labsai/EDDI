package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;

/**
 * @author ginccc
 */
public interface IAgentStoreService {
    AgentConfiguration getAgentConfiguration(String agentId, Integer version) throws ServiceException;
}
