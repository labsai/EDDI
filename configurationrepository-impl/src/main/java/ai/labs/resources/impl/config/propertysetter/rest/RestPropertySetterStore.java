package ai.labs.resources.impl.config.propertysetter.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.propertysetter.IPropertySetterStore;
import ai.labs.resources.rest.config.propertysetter.IRestPropertySetterStore;
import ai.labs.resources.rest.config.propertysetter.model.PropertySetterConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.schema.IJsonSchemaCreator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestPropertySetterStore extends RestVersionInfo<PropertySetterConfiguration> implements IRestPropertySetterStore {
    private final IPropertySetterStore propertySetterStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestPropertySetterStore restPropertySetterStore;

    @Inject
    public RestPropertySetterStore(IPropertySetterStore propertySetterStore,
                                   IRestInterfaceFactory restInterfaceFactory,
                                   IDocumentDescriptorStore documentDescriptorStore,
                                   IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, propertySetterStore, documentDescriptorStore);
        this.propertySetterStore = propertySetterStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restPropertySetterStore = restInterfaceFactory.get(IRestPropertySetterStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restPropertySetterStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(PropertySetterConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readPropertySetterDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.property", filter, index, limit);
    }

    @Override
    public PropertySetterConfiguration readPropertySetter(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updatePropertySetter(String id, Integer version, PropertySetterConfiguration propertySetterConfiguration) {
        return update(id, version, propertySetterConfiguration);
    }

    @Override
    public Response createPropertySetter(PropertySetterConfiguration propertySetterConfiguration) {
        return create(propertySetterConfiguration);
    }

    @Override
    public Response deletePropertySetter(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    public Response duplicatePropertySetter(String id, Integer version) {
        validateParameters(id, version);
        PropertySetterConfiguration propertySetterConfiguration = restPropertySetterStore.readPropertySetter(id, version);
        return restPropertySetterStore.createPropertySetter(propertySetterConfiguration);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return propertySetterStore.getCurrentResourceId(id);
    }
}
