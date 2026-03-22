package ai.labs.eddi.configs.agents.mongo;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.mongo.DocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

/**
 * @author ginccc
 */
@ApplicationScoped
public class AgentStore extends AbstractResourceStore<AgentConfiguration> implements IAgentStore {
    public static final String PACKAGES_FIELD = "packages";
    private static final String PACKAGE_RESOURCE_URI = "eddi://ai.labs.package/WorkflowStore/packages/";
    private static final String VERSION_QUERY_PARAM = "?version=";

    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public AgentStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder,
            DocumentDescriptorStore documentDescriptorStore) {
        super(storageFactory, "bots", documentBuilder, AgentConfiguration.class, PACKAGES_FIELD);
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public IResourceStore.IResourceId create(AgentConfiguration agentConfiguration)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(agentConfiguration.getWorkflows(), PACKAGES_FIELD);
        return super.create(agentConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, AgentConfiguration agentConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(agentConfiguration.getWorkflows(), PACKAGES_FIELD);
        return super.update(id, version, agentConfiguration);
    }

    public List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId, Integer packageVersion,
            boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        do {
            String workflowUri = PACKAGE_RESOURCE_URI + packageId + VERSION_QUERY_PARAM + packageVersion;

            // Search in current resources
            List<IResourceStore.IResourceId> currentIds =
                    resourceStorage.findResourceIdsContaining(PACKAGES_FIELD, workflowUri);
            // Search in history
            List<IResourceStore.IResourceId> historyIds =
                    resourceStorage.findHistoryResourceIdsContaining(PACKAGES_FIELD, workflowUri);

            // Merge and sort
            List<IResourceStore.IResourceId> allIds = new LinkedList<>(currentIds);
            allIds.addAll(historyIds);
            Comparator<IResourceStore.IResourceId> comparator =
                    Comparator.comparing(IResourceStore.IResourceId::getId)
                            .thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();
            allIds = allIds.stream().sorted(comparator).collect(Collectors.toList());

            for (IResourceStore.IResourceId agentId : allIds) {
                if (agentId.getVersion() < getCurrentResourceId(agentId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        descriptor -> extractResourceId(descriptor.getResource()).getId().equals(agentId.getId()));
                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var botDescriptor = documentDescriptorStore.readDescriptor(agentId.getId(), agentId.getVersion());
                    ret.add(botDescriptor);
                } catch (ResourceNotFoundException e) {
                    // skip, as this resource is not available anymore due to deletion
                }
            }

            packageVersion--;
        } while (includePreviousVersions && packageVersion >= 1);

        return ret;
    }
}
