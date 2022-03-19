package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.ConversationState;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static ai.labs.eddi.models.ConversationState.ENDED;

/**
 * @author ginccc
 */
@ApplicationScoped
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

        var memorySnapshot = conversationCollectionObject.find(
                new Document(OBJECT_ID, new ObjectId(conversationId))).first();

        for (ConversationMemorySnapshot.ConversationStepSnapshot conversationStep : memorySnapshot.getConversationSteps()) {
            for (ConversationMemorySnapshot.PackageRunSnapshot aPackage : conversationStep.getPackages()) {
                for (ConversationMemorySnapshot.ResultSnapshot lifecycleTask : aPackage.getLifecycleTasks()) {
                    if (lifecycleTask.getKey().startsWith("context")) {
                        Object result = lifecycleTask.getResult();
                        if (result instanceof LinkedHashMap) {
                            LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) result;
                            Context context = new Context(
                                    Context.ContextType.valueOf(map.get("type").toString()),
                                    map.get("value"));
                            lifecycleTask.setResult(context);
                        }
                    }
                }
            }
        }

        if (memorySnapshot == null) {
            String message = "Could not find ConversationMemorySnapshot (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        return memorySnapshot;
    }

    @Override
    public List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {

        try {
            List<ConversationMemorySnapshot> ret = new ArrayList<>();

            Document query = new Document();
            query.put("botId", botId);
            query.put("botVersion", botVersion);
            query.put("conversationState", new Document("$ne", ENDED.toString()));

            var cursor = conversationCollectionObject.find(query).cursor();
            while (cursor.hasNext()) {
                ret.add(cursor.next());
            }

            return ret;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
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
    public ConversationMemorySnapshot readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public IResourceStore.IResourceId create(ConversationMemorySnapshot content) {
        final String conversationId = storeConversationMemorySnapshot(content);

        return new IResourceStore.IResourceId() {
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
    public ConversationMemorySnapshot read(String id, Integer version) throws IResourceStore.ResourceNotFoundException {
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
    public IResourceStore.IResourceId getCurrentResourceId(final String id) {
        return new IResourceStore.IResourceId() {
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
