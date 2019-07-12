package ai.labs.resources.rest.config.bots;


import ai.labs.models.BotConfiguration;
import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IBotStore extends IResourceStore<BotConfiguration> {
    List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId,
                                                                Integer packageVersion,
                                                                boolean includePreviousVersions)
            throws ResourceNotFoundException, ResourceStoreException;
}
