/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy.mongo;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IAutonomyStateStore}: collection
 * {@code autonomy_state}, unique on {@code (agentId, category)}. The state
 * itself is JSON in {@code data}; {@code version} and {@code suspensionActive}
 * are fields for compare-and-set and the overlay lookup.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class MongoAutonomyStateStore implements IAutonomyStateStore {

    static final String COLLECTION = "autonomy_state";
    private static final int DUPLICATE_KEY = 11000;

    static final String AGENT_ID = "agentId";
    static final String CATEGORY = "category";
    static final String VERSION = "version";
    static final String SUSPENSION_ACTIVE = "suspensionActive";
    static final String DATA = "data";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoAutonomyStateStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.ascending(CATEGORY)),
                new IndexOptions().name("idx_autonomy_key").unique(true));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(SUSPENSION_ACTIVE), Indexes.ascending(AGENT_ID)),
                new IndexOptions().name("idx_autonomy_suspended"));
    }

    @Override
    public Optional<AutonomyState> read(String agentId, String category) {
        Document doc = collection.find(and(eq(AGENT_ID, agentId), eq(CATEGORY, category))).first();
        return Optional.ofNullable(doc).map(MongoAutonomyStateStore::fromDocument);
    }

    @Override
    public List<AutonomyState> list(String agentId, int limit) {
        Bson filter = agentId != null ? eq(AGENT_ID, agentId) : new Document();
        List<AutonomyState> rows = new ArrayList<>();
        for (Document doc : collection.find(filter).sort(Sorts.ascending(AGENT_ID, CATEGORY)).limit(Math.max(0, limit))) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public List<AutonomyState> listSuspended(String agentId) {
        List<AutonomyState> rows = new ArrayList<>();
        for (Document doc : collection.find(and(eq(SUSPENSION_ACTIVE, true), eq(AGENT_ID, agentId)))) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public long countSuspended() {
        return collection.countDocuments(eq(SUSPENSION_ACTIVE, true));
    }

    @Override
    public Optional<AutonomyState> save(AutonomyState state, long expectedVersion) {
        AutonomyState stored = state.withVersion(expectedVersion + 1);
        if (expectedVersion == 0) {
            try {
                collection.insertOne(toDocument(stored));
                return Optional.of(stored);
            } catch (MongoException e) {
                if (e.getCode() == DUPLICATE_KEY) {
                    return Optional.empty();
                }
                throw e;
            }
        }
        Bson filter = and(eq(AGENT_ID, state.agentId()), eq(CATEGORY, state.category()), eq(VERSION, expectedVersion));
        Bson update = combine(set(VERSION, stored.version()), set(SUSPENSION_ACTIVE, stored.suspended()),
                set(DATA, OversightJson.write(stored)));
        Document after = collection.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(after).map(MongoAutonomyStateStore::fromDocument);
    }

    public static Document toDocument(AutonomyState s) {
        Document doc = new Document(AGENT_ID, s.agentId());
        doc.put(CATEGORY, s.category());
        doc.put(VERSION, s.version());
        doc.put(SUSPENSION_ACTIVE, s.suspended());
        doc.put(DATA, OversightJson.write(s));
        return doc;
    }

    public static AutonomyState fromDocument(Document doc) {
        Number version = doc.get(VERSION, Number.class);
        return OversightJson.read(doc.getString(DATA), AutonomyState.class).withVersion(version != null ? version.longValue() : 0L);
    }
}
