package ai.labs.eddi.engine.triggermanagement;


import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;

import java.util.List;

public interface IAgentTriggerStore {

    List<AgentTriggerConfiguration> readAllBotTriggers()
            throws IResourceStore.ResourceStoreException;

    AgentTriggerConfiguration readBotTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateBotTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteBotTrigger(String intent) throws IResourceStore.ResourceStoreException;
}
