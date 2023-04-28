package ai.labs.eddi.configs.git.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.git.IGitCallsStore;
import ai.labs.eddi.configs.git.IRestGitCallsStore;
import ai.labs.eddi.configs.git.model.GitCallsConfiguration;
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
 * @author rpi
 */
@ApplicationScoped
public class RestGitCallsStore implements IRestGitCallsStore {
    private final IGitCallsStore gitCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<GitCallsConfiguration> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestGitCallsStore.class);

    @Inject
    public RestGitCallsStore(IGitCallsStore gitCallsStore,
                             IDocumentDescriptorStore documentDescriptorStore,
                             IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, gitCallsStore, documentDescriptorStore);
        this.gitCallsStore = gitCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(GitCallsConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readGitCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.gitcalls", filter, index, limit);
    }

    @Override
    public GitCallsConfiguration readGitCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateGitCalls(String id, Integer version, GitCallsConfiguration gitCallsConfiguration) {
        return restVersionInfo.update(id, version, gitCallsConfiguration);
    }

    @Override
    public Response createGitCalls(GitCallsConfiguration gitCallsConfiguration) {
        return restVersionInfo.create(gitCallsConfiguration);
    }

    @Override
    public Response deleteGitCalls(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response duplicateGitCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        GitCallsConfiguration gitCallsConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(gitCallsConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return gitCallsStore.getCurrentResourceId(id);
    }
}
