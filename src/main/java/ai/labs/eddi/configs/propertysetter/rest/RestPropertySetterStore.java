package ai.labs.eddi.configs.propertysetter.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestPropertySetterStore implements IRestPropertySetterStore {
    private final IPropertySetterStore propertySetterStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<PropertySetterConfiguration> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestPropertySetterStore.class);

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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
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
    public Response updatePropertySetter(String id, Integer version, PropertySetterConfiguration propertySetterConfiguration) {
        return restVersionInfo.update(id, version, propertySetterConfiguration);
    }

    @Override
    public Response createPropertySetter(PropertySetterConfiguration propertySetterConfiguration) {
        return restVersionInfo.create(propertySetterConfiguration);
    }

    @Override
    public Response deletePropertySetter(String id, Integer version) {
        return restVersionInfo.delete(id, version);
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
