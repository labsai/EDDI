package io.sls.persistence.impl.packages.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.utilities.RuntimeUtilities;

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
