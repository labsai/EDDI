package ai.labs.eddi.configs.langchain.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.langchain.ILangChainStore;
import ai.labs.eddi.configs.langchain.IRestLangChainStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestLangChainStore implements IRestLangChainStore {
    private final ILangChainStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<LangChainConfiguration> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestLangChainStore.class);

    @Inject
    public RestLangChainStore(ILangChainStore httpCallsStore,
                              IDocumentDescriptorStore documentDescriptorStore,
                              IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(LangChainConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readLangChainDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.langchain", filter, index, limit);
    }

    @Override
    public LangChainConfiguration readLangChain(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateLangChain(String id, Integer version, LangChainConfiguration langChainConfiguration) {
        return restVersionInfo.update(id, version, langChainConfiguration);
    }

    @Override
    public Response createLangChain(LangChainConfiguration langChainConfiguration) {
        return restVersionInfo.create(langChainConfiguration);
    }

    @Override
    public Response deleteLangChain(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response duplicateLangChain(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        LangChainConfiguration langChainConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(langChainConfiguration);
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
