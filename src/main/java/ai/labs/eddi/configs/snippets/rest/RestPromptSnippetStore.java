package ai.labs.eddi.configs.snippets.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.ws.rs.core.Response;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for prompt snippet CRUD.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestPromptSnippetStore implements IRestPromptSnippetStore {
    private final IPromptSnippetStore snippetStore;
    private final RestVersionInfo<PromptSnippet> restVersionInfo;

    @Inject
    public RestPromptSnippetStore(IPromptSnippetStore snippetStore, IDocumentDescriptorStore documentDescriptorStore) {
        this.snippetStore = snippetStore;
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, snippetStore, documentDescriptorStore);
    }

    @Override
    public List<DocumentDescriptor> readSnippetDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.snippet", filter, index, limit);
    }

    @Override
    public PromptSnippet readSnippet(String id, Integer version) {
        try {
            return snippetStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateSnippet(String id, Integer version, PromptSnippet snippet) {
        return restVersionInfo.update(id, version, snippet);
    }

    @Override
    public Response createSnippet(PromptSnippet snippet) {
        return restVersionInfo.create(snippet);
    }

    @Override
    public Response deleteSnippet(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return snippetStore.getCurrentResourceId(id);
    }
}
