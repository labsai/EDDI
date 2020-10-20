package ai.labs.resources.impl.config.git.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.git.IGitCallsStore;
import ai.labs.resources.rest.config.git.IRestGitCallsStore;
import ai.labs.resources.rest.config.git.model.GitCallsConfiguration;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.http.IRestHttpCallsStore;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.schema.IJsonSchemaCreator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author rpi
 */
@Slf4j
public class RestGitCallsStore extends RestVersionInfo<GitCallsConfiguration> implements IRestGitCallsStore {
    private final IGitCallsStore gitCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestGitCallsStore restGitCallsStore;

    @Inject
    public RestGitCallsStore(IGitCallsStore gitCallsStore,
                             IRestInterfaceFactory restInterfaceFactory,
                             IDocumentDescriptorStore documentDescriptorStore,
                             IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, gitCallsStore, documentDescriptorStore);
        this.gitCallsStore = gitCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restGitCallsStore = restInterfaceFactory.get(IRestGitCallsStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restGitCallsStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(GitCallsConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readGitCallsDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.gitcalls", filter, index, limit);
    }

    @Override
    public GitCallsConfiguration readGitCalls(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updateGitCalls(String id, Integer version, GitCallsConfiguration gitCallsConfiguration) {
        return update(id, version, gitCallsConfiguration);
    }

    @Override
    public Response createGitCalls(GitCallsConfiguration gitCallsConfiguration) {
        return create(gitCallsConfiguration);
    }

    @Override
    public Response deleteGitCalls(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    public Response duplicateGitCalls(String id, Integer version) {
        validateParameters(id, version);
        GitCallsConfiguration gitCallsConfiguration = restGitCallsStore.readGitCalls(id, version);
        return restGitCallsStore.createGitCalls(gitCallsConfiguration);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return gitCallsStore.getCurrentResourceId(id);
    }
}
