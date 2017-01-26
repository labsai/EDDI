package ai.labs.resources.impl.packages.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.DB;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class PackageStore implements IPackageStore {
    private HistorizedResourceStore<PackageConfiguration> packageResourceStore;

    @Inject
    public PackageStore(DB database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");

        final String collectionName = "packages";
        MongoResourceStorage<PackageConfiguration> mongoResourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, PackageConfiguration.class);
        packageResourceStore = new HistorizedResourceStore<>(mongoResourceStorage);
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
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
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
}
