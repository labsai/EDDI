package ai.labs.eddi.configs.packages.mongo;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.utilities.ResourceUtilities;
import ai.labs.eddi.datastore.IResourceStore;
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
public class PackageStore implements IPackageStore {
    public static final String PACKAGE_EXTENSIONS_FIELD = "packageExtensions";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final PackageHistorizedResourceStore packageResourceStore;

    @Inject
    public PackageStore(MongoDatabase database, IDocumentBuilder documentBuilder, IDocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        RuntimeUtilities.checkNotNull(database, "database");

        final String collectionName = "packages";
        PackageMongoResourceStorage mongoResourceStorage =
                new PackageMongoResourceStorage(database, collectionName, documentBuilder, PackageConfiguration.class);
        packageResourceStore = new PackageHistorizedResourceStore(mongoResourceStorage);
    }

    @Override
    public PackageConfiguration readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return packageResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(PackageConfiguration packageConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), PACKAGE_EXTENSIONS_FIELD);
        return packageResourceStore.create(packageConfiguration);
    }

    @Override
    public PackageConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return packageResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PackageConfiguration packageConfiguration) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), PACKAGE_EXTENSIONS_FIELD);
        return packageResourceStore.update(id, version, packageConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        packageResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        packageResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return packageResourceStore.getCurrentResourceId(id);
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
            List<IResourceStore.IResourceId> packagesContainingResource =
                    packageResourceStore.getPackageDescriptorsContainingResource(resourceURI);
            for (IResourceStore.IResourceId packageId : packagesContainingResource) {

                if (packageId.getVersion() < getCurrentResourceId(packageId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        resource ->
                        {
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
                    //skip, as this resource is not available anymore due to deletion
                }
            }

            version--;
        } while (includePreviousVersions && version >= 1);

        return ret;
    }

    private class PackageMongoResourceStorage extends MongoResourceStorage<PackageConfiguration> {

        public static final String PACKAGE_EXTENSIONS_CONFIG_URI_FIELD = "packageExtensions.config.uri";
        public static final String PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD =
                "packageExtensions.extensions.dictionaries.config.uri";

        PackageMongoResourceStorage(MongoDatabase database, String collectionName,
                                    IDocumentBuilder documentBuilder, Class<PackageConfiguration> documentType) {

            super(database, collectionName, documentBuilder, documentType,
                    PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD);
        }

        List<IResourceStore.IResourceId> getPackageDescriptorsContainingResource(String resourceURI)
                throws IResourceStore.ResourceNotFoundException {

            // Building a filter to search in arrays and nested objects
            var filter = or(
                    elemMatch(PACKAGE_EXTENSIONS_FIELD, eq(PACKAGE_EXTENSIONS_CONFIG_URI_FIELD, resourceURI)),
                    elemMatch(PACKAGE_EXTENSIONS_FIELD, eq(PACKAGE_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI))
            );

            return ResourceUtilities.getAllConfigsContainingResources(filter,
                    currentCollection, historyCollection, documentDescriptorStore);
        }
    }

    private class PackageHistorizedResourceStore extends HistorizedResourceStore<PackageConfiguration> {
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
