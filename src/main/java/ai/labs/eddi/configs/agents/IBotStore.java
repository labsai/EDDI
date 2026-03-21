package ai.labs.eddi.configs.agents;


import ai.labs.eddi.configs.agents.model.BotConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

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
