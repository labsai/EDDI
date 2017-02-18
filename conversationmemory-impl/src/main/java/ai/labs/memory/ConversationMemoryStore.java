package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationState;
import ai.labs.persistence.IResourceStore;
import ai.labs.serialization.IJsonSerialization;
import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
public class ConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {
    private static final String CONVERSATION_COLLECTION = "conversationmemories";
    private static final String CONVERSATION_STATE_FIELD = "conversationState";
    private final MongoCollection<Document> conversationCollection;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public ConversationMemoryStore(MongoDatabase database, IJsonSerialization jsonSerialization) {
        conversationCollection = database.getCollection(CONVERSATION_COLLECTION);
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        try {
            String json = jsonSerialization.serialize(snapshot);
            Document document = Document.parse(json);

            if (snapshot.getId() != null) {
                document.put("_id", new ObjectId(snapshot.getId()));
            }

            document.remove("id");

            conversationCollection.insertOne(document);

            return document.get("_id").toString();
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        Document document = conversationCollection.find(new Document("_id", new ObjectId(conversationId))).first();

        try {
            if (document == null) {
                String message = "Could not find ConversationMemorySnapshot (id=%s)";
                message = String.format(message, conversationId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            document.remove("_id");

            ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(document.toString(), ConversationMemorySnapshot.class);

            snapshot.setId(conversationId);

            return snapshot;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        BasicDBObject updateConversationStateField = new BasicDBObject("$set", new BasicDBObject(CONVERSATION_STATE_FIELD, conversationState.name()));
        conversationCollection.updateMany(new BasicDBObject("_id", new ObjectId(conversationId)), updateConversationStateField);
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        conversationCollection.deleteOne(new BasicDBObject("_id", new ObjectId(conversationId)));
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        Document conversationMemoryDocument = conversationCollection.find(new Document("_id", new ObjectId(conversationId))).first();
        if (conversationMemoryDocument != null && conversationMemoryDocument.containsKey(CONVERSATION_STATE_FIELD)) {
            return ConversationState.valueOf(conversationMemoryDocument.get(CONVERSATION_STATE_FIELD).toString());
        }

        return null;
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
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        storeConversationMemorySnapshot(content);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        //todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        //todo implement
    }

    @Override
    public IResourceId getCurrentResourceId(final String id) throws ResourceNotFoundException {
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
