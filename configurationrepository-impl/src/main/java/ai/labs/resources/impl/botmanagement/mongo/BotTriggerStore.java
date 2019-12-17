package ai.labs.resources.impl.botmanagement.mongo;

import ai.labs.models.BotTriggerConfiguration;
import ai.labs.resources.rest.botmanagement.IBotTriggerStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;

import static ai.labs.persistence.IResourceStore.ResourceAlreadyExistsException;
import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;

/**
 * @author ginccc
 */
@Slf4j
public class BotTriggerStore implements IBotTriggerStore {
    private static final String COLLECTION_BOT_TRIGGERS = "bottriggers";
    private static final String INTENT_FIELD = "intent";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;
    private BotTriggerResourceStore botTriggerStore;

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
    public BotTriggerConfiguration readBotTrigger(String intent)
            throws ResourceNotFoundException, ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);

        return botTriggerStore.readBotTrigger(intent);
    }

    @Override
    public void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
            throws ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(botTriggerConfiguration, "botTriggerConfiguration");

        botTriggerStore.updateBotTrigger(intent, botTriggerConfiguration);
    }

    @Override
    public void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
            throws ResourceAlreadyExistsException, ResourceStoreException {
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
                throws ResourceStoreException, ResourceNotFoundException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, intent);

            try {
                Document document = collection.find(filter).first();
                if (document != null) {
                    return documentBuilder.build(document, BotTriggerConfiguration.class);
                } else {
                    String message = "BotTriggerConfiguration with intent=%s does not exist";
                    message = String.format(message, intent);
                    throw new ResourceNotFoundException(message);
                }
            } catch (IOException e) {
                throw new ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
                throws ResourceStoreException {

            Document document = createDocument(botTriggerConfiguration);
            collection.replaceOne(new Document(INTENT_FIELD, intent), document);
        }

        void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
                throws ResourceStoreException, ResourceAlreadyExistsException {

            if (collection.find(new Document(INTENT_FIELD, botTriggerConfiguration.getIntent())).first() != null) {
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
                throws ResourceStoreException {
            try {
                return jsonSerialization.deserialize(jsonSerialization.serialize(botTriggerConfiguration),
                        Document.class);
            } catch (IOException e) {
                throw new ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
