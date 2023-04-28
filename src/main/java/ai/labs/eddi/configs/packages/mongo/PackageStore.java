package ai.labs.eddi.configs.packages.mongo;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.utilities.ResourceUtilities;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageStore implements IPackageStore {
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
        return readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(PackageConfiguration packageConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
        return packageResourceStore.create(packageConfiguration);
    }

    @Override
    public PackageConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return packageResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PackageConfiguration packageConfiguration) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
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
        Integer version = Integer.parseInt(resourceURI.substring(startIndexVersion));
        String resourceURIPart = resourceURI.substring(0, startIndexVersion);

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
                            String id = RestUtilities.extractResourceId(resource.getResource()).getId();
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
        PackageMongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<PackageConfiguration> documentType) {
            super(database, collectionName, documentBuilder, documentType);
        }

        List<IResourceStore.IResourceId> getPackageDescriptorsContainingResource(String resourceURI) throws IResourceStore.ResourceNotFoundException {
            String searchQuery = String.format("JSON.stringify(this).indexOf('%s')!=-1", resourceURI);
            Document filter = new Document("$where", searchQuery);

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
