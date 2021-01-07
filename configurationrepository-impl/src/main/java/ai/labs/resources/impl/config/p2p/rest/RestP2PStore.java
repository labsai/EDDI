package ai.labs.resources.impl.config.p2p.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.git.model.GitCallsConfiguration;
import ai.labs.resources.rest.config.p2p.IP2PStore;
import ai.labs.resources.rest.config.p2p.IRestP2PStore;
import ai.labs.resources.rest.config.p2p.model.P2PConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.schema.IJsonSchemaCreator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

@Slf4j
public class RestP2PStore extends RestVersionInfo<P2PConfiguration> implements IRestP2PStore {

    private final IP2PStore p2pStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestP2PStore restP2PStore;

    @Inject
    public RestP2PStore(IP2PStore p2pStore,
                             IRestInterfaceFactory restInterfaceFactory,
                             IDocumentDescriptorStore documentDescriptorStore,
                             IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, p2pStore, documentDescriptorStore);
        this.p2pStore = p2pStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restP2PStore = restInterfaceFactory.get(IRestP2PStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restP2PStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }


    @Override
    public Response redirectToLatestVersion(String id) {
        return null;
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return null;
    }

    @Override
    public IResourceStore.IResourceId getCurrentVersion(String id)  {
        try {
            return p2pStore.getCurrentResourceId(id);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.error("p2p ressource not found", e);
            return null;
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(P2PConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readP2PCallsDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.p2p", filter, index, limit);
    }

    @Override
    public P2PConfiguration readP2PCalls(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updateP2PCalls(String id, Integer version, P2PConfiguration p2PConfiguration) {
        return update(id, version, p2PConfiguration);
    }

    @Override
    public Response createP2PCalls(P2PConfiguration p2PConfiguration) {
        return create(p2PConfiguration);
    }

    @Override
    public Response duplicateP2PCalls(String id, Integer version) {
        validateParameters(id, version);
        P2PConfiguration p2PConfiguration = restP2PStore.readP2PCalls(id, version);
        return restP2PStore.createP2PCalls(p2PConfiguration);
    }

    @Override
    public Response deleteP2PCalls(String id, Integer version) {
        return delete(id, version);
    }
}
