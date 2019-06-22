package ai.labs.resources.rest.config.bots;


import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;

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
