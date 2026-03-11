package ai.labs.eddi.configs.bots.mongo;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
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
public class BotStore extends AbstractResourceStore<BotConfiguration> implements IBotStore {
    public static final String PACKAGES_FIELD = "packages";
    private static final String PACKAGE_RESOURCE_URI = "eddi://ai.labs.package/packagestore/packages/";
    private static final String VERSION_QUERY_PARAM = "?version=";

    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public BotStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder,
            DocumentDescriptorStore documentDescriptorStore) {
        super(storageFactory, "bots", documentBuilder, BotConfiguration.class, PACKAGES_FIELD);
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public IResourceStore.IResourceId create(BotConfiguration botConfiguration)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), PACKAGES_FIELD);
        return super.create(botConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, BotConfiguration botConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), PACKAGES_FIELD);
        return super.update(id, version, botConfiguration);
    }

    public List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId, Integer packageVersion,
            boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        do {
            String packageUri = PACKAGE_RESOURCE_URI + packageId + VERSION_QUERY_PARAM + packageVersion;

            // Search in current resources
            List<IResourceStore.IResourceId> currentIds =
                    resourceStorage.findResourceIdsContaining(PACKAGES_FIELD, packageUri);
            // Search in history
            List<IResourceStore.IResourceId> historyIds =
                    resourceStorage.findHistoryResourceIdsContaining(PACKAGES_FIELD, packageUri);

            // Merge and sort
            List<IResourceStore.IResourceId> allIds = new LinkedList<>(currentIds);
            allIds.addAll(historyIds);
            Comparator<IResourceStore.IResourceId> comparator =
                    Comparator.comparing(IResourceStore.IResourceId::getId)
                            .thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();
            allIds = allIds.stream().sorted(comparator).collect(Collectors.toList());

            for (IResourceStore.IResourceId botId : allIds) {
                if (botId.getVersion() < getCurrentResourceId(botId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        descriptor -> extractResourceId(descriptor.getResource()).getId().equals(botId.getId()));
                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var botDescriptor = documentDescriptorStore.readDescriptor(botId.getId(), botId.getVersion());
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
