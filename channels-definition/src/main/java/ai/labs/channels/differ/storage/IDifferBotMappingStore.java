package ai.labs.channels.differ.storage;

import ai.labs.channels.differ.model.DifferBotMapping;
import ai.labs.persistence.IResourceStore;

import java.util.List;

public interface IDifferBotMappingStore {
    List<DifferBotMapping> readAllDifferBotMappings() throws IResourceStore.ResourceStoreException;

    void createDifferBotMapping(DifferBotMapping differBotMapping)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void addBotUserIdToDifferBotMapping(String botIntent, String botUserId) throws IResourceStore.ResourceStoreException;

    void deleteBotUserIdFromDifferBotMappings(String botUserId) throws IResourceStore.ResourceStoreException;

    void deleteDifferBotMapping(String botIntent) throws IResourceStore.ResourceStoreException;

    DifferBotMapping readDifferBotMapping(String botIntent) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
