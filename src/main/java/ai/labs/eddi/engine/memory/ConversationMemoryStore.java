package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.ConversationState;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;

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
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_STATE_FIELD))
        ).blockingFirst();
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_BOT_ID_FIELD))
        ).blockingFirst();
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(CONVERSATION_BOT_VERSION_FIELD))
        ).blockingFirst();
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        String conversationId = snapshot.getConversationId();
        if (conversationId != null) {
            Observable.fromPublisher(conversationCollectionObject.replaceOne(
                    new Document(OBJECT_ID, new ObjectId(conversationId)), snapshot)).blockingFirst();
        } else {
            snapshot.setId(new ObjectId().toString());
            Observable.fromPublisher(conversationCollectionObject.insertOne(snapshot)).blockingFirst();
        }

        return snapshot.getConversationId();
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceNotFoundException {

        var memorySnapshot = Observable.fromPublisher(conversationCollectionObject.find(
                new Document(OBJECT_ID, new ObjectId(conversationId))).first()).blockingFirst();

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
            ArrayList<ConversationMemorySnapshot> retRet = new ArrayList<>();

            Document query = new Document();
            query.put("botId", botId);
            query.put("botVersion", botVersion);
            query.put("conversationState", new Document("$ne", ENDED.toString()));

            Iterable<ConversationMemorySnapshot> ret = Observable.fromPublisher(conversationCollectionObject.find(query)).blockingIterable();
            ret.forEach(retRet::add);
            return retRet;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        Document updateConversationStateField = new Document("$set", new Document(CONVERSATION_STATE_FIELD, conversationState.name()));
        Observable.fromPublisher(conversationCollectionDocument.updateOne(new Document(OBJECT_ID, new ObjectId(conversationId)), updateConversationStateField)).blockingFirst();
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        Observable.fromPublisher(conversationCollectionDocument.deleteOne(new Document(OBJECT_ID, new ObjectId(conversationId)))).blockingFirst();
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        try {
            Document conversationMemoryDocument = Observable.fromPublisher(conversationCollectionDocument.find(
                            new Document(OBJECT_ID, new ObjectId(conversationId))).
                    projection(new Document(CONVERSATION_STATE_FIELD, 1).append(OBJECT_ID, 0)).
                    first()).blockingFirst();
            if (conversationMemoryDocument.containsKey(CONVERSATION_STATE_FIELD)) {
                return ConversationState.valueOf(conversationMemoryDocument.get(CONVERSATION_STATE_FIELD).toString());
            }
        } catch (NoSuchElementException ne) {
            return null;
        }
        return null;
    }

    @Override
    public Long getActiveConversationCount(String botId, Integer botVersion) {
        Bson query = Filters.and(Filters.eq("botId", botId), Filters.eq("botVersion", botVersion),
                Filters.not(new Document("conversationState", ENDED.toString())));
        return Observable.fromPublisher(conversationCollectionDocument.countDocuments(query)).blockingFirst();
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
