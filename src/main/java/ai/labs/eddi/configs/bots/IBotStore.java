package ai.labs.eddi.configs.bots;


import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.models.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IBotStore extends IResourceStore<BotConfiguration> {
    List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId,
                                                                Integer packageVersion,
                                                                boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
