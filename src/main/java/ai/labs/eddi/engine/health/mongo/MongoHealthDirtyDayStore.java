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
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
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
import static com.mongodb.client.model.Filters.lt;

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
    static final String FIELD_ATTEMPTS = "attempts";
    /** The rollup's own state (the self-heal watermark), apart from the queue. */
    static final String STATE_COLLECTION = "healthrollupstate";
    static final String WATERMARK_ID = "self-heal";
    static final String FIELD_AT = "at";

    private final MongoCollection<Document> dirty;
    private final MongoCollection<Document> state;

    @Inject
    public MongoHealthDirtyDayStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION), database.getCollection(STATE_COLLECTION));
        dirty.createIndex(Indexes.ascending(FIELD_MARKED), new IndexOptions().name("idx_health_dirty_marked").background(true));
        dirty.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_dirty_agent_day").background(true));
    }

    /** For tests: collections without index creation. */
    MongoHealthDirtyDayStore(MongoCollection<Document> dirty, MongoCollection<Document> state) {
        this.dirty = dirty;
        this.state = state;
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
                Updates.set(FIELD_ATTEMPTS, 0),
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
                Object attempts = d.get(FIELD_ATTEMPTS);
                entries.add(new HealthDirtyDay(d.getString(FIELD_AGENT), LocalDate.parse(d.getString(FIELD_DAY)), d.getString(FIELD_TOKEN),
                        marked == null ? null : marked.toInstant(), attempts instanceof Number n ? n.intValue() : 0));
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
    public int defer(HealthDirtyDay entry, Instant now) {
        try {
            Document updated = dirty.findOneAndUpdate(and(eq("_id", id(entry.agentId(), entry.day())), eq(FIELD_TOKEN, entry.token())),
                    Updates.combine(Updates.set(FIELD_MARKED, Date.from(now)), Updates.inc(FIELD_ATTEMPTS, 1)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return updated == null ? 0 : updated.get(FIELD_ATTEMPTS) instanceof Number n ? n.intValue() : 0;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not defer a dirty health day", e);
        }
    }

    @Override
    public Optional<LocalDate> earliestPending(String agentId, Instant markedBefore) {
        try {
            Document first = dirty.find(and(eq(FIELD_AGENT, agentId), lt(FIELD_MARKED, Date.from(markedBefore)))).sort(Sorts.ascending(FIELD_DAY))
                    .first();
            return first == null ? Optional.empty() : Optional.of(LocalDate.parse(first.getString(FIELD_DAY)));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read dirty health days", e);
        }
    }

    @Override
    public Optional<Instant> selfHealWatermark() {
        try {
            Document d = state.find(eq("_id", WATERMARK_ID)).first();
            return d == null || d.getDate(FIELD_AT) == null ? Optional.empty() : Optional.of(d.getDate(FIELD_AT).toInstant());
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the health rollup watermark", e);
        }
    }

    @Override
    public void setSelfHealWatermark(Instant at) {
        try {
            state.replaceOne(eq("_id", WATERMARK_ID), new Document("_id", WATERMARK_ID).append(FIELD_AT, Date.from(at)),
                    new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store the health rollup watermark", e);
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
