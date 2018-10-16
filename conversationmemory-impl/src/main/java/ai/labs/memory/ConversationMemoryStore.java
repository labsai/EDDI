package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.persistence.IResourceStore;
import ai.labs.serialization.IDocumentBuilder;
import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;

import static ai.labs.models.ConversationState.ENDED;

/**
 * @author ginccc
 */
public class ConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {
    private static final String CONVERSATION_COLLECTION = "conversationmemories";
    private static final String CONVERSATION_STATE_FIELD = "conversationState";
    private static final String CONVERSATION_BOT_ID_FIELD = "botId";
    private static final String CONVERSATION_BOT_VERSION_FIELD = "botVersion";
    private static final String OBJECT_ID = "_id";
    private final MongoCollection<Document> conversationCollection;
    private final IDocumentBuilder documentBuilder;

    @Inject
    public ConversationMemoryStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        conversationCollection = database.getCollection(CONVERSATION_COLLECTION);
        this.documentBuilder = documentBuilder;
        conversationCollection.createIndex(Indexes.ascending(CONVERSATION_STATE_FIELD));
        conversationCollection.createIndex(Indexes.ascending(CONVERSATION_BOT_ID_FIELD));
        conversationCollection.createIndex(Indexes.ascending(CONVERSATION_BOT_VERSION_FIELD));
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        try {
            String json = documentBuilder.toString(snapshot);
            Document document = Document.parse(json);

            document.remove("id");

            if (snapshot.getId() != null) {
                document.put(OBJECT_ID, new ObjectId(snapshot.getId()));
                conversationCollection.updateOne(new Document(OBJECT_ID, new ObjectId(snapshot.getId())),
                        new Document("$set", document),
                        new UpdateOptions().upsert(true));
            } else {
                conversationCollection.insertOne(document);
            }

            return document.get(OBJECT_ID).toString();
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        Document document = conversationCollection.find(new Document(OBJECT_ID, new ObjectId(conversationId))).first();

        try {
            if (document == null) {
                String message = "Could not find ConversationMemorySnapshot (id=%s)";
                message = String.format(message, conversationId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            document.remove(OBJECT_ID);

            ConversationMemorySnapshot snapshot = documentBuilder.build(document, ConversationMemorySnapshot.class);

            snapshot.setId(conversationId);

            return snapshot;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        Document updateConversationStateField = new Document("$set", new BasicDBObject(CONVERSATION_STATE_FIELD, conversationState.name()));
        conversationCollection.updateOne(new Document(OBJECT_ID, new ObjectId(conversationId)), updateConversationStateField);
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        conversationCollection.deleteOne(new Document(OBJECT_ID, new ObjectId(conversationId)));
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        Document conversationMemoryDocument = conversationCollection.find(
                new Document(OBJECT_ID, new ObjectId(conversationId))).
                projection(new Document(CONVERSATION_STATE_FIELD, 1).append(OBJECT_ID, 0)).
                first();
        if (conversationMemoryDocument != null && conversationMemoryDocument.containsKey(CONVERSATION_STATE_FIELD)) {
            return ConversationState.valueOf(conversationMemoryDocument.get(CONVERSATION_STATE_FIELD).toString());
        }

        return null;
    }

    @Override
    public Long getActiveConversationCount(String botId, Integer botVersion) {
        Bson query = Filters.and(Filters.eq("botId", botId), Filters.eq("botVersion", botVersion),
                Filters.not(new Document("conversationState", ENDED.toString())));
        return conversationCollection.countDocuments(query);
    }

    @Override
    public IResourceId create(ConversationMemorySnapshot content) throws ResourceStoreException {
        final String conversationId = storeConversationMemorySnapshot(content);

        return new IResourceId() {
            @Override
            public String getId() {
                return conversationId;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    @Override
    public ConversationMemorySnapshot read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) throws ResourceStoreException {
        storeConversationMemorySnapshot(content);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) {
        //todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        //todo implement
    }

    @Override
    public IResourceId getCurrentResourceId(final String id) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }
}
