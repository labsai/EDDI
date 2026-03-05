package ai.labs.eddi.configs.packages.mongo;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.utilities.ResourceUtilities;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedList;
import java.util.List;

import static com.mongodb.client.model.Filters.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageStore extends AbstractMongoResourceStore<PackageConfiguration> implements IPackageStore {
    public static final String PACKAGE_EXTENSIONS_FIELD = "packageExtensions";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final PackageHistorizedResourceStore packageResourceStore;

    @Inject
    public PackageStore(MongoDatabase database, IDocumentBuilder documentBuilder,
            IDocumentDescriptorStore documentDescriptorStore) {
        super(createPackageResourceStore(database, documentBuilder, documentDescriptorStore));
        this.documentDescriptorStore = documentDescriptorStore;
        this.packageResourceStore = (PackageHistorizedResourceStore) this.resourceStore;
    }

    private static PackageHistorizedResourceStore createPackageResourceStore(MongoDatabase database,
            IDocumentBuilder documentBuilder,
            IDocumentDescriptorStore documentDescriptorStore) {
        RuntimeUtilities.checkNotNull(database, "database");
        PackageMongoResourceStorage mongoResourceStorage = new PackageMongoResourceStorage(database, "packages",
                documentBuilder, PackageConfiguration.class, documentDescriptorStore);
        return new PackageHistorizedResourceStore(mongoResourceStorage);
    }

    @Override
    public IResourceStore.IResourceId create(PackageConfiguration packageConfiguration)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(),
                PACKAGE_EXTENSIONS_FIELD);
        return super.create(packageConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PackageConfiguration packageConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(),
                PACKAGE_EXTENSIONS_FIELD);
        return super.update(id, version, packageConfiguration);
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
            List<IResourceStore.IResourceId> packagesContainingResource = packageResourceStore
                    .getPackageDescriptorsContainingResource(resourceURI);
            for (IResourceStore.IResourceId packageId : packagesContainingResource) {

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
                            packageId.getId(),
                            packageId.getVersion());
                    ret.add(packageDescriptor);
                } catch (ResourceNotFoundException e) {
                    // skip, as this resource is not available anymore due to deletion
                }
            }

            version--;
        } while (includePreviousVersions && version >= 1);

        return ret;
    }

    private static class PackageMongoResourceStorage extends MongoResourceStorage<PackageConfiguration> {

        public static final String PACKAGE_EXTENSIONS_CONFIG_URI_FIELD = "packageExtensions.config.uri";
        public static final String PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD = "packageExtensions.extensions.dictionaries.config.uri";
        private final IDocumentDescriptorStore documentDescriptorStore;

        PackageMongoResourceStorage(MongoDatabase database, String collectionName,
                IDocumentBuilder documentBuilder, Class<PackageConfiguration> documentType,
                IDocumentDescriptorStore documentDescriptorStore) {

            super(database, collectionName, documentBuilder, documentType,
                    PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD);
            this.documentDescriptorStore = documentDescriptorStore;
        }

        List<IResourceStore.IResourceId> getPackageDescriptorsContainingResource(String resourceURI)
                throws IResourceStore.ResourceNotFoundException {

            // Building a filter to search in arrays and nested objects
            var filter = or(
                    elemMatch(PACKAGE_EXTENSIONS_FIELD, eq(PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, resourceURI)),
                    elemMatch(PACKAGE_EXTENSIONS_FIELD,
                            eq(PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI)));

            return ResourceUtilities.getAllConfigsContainingResources(filter,
                    currentCollection, historyCollection, documentDescriptorStore);
        }
    }

    private static class PackageHistorizedResourceStore extends HistorizedResourceStore<PackageConfiguration> {
        private final PackageMongoResourceStorage resourceStorage;

        PackageHistorizedResourceStore(PackageMongoResourceStorage resourceStorage) {
            super(resourceStorage);
            this.resourceStorage = resourceStorage;
        }

        List<IResourceStore.IResourceId> getPackageDescriptorsContainingResource(String resourceURI)
                throws IResourceStore.ResourceNotFoundException {
            return resourceStorage.getPackageDescriptorsContainingResource(resourceURI);
        }
    }
}
