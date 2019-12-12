package ai.labs.resources.impl.config.propertysetter.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.propertysetter.IPropertySetterStore;
import ai.labs.resources.rest.config.propertysetter.model.PropertySetterConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
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
    public PropertySetterConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return propertySetterResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(PropertySetterConfiguration propertySetterConfiguration) throws ResourceStoreException {
        return propertySetterResourceStore.create(propertySetterConfiguration);
    }

    @Override
    public PropertySetterConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return propertySetterResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PropertySetterConfiguration propertySetterConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return propertySetterResourceStore.update(id, version, propertySetterConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        propertySetterResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        propertySetterResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return propertySetterResourceStore.getCurrentResourceId(id);
    }
}
