package ai.labs.resources.rest.bots;


import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;

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
