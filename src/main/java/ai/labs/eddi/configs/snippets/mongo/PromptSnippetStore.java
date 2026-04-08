package ai.labs.eddi.configs.snippets.mongo;

import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MongoDB store for {@link PromptSnippet} configuration documents.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class PromptSnippetStore extends AbstractResourceStore<PromptSnippet> implements IPromptSnippetStore {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]+");

    @Inject
    public PromptSnippetStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "promptsnippets", documentBuilder, PromptSnippet.class);
    }

    @Override
    @ConfigurationUpdate
    public IResourceId create(PromptSnippet snippet) throws ResourceStoreException {
        validateName(snippet);
        return super.create(snippet);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, PromptSnippet snippet)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        validateName(snippet);
        return super.update(id, version, snippet);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        super.delete(id, version);
    }

    @Override
    public List<PromptSnippet> readAll() throws ResourceStoreException {
        // This delegates to the descriptor store via the PromptSnippetService.
        // The store itself doesn't implement enumeration — that's the service's job
        // via IDocumentDescriptorStore. This method exists for future direct-query
        // implementations (e.g., a MongoDB aggregation pipeline).
        throw new ResourceStoreException("readAll is implemented via PromptSnippetService using descriptor store enumeration");
    }

    private static void validateName(PromptSnippet snippet) throws ResourceStoreException {
        if (snippet.getName() == null || !NAME_PATTERN.matcher(snippet.getName()).matches()) {
            throw new ResourceStoreException(
                    "Snippet name must match [a-z0-9_]+ (lowercase letters, digits, underscores only). Got: " + snippet.getName());
        }
    }
}
