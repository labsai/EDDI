/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthDirtyDayStore;
import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB implementation of {@link IHealthDirtyDayStore}: collection
 * {@code healthdirty}, {@code _id} {@code <agentId>|<day>}.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthDirtyDayStore implements IHealthDirtyDayStore {

    static final String COLLECTION = "healthdirty";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_DAY = "day";
    static final String FIELD_TOKEN = "token";
    static final String FIELD_MARKED = "markedAt";

    private final MongoCollection<Document> dirty;

    @Inject
    public MongoHealthDirtyDayStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        dirty.createIndex(Indexes.ascending(FIELD_MARKED), new IndexOptions().name("idx_health_dirty_marked").background(true));
        dirty.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_dirty_agent_day").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthDirtyDayStore(MongoCollection<Document> dirty) {
        this.dirty = dirty;
    }

    static String id(String agentId, LocalDate day) {
        return agentId + "|" + day;
    }

    @Override
    public void mark(String agentId, Collection<LocalDate> days) {
        if (agentId == null || days == null) {
            return;
        }
        try {
            for (LocalDate day : new TreeSet<>(days)) {
                markOne(agentId, day);
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not mark health days dirty", e);
        }
    }

    private void markOne(String agentId, LocalDate day) {
        var update = Updates.combine(Updates.set(FIELD_TOKEN, UUID.randomUUID().toString()), Updates.set(FIELD_MARKED, new Date()),
                Updates.setOnInsert(FIELD_AGENT, agentId), Updates.setOnInsert(FIELD_DAY, day.toString()));
        try {
            dirty.updateOne(eq("_id", id(agentId, day)), update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            // Two writers inserted the same entry at once: the retry finds it and updates
            // it.
            dirty.updateOne(eq("_id", id(agentId, day)), update, new UpdateOptions().upsert(true));
        }
    }

    @Override
    public List<HealthDirtyDay> oldest(int limit) {
        try {
            List<HealthDirtyDay> entries = new ArrayList<>();
            for (Document d : dirty.find().sort(Sorts.ascending(FIELD_MARKED)).limit(Math.max(1, limit))) {
                Date marked = d.getDate(FIELD_MARKED);
                entries.add(new HealthDirtyDay(d.getString(FIELD_AGENT), LocalDate.parse(d.getString(FIELD_DAY)), d.getString(FIELD_TOKEN),
                        marked == null ? null : marked.toInstant()));
            }
            return entries;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read dirty health days", e);
        }
    }

    @Override
    public boolean clear(HealthDirtyDay entry) {
        try {
            return dirty.deleteOne(and(eq("_id", id(entry.agentId(), entry.day())), eq(FIELD_TOKEN, entry.token()))).getDeletedCount() > 0;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not clear a dirty health day", e);
        }
    }

    @Override
    public Optional<LocalDate> earliestPending(String agentId) {
        try {
            Document first = dirty.find(eq(FIELD_AGENT, agentId)).sort(Sorts.ascending(FIELD_DAY)).first();
            return first == null ? Optional.empty() : Optional.of(LocalDate.parse(first.getString(FIELD_DAY)));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read dirty health days", e);
        }
    }

    @Override
    public long count() {
        try {
            return dirty.countDocuments();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count dirty health days", e);
        }
    }
}
