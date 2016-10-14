package io.sls.persistence.impl.packages.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.utilities.RuntimeUtilities;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * User: jarisch
 * Date: 15.07.12
 * Time: 15:20
 */
public class PackageStore implements IPackageStore {
    private HistorizedResourceStore<PackageConfiguration> packageResourceStore;
    private final String collectionName = "packages";

    @Inject
    public PackageStore(DB database) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<PackageConfiguration> mongoResourceStorage = new MongoResourceStorage<PackageConfiguration>(database, collectionName, new IDocumentBuilder<PackageConfiguration>() {
            @Override
            public PackageConfiguration build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<PackageConfiguration>() {});
            }
        });
        packageResourceStore = new HistorizedResourceStore<PackageConfiguration>(mongoResourceStorage);
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
