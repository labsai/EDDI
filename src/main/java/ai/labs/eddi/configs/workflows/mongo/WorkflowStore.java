package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowStore extends AbstractResourceStore<WorkflowConfiguration> implements IWorkflowStore {
    public static final String WORKFLOW_EXTENSIONS_FIELD = "WorkflowSteps";
    public static final String WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD = "WorkflowSteps.config.uri";
    public static final String WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD = "WorkflowSteps.extensions.dictionaries.config.uri";

    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public WorkflowStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, IDocumentDescriptorStore documentDescriptorStore) {
        super(storageFactory, "workflows", documentBuilder, WorkflowConfiguration.class, WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD,
                WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD);
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public IResourceStore.IResourceId create(WorkflowConfiguration workflowConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(workflowConfiguration.getWorkflowSteps(), WORKFLOW_EXTENSIONS_FIELD);
        return super.create(workflowConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, WorkflowConfiguration workflowConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(workflowConfiguration.getWorkflowSteps(), WORKFLOW_EXTENSIONS_FIELD);
        return super.update(id, version, workflowConfiguration);
    }

    @Override
    public List<DocumentDescriptor> getWorkflowDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<DocumentDescriptor> ret = new LinkedList<>();

        int startIndexVersion = resourceURI.lastIndexOf("=") + 1;
        var version = Integer.parseInt(resourceURI.substring(startIndexVersion));
        var resourceURIPart = resourceURI.substring(0, startIndexVersion);

        do {
            resourceURI = resourceURIPart + version;

            // Search both config URI paths in current + history
            List<IResourceStore.IResourceId> allIds = new LinkedList<>();

            // Search in config.uri field
            allIds.addAll(resourceStorage.findResourceIdsContaining(WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));
            allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));

            // Search in dictionaries config.uri field
            allIds.addAll(resourceStorage.findResourceIdsContaining(WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));
            allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));

            // Sort and deduplicate
            Comparator<IResourceStore.IResourceId> comparator = Comparator.comparing(IResourceStore.IResourceId::getId)
                    .thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();
            allIds = allIds.stream().sorted(comparator).collect(Collectors.toList());

            for (IResourceStore.IResourceId workflowId : allIds) {
                if (workflowId.getVersion() < getCurrentResourceId(workflowId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(resource -> {
                    var id = RestUtilities.extractResourceId(resource.getResource()).getId();
                    return id.equals(workflowId.getId());
                });
                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var packageDescriptor = documentDescriptorStore.readDescriptor(workflowId.getId(), workflowId.getVersion());
                    ret.add(packageDescriptor);
                } catch (ResourceNotFoundException e) {
                    // skip, as this resource is not available anymore due to deletion
                }
            }

            version--;
        } while (includePreviousVersions && version >= 1);

        return ret;
    }
}
