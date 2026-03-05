package ai.labs.eddi.configs.langchain.mongo;

import ai.labs.eddi.configs.langchain.ILangChainStore;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import com.mongodb.reactivestreams.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LangChainStore extends AbstractMongoResourceStore<LangChainConfiguration>
        implements ILangChainStore {

    @Inject
    public LangChainStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, "langchain", documentBuilder, LangChainConfiguration.class);
    }
}
