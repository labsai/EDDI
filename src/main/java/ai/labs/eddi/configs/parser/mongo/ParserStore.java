package ai.labs.eddi.configs.parser.mongo;

import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.reactivestreams.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ParserStore extends AbstractMongoResourceStore<ParserConfiguration>
        implements IParserStore {

    @Inject
    public ParserStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, "parsers", documentBuilder, ParserConfiguration.class);
    }
}
