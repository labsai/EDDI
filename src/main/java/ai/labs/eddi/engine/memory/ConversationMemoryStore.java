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
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.Document;
import org.bson.codecs.EncoderContext;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

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
    /**
     * The revision created by the last full-document (history-rewriting) write —
     * see {@link ConversationMemorySnapshot#getHistoryRevision()}.
     */
    private static final String KEY_HISTORY_REVISION = "_histRev";
    private static final String KEY_CONVERSATION_STEPS = "conversationSteps";
    private static final String KEY_CONVERSATION_OUTPUTS = "conversationOutputs";
    /**
     * Keys the append path handles with a dedicated operator, so they must not also
     * be {@code $set} or {@code $unset}: the two step/output arrays are pushed to,
     * {@code _rev} is incremented, and {@code _id} is the filter (MongoDB refuses
     * to update it).
     */
    private static final Set<String> APPEND_HANDLED_KEYS = Set.of(OBJECT_ID, KEY_REVISION, KEY_HISTORY_REVISION,
            KEY_CONVERSATION_STEPS, KEY_CONVERSATION_OUTPUTS);
    /**
     * States a say turn's append must never be applied over — see
     * {@link #appendPreconditions(long)}.
     */
    private static final List<String> NON_APPENDABLE_STATES = List.of(ENDED.name(),
            ConversationState.AWAITING_HUMAN.name(), ConversationState.IN_PROGRESS.name());
    /**
     * How many times an append re-applies itself on a newer revision before giving
     * up and reporting the conflict. Bounded rather than unbounded so a
     * pathological write-storm on one conversation cannot pin a pipeline thread;
     * the loop only repeats when another writer commits between this attempt's
     * filter and its write, which needs a genuinely concurrent writer each time.
     */
    private static final int MAX_APPEND_ATTEMPTS = 5;
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
    public String newConversationId() {
        return new ObjectId().toString();
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        String conversationId = snapshot.getConversationId();
        if (conversationId != null && !snapshot.isUnpersisted()) {
            if (isPureAppend(snapshot)) {
                appendConversationSteps(snapshot);
                return conversationId;
            }
            long expectedRevision = snapshot.getRevision();
            // The revision the write creates. Set BEFORE the replace, because
            // replaceOne serializes the snapshot as-is — the stored _rev has to be the
            // new one, or every subsequent write would match the same revision and the
            // guard would never fire.
            snapshot.setRevision(expectedRevision + 1);
            long loadedHistoryRevision = snapshot.getHistoryRevision();
            // A full-document write rewrites the history (or may), so it records itself as
            // the latest rewrite. An append in flight elsewhere reads this to learn that
            // re-applying its push would no longer land on the history it was built on.
            snapshot.setHistoryRevision(expectedRevision + 1);
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
                snapshot.setHistoryRevision(loadedHistoryRevision);
                throw conversationNotWritten(conversationId, expectedRevision);
            }
            snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
        } else {
            // Insert under the id allocated before the first turn, if there is one.
            snapshot.setId(conversationId != null ? conversationId : newConversationId());
            snapshot.setUnpersisted(false);
            // A fresh conversation starts at revision 1, so a legacy-shaped document
            // (no _rev, read as UNVERSIONED_REVISION) can never be mistaken for one.
            snapshot.setRevision(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1);
            snapshot.setHistoryRevision(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1);
            conversationCollectionObject.insertOne(snapshot);
            snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
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
        long loadedHistoryRevision = snapshot.getHistoryRevision();
        snapshot.setRevision(expectedRevision + 1);
        snapshot.setHistoryRevision(expectedRevision + 1);
        var result = conversationCollectionObject.replaceOne(filter, snapshot);
        if (result.getMatchedCount() == 0) {
            snapshot.setRevision(expectedRevision);
            snapshot.setHistoryRevision(loadedHistoryRevision);
            return false;
        }
        snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
        return true;
    }

    /**
     * Whether this write only <em>adds</em> steps to the document it was derived
     * from, which is what every ordinary conversation turn does.
     * <p>
     * All three conditions matter:
     * <ul>
     * <li>the snapshot knows the persisted step count — so it came from a load
     * whose steps and outputs agreed, and was not rewritten by an undo or a redo
     * (both reset the count; see
     * {@code ConversationMemory.forgetPersistedStepCount})</li>
     * <li>steps and outputs are still in step with each other, so one count
     * identifies the new tail of both</li>
     * <li>the count grew — a rerun re-executes the current step without starting a
     * new one, so it lands here with no growth and takes the full-document replace,
     * which is the only shape that can persist a change to an existing step</li>
     * </ul>
     * <p>
     * <b>What leaving the prefix untouched rests on — a convention, not a type.</b>
     * The memory API makes writing to an earlier step awkward:
     * {@code IConversationStepStack.get}/{@code peek} return
     * {@code IConversationStep}, which has no {@code storeData}; only
     * {@code getCurrentStep()} returns an {@code IWritableConversationStep}. It
     * does not make it impossible: the {@code IData} those steps hand out has
     * setters, their {@code getConversationOutput()} is a mutable map, and so is
     * every entry of {@code getConversationOutputs()}. No production code mutates a
     * prior step today — the readers (InputParserTask, MemoryItemConverter, the
     * behavior-rule matchers, PropertySetterTask, ConversationHistoryBuilder,
     * ConversationSummarizer, ContextualToolsProvider) only read, and
     * LifecycleManager's strict-write handling touches the current step only. A
     * future caller that did would have its change dropped on the next append,
     * which is why this is written down here.
     */
    private static boolean isPureAppend(ConversationMemorySnapshot snapshot) {
        int baseline = snapshot.getPersistedStepCount();
        int steps = snapshot.getConversationSteps().size();
        int outputs = snapshot.getConversationOutputs().size();
        return baseline >= 0 && steps == outputs && steps > baseline;
    }

    /**
     * Persists a pure-append turn by pushing only the new steps and outputs,
     * instead of shipping and rewriting the entire document.
     * <p>
     * <b>Why this exists.</b> Conversations on a production 5.x database average
     * 410 KB, so appending one step used to send 410 KB over the wire, rewrite the
     * whole document and put the whole document in the oplog — per turn. That same
     * whole-document cost is what made the 6.x startup migration take 24 minutes on
     * that database.
     * <p>
     * <b>It is also the repair for a lost update.</b> Because the operation only
     * adds to the tail, a revision conflict can be retried rather than reported:
     * reload the revision, re-apply the same push on top of whatever the winner
     * wrote, and both turns survive. That is not available to a full-document
     * replace, whose "retry" would be the overwrite this whole change exists to
     * prevent.
     * <p>
     * <b>The field set is derived, not listed.</b> Everything the snapshot encodes
     * is {@code $set}, and every key of
     * {@link ConversationMemorySnapshot#TOP_LEVEL_KEYS} it did NOT encode is
     * {@code $unset} — so the non-array part of the document ends up exactly as
     * {@code replaceOne} would have left it, including fields the turn cleared to
     * null (the HITL bookmark, every time {@code clearStaleToolPauseState} runs). A
     * hand-written field list here would silently stop persisting the next field
     * somebody adds, which would be a worse bug than the one this fixes.
     */
    private void appendConversationSteps(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        String conversationId = snapshot.getConversationId();
        int baseline = snapshot.getPersistedStepCount();
        int totalSteps = snapshot.getConversationSteps().size();
        // The revision this turn was LOADED at. Kept apart from the per-attempt
        // revision
        // below: it is what the retry precondition compares against, and it is what a
        // conflict report must name.
        final long loadedRevision = snapshot.getRevision();
        BsonDocument encoded = encodeWithTailOnly(snapshot, baseline);

        List<Bson> operations = new ArrayList<>();
        for (var field : encoded.entrySet()) {
            if (!APPEND_HANDLED_KEYS.contains(field.getKey())) {
                operations.add(Updates.set(field.getKey(), field.getValue()));
            }
        }
        for (String key : ConversationMemorySnapshot.TOP_LEVEL_KEYS) {
            if (!APPEND_HANDLED_KEYS.contains(key) && !encoded.containsKey(key)) {
                operations.add(Updates.unset(key));
            }
        }
        operations.add(Updates.pushEach(KEY_CONVERSATION_STEPS, List.copyOf(encoded.getArray(KEY_CONVERSATION_STEPS))));
        operations.add(Updates.pushEach(KEY_CONVERSATION_OUTPUTS, List.copyOf(encoded.getArray(KEY_CONVERSATION_OUTPUTS))));
        operations.add(Updates.inc(KEY_REVISION, 1L));
        Bson update = Updates.combine(operations);

        long attemptRevision = loadedRevision;
        for (int attempt = 1;; attempt++) {
            var filter = Filters.and(revisionFilter(conversationId, attemptRevision), appendPreconditions(loadedRevision));
            var result = conversationCollectionDocument.updateOne(filter, update);
            if (result.getMatchedCount() > 0) {
                if (attempt == 1) {
                    snapshot.setRevision(loadedRevision + 1);
                    snapshot.setPersistedStepCount(totalSteps);
                } else {
                    markMerged(snapshot, loadedRevision);
                }
                return;
            }
            var stored = conversationCollectionDocument.find(Filters.eq(OBJECT_ID, new ObjectId(conversationId)))
                    .projection(new Document(KEY_REVISION, 1).append(KEY_HISTORY_REVISION, 1).append(KEY_CONVERSATION_STATE, 1))
                    .first();
            if (stored == null) {
                // Gone, not contended — there is nothing to append to.
                throw conversationNotWritten(conversationId, loadedRevision);
            }
            long storedRevision = longOrUnversioned(stored.get(KEY_REVISION));
            long storedHistoryRevision = longOrUnversioned(stored.get(KEY_HISTORY_REVISION));
            Object storedState = stored.get(KEY_CONVERSATION_STATE);
            if (storedHistoryRevision > loadedRevision || isNonAppendableState(storedState)) {
                // The winner did not merely append: it rewrote the history (an undo, a
                // redo, a rerun, a HITL pause or resume commit) or moved the conversation
                // into a state a say turn must never overwrite (ENDED, AWAITING_HUMAN,
                // IN_PROGRESS). Re-applying our $set/$unset on top of that would erase a
                // pending approval, resurrect an ended conversation, or push our step
                // after a history it was never an answer to. Refuse and report.
                throw new ConcurrentConversationModificationException(conversationId, loadedRevision);
            }
            if (attempt >= MAX_APPEND_ATTEMPTS) {
                LOGGER.warnf("Gave up appending the turn of conversation %s after %d attempts (loaded revision %d, now %d)",
                        LogSanitizer.sanitize(conversationId), attempt, loadedRevision, storedRevision);
                throw new ConcurrentConversationModificationException(conversationId, loadedRevision);
            }
            // Every write since our load was itself an append, so our history is still a
            // prefix of the stored one: re-apply the SAME push after the winner's steps.
            // The $set fields stay last-writer-wins, which is the pre-existing semantics.
            LOGGER.debugf("Retrying the append for conversation %s: revision moved from %d to %d",
                    LogSanitizer.sanitize(conversationId), attemptRevision, storedRevision);
            attemptRevision = storedRevision;
        }
    }

    /**
     * Conditions every append attempt must meet besides the revision, evaluated in
     * the same atomic filter so there is no check-then-act window.
     * <ul>
     * <li><b>No history rewrite since the load.</b> Every full-document write
     * stamps {@code _histRev} with the revision it creates; appends leave it alone.
     * So {@code _histRev <= loadedRevision} holds exactly when every write since
     * this turn loaded was an append — the only case in which pushing our tail
     * after the winner's is correct.</li>
     * <li><b>A state a say turn may complete over.</b> Never AWAITING_HUMAN (a
     * pause another instance committed — overwriting it erases the approval while
     * its timeout stays armed), IN_PROGRESS (a resume is running) or ENDED. The
     * narrow {@code setConversationState(ENDED)} does not bump the revision, so
     * without this an in-flight turn on another instance would resurrect an ended
     * conversation.</li>
     * </ul>
     */
    private static Bson appendPreconditions(long loadedRevision) {
        return Filters.and(
                Filters.or(Filters.lte(KEY_HISTORY_REVISION, loadedRevision), Filters.exists(KEY_HISTORY_REVISION, false)),
                Filters.nin(KEY_CONVERSATION_STATE, NON_APPENDABLE_STATES));
    }

    private static boolean isNonAppendableState(Object storedState) {
        return storedState != null && NON_APPENDABLE_STATES.contains(storedState.toString());
    }

    /**
     * After a retried (merged) append the document holds the winner's steps
     * followed by ours, but the live memory holds only ours. It must no longer
     * claim to mirror the document: leave the snapshot on the revision it was
     * LOADED at and forget the step baseline, so any further write from this memory
     * is refused as a conflict instead of silently erasing the winner's step.
     */
    private static void markMerged(ConversationMemorySnapshot snapshot, long loadedRevision) {
        snapshot.setRevision(loadedRevision);
        snapshot.setPersistedStepCount(ConversationMemorySnapshot.UNKNOWN_PERSISTED_STEP_COUNT);
    }

    /**
     * Encodes the snapshot through the collection's own codec, so the append writes
     * byte-for-byte what {@code replaceOne} would have written for the same fields
     * — but with the two arrays swapped for their new tails first, so the stored
     * history is neither re-serialized nor re-sent.
     */
    private BsonDocument encodeWithTailOnly(ConversationMemorySnapshot snapshot, int baseline) {
        var steps = snapshot.getConversationSteps();
        var outputs = snapshot.getConversationOutputs();
        snapshot.setConversationSteps(new ArrayList<>(steps.subList(baseline, steps.size())));
        snapshot.setConversationOutputs(new ArrayList<>(outputs.subList(baseline, outputs.size())));
        try {
            var codec = conversationCollectionObject.getCodecRegistry().get(ConversationMemorySnapshot.class);
            var writer = new BsonDocumentWriter(new BsonDocument());
            codec.encode(writer, snapshot, EncoderContext.builder().build());
            return writer.getDocument();
        } finally {
            snapshot.setConversationSteps(steps);
            snapshot.setConversationOutputs(outputs);
        }
    }

    private static long longOrUnversioned(Object value) {
        // Absent on a document written before the field existed.
        return value instanceof Number number ? number.longValue() : ConversationMemorySnapshot.UNVERSIONED_REVISION;
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
