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
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IOutcomeEventStore}: collection
 * {@code group_outcomes}. Indexed fields are top-level; the row is the JSON in
 * {@code data} without {@code recordedBy}, which lives only in its own field so
 * an erasure is a plain field update. Times are epoch milliseconds, as in the
 * PostgreSQL table.
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

    private final MongoCollection<Document> collection;

    @Inject
    public MongoOutcomeEventStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.descending(RECORDED_AT)),
                new IndexOptions().name("idx_outcome_group"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.ascending(SUBJECT_ID), Indexes.descending(RECORDED_AT)),
                new IndexOptions().name("idx_outcome_subject"));
        collection.createIndex(Indexes.ascending(RECORDED_BY), new IndexOptions().name("idx_outcome_user"));
    }

    @Override
    public boolean insertIfAbsent(OutcomeEvent event) {
        try {
            collection.insertOne(toDocument(event));
            return true;
        } catch (MongoException e) {
            if (e.getCode() == DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
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
    public long countBySubject(String groupId, String subjectId) {
        return collection.countDocuments(and(eq(GROUP_ID, groupId), eq(SUBJECT_ID, subjectId)));
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
