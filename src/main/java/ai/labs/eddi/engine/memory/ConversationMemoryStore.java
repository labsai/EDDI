/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
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
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.mongodb.client.FindIterable;
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
    private static final Logger LOGGER = Logger.getLogger(ConversationMemoryStore.class);
    private static final String CONVERSATION_COLLECTION = "conversationmemories";
    private static final String OBJECT_ID = "_id";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_AGENT_VERSION = "agentVersion";
    private static final String KEY_CONVERSATION_STATE = "conversationState";
    /**
     * Optimistic-concurrency revision — see
     * {@link ConversationMemorySnapshot#getRevision()}.
     */
    private static final String KEY_REVISION = "_rev";
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
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        String conversationId = snapshot.getConversationId();
        if (conversationId != null) {
            long expectedRevision = snapshot.getRevision();
            // The revision the write creates. Set BEFORE the replace, because
            // replaceOne serializes the snapshot as-is — the stored _rev has to be the
            // new one, or every subsequent write would match the same revision and the
            // guard would never fire.
            snapshot.setRevision(expectedRevision + 1);
            var result = conversationCollectionObject.replaceOne(revisionFilter(conversationId, expectedRevision), snapshot);
            // No upsert on purpose: a missing document means the conversation was
            // deleted while the turn was running (GDPR erasure, retention sweep).
            // Ignoring matchedCount discarded the turn's memory silently and still
            // returned a normal response to the caller — surface the conflict instead.
            if (result.getMatchedCount() == 0) {
                // Restore the revision so the caller's snapshot still describes the
                // document it was derived from — a retry must re-present the revision
                // it actually loaded, not the one this attempt failed to create.
                snapshot.setRevision(expectedRevision);
                throw conversationNotWritten(conversationId, expectedRevision);
            }
        } else {
            snapshot.setId(new ObjectId().toString());
            // A fresh conversation starts at revision 1, so a legacy-shaped document
            // (no _rev, read as UNVERSIONED_REVISION) can never be mistaken for one.
            snapshot.setRevision(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1);
            conversationCollectionObject.insertOne(snapshot);
        }

        return snapshot.getConversationId();
    }

    @Override
    public boolean storeConversationMemorySnapshotIfState(ConversationMemorySnapshot snapshot, ConversationState expectedState) {
        String conversationId = snapshot.getConversationId();
        if (conversationId == null || expectedState == null) {
            // A conditional store only makes sense against an existing document with a
            // known expected state. expectedState can now be null when the caller
            // derives it from a live lookup (say-path preTurnPersistedState, undo/redo
            // loaded state) and the document was deleted concurrently — treat that as a
            // CAS miss (discard) rather than NPE on expectedState.name().
            return false;
        }
        long expectedRevision = snapshot.getRevision();
        var filter = Filters.and(
                revisionFilter(conversationId, expectedRevision),
                Filters.eq(KEY_CONVERSATION_STATE, expectedState.name()));
        // Atomic compare-and-store on BOTH arbiters: replaces the whole document
        // (including its new state) only while the persisted state still equals
        // expectedState AND nothing has written the document since this snapshot was
        // loaded. The state half keeps a concurrent terminal writer
        // (ENDED/EXECUTION_INTERRUPTED) from being overwritten; the revision half keeps
        // a concurrent NON-terminal writer — a say turn that appended a step while an
        // undo was in flight — from being overwritten too, which the state filter alone
        // cannot see because both writers leave the same state behind.
        snapshot.setRevision(expectedRevision + 1);
        var result = conversationCollectionObject.replaceOne(filter, snapshot);
        if (result.getMatchedCount() == 0) {
            snapshot.setRevision(expectedRevision);
            return false;
        }
        return true;
    }

    /**
     * Matches the conversation only while it still holds {@code expectedRevision}.
     * <p>
     * {@code UNVERSIONED_REVISION} needs the {@code $or}: a document written before
     * {@code _rev} existed has no such field, and MongoDB's {@code {_rev: 0}} does
     * not match a missing field. Without this, every pre-upgrade conversation's
     * next write would be refused as a phantom conflict.
     */
    private static Bson revisionFilter(String conversationId, long expectedRevision) {
        var idFilter = Filters.eq(OBJECT_ID, new ObjectId(conversationId));
        if (expectedRevision == ConversationMemorySnapshot.UNVERSIONED_REVISION) {
            return Filters.and(idFilter,
                    Filters.or(Filters.eq(KEY_REVISION, expectedRevision), Filters.exists(KEY_REVISION, false)));
        }
        return Filters.and(idFilter, Filters.eq(KEY_REVISION, expectedRevision));
    }

    /**
     * A zero-match write has exactly two causes, and they need different answers
     * from the caller, so tell them apart with one extra point-read of the id
     * alone: the document is gone (deleted mid-turn — nothing to retry against) or
     * it is there at a different revision (another writer committed first — a retry
     * from a fresh load can still land).
     */
    private IResourceStore.ResourceStoreException conversationNotWritten(String conversationId, long expectedRevision) {
        boolean stillExists = conversationCollectionDocument
                .find(Filters.eq(OBJECT_ID, new ObjectId(conversationId)))
                .projection(new Document(OBJECT_ID, 1)).first() != null;
        if (stillExists) {
            return new ConcurrentConversationModificationException(conversationId, expectedRevision);
        }
        return new IResourceStore.ResourceStoreException(
                "Conversation '" + conversationId + "' no longer exists — the turn was NOT persisted. "
                        + "The conversation document was deleted concurrently (e.g. erasure or retention cleanup).");
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
                            // Degrade per entry: a context entry written without a "type",
                            // or with a ContextType a NEWER version knows and this one does
                            // not, must not fail the load of the WHOLE conversation. Leave
                            // the raw map in place and warn.
                            Object rawType = map.get(KEY_TYPE);
                            if (rawType == null) {
                                LOGGER.warnf("Conversation '%s': context entry '%s' has no '%s' field — left unconverted.",
                                        LogSanitizer.sanitize(conversationId), LogSanitizer.sanitize(lifecycleTask.getKey()), KEY_TYPE);
                                continue;
                            }
                            try {
                                lifecycleTask.setResult(new Context(valueOf(rawType.toString()), map.get(KEY_VALUE)));
                            } catch (IllegalArgumentException e) {
                                LOGGER.warnf("Conversation '%s': context entry '%s' has unknown %s '%s' — left unconverted.",
                                        LogSanitizer.sanitize(conversationId), LogSanitizer.sanitize(lifecycleTask.getKey()), KEY_TYPE,
                                        LogSanitizer.sanitize(String.valueOf(rawType)));
                            }
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
            if (agentVersion != null) {
                // null means every version; putting it would match only documents
                // that carry no version at all.
                query.put(KEY_AGENT_VERSION, agentVersion);
            }
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
     * {@code hitlPendingToolCalls.calls.toolName} pulls in ONLY the tool names
     * (never {@code argumentsRaw}/{@code argumentsRedacted}) so this bulk listing
     * stays cheap and never risks exposing tool-call arguments.
     */
    private static final Bson PENDING_SUMMARY_PROJECTION = Projections.include(KEY_AGENT_ID, "userId",
            "hitlPausedAt", "hitlPauseReason", "hitlTimeoutPolicy", "hitlApprovalTimeout",
            "hitlPauseType", "hitlPendingToolCalls.calls.toolName");

    @Override
    public List<PendingApprovalSummary> findPendingApprovalSummaries(int limit) {
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
    public List<PendingApprovalSummary> findPendingApprovalSummaries(String ownerUserId, int limit) {
        // Owner filter INSIDE the query: the limit applies after the restriction,
        // so a user's inbox is complete even behind a large global backlog.
        return collectPendingSummaries(
                conversationCollectionObject.find(Filters.and(
                        Filters.eq(KEY_CONVERSATION_STATE, ConversationState.AWAITING_HUMAN.name()),
                        Filters.eq("userId", ownerUserId)))
                        .projection(PENDING_SUMMARY_PROJECTION)
                        .limit(limit));
    }

    private List<PendingApprovalSummary> collectPendingSummaries(
                                                                 FindIterable<ConversationMemorySnapshot> snapshots) {
        List<PendingApprovalSummary> out = new ArrayList<>();
        snapshots.forEach(snapshot -> {
            var summary = new PendingApprovalSummary(
                    snapshot.getConversationId(), snapshot.getAgentId(), snapshot.getUserId(),
                    snapshot.getHitlPausedAt(), snapshot.getHitlPauseReason(),
                    snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy().name() : null);
            summary.setApprovalTimeout(snapshot.getHitlApprovalTimeout());
            // A rule pause written before the pause type survived clearToolPauseState()
            // is stored with a null type; every pause that is not a tool gate is a RULE
            // pause, and docs/hitl.md promises the field on every entry.
            summary.setPauseType(snapshot.getHitlPauseType() != null
                    ? snapshot.getHitlPauseType()
                    : ConversationPauseException.PauseOrigin.RULE.name());
            if (snapshot.getHitlPendingToolCalls() != null && snapshot.getHitlPendingToolCalls().getCalls() != null) {
                summary.setToolNames(snapshot.getHitlPendingToolCalls().getCalls().stream()
                        .map(PendingToolCallBatch.PendingToolCall::getToolName)
                        .toList());
            }
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
    public IResourceStore.IResourceId create(ConversationMemorySnapshot content) throws IResourceStore.ResourceStoreException {
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
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) throws IResourceStore.ResourceStoreException {
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
