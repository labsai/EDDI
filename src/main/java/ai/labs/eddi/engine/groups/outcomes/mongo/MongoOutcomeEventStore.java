/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes.mongo;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IOutcomeEventStore}: collection
 * {@code group_outcomes}. Indexed fields are top-level; the row is the JSON in
 * {@code data} without {@code recordedBy}, which lives only in its own field so
 * an erasure is a plain field update. Times are epoch milliseconds, as in the
 * PostgreSQL table. The per-subject cap is held by a unique {@code slot} per
 * subject (see {@link #insert}).
 *
 * @since 6.8.0
 */
@ApplicationScoped
@DefaultBean
public class MongoOutcomeEventStore implements IOutcomeEventStore {

    static final String COLLECTION = "group_outcomes";
    private static final int DUPLICATE_KEY = 11000;

    static final String ID = "_id";
    static final String GROUP_ID = "groupId";
    static final String SUBJECT_ID = "subjectId";
    static final String RECORDED_AT = "recordedAt";
    static final String RECORDED_BY = "recordedBy";
    static final String DATA = "data";
    /**
     * The event's place in its subject, {@code 0 .. maxPerSubject - 1}, unique per
     * subject: what makes the cap hold under concurrent inserts without a
     * transaction.
     */
    static final String SLOT = "slot";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoOutcomeEventStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.descending(RECORDED_AT)),
                new IndexOptions().name("idx_outcome_group"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.ascending(SUBJECT_ID), Indexes.descending(RECORDED_AT)),
                new IndexOptions().name("idx_outcome_subject"));
        collection.createIndex(Indexes.ascending(RECORDED_BY), new IndexOptions().name("idx_outcome_user"));
        collection.createIndex(Indexes.ascending(GROUP_ID, SUBJECT_ID, SLOT),
                new IndexOptions().name("idx_outcome_subject_slot").unique(true).partialFilterExpression(exists(SLOT)));
    }

    /**
     * Claims the lowest free slot of the subject and inserts with it. Two writers
     * that read the same subject pick the same slot, and the unique slot index lets
     * only one of them store; the other reads again and finds the subject one
     * fuller. Every lost slot is an event someone else stored, so
     * {@code maxPerSubject + 1} attempts always end in a decision.
     */
    @Override
    public InsertOutcome insert(OutcomeEvent event, int maxPerSubject) {
        Bson subject = and(eq(GROUP_ID, event.groupId()), eq(SUBJECT_ID, event.subjectId()));
        for (int attempt = 0; attempt <= maxPerSubject; attempt++) {
            int held = 0;
            Set<Integer> taken = new HashSet<>();
            for (Document doc : collection.find(subject).projection(new Document(SLOT, 1))) {
                held++;
                if (doc.get(SLOT) instanceof Integer slot) {
                    taken.add(slot);
                }
            }
            if (held >= maxPerSubject) {
                return isStored(event.id()) ? InsertOutcome.DUPLICATE : InsertOutcome.FULL;
            }
            int slot = 0;
            while (taken.contains(slot)) {
                slot++;
            }
            try {
                collection.insertOne(toDocument(event).append(SLOT, slot));
                return InsertOutcome.INSERTED;
            } catch (MongoException e) {
                if (e.getCode() != DUPLICATE_KEY) {
                    throw e;
                }
                if (isStored(event.id())) {
                    return InsertOutcome.DUPLICATE;
                }
                // another writer stored into this slot first: read the subject again
            }
        }
        throw new IllegalStateException("Could not store an outcome: the subject kept changing");
    }

    private boolean isStored(String id) {
        return collection.find(eq(ID, id)).projection(new Document(ID, 1)).first() != null;
    }

    @Override
    public List<OutcomeEvent> listBySubject(String groupId, String subjectId, int limit) {
        return read(and(eq(GROUP_ID, groupId), eq(SUBJECT_ID, subjectId)), limit);
    }

    @Override
    public List<OutcomeEvent> listByGroup(String groupId, Instant since, int limit) {
        Bson filter = since == null ? eq(GROUP_ID, groupId) : and(eq(GROUP_ID, groupId), gte(RECORDED_AT, since.toEpochMilli()));
        return read(filter, limit);
    }

    @Override
    public List<OutcomeEvent> listBySubjects(String groupId, Collection<String> subjectIds, int limit) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return List.of();
        }
        return read(and(eq(GROUP_ID, groupId), in(SUBJECT_ID, subjectIds)), limit);
    }

    @Override
    public long countByGroup(String groupId) {
        return collection.countDocuments(eq(GROUP_ID, groupId));
    }

    @Override
    public long deleteOldestBeyond(String groupId, int keep) {
        List<Object> ids = new ArrayList<>();
        for (Document doc : collection.find(eq(GROUP_ID, groupId)).projection(new Document(ID, 1))
                .sort(Sorts.orderBy(Sorts.descending(RECORDED_AT), Sorts.descending(ID))).skip(Math.max(0, keep))) {
            ids.add(doc.get(ID));
        }
        return ids.isEmpty() ? 0 : collection.deleteMany(in(ID, ids)).getDeletedCount();
    }

    @Override
    public long deleteRecordedBefore(String groupId, Instant cutoff) {
        return collection.deleteMany(and(eq(GROUP_ID, groupId), lt(RECORDED_AT, cutoff.toEpochMilli()))).getDeletedCount();
    }

    @Override
    public long deleteByGroup(String groupId) {
        return collection.deleteMany(eq(GROUP_ID, groupId)).getDeletedCount();
    }

    @Override
    public List<OutcomeEvent> readByUser(String userId, int limit) {
        return read(eq(RECORDED_BY, userId), limit);
    }

    @Override
    public long eraseUser(String userId, String pseudonym) {
        return collection.updateMany(eq(RECORDED_BY, userId), set(RECORDED_BY, pseudonym)).getModifiedCount();
    }

    private List<OutcomeEvent> read(Bson filter, int limit) {
        List<OutcomeEvent> rows = new ArrayList<>();
        for (Document doc : collection.find(filter).sort(Sorts.orderBy(Sorts.descending(RECORDED_AT), Sorts.descending(ID)))
                .limit(Math.max(0, limit)).batchSize(1000)) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    public static Document toDocument(OutcomeEvent e) {
        Document doc = new Document(ID, e.id());
        doc.put(GROUP_ID, e.groupId());
        doc.put(SUBJECT_ID, e.subjectId());
        doc.put(RECORDED_AT, e.recordedAt() != null ? e.recordedAt().toEpochMilli() : 0L);
        doc.put(RECORDED_BY, e.recordedBy());
        doc.put(DATA, OversightJson.write(e.withRecordedBy(null)));
        return doc;
    }

    public static OutcomeEvent fromDocument(Document doc) {
        return OversightJson.read(doc.getString(DATA), OutcomeEvent.class).withRecordedBy(doc.getString(RECORDED_BY));
    }
}
