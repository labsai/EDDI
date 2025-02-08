package ai.labs.eddi.configs.botmanagement;


import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.BotTriggerConfiguration;

import java.util.List;

public interface IBotTriggerStore {

    List<BotTriggerConfiguration> readAllBotTriggers()
            throws IResourceStore.ResourceStoreException;

    BotTriggerConfiguration readBotTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteBotTrigger(String intent) throws IResourceStore.ResourceStoreException;
}
