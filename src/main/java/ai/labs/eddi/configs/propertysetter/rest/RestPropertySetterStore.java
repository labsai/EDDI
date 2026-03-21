package ai.labs.eddi.configs.propertysetter.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestPropertySetterStore implements IRestPropertySetterStore {
    private final IPropertySetterStore propertySetterStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<PropertySetterConfiguration> restVersionInfo;

    @Inject
    public RestPropertySetterStore(IPropertySetterStore propertySetterStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, propertySetterStore, documentDescriptorStore);
        this.propertySetterStore = propertySetterStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(PropertySetterConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readPropertySetterDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.property", filter, index, limit);
    }

    @Override
    public PropertySetterConfiguration readPropertySetter(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updatePropertySetter(String id, Integer version,
            PropertySetterConfiguration propertySetterConfiguration) {
        return restVersionInfo.update(id, version, propertySetterConfiguration);
    }

    @Override
    public Response createPropertySetter(PropertySetterConfiguration propertySetterConfiguration) {
        return restVersionInfo.create(propertySetterConfiguration);
    }

    @Override
    public Response deletePropertySetter(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicatePropertySetter(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        PropertySetterConfiguration propertySetterConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(propertySetterConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return propertySetterStore.getCurrentResourceId(id);
    }
}
