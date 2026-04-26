/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static ai.labs.eddi.engine.model.Context.ContextType.valueOf;
import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;

/**
 * MongoDB implementation of {@link IConversationMemoryStore}.
 * <p>
 * Annotated {@code @DefaultBean} so that future database backends (e.g.,
 * PostgreSQL) can provide an alternative implementation activated via
 * {@code @LookupIfProperty(name = "eddi.datastore.type", stringValue = "postgres")}.
 *
 * @author ginccc
 */
@ApplicationScoped
@DefaultBean
public class ConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {
    private static final String CONVERSATION_COLLECTION = "conversationmemories";
    private static final String OBJECT_ID = "_id";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_AGENT_VERSION = "agentVersion";
    private static final String KEY_CONVERSATION_STATE = "conversationState";
    private final MongoCollection<Document> conversationCollectionDocument;
    private final MongoCollection<ConversationMemorySnapshot> conversationCollectionObject;

    @Inject
    public ConversationMemoryStore(MongoDatabase database) {
        this.conversationCollectionDocument = database.getCollection(CONVERSATION_COLLECTION, Document.class);
        this.conversationCollectionObject = database.getCollection(CONVERSATION_COLLECTION, ConversationMemorySnapshot.class);
        conversationCollectionDocument.createIndex(Indexes.ascending(KEY_CONVERSATION_STATE));
        conversationCollectionDocument.createIndex(Indexes.ascending(KEY_AGENT_ID));
        conversationCollectionDocument.createIndex(Indexes.ascending(KEY_AGENT_VERSION));
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        String conversationId = snapshot.getConversationId();
        if (conversationId != null) {
            conversationCollectionObject.replaceOne(new Document(OBJECT_ID, new ObjectId(conversationId)), snapshot);
        } else {
            snapshot.setId(new ObjectId().toString());
            conversationCollectionObject.insertOne(snapshot);
        }

        return snapshot.getConversationId();
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) {
        var memorySnapshot = conversationCollectionObject.find(new Document(OBJECT_ID, new ObjectId(conversationId))).first();

        if (memorySnapshot == null) {
            return null;
        }

        for (var conversationStep : memorySnapshot.getConversationSteps()) {
            for (var aWorkflow : conversationStep.getWorkflows()) {
                for (var lifecycleTask : aWorkflow.getLifecycleTasks()) {
                    if (lifecycleTask.getKey().startsWith(KEY_CONTEXT)) {
                        var result = lifecycleTask.getResult();
                        if (result instanceof LinkedHashMap<?, ?>) {
                            @SuppressWarnings("unchecked")
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
    public List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException {

        try {
            ArrayList<ConversationMemorySnapshot> retRet = new ArrayList<>();

            Document query = new Document();
            query.put(KEY_AGENT_ID, agentId);
            query.put(KEY_AGENT_VERSION, agentVersion);
            query.put(KEY_CONVERSATION_STATE, new Document("$ne", ENDED.toString()));

            conversationCollectionObject.find(query).forEach(retRet::add);
            return retRet;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        var updateConversationStateField = new Document("$set", new Document(KEY_CONVERSATION_STATE, conversationState.name()));

        conversationCollectionDocument.updateOne(new Document(OBJECT_ID, new ObjectId(conversationId)), updateConversationStateField);
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        conversationCollectionDocument.deleteOne(new Document(OBJECT_ID, new ObjectId(conversationId)));
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        Document conversationMemoryDocument = conversationCollectionDocument.find(new Document(OBJECT_ID, new ObjectId(conversationId)))
                .projection(new Document(KEY_CONVERSATION_STATE, 1).append(OBJECT_ID, 0)).first();
        if (conversationMemoryDocument == null) {
            return null;
        }
        if (conversationMemoryDocument.containsKey(KEY_CONVERSATION_STATE)) {
            return ConversationState.valueOf(conversationMemoryDocument.get(KEY_CONVERSATION_STATE).toString());
        }
        return null;
    }

    @Override
    public Long getActiveConversationCount(String agentId, Integer agentVersion) {
        Bson query = Filters.and(Filters.eq(KEY_AGENT_ID, agentId), Filters.eq(KEY_AGENT_VERSION, agentVersion),
                Filters.not(new Document(KEY_CONVERSATION_STATE, ENDED.toString())));
        return conversationCollectionDocument.countDocuments(query);
    }

    @Override
    public List<String> getEndedConversationIds() {
        List<String> ids = new ArrayList<>();
        conversationCollectionDocument.find(Filters.eq(KEY_CONVERSATION_STATE, ENDED.toString()))
                .forEach(document -> ids.add(document.get(OBJECT_ID).toString()));
        return ids;
    }

    @Override
    public List<String> getConversationIdsByUserId(String userId) {
        List<String> ids = new ArrayList<>();
        conversationCollectionDocument.find(new Document("userId", userId))
                .projection(new Document(OBJECT_ID, 1))
                .forEach(document -> ids.add(document.get(OBJECT_ID).toString()));
        return ids;
    }

    @Override
    public long deleteConversationsByUserId(String userId) {
        return conversationCollectionDocument.deleteMany(new Document("userId", userId)).getDeletedCount();
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
        // todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        // todo implement
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
