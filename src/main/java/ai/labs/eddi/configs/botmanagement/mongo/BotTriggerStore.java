package ai.labs.eddi.configs.botmanagement.mongo;

import ai.labs.eddi.configs.botmanagement.IBotTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.models.BotTriggerConfiguration;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */

@ApplicationScoped
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
        Observable.fromPublisher(
                collection.createIndex(Indexes.ascending(INTENT_FIELD), new IndexOptions().unique(true))
        ).blockingFirst();
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
                Document document = Observable.fromPublisher(collection.find(filter).first()).blockingFirst();
                return documentBuilder.build(document, BotTriggerConfiguration.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            } catch (NoSuchElementException ne) {
                String message = "BotTriggerConfiguration with intent=%s does not exist";
                message = String.format(message, intent);
                throw new IResourceStore.ResourceNotFoundException(message);
            }
        }

        void updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            Document document = createDocument(botTriggerConfiguration);
            try {
                Observable.fromPublisher(
                                collection.replaceOne(new Document(INTENT_FIELD, intent), document)).
                        blockingFirst();
            } catch (NoSuchElementException e) {
                String message = "BotTriggerConfiguration with intent=%s does not exist";
                message = String.format(message, intent);
                throw new IResourceStore.ResourceNotFoundException(message);
            }
        }

        void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration)
                throws IResourceStore.ResourceStoreException, ResourceAlreadyExistsException {

            try {
                Observable.fromPublisher(
                                collection.find(new Document(INTENT_FIELD, botTriggerConfiguration.getIntent()))).
                        blockingFirst();

                String message = "BotTriggerConfiguration with intent=%s already exists";
                message = String.format(message, botTriggerConfiguration.getIntent());
                throw new ResourceAlreadyExistsException(message);

            } catch (NoSuchElementException e) {
                Observable.fromPublisher(collection.insertOne(createDocument(botTriggerConfiguration))).blockingFirst();
            }
        }

        void deleteBotTrigger(String intent) {
            Observable.fromPublisher(collection.deleteOne(new Document(INTENT_FIELD, intent))).blockingFirst();
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
