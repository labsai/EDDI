/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.lessons.mongo;

import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryEntry;
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

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * MongoDB implementation of {@link ILessonHistoryStore}: collection
 * {@code group_lesson_history}. Indexed fields are top-level, the row is the
 * JSON in {@code data}; times are epoch milliseconds, as in the PostgreSQL
 * table.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@DefaultBean
public class MongoLessonHistoryStore implements ILessonHistoryStore {

    static final String COLLECTION = "group_lesson_history";
    private static final int DUPLICATE_KEY = 11000;

    static final String ID = "_id";
    static final String GROUP_ID = "groupId";
    static final String AT = "at";
    static final String DATA = "data";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoLessonHistoryStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.descending(AT)),
                new IndexOptions().name("idx_lesson_history_group"));
    }

    @Override
    public boolean insertIfAbsent(LessonHistoryEntry entry) {
        Document doc = new Document(ID, entry.id());
        doc.put(GROUP_ID, entry.groupId());
        doc.put(AT, entry.at() != null ? entry.at().toEpochMilli() : 0L);
        doc.put(DATA, OversightJson.write(entry));
        try {
            collection.insertOne(doc);
            return true;
        } catch (MongoException e) {
            if (e.getCode() == DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<LessonHistoryEntry> listByGroup(String groupId, int limit) {
        List<LessonHistoryEntry> rows = new ArrayList<>();
        for (Document doc : collection.find(eq(GROUP_ID, groupId)).sort(Sorts.orderBy(Sorts.descending(AT), Sorts.descending(ID)))
                .limit(Math.max(0, limit))) {
            rows.add(OversightJson.read(doc.getString(DATA), LessonHistoryEntry.class));
        }
        return rows;
    }

    @Override
    public long countByGroup(String groupId) {
        return collection.countDocuments(eq(GROUP_ID, groupId));
    }

    @Override
    public long deleteOldestBeyond(String groupId, int keep) {
        List<Object> ids = new ArrayList<>();
        for (Document doc : collection.find(eq(GROUP_ID, groupId)).projection(new Document(ID, 1))
                .sort(Sorts.orderBy(Sorts.descending(AT), Sorts.descending(ID))).skip(Math.max(0, keep))) {
            ids.add(doc.get(ID));
        }
        return ids.isEmpty() ? 0 : collection.deleteMany(in(ID, ids)).getDeletedCount();
    }

    @Override
    public long deleteByGroup(String groupId) {
        return collection.deleteMany(eq(GROUP_ID, groupId)).getDeletedCount();
    }
}
