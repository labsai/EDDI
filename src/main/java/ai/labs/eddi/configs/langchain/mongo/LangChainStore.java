package ai.labs.eddi.configs.langchain.mongo;

import ai.labs.eddi.configs.langchain.ILangChainStore;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LangChainStore extends AbstractResourceStore<LangChainConfiguration>
        implements ILangChainStore {

    @Inject
    public LangChainStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "langchain", documentBuilder, LangChainConfiguration.class);
    }
}
