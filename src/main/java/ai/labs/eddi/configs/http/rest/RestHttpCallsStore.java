package ai.labs.eddi.configs.http.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.IRestHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.models.DocumentDescriptor;
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
public class RestHttpCallsStore implements IRestHttpCallsStore {
    private final IHttpCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<HttpCallsConfiguration> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestHttpCallsStore.class);

    @Inject
    public RestHttpCallsStore(IHttpCallsStore httpCallsStore,
                              IDocumentDescriptorStore documentDescriptorStore,
                              IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(HttpCallsConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readHttpCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.httpcalls", filter, index, limit);
    }

    @Override
    public HttpCallsConfiguration readHttpCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateHttpCalls(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.update(id, version, httpCallsConfiguration);
    }

    @Override
    public Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public Response deleteHttpCalls(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response duplicateHttpCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        HttpCallsConfiguration httpCallsConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
