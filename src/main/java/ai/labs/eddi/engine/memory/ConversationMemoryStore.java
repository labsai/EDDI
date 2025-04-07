package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.ConversationState;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.model.Context.ContextType.valueOf;
import static ai.labs.eddi.engine.model.ConversationState.ENDED;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {
    private static final String CONVERSATION_COLLECTION = "conversationmemories";
    private static final String OBJECT_ID = "_id";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";
    private static final String KEY_BOT_ID = "botId";
    private static final String KEY_BOT_VERSION = "botVersion";
    private static final String KEY_CONVERSATION_STATE = "conversationState";
    private final MongoCollection<Document> conversationCollectionDocument;
    private final MongoCollection<ConversationMemorySnapshot> conversationCollectionObject;

    @Inject
    public ConversationMemoryStore(MongoDatabase database) {
        this.conversationCollectionDocument = database.getCollection(CONVERSATION_COLLECTION, Document.class);
        this.conversationCollectionObject = database.getCollection(CONVERSATION_COLLECTION, ConversationMemorySnapshot.class);
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(KEY_CONVERSATION_STATE))
        ).blockingFirst();
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(KEY_BOT_ID))
        ).blockingFirst();
        Observable.fromPublisher(
                conversationCollectionDocument.createIndex(Indexes.ascending(KEY_BOT_VERSION))
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
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) {
        var memorySnapshot = Observable.fromPublisher(conversationCollectionObject.find(
                new Document(OBJECT_ID, new ObjectId(conversationId))).first()).blockingFirst();

        for (var conversationStep : memorySnapshot.getConversationSteps()) {
            for (var aPackage : conversationStep.getPackages()) {
                for (var lifecycleTask : aPackage.getLifecycleTasks()) {
                    if (lifecycleTask.getKey().startsWith(KEY_CONTEXT)) {
                        var result = lifecycleTask.getResult();
                        if (result instanceof LinkedHashMap) {
                            var map = (LinkedHashMap<String, Object>) result;
                            var context = new Context(valueOf(map.get(KEY_TYPE).toString()), map.get(KEY_VALUE));
                            lifecycleTask.setResult(context);
                        }
                    }
                }
            }
        }

        memorySnapshot.setConversationId(conversationId);
        return memorySnapshot;
    }

    @Override
    public List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {

        try {
            ArrayList<ConversationMemorySnapshot> retRet = new ArrayList<>();

            Document query = new Document();
            query.put(KEY_BOT_ID, botId);
            query.put(KEY_BOT_VERSION, botVersion);
            query.put(KEY_CONVERSATION_STATE, new Document("$ne", ENDED.toString()));

            var ret = Observable.fromPublisher(conversationCollectionObject.find(query)).blockingIterable();
            ret.forEach(retRet::add);
            return retRet;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        var updateConversationStateField =
                new Document("$set", new Document(KEY_CONVERSATION_STATE, conversationState.name()));

        Observable.fromPublisher(conversationCollectionDocument.updateOne(
                new Document(OBJECT_ID, new ObjectId(conversationId)), updateConversationStateField)).blockingFirst();
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        Observable.fromPublisher(conversationCollectionDocument.deleteOne(
                new Document(OBJECT_ID, new ObjectId(conversationId)))).blockingFirst();
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        try {
            Document conversationMemoryDocument = Observable.fromPublisher(conversationCollectionDocument.find(
                            new Document(OBJECT_ID, new ObjectId(conversationId))).
                    projection(new Document(KEY_CONVERSATION_STATE, 1).append(OBJECT_ID, 0)).
                    first()).blockingFirst();
            if (conversationMemoryDocument.containsKey(KEY_CONVERSATION_STATE)) {
                return ConversationState.valueOf(conversationMemoryDocument.get(KEY_CONVERSATION_STATE).toString());
            }
        } catch (NoSuchElementException ne) {
            return null;
        }
        return null;
    }

    @Override
    public Long getActiveConversationCount(String botId, Integer botVersion) {
        Bson query = Filters.and(Filters.eq(KEY_BOT_ID, botId), Filters.eq(KEY_BOT_VERSION, botVersion),
                Filters.not(new Document(KEY_CONVERSATION_STATE, ENDED.toString())));
        return Observable.fromPublisher(conversationCollectionDocument.countDocuments(query)).blockingFirst();
    }

    @Override
    public List<String> getEndedConversationIds() {
        return Observable.fromPublisher(
                conversationCollectionDocument.find(Filters.eq(KEY_CONVERSATION_STATE, ENDED.toString()))
        ).blockingStream().map(document -> document.get(OBJECT_ID).toString()).collect(Collectors.toList());
    }

    @Override
    public ConversationMemorySnapshot readIncludingDeleted(String id, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

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
