/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;

/**
 * MongoDB implementation of {@link IHealthTurnStore}: collection
 * {@code healthturns}, one document per turn execution, {@code _id}
 * {@code <conversationId>|<executionId>}.
 * <p>
 * {@link #upsert} is one {@code updateOne(upsert)} with an aggregation-pipeline
 * update, so the rules of the interface are applied by the server in the same
 * write:
 * <ul>
 * <li>every value is {@code $set} (as {@code $literal}, so an action named
 * {@code "$x"} stays a string) — a replace, never an increment;</li>
 * <li>{@code day} and {@code timestamp} are {@code $ifNull} of the stored value
 * — first recorded wins;</li>
 * <li>{@code discarded} is the stored value OR the written one — sticky;</li>
 * <li>the filter matches only a row whose {@code updatedAt} is not newer than
 * the write's. A newer stored row makes the upsert try to insert a second
 * document with the same {@code _id}, and the duplicate-key error that follows
 * is the "stale write, ignored" answer.</li>
 * </ul>
 * {@code day} is stored as an ISO {@code yyyy-MM-dd} string, which sorts and
 * range-compares in day order. A discard marker carries {@code discardedAt} and
 * no {@code timestamp}; reads skip it.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthTurnStore implements IHealthTurnStore {

    static final String COLLECTION = "healthturns";

    static final String FIELD_CONVERSATION = "conversationId";
    static final String FIELD_STEP = "stepIndex";
    static final String FIELD_EXECUTION = "executionId";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_AGENT_VERSION = "agentVersion";
    static final String FIELD_PREVIOUS_VERSION = "previousAgentVersion";
    static final String FIELD_USER = "userId";
    static final String FIELD_ORIGIN = "origin";
    static final String FIELD_DAY = "day";
    static final String FIELD_TIMESTAMP = "timestamp";
    static final String FIELD_UPDATED = "updatedAt";
    static final String FIELD_COST = "costUsd";
    static final String FIELD_DISCARDED_COST = "discardedCostUsd";
    static final String FIELD_INPUT_TOKENS = "inputTokens";
    static final String FIELD_OUTPUT_TOKENS = "outputTokens";
    static final String FIELD_TOTAL_TOKENS = "totalTokens";
    static final String FIELD_DURATION = "durationMs";
    static final String FIELD_ERROR_KIND = "errorKind";
    static final String FIELD_PAUSED = "paused";
    static final String FIELD_FAILURES = "failures";
    static final String FIELD_FALLBACK = "fallbackServed";
    static final String FIELD_TOOL_CALLS = "toolCalls";
    static final String FIELD_TOOL_FAILURES = "toolFailures";
    static final String FIELD_TOOL_REFUSALS = "toolRefusals";
    static final String FIELD_GUARDRAIL_TRIPS = "guardrailTrips";
    static final String FIELD_USER_INPUT = "userInput";
    static final String FIELD_AGENT_OUTPUT = "agentOutput";
    static final String FIELD_QUESTION = "lastOutputWasQuestion";
    static final String FIELD_ACTIONS = "actions";
    static final String FIELD_DISCARDED = "discarded";
    static final String FIELD_DISCARDED_AT = "discardedAt";

    private final MongoCollection<Document> turns;

    @Inject
    public MongoHealthTurnStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        // Rollups and drill-down read an agent's days; erasure finds rows by user and
        // by conversation; retention deletes by day and markers by discardedAt.
        turns.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_turns_agent_day").background(true));
        turns.createIndex(Indexes.ascending(FIELD_CONVERSATION, FIELD_STEP),
                new IndexOptions().name("idx_health_turns_conversation").background(true));
        turns.createIndex(Indexes.ascending(FIELD_USER), new IndexOptions().name("idx_health_turns_user").background(true));
        turns.createIndex(Indexes.ascending(FIELD_DAY), new IndexOptions().name("idx_health_turns_day").background(true));
        turns.createIndex(Indexes.ascending(FIELD_DISCARDED_AT),
                new IndexOptions().name("idx_health_turns_discarded_at").sparse(true).background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthTurnStore(MongoCollection<Document> turns) {
        this.turns = turns;
    }

    static String id(String conversationId, String executionId) {
        return conversationId + "|" + executionId;
    }

    @Override
    public void upsert(HealthTurn turn) {
        try {
            turns.updateOne(guardedFilter(turn), replacePipeline(turn), new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                // The stored row is newer than this write: the write is stale and ignored.
                return;
            }
            throw new HealthStoreException("Could not store a health turn", e);
        }
    }

    /** The row, unless the stored one is newer. */
    static Bson guardedFilter(HealthTurn turn) {
        return and(eq("_id", id(turn.conversationId(), turn.executionId())),
                or(lte(FIELD_UPDATED, date(turn.updatedAt())), exists(FIELD_UPDATED, false)));
    }

    static List<Bson> replacePipeline(HealthTurn turn) {
        Document values = toDocument(turn);
        Document set = new Document();
        values.forEach((field, value) -> {
            switch (field) {
                case FIELD_DAY, FIELD_TIMESTAMP -> set.put(field, new Document("$ifNull", List.of("$" + field, literal(value))));
                case FIELD_DISCARDED -> set.put(field, new Document("$or", List.of(new Document("$ifNull", List.of("$" + field, false)), value)));
                default -> set.put(field, literal(value));
            }
        });
        return List.of(new Document("$set", set));
    }

    private static Document literal(Object value) {
        return new Document("$literal", value);
    }

    @Override
    public void insertIfAbsent(HealthTurn turn) {
        try {
            Document values = toDocument(turn);
            List<Bson> inserts = new ArrayList<>(values.size());
            values.forEach((field, value) -> inserts.add(Updates.setOnInsert(field, value)));
            turns.updateOne(eq("_id", id(turn.conversationId(), turn.executionId())), Updates.combine(inserts), new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                return; // inserted concurrently: absent no more
            }
            throw new HealthStoreException("Could not store a health turn", e);
        }
    }

    @Override
    public long markDiscarded(HealthTurn replacing, Collection<String> executionIds) {
        if (replacing == null || replacing.conversationId() == null || executionIds == null || executionIds.isEmpty()) {
            return 0L;
        }
        try {
            Date now = date(replacing.updatedAt() != null ? replacing.updatedAt() : Instant.now());
            List<WriteModel<Document>> writes = new ArrayList<>();
            for (String executionId : new LinkedHashSet<>(executionIds)) {
                writes.add(new UpdateOneModel<>(eq("_id", id(replacing.conversationId(), executionId)),
                        Updates.combine(Updates.set(FIELD_DISCARDED, true), Updates.setOnInsert(FIELD_CONVERSATION, replacing.conversationId()),
                                Updates.setOnInsert(FIELD_EXECUTION, executionId), Updates.setOnInsert(FIELD_AGENT, replacing.agentId()),
                                Updates.setOnInsert(FIELD_USER, replacing.userId()), Updates.setOnInsert(FIELD_DISCARDED_AT, now)),
                        new UpdateOptions().upsert(true)));
            }
            turns.bulkWrite(writes);
            return writes.size();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not mark health turns discarded", e);
        }
    }

    @Override
    public List<HealthTurn> findByConversation(String conversationId, int limit) {
        return find(and(eq(FIELD_CONVERSATION, conversationId), ne(FIELD_TIMESTAMP, null)), Sorts.ascending(FIELD_STEP, FIELD_TIMESTAMP), limit,
                "Could not read the health turns of a conversation");
    }

    @Override
    public List<HealthTurn> findByAgentAndDayRange(String agentId, LocalDate from, LocalDate to, int limit) {
        return find(agentDayRange(agentId, from, to), Sorts.ascending(FIELD_DAY, FIELD_TIMESTAMP), limit,
                "Could not read the health turns of an agent");
    }

    static Bson agentDayRange(String agentId, LocalDate from, LocalDate to) {
        return and(eq(FIELD_AGENT, agentId), gte(FIELD_DAY, from.toString()), lte(FIELD_DAY, to.toString()));
    }

    @Override
    public void forEachByAgentAndDay(String agentId, LocalDate day, Consumer<HealthTurn> consumer) {
        try (MongoCursor<Document> cursor = turns.find(and(eq(FIELD_AGENT, agentId), eq(FIELD_DAY, day.toString()))).iterator()) {
            while (cursor.hasNext()) {
                consumer.accept(fromDocument(cursor.next()));
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the health turns of an agent's day", e);
        }
    }

    @Override
    public List<HealthTurn> findByUserId(String userId, int limit) {
        return find(and(eq(FIELD_USER, userId), ne(FIELD_TIMESTAMP, null)), Sorts.descending(FIELD_TIMESTAMP), limit,
                "Could not read a user's health turns");
    }

    @Override
    public long deleteByUserId(String userId) {
        try {
            return turns.deleteMany(eq(FIELD_USER, userId)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete a user's health turns", e);
        }
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        try {
            return turns.deleteMany(eq(FIELD_CONVERSATION, conversationId)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete a conversation's health turns", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        try {
            return turns.deleteMany(olderThan(day)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete expired health turns", e);
        }
    }

    /** Rows of earlier days, and markers written before the day began. */
    static Bson olderThan(LocalDate day) {
        return or(lt(FIELD_DAY, day.toString()),
                and(eq(FIELD_DAY, null), lt(FIELD_DISCARDED_AT, Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()))));
    }

    private List<HealthTurn> find(Bson filter, Bson sort, int limit, String failure) {
        try {
            List<HealthTurn> found = new ArrayList<>();
            for (Document document : turns.find(filter).sort(sort).limit(Math.max(1, limit))) {
                found.add(fromDocument(document));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    static Document toDocument(HealthTurn turn) {
        Document d = new Document();
        d.put(FIELD_CONVERSATION, turn.conversationId());
        d.put(FIELD_STEP, turn.stepIndex());
        d.put(FIELD_EXECUTION, turn.executionId());
        d.put(FIELD_AGENT, turn.agentId());
        d.put(FIELD_AGENT_VERSION, turn.agentVersion());
        d.put(FIELD_PREVIOUS_VERSION, turn.previousAgentVersion());
        d.put(FIELD_USER, turn.userId());
        d.put(FIELD_ORIGIN, turn.origin());
        d.put(FIELD_DAY, turn.day() == null ? null : turn.day().toString());
        d.put(FIELD_TIMESTAMP, date(turn.timestamp()));
        d.put(FIELD_UPDATED, date(turn.updatedAt()));
        d.put(FIELD_COST, turn.costUsd());
        d.put(FIELD_DISCARDED_COST, turn.discardedCostUsd());
        d.put(FIELD_INPUT_TOKENS, turn.inputTokens());
        d.put(FIELD_OUTPUT_TOKENS, turn.outputTokens());
        d.put(FIELD_TOTAL_TOKENS, turn.totalTokens());
        d.put(FIELD_DURATION, turn.durationMs());
        d.put(FIELD_ERROR_KIND, turn.errorKind());
        d.put(FIELD_PAUSED, turn.paused());
        List<Document> failures = new ArrayList<>(turn.failures().size());
        for (HealthFailure failure : turn.failures()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(HealthFailure.KEY_CLASS, failure.failureClass());
            entry.put(HealthFailure.KEY_MODEL, failure.model());
            entry.put(HealthFailure.KEY_RECOVERY, failure.recovery());
            failures.add(new Document(entry));
        }
        d.put(FIELD_FAILURES, failures);
        d.put(FIELD_FALLBACK, turn.fallbackServed());
        d.put(FIELD_TOOL_CALLS, turn.toolCalls());
        d.put(FIELD_TOOL_FAILURES, turn.toolFailures());
        d.put(FIELD_TOOL_REFUSALS, turn.toolRefusals());
        d.put(FIELD_GUARDRAIL_TRIPS, turn.guardrailTrips());
        d.put(FIELD_USER_INPUT, turn.userInput());
        d.put(FIELD_AGENT_OUTPUT, turn.agentOutput());
        d.put(FIELD_QUESTION, turn.lastOutputWasQuestion());
        d.put(FIELD_ACTIONS, turn.actions());
        d.put(FIELD_DISCARDED, turn.discarded());
        return d;
    }

    static HealthTurn fromDocument(Document d) {
        List<HealthFailure> failures = new ArrayList<>();
        Object storedFailures = d.get(FIELD_FAILURES);
        if (storedFailures instanceof List<?> list) {
            for (Object entry : list) {
                HealthFailure failure = HealthFailure.fromRecord(entry);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }
        List<String> actions = new ArrayList<>();
        if (d.get(FIELD_ACTIONS) instanceof List<?> list) {
            for (Object action : list) {
                if (action != null) {
                    actions.add(String.valueOf(action));
                }
            }
        }
        String day = d.getString(FIELD_DAY);
        return new HealthTurn(d.getString(FIELD_CONVERSATION), intValue(d.get(FIELD_STEP)), d.getString(FIELD_EXECUTION), d.getString(FIELD_AGENT),
                integer(d.get(FIELD_AGENT_VERSION)), integer(d.get(FIELD_PREVIOUS_VERSION)), d.getString(FIELD_USER), d.getString(FIELD_ORIGIN),
                day == null ? null : LocalDate.parse(day), instant(d.getDate(FIELD_TIMESTAMP)), instant(d.getDate(FIELD_UPDATED)),
                decimal(d.get(FIELD_COST)), decimal(d.get(FIELD_DISCARDED_COST)), longValue(d.get(FIELD_INPUT_TOKENS)),
                longValue(d.get(FIELD_OUTPUT_TOKENS)), longValue(d.get(FIELD_TOTAL_TOKENS)), longValue(d.get(FIELD_DURATION)),
                d.getString(FIELD_ERROR_KIND), flag(d.get(FIELD_PAUSED)), failures, flag(d.get(FIELD_FALLBACK)), intValue(d.get(FIELD_TOOL_CALLS)),
                intValue(d.get(FIELD_TOOL_FAILURES)), intValue(d.get(FIELD_TOOL_REFUSALS)), intValue(d.get(FIELD_GUARDRAIL_TRIPS)),
                flag(d.get(FIELD_USER_INPUT)), flag(d.get(FIELD_AGENT_OUTPUT)), flag(d.get(FIELD_QUESTION)), actions, flag(d.get(FIELD_DISCARDED)));
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Double decimal(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
