/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB implementation of {@link IConversationCheckpointStore}.
 * <p>
 * Stores checkpoints in a dedicated {@code conversation_checkpoints} collection
 * with indices on {@code conversationId} and {@code createdAt}.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class MongoConversationCheckpointStore implements IConversationCheckpointStore {

    private static final Logger LOGGER = Logger.getLogger(MongoConversationCheckpointStore.class);
    private static final String COLLECTION_NAME = "conversation_checkpoints";
    private static final String KEY_CHECKPOINT_ID = "checkpointId";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_CREATED_AT = "createdAt";

    private final MongoCollection<MemoryCheckpoint> collection;

    @Inject
    public MongoConversationCheckpointStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME, MemoryCheckpoint.class);
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(KEY_CONVERSATION_ID),
                Indexes.descending(KEY_CREATED_AT)));
    }

    @Override
    public void create(MemoryCheckpoint checkpoint) {
        collection.insertOne(checkpoint);
    }

    @Override
    public List<MemoryCheckpoint> findByConversationId(String conversationId, int limit) {
        List<MemoryCheckpoint> results = new ArrayList<>();
        collection.find(Filters.eq(KEY_CONVERSATION_ID, conversationId))
                .sort(Sorts.descending(KEY_CREATED_AT))
                .limit(limit)
                .forEach(results::add);
        return results;
    }

    @Override
    public MemoryCheckpoint findById(String checkpointId) {
        return collection.find(Filters.eq(KEY_CHECKPOINT_ID, checkpointId)).first();
    }

    @Override
    public void deleteById(String checkpointId) {
        collection.deleteOne(Filters.eq(KEY_CHECKPOINT_ID, checkpointId));
    }

    @Override
    public int pruneOldest(String conversationId, int keepCount) {
        // Find the IDs to keep (newest N)
        List<String> keepIds = new ArrayList<>();
        collection.find(Filters.eq(KEY_CONVERSATION_ID, conversationId))
                .sort(Sorts.descending(KEY_CREATED_AT))
                .limit(keepCount)
                .forEach(ckpt -> keepIds.add(ckpt.checkpointId()));

        if (keepIds.isEmpty()) {
            return 0;
        }

        // Delete all except the kept ones
        long deleted = collection.deleteMany(
                Filters.and(
                        Filters.eq(KEY_CONVERSATION_ID, conversationId),
                        Filters.nin(KEY_CHECKPOINT_ID, keepIds)))
                .getDeletedCount();

        if (deleted > 0) {
            LOGGER.debugf("Pruned %d checkpoints for conversation '%s' (keeping %d)", (Object) deleted, conversationId, keepCount);
        }

        return (int) deleted;
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return collection.deleteMany(Filters.eq(KEY_CONVERSATION_ID, conversationId)).getDeletedCount();
    }
}
