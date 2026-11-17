/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthEventStore;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;

/**
 * MongoDB implementation of {@link IHealthEventStore}: collection
 * {@code healthevents}, {@code _id} the event id, {@code day} an ISO
 * {@code yyyy-MM-dd} string as in the other health collections.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthEventStore implements IHealthEventStore {

    static final String COLLECTION = "healthevents";

    static final String FIELD_INTEGRATION = "integrationId";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_TYPE = "eventType";
    static final String FIELD_REF = "ref";
    static final String FIELD_REF_VALUE = "refValue";
    static final String FIELD_CONVERSATION = "conversationId";
    static final String FIELD_VALUE = "value";
    static final String FIELD_AT = "at";
    static final String FIELD_RECEIVED = "receivedAt";
    static final String FIELD_DAY = "day";
    static final String FIELD_STATE = "state";
    static final String FIELD_STEP = "matchedStep";
    static final String FIELD_VERSION = "agentVersion";
    static final String FIELD_ORIGIN = "origin";
    static final String FIELD_USER = "userId";
    static final String FIELD_EVIDENCE = "evidence";
    static final String FIELD_MATCHED_AT = "matchedAt";

    private final MongoCollection<Document> events;

    @Inject
    public MongoHealthEventStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        // The matcher reads pending events by receipt; reports count an agent's days;
        // evaluation reads a conversation's matches; erasure finds rows by user;
        // retention deletes by day.
        events.createIndex(Indexes.ascending(FIELD_STATE, FIELD_RECEIVED), new IndexOptions().name("idx_health_events_state").background(true));
        events.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_events_agent_day").background(true));
        events.createIndex(Indexes.ascending(FIELD_CONVERSATION), new IndexOptions().name("idx_health_events_conversation").background(true));
        events.createIndex(Indexes.ascending(FIELD_USER), new IndexOptions().name("idx_health_events_user").sparse(true).background(true));
        events.createIndex(Indexes.ascending(FIELD_DAY), new IndexOptions().name("idx_health_events_day").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthEventStore(MongoCollection<Document> events) {
        this.events = events;
    }

    @Override
    public int insert(Collection<HealthEvent> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Document> documents = rows.stream().map(MongoHealthEventStore::toDocument).toList();
        try {
            events.insertMany(documents, new InsertManyOptions().ordered(false));
            return documents.size();
        } catch (MongoBulkWriteException e) {
            int duplicates = 0;
            for (BulkWriteError error : e.getWriteErrors()) {
                if (ErrorCategory.fromErrorCode(error.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw new HealthStoreException("Could not store health events", e);
                }
                duplicates++;
            }
            return documents.size() - duplicates;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store health events", e);
        }
    }

    @Override
    public List<HealthEvent> findPending(int limit) {
        return find(eq(FIELD_STATE, HealthEvent.PENDING), Sorts.ascending(FIELD_RECEIVED), limit, "Could not read the pending health events");
    }

    @Override
    public boolean markMatched(HealthEvent m) {
        try {
            Document set = new Document(FIELD_STATE, HealthEvent.MATCHED).append(FIELD_CONVERSATION, m.conversationId())
                    .append(FIELD_STEP, m.matchedStep()).append(FIELD_VERSION, m.agentVersion()).append(FIELD_ORIGIN, m.origin())
                    .append(FIELD_USER, m.userId()).append(FIELD_EVIDENCE, m.evidence()).append(FIELD_MATCHED_AT, date(m.matchedAt()));
            Document update = new Document("$set", set).append("$unset", new Document(FIELD_REF_VALUE, ""));
            return events.updateOne(and(eq("_id", m.id()), eq(FIELD_STATE, HealthEvent.PENDING)), update).getModifiedCount() > 0;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not record a health event match", e);
        }
    }

    @Override
    public long delete(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0L;
        }
        return deleteMany(in("_id", List.copyOf(ids)), "Could not delete health events");
    }

    @Override
    public long deletePendingReceivedBefore(Instant before) {
        return deleteMany(and(eq(FIELD_STATE, HealthEvent.PENDING), lt(FIELD_RECEIVED, Date.from(before))),
                "Could not delete unmatched health events");
    }

    @Override
    public long countPending(String agentId) {
        try {
            return events.countDocuments(and(eq(FIELD_STATE, HealthEvent.PENDING), eq(FIELD_AGENT, agentId)));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the pending health events of an agent", e);
        }
    }

    @Override
    public List<HealthEvent> findMatchedByConversation(String conversationId) {
        return find(and(eq(FIELD_CONVERSATION, conversationId), eq(FIELD_STATE, HealthEvent.MATCHED)), Sorts.ascending(FIELD_AT, FIELD_RECEIVED),
                Integer.MAX_VALUE, "Could not read the health events of a conversation");
    }

    @Override
    public List<HealthEventCount> counts(HealthUsageQuery query, boolean perDay) {
        if (query.origins().isEmpty()) {
            return List.of();
        }
        List<Bson> filters = new ArrayList<>();
        filters.add(eq(FIELD_AGENT, query.agentId()));
        filters.add(eq(FIELD_STATE, HealthEvent.MATCHED));
        filters.add(gte(FIELD_DAY, query.from().toString()));
        filters.add(lte(FIELD_DAY, query.to().toString()));
        filters.add(in(FIELD_ORIGIN, query.origins()));
        if (query.version() != null) {
            filters.add(eq(FIELD_VERSION, query.version()));
        }
        Document key = new Document("t", "$" + FIELD_TYPE).append("e", "$" + FIELD_EVIDENCE).append("d", perDay ? "$" + FIELD_DAY : null);
        Document valued = new Document("$cond", List.of(new Document("$isNumber", "$" + FIELD_VALUE), 1, 0));
        List<Document> pipeline = List.of(new Document("$match", and(filters)), new Document("$group", new Document("_id", key)
                .append("n", new Document("$sum", 1)).append("sum", new Document("$sum", "$" + FIELD_VALUE)).append("valued", new Document("$sum",
                        valued))));
        try {
            List<HealthEventCount> counts = new ArrayList<>();
            for (Document d : events.aggregate(pipeline).allowDiskUse(true)) {
                Document k = d.get("_id", Document.class);
                String day = k.getString("d");
                counts.add(new HealthEventCount(day == null ? null : LocalDate.parse(day), k.getString("t"), k.getString("e"), number(d.get("n")),
                        d.get("sum") instanceof Number n ? n.doubleValue() : 0.0, number(d.get("valued"))));
            }
            return counts;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the health events of an agent", e);
        }
    }

    @Override
    public List<HealthEvent> findByUserId(String userId, int limit) {
        return find(eq(FIELD_USER, userId), Sorts.descending(FIELD_AT), limit, "Could not read a user's health events");
    }

    @Override
    public long deleteByUserId(String userId) {
        return deleteMany(eq(FIELD_USER, userId), "Could not delete a user's health events");
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return deleteMany(eq(FIELD_CONVERSATION, conversationId), "Could not delete a conversation's health events");
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        return deleteMany(lt(FIELD_DAY, day.toString()), "Could not delete expired health events");
    }

    private long deleteMany(Bson filter, String failure) {
        try {
            return events.deleteMany(filter).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    private List<HealthEvent> find(Bson filter, Bson sort, int limit, String failure) {
        try {
            List<HealthEvent> found = new ArrayList<>();
            var cursor = events.find(filter).sort(sort);
            if (limit < Integer.MAX_VALUE) {
                cursor = cursor.limit(Math.max(1, limit));
            }
            for (Document d : cursor) {
                found.add(fromDocument(d));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    static Document toDocument(HealthEvent e) {
        Document d = new Document("_id", e.id());
        d.put(FIELD_INTEGRATION, e.integrationId());
        d.put(FIELD_AGENT, e.agentId());
        d.put(FIELD_TYPE, e.eventType());
        d.put(FIELD_REF, e.ref());
        if (e.refValue() != null) {
            d.put(FIELD_REF_VALUE, e.refValue());
        }
        d.put(FIELD_CONVERSATION, e.conversationId());
        d.put(FIELD_VALUE, e.value());
        d.put(FIELD_AT, date(e.at()));
        d.put(FIELD_RECEIVED, date(e.receivedAt()));
        d.put(FIELD_DAY, e.day() == null ? null : e.day().toString());
        d.put(FIELD_STATE, e.state());
        d.put(FIELD_STEP, e.matchedStep());
        d.put(FIELD_VERSION, e.agentVersion());
        d.put(FIELD_ORIGIN, e.origin());
        if (e.userId() != null) {
            d.put(FIELD_USER, e.userId());
        }
        d.put(FIELD_EVIDENCE, e.evidence());
        d.put(FIELD_MATCHED_AT, date(e.matchedAt()));
        return d;
    }

    static HealthEvent fromDocument(Document d) {
        String day = d.getString(FIELD_DAY);
        return new HealthEvent(d.getString("_id"), d.getString(FIELD_INTEGRATION), d.getString(FIELD_AGENT), d.getString(FIELD_TYPE),
                d.getString(FIELD_REF), d.getString(FIELD_REF_VALUE), d.getString(FIELD_CONVERSATION),
                d.get(FIELD_VALUE) instanceof Number n ? n.doubleValue() : null, instant(d.getDate(FIELD_AT)), instant(d.getDate(FIELD_RECEIVED)),
                day == null ? null : LocalDate.parse(day), d.getString(FIELD_STATE), integer(d.get(FIELD_STEP)), integer(d.get(FIELD_VERSION)),
                d.getString(FIELD_ORIGIN), d.getString(FIELD_USER), d.getString(FIELD_EVIDENCE), instant(d.getDate(FIELD_MATCHED_AT)));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
