package ai.labs.resources.rest.botmanagement;

import ai.labs.models.BotTriggerConfiguration;
import ai.labs.persistence.IResourceStore.ResourceNotFoundException;

import static ai.labs.persistence.IResourceStore.ResourceAlreadyExistsException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;

public interface IBotTriggerStore {
    BotTriggerConfiguration readBotTrigger(String intent)
            throws ResourceNotFoundException, ResourceStoreException;

    void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
            throws ResourceNotFoundException, ResourceStoreException;

    void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
            throws ResourceAlreadyExistsException, ResourceStoreException;

    void deleteBotTrigger(String intent) throws ResourceStoreException;
}
