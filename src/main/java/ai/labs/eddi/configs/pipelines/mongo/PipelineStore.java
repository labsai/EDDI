package ai.labs.eddi.configs.pipelines.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.pipelines.IPipelineStore;
import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
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
public class PipelineStore extends AbstractResourceStore<PipelineConfiguration> implements IPipelineStore {
    public static final String PACKAGE_EXTENSIONS_FIELD = "PipelineSteps";
    public static final String PACKAGE_EXTENSIONS_CONFIG_URI_FIELD = "PipelineSteps.config.uri";
    public static final String PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD =
            "PipelineSteps.extensions.dictionaries.config.uri";

    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public PipelineStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder,
            IDocumentDescriptorStore documentDescriptorStore) {
        super(storageFactory, "packages", documentBuilder, PipelineConfiguration.class,
                PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD);
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public IResourceStore.IResourceId create(PipelineConfiguration PipelineConfiguration)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(PipelineConfiguration.getPipelineSteps(),
                PACKAGE_EXTENSIONS_FIELD);
        return super.create(PipelineConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PipelineConfiguration PipelineConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(PipelineConfiguration.getPipelineSteps(),
                PACKAGE_EXTENSIONS_FIELD);
        return super.update(id, version, PipelineConfiguration);
    }

    @Override
    public List<DocumentDescriptor> getPackageDescriptorsContainingResource(String resourceURI,
            boolean includePreviousVersions)
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
            allIds.addAll(resourceStorage.findResourceIdsContaining(
                    PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));
            allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(
                    PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));

            // Search in dictionaries config.uri field
            allIds.addAll(resourceStorage.findResourceIdsContaining(
                    PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));
            allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(
                    PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));

            // Sort and deduplicate
            Comparator<IResourceStore.IResourceId> comparator =
                    Comparator.comparing(IResourceStore.IResourceId::getId)
                            .thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();
            allIds = allIds.stream().sorted(comparator).collect(Collectors.toList());

            for (IResourceStore.IResourceId packageId : allIds) {
                if (packageId.getVersion() < getCurrentResourceId(packageId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        resource -> {
                            var id = RestUtilities.extractResourceId(resource.getResource()).getId();
                            return id.equals(packageId.getId());
                        });
                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var packageDescriptor = documentDescriptorStore.readDescriptor(
                            packageId.getId(), packageId.getVersion());
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
