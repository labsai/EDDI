package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LlmStore extends AbstractResourceStore<LlmConfiguration>
        implements ILlmStore {

    @Inject
    public LlmStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "llmconfigs", documentBuilder, LlmConfiguration.class);
    }
}
