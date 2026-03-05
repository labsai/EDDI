package ai.labs.eddi.configs.propertysetter.mongo;

import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.reactivestreams.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertySetterStore extends AbstractMongoResourceStore<PropertySetterConfiguration>
        implements IPropertySetterStore {

    @Inject
    public PropertySetterStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, "propertysetter", documentBuilder, PropertySetterConfiguration.class);
    }
}
