package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.persistence.IResourceStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import javax.inject.Inject;

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
    private final MongoCollection<Document> conversationCollectionDocument;
    private final MongoCollection<ConversationMemorySnapshot> conversationCollectionObject;

    @Inject
    public ConversationMemoryStore(MongoDatabase database) {
        this.conversationCollectionDocument = database.getCollection(CONVERSATION_COLLECTION, Document.class);
        this.conversationCollectionObject = database.getCollection(CONVERSATION_COLLECTION, ConversationMemorySnapshot.class);
        conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_STATE_FIELD));
        conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_BOT_ID_FIELD));
        conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_BOT_VERSION_FIELD));
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        String conversationId = snapshot.getConversationId();
        if (conversationId != null) {
            conversationCollectionObject.replaceOne(
                    new Document(OBJECT_ID, new ObjectId(conversationId)), snapshot);
        } else {
            snapshot.setId(new ObjectId().toString());
            conversationCollectionObject.insertOne(snapshot);
        }

        return snapshot.getConversationId();
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceNotFoundException {

        var memorySnapshot = conversationCollectionObject.find(new Document(OBJECT_ID, new ObjectId(conversationId))).first();

        if (memorySnapshot == null) {
            String message = "Could not find ConversationMemorySnapshot (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new ResourceNotFoundException(message);
        }

        return memorySnapshot;
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        Document updateConversationStateField = new Document("$set", new Document(CONVERSATION_STATE_FIELD, conversationState.name()));
        conversationCollectionDocument.updateOne(new Document(OBJECT_ID, new ObjectId(conversationId)), updateConversationStateField);
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        conversationCollectionDocument.deleteOne(new Document(OBJECT_ID, new ObjectId(conversationId)));
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        Document conversationMemoryDocument = conversationCollectionDocument.find(
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
        return conversationCollectionDocument.countDocuments(query);
    }

    @Override
    public ConversationMemorySnapshot readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public IResourceId create(ConversationMemorySnapshot content) {
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
    public ConversationMemorySnapshot read(String id, Integer version) throws ResourceNotFoundException {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) {
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
