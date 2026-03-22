package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IAgentStore extends IResourceStore<AgentConfiguration> {
    List<DocumentDescriptor> getAgentDescriptorsContainingWorkflow(String workflowId,
            Integer packageVersion,
            boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
