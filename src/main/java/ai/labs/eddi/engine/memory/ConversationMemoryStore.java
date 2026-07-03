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
import com.mongodb.client.model.Projections;
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
        // owner-scoped pending-approvals inbox: filter by (state, userId) in-query
        conversationCollectionDocument.createIndex(Indexes.ascending(KEY_CONVERSATION_STATE, "userId"));
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
    public boolean storeConversationMemorySnapshotIfState(ConversationMemorySnapshot snapshot, ConversationState expectedState) {
        String conversationId = snapshot.getConversationId();
        if (conversationId == null) {
            // A conditional store only makes sense against an existing document.
            return false;
        }
        var filter = new Document(OBJECT_ID, new ObjectId(conversationId))
                .append(KEY_CONVERSATION_STATE, expectedState.name());
        // Atomic compare-and-store: replaces the whole document (including its new
        // state) only while the persisted state still equals expectedState. If a
        // concurrent terminal writer already flipped it (ENDED/EXECUTION_INTERRUPTED),
        // the filter misses and nothing is overwritten.
        var result = conversationCollectionObject.replaceOne(filter, snapshot);
        return result.getMatchedCount() > 0;
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
        // Plan §10(a): AWAITING_HUMAN conversations do not count as active — with
        // the default WAIT_INDEFINITELY policy a single forgotten approval would
        // otherwise block undeploy and old-version GC forever. A paused
        // conversation whose agent was undeployed keeps its pause; resume then
        // reports 409 "agent not deployed" and restores the pause.
        Bson query = Filters.and(Filters.eq(KEY_AGENT_ID, agentId), Filters.eq(KEY_AGENT_VERSION, agentVersion),
                Filters.nin(KEY_CONVERSATION_STATE,
                        ENDED.toString(), ConversationState.AWAITING_HUMAN.toString()));
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
    public boolean compareAndSetState(String conversationId, ConversationState expected, ConversationState target) {
        var filter = Filters.and(
                Filters.eq(OBJECT_ID, new ObjectId(conversationId)),
                Filters.eq(KEY_CONVERSATION_STATE, expected.name()));
        var update = new Document("$set", new Document(KEY_CONVERSATION_STATE, target.name()));
        var result = conversationCollectionDocument.updateOne(filter, update);
        // matchedCount (not modifiedCount) so a no-op CAS (expected == target) still
        // reports success — consistent with storeConversationMemorySnapshotIfState.
        return result.getMatchedCount() > 0;
    }

    @Override
    public List<String> findConversationIdsByState(ConversationState state) {
        List<String> ids = new ArrayList<>();
        conversationCollectionDocument.find(Filters.eq(KEY_CONVERSATION_STATE, state.name()))
                .projection(new Document(OBJECT_ID, 1))
                .forEach(document -> ids.add(document.get(OBJECT_ID).toString()));
        return ids;
    }

    /**
     * Projected fields for pending-approval summaries — never the full document.
     */
    private static final Bson PENDING_SUMMARY_PROJECTION = Projections.include(KEY_AGENT_ID, "userId",
            "hitlPausedAt", "hitlPauseReason", "hitlTimeoutPolicy", "hitlApprovalTimeout");

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(int limit) {
        // Single bounded, projected query on the indexed state field — the
        // (potentially multi-MB) step/output data of paused conversations is
        // never deserialized, and there are no per-id point-reads (this listing
        // is polled and backs the crash-recovery sweep).
        return collectPendingSummaries(
                conversationCollectionObject.find(Filters.eq(KEY_CONVERSATION_STATE, ConversationState.AWAITING_HUMAN.name()))
                        .projection(PENDING_SUMMARY_PROJECTION)
                        .limit(limit));
    }

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> findPendingApprovalSummaries(String ownerUserId, int limit) {
        // Owner filter INSIDE the query: the limit applies after the restriction,
        // so a user's inbox is complete even behind a large global backlog.
        return collectPendingSummaries(
                conversationCollectionObject.find(Filters.and(
                        Filters.eq(KEY_CONVERSATION_STATE, ConversationState.AWAITING_HUMAN.name()),
                        Filters.eq("userId", ownerUserId)))
                        .projection(PENDING_SUMMARY_PROJECTION)
                        .limit(limit));
    }

    private List<ai.labs.eddi.engine.model.PendingApprovalSummary> collectPendingSummaries(
                                                                                           com.mongodb.client.FindIterable<ConversationMemorySnapshot> snapshots) {
        List<ai.labs.eddi.engine.model.PendingApprovalSummary> out = new ArrayList<>();
        snapshots.forEach(snapshot -> {
            var summary = new ai.labs.eddi.engine.model.PendingApprovalSummary(
                    snapshot.getConversationId(), snapshot.getAgentId(), snapshot.getUserId(),
                    snapshot.getHitlPausedAt(), snapshot.getHitlPauseReason(),
                    snapshot.getHitlTimeoutPolicy());
            summary.setApprovalTimeout(snapshot.getHitlApprovalTimeout());
            out.add(summary);
        });
        return out;
    }

    @Override
    public void clearHitlBookmark(String conversationId) {
        var unset = new Document();
        // Terminal cleanup (end/cancel) must remove ALL pause state, including the
        // tool-level HITL fields — otherwise a stale hitlPauseType / pending batch
        // would linger on an ended or cancelled conversation document.
        for (String field : List.of("hitlPausedWorkflowId", "hitlPausedAbsoluteTaskIndex", "hitlPausedAt",
                "hitlPauseReason", "hitlTimeoutPolicy", "hitlApprovalTimeout",
                "hitlPauseType", "hitlPendingToolCalls")) {
            unset.append(field, "");
        }
        conversationCollectionDocument.updateOne(
                new Document(OBJECT_ID, new ObjectId(conversationId)),
                new Document("$unset", unset));
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
