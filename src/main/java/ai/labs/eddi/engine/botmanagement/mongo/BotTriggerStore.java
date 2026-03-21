package ai.labs.eddi.engine.botmanagement.mongo;

import ai.labs.eddi.engine.botmanagement.IBotTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.botmanagement.model.BotTriggerConfiguration;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB implementation of {@link IBotTriggerStore}.
 * Annotated {@code @DefaultBean} so PostgreSQL can override.
 *
 * @author ginccc
 */
@ApplicationScoped
@DefaultBean
public class BotTriggerStore implements IBotTriggerStore {
    private static final String COLLECTION_BOT_TRIGGERS = "bottriggers";
    private static final String INTENT_FIELD = "intent";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;
    private final BotTriggerResourceStore botTriggerStore;

    @Inject
    public BotTriggerStore(MongoDatabase database,
                           IJsonSerialization jsonSerialization,
                           IDocumentBuilder documentBuilder) {
        this.jsonSerialization = jsonSerialization;
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_BOT_TRIGGERS);
        this.documentBuilder = documentBuilder;
        this.botTriggerStore = new BotTriggerResourceStore();
        collection.createIndex(Indexes.ascending(INTENT_FIELD), new IndexOptions().unique(true));
    }

    @Override
    public List<BotTriggerConfiguration> readAllBotTriggers() throws IResourceStore.ResourceStoreException {
        return botTriggerStore.readAllBotTriggers();
    }

    @Override
    public BotTriggerConfiguration readBotTrigger(String intent)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);

        return botTriggerStore.readBotTrigger(intent);
    }

    @Override
    public void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(botTriggerConfiguration, "botTriggerConfiguration");

        botTriggerStore.updateBotTrigger(intent, botTriggerConfiguration);
    }

    @Override
    public void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
            throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(botTriggerConfiguration, "botTriggerConfiguration");

        botTriggerStore.createBotTrigger(botTriggerConfiguration);
    }

    @Override
    public void deleteBotTrigger(String intent) {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);

        botTriggerStore.deleteBotTrigger(intent);
    }

    private class BotTriggerResourceStore {
        BotTriggerConfiguration readBotTrigger(String intent)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, intent);

            try {
                Document document = collection.find(filter).first();
                if (document == null) {
                    String message = "BotTriggerConfiguration with intent=%s does not exist";
                    message = String.format(message, intent);
                    throw new IResourceStore.ResourceNotFoundException(message);
                }
                return documentBuilder.build(document, BotTriggerConfiguration.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        List<BotTriggerConfiguration> readAllBotTriggers()
                throws IResourceStore.ResourceStoreException {

            List<BotTriggerConfiguration> botTriggers = new ArrayList<>();
            try {
                for (var document : collection.find()) {
                    botTriggers.add(documentBuilder.build(document, BotTriggerConfiguration.class));
                }

                return botTriggers;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            Document document = createDocument(botTriggerConfiguration);
            var result = collection.replaceOne(new Document(INTENT_FIELD, intent), document);
            if (result.getMatchedCount() == 0) {
                String message = "BotTriggerConfiguration with intent=%s does not exist";
                message = String.format(message, intent);
                throw new IResourceStore.ResourceNotFoundException(message);
            }
        }

        void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, ResourceAlreadyExistsException {

            Document existing = collection.find(
                    new Document(INTENT_FIELD, botTriggerConfiguration.getIntent())).first();
            if (existing != null) {
                String message = "BotTriggerConfiguration with intent=%s already exists";
                message = String.format(message, botTriggerConfiguration.getIntent());
                throw new ResourceAlreadyExistsException(message);
            }

            collection.insertOne(createDocument(botTriggerConfiguration));
        }

        void deleteBotTrigger(String intent) {
            collection.deleteOne(new Document(INTENT_FIELD, intent));
        }

        private Document createDocument(BotTriggerConfiguration botTriggerConfiguration)
                throws IResourceStore.ResourceStoreException {
            try {
                return jsonSerialization.deserialize(jsonSerialization.serialize(botTriggerConfiguration),
                        Document.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
