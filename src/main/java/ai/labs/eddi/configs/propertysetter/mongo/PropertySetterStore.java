package ai.labs.eddi.configs.propertysetter.mongo;

import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertySetterStore implements IPropertySetterStore {
    private HistorizedResourceStore<PropertySetterConfiguration> propertySetterResourceStore;

    @Inject
    public PropertySetterStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "propertysetter";
        MongoResourceStorage<PropertySetterConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, PropertySetterConfiguration.class);
        this.propertySetterResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public PropertySetterConfiguration readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return propertySetterResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(PropertySetterConfiguration propertySetterConfiguration) throws IResourceStore.ResourceStoreException {
        return propertySetterResourceStore.create(propertySetterConfiguration);
    }

    @Override
    public PropertySetterConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return propertySetterResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PropertySetterConfiguration propertySetterConfiguration) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return propertySetterResourceStore.update(id, version, propertySetterConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        propertySetterResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        propertySetterResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return propertySetterResourceStore.getCurrentResourceId(id);
    }
}
