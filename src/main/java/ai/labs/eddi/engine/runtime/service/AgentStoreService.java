package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/**
 * @author ginccc
 */
@ApplicationScoped
public class AgentStoreService implements IAgentStoreService {

    private final IRestAgentStore restAgentStore;

    @Inject
    public AgentStoreService(IRestAgentStore restAgentStore) {
        this.restAgentStore = restAgentStore;
    }

    @Override
    public AgentConfiguration getAgentConfiguration(String agentId, Integer version) throws ServiceException {
        try {
            return restAgentStore.readAgent(agentId, version);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
