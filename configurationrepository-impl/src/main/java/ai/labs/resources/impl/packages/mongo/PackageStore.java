package ai.labs.resources.impl.packages.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.impl.utilities.ResourceUtilities;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
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
    public IResourceId create(PackageConfiguration packageConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
        return packageResourceStore.create(packageConfiguration);
    }

    @Override
    public PackageConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return packageResourceStore.read(id, version);
    }

    @Override
    public Integer update(String id, Integer version, PackageConfiguration packageConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
        return packageResourceStore.update(id, version, packageConfiguration);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        packageResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        packageResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return packageResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<DocumentDescriptor> getPackageDescriptorsContainingResource(URI resourceURI)
            throws ResourceStoreException, ResourceNotFoundException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        List<IResourceId> packageIdsContainingPackageUri =
                packageResourceStore.getPackageDescriptorsContainingResource(resourceURI);

        for (IResourceId packageId : packageIdsContainingPackageUri) {
            DocumentDescriptor documentDescriptor =
                    documentDescriptorStore.readDescriptor(packageId.getId(), packageId.getVersion());
            ret.add(documentDescriptor);
        }

        return ret;
    }

    private class PackageMongoResourceStorage extends MongoResourceStorage<PackageConfiguration> {
        PackageMongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<PackageConfiguration> documentType) {
            super(database, collectionName, documentBuilder, documentType);
        }

        List<IResourceId> getPackageDescriptorsContainingResource(URI resourceURI) throws ResourceNotFoundException {
            String searchQuery = String.format("JSON.stringify(this).indexOf('%s')!=-1", resourceURI);
            Document filter = new Document("$where", searchQuery);
            List<String> packageIds = new LinkedList<>();


            FindIterable<Document> documentIterable;

            documentIterable = currentCollection.find(filter);
            ResourceUtilities.extractIds(packageIds, documentIterable);

            documentIterable = historyCollection.find(filter);
            ResourceUtilities.extractIds(packageIds, documentIterable);

            List<IResourceId> latestPackages = new LinkedList<>();

            IResourceId currentResourceId;
            for (String packageId : packageIds) {
                currentResourceId = documentDescriptorStore.getCurrentResourceId(packageId);
                ResourceUtilities.addIfNewerVersion(currentResourceId, latestPackages);
            }

            return latestPackages;
        }
    }

    private class PackageHistorizedResourceStore extends HistorizedResourceStore<PackageConfiguration> {
        private final PackageMongoResourceStorage resourceStorage;

        PackageHistorizedResourceStore(PackageMongoResourceStorage resourceStorage) {
            super(resourceStorage);
            this.resourceStorage = resourceStorage;
        }

        List<IResourceId> getPackageDescriptorsContainingResource(URI resourceURI)
                throws ResourceNotFoundException {
            return resourceStorage.getPackageDescriptorsContainingResource(resourceURI);
        }
    }
}
