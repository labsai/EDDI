/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
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
        // by conversation; retention deletes by day and markers by discardedAt; the
        // orphan sweep finds recently written rows by updatedAt (and markers by
        // discardedAt).
        turns.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_turns_agent_day").background(true));
        turns.createIndex(Indexes.ascending(FIELD_CONVERSATION, FIELD_STEP),
                new IndexOptions().name("idx_health_turns_conversation").background(true));
        turns.createIndex(Indexes.ascending(FIELD_USER), new IndexOptions().name("idx_health_turns_user").background(true));
        turns.createIndex(Indexes.ascending(FIELD_DAY), new IndexOptions().name("idx_health_turns_day").background(true));
        turns.createIndex(Indexes.ascending(FIELD_DISCARDED_AT),
                new IndexOptions().name("idx_health_turns_discarded_at").sparse(true).background(true));
        turns.createIndex(Indexes.ascending(FIELD_UPDATED), new IndexOptions().name("idx_health_turns_updated").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthTurnStore(MongoCollection<Document> turns) {
        this.turns = turns;
    }

    static String id(String conversationId, String executionId) {
        return conversationId + "|" + executionId;
    }

    /**
     * A duplicate key means the guarded filter matched nothing although the row
     * exists: either the stored row is newer, or another node inserted the first
     * version of the same row between this write's match and its insert — in which
     * case this write may well be the newer one. One retry tells the two apart: it
     * now finds the row and replaces it when this write is not older, and a second
     * duplicate key is the stored row being newer, so the write is stale and
     * ignored.
     */
    @Override
    public void upsert(HealthTurn turn) {
        try {
            guardedUpsert(turn);
        } catch (MongoException e) {
            if (!isDuplicateKey(e)) {
                throw new HealthStoreException("Could not store a health turn", e);
            }
            try {
                guardedUpsert(turn);
            } catch (MongoException retry) {
                if (!isDuplicateKey(retry)) {
                    throw new HealthStoreException("Could not store a health turn", retry);
                }
                // The stored row is newer than this write: the write is stale and ignored.
            }
        }
    }

    private void guardedUpsert(HealthTurn turn) {
        turns.updateOne(guardedFilter(turn), replacePipeline(turn), new UpdateOptions().upsert(true));
    }

    private static boolean isDuplicateKey(MongoException e) {
        return ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY;
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
                writes.add(new UpdateOneModel<>(eq("_id", id(replacing.conversationId(), executionId)), discardPipeline(replacing, executionId, now),
                        new UpdateOptions().upsert(true)));
            }
            turns.bulkWrite(writes);
            return writes.size();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not mark health turns discarded", e);
        }
    }

    /**
     * Marks a row discarded, or inserts a marker. {@code discardedAt} is when the
     * row first became discarded — set once, so the rollup's self-heal finds the
     * day of an existing row discarded since its last pass; {@code updatedAt}, the
     * write-ordering guard, is left alone. The other fields are set only on insert.
     */
    static List<Bson> discardPipeline(HealthTurn replacing, String executionId, Date now) {
        Document set = new Document();
        set.put(FIELD_DISCARDED_AT, new Document("$cond", List.of(new Document("$eq", List.of("$" + FIELD_DISCARDED, true)),
                new Document("$ifNull", List.of("$" + FIELD_DISCARDED_AT, literal(now))), literal(now))));
        set.put(FIELD_DISCARDED, literal(true));
        set.put(FIELD_CONVERSATION, ifNull(FIELD_CONVERSATION, replacing.conversationId()));
        set.put(FIELD_EXECUTION, ifNull(FIELD_EXECUTION, executionId));
        set.put(FIELD_AGENT, ifNull(FIELD_AGENT, replacing.agentId()));
        set.put(FIELD_USER, ifNull(FIELD_USER, replacing.userId()));
        return List.of(new Document("$set", set));
    }

    private static Document ifNull(String field, Object value) {
        return new Document("$ifNull", Arrays.asList("$" + field, literal(value)));
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
    public Set<LocalDate> daysOf(String conversationId, Collection<String> executionIds) {
        if (conversationId == null || executionIds == null || executionIds.isEmpty()) {
            return Set.of();
        }
        try {
            List<String> ids = new LinkedHashSet<>(executionIds).stream().map(e -> id(conversationId, e)).toList();
            Set<LocalDate> days = new TreeSet<>();
            for (Document d : turns.find(and(in("_id", ids), ne(FIELD_DAY, null))).projection(new Document(FIELD_DAY, 1))) {
                String day = d.getString(FIELD_DAY);
                if (day != null) {
                    days.add(LocalDate.parse(day));
                }
            }
            return days;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the days of health turns", e);
        }
    }

    /** The rows an adoption count reads: recorded, not discarded, in range. */
    static Bson usageFilter(HealthUsageQuery query, LocalDate from) {
        List<Bson> filters = new ArrayList<>();
        filters.add(eq(FIELD_AGENT, query.agentId()));
        filters.add(gte(FIELD_DAY, from.toString()));
        filters.add(lte(FIELD_DAY, query.to().toString()));
        filters.add(ne(FIELD_TIMESTAMP, null));
        filters.add(ne(FIELD_DISCARDED, true));
        filters.add(in(FIELD_ORIGIN, query.origins()));
        if (query.version() != null) {
            filters.add(eq(FIELD_AGENT_VERSION, query.version()));
        }
        return and(filters);
    }

    /**
     * A conversation is engaged when its last turn with a person's input comes
     * after its first turn with output ({@code HealthTurnFactory} engagement rule).
     */
    private static Document conversationSummary(Object groupId) {
        return new Document("_id", groupId)
                .append("minOut", new Document("$min", new Document("$cond", Arrays.asList("$" + FIELD_AGENT_OUTPUT, "$" + FIELD_STEP, null))))
                .append("maxIn", new Document("$max", new Document("$cond", Arrays.asList("$" + FIELD_USER_INPUT, "$" + FIELD_STEP, null))))
                .append("turns", new Document("$sum", 1)).append("users", new Document("$addToSet", "$" + FIELD_USER));
    }

    private static Document engagedExpression() {
        return new Document("$cond",
                List.of(new Document("$and",
                        List.of(new Document("$ne", Arrays.asList("$minOut", null)), new Document("$ne", Arrays.asList("$maxIn", null)),
                                new Document("$gt", List.of("$maxIn", "$minOut")))),
                        1, 0));
    }

    @Override
    public HealthUsage usage(HealthUsageQuery query, LocalDate lookbackFrom) {
        if (query.origins().isEmpty()) {
            return HealthUsage.EMPTY;
        }
        try {
            long sessions = 0;
            long engaged = 0;
            long turnCount = 0;
            List<Document> conversations = List.of(new Document("$match", usageFilter(query, query.from())),
                    new Document("$group", conversationSummary("$" + FIELD_CONVERSATION)),
                    new Document("$group", new Document("_id", null).append("sessions", new Document("$sum", 1))
                            .append("engaged", new Document("$sum", engagedExpression())).append("turns", new Document("$sum", "$turns"))));
            for (Document d : turns.aggregate(conversations).allowDiskUse(true)) {
                sessions = number(d.get("sessions"));
                engaged = number(d.get("engaged"));
                turnCount = number(d.get("turns"));
            }
            LocalDate lookback = lookbackFrom == null || lookbackFrom.isAfter(query.from()) ? query.from() : lookbackFrom;
            long users = 0;
            long returning = 0;
            List<Document> people = List.of(new Document("$match", and(usageFilter(query, lookback), ne(FIELD_USER, null))),
                    new Document("$group",
                            new Document("_id", "$" + FIELD_USER).append("first", new Document("$min", "$" + FIELD_DAY)).append("last",
                                    new Document("$max", "$" + FIELD_DAY))),
                    new Document("$match", gte("last", query.from().toString())),
                    new Document("$group", new Document("_id", null).append("users", new Document("$sum", 1)).append("returning",
                            new Document("$sum",
                                    new Document("$cond", List.of(new Document("$lt", List.of("$first", query.from().toString())), 1, 0))))));
            for (Document d : turns.aggregate(people).allowDiskUse(true)) {
                users = number(d.get("users"));
                returning = number(d.get("returning"));
            }
            return new HealthUsage(sessions, engaged, turnCount, users, returning);
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the usage of an agent", e);
        }
    }

    @Override
    public List<HealthUsagePoint> dailyUsage(HealthUsageQuery query, String groupBy) {
        if (query.origins().isEmpty()) {
            return List.of();
        }
        Object group = switch (groupBy == null ? "none" : groupBy) {
            case "version" -> "$" + FIELD_AGENT_VERSION;
            case "origin" -> "$" + FIELD_ORIGIN;
            default -> null;
        };
        try {
            Document conversationKey = new Document("c", "$" + FIELD_CONVERSATION).append("d", "$" + FIELD_DAY).append("g", group);
            List<Document> pipeline = List.of(new Document("$match", usageFilter(query, query.from())),
                    new Document("$group", conversationSummary(conversationKey)),
                    new Document("$group",
                            new Document("_id", new Document("d", "$_id.d").append("g", "$_id.g")).append("sessions", new Document("$sum", 1))
                                    .append("engaged", new Document("$sum", engagedExpression()))
                                    .append("turns", new Document("$sum", "$turns")).append("users", new Document("$push", "$users"))),
                    new Document("$project", new Document("sessions", 1).append("engaged", 1).append("turns", 1).append("users",
                            new Document("$size", new Document("$setDifference", List.of(new Document("$reduce",
                                    new Document("input", "$users").append("initialValue", List.of()).append("in",
                                            new Document("$setUnion", List.of("$$value", "$$this")))),
                                    Arrays.asList((Object) null)))))));
            List<HealthUsagePoint> points = new ArrayList<>();
            for (Document d : turns.aggregate(pipeline).allowDiskUse(true)) {
                Document key = d.get("_id", Document.class);
                Object g = key.get("g");
                points.add(new HealthUsagePoint(LocalDate.parse(key.getString("d")), g == null ? null : String.valueOf(g), number(d.get("sessions")),
                        number(d.get("engaged")), number(d.get("turns")), number(d.get("users"))));
            }
            return points;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the daily usage of an agent", e);
        }
    }

    @Override
    public Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom,
                                                                  LocalDate to, Collection<String> interactive, Collection<String> origins) {
        if (agentIds == null || agentIds.isEmpty() || origins == null || origins.isEmpty()) {
            return Map.of();
        }
        try {
            Document inWindow = new Document("$gte", List.of("$" + FIELD_DAY, windowFrom.toString()));
            Document interactiveOrigin = new Document("$in", List.of("$" + FIELD_ORIGIN, List.copyOf(interactive == null ? List.of() : interactive)));
            List<Document> pipeline = List.of(
                    new Document("$match",
                            and(in(FIELD_AGENT, agentIds), gte(FIELD_DAY, baselineFrom.toString()), lte(FIELD_DAY, to.toString()),
                                    ne(FIELD_TIMESTAMP, null), ne(FIELD_DISCARDED, true), in(FIELD_ORIGIN, origins))),
                    // One document per (agent, conversation), flagged with where it had turns.
                    new Document("$group", new Document("_id", new Document("a", "$" + FIELD_AGENT).append("c", "$" + FIELD_CONVERSATION))
                            .append("iNow", flag(new Document("$and", List.of(inWindow, interactiveOrigin))))
                            .append("aNow", flag(inWindow))
                            .append("iBefore", flag(new Document("$and", List.of(new Document("$not", List.of(inWindow)), interactiveOrigin))))
                            .append("aBefore", flag(new Document("$not", List.of(inWindow))))),
                    new Document("$group", new Document("_id", "$_id.a").append("iNow", new Document("$sum", "$iNow"))
                            .append("aNow", new Document("$sum", "$aNow")).append("iBefore", new Document("$sum", "$iBefore"))
                            .append("aBefore", new Document("$sum", "$aBefore"))));
            Map<String, HealthPortfolioSessions> sessions = new LinkedHashMap<>();
            for (Document d : turns.aggregate(pipeline).allowDiskUse(true)) {
                sessions.put(d.getString("_id"), new HealthPortfolioSessions(number(d.get("iNow")), number(d.get("aNow")),
                        number(d.get("iBefore")), number(d.get("aBefore"))));
            }
            return sessions;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the sessions of agents", e);
        }
    }

    private static Document flag(Document condition) {
        return new Document("$max", new Document("$cond", List.of(condition, 1, 0)));
    }

    @Override
    public void forEachAgentDayUpdatedSince(Instant since, Instant until, int limit, BiConsumer<String, LocalDate> consumer) {
        // Rows written, or discarded, in the window: a discard leaves updatedAt as it
        // was.
        Bson inWindow = or(and(gte(FIELD_UPDATED, date(since)), lt(FIELD_UPDATED, date(until))),
                and(gte(FIELD_DISCARDED_AT, date(since)), lt(FIELD_DISCARDED_AT, date(until))));
        List<Bson> pipeline = List.of(Aggregates.match(and(inWindow, ne(FIELD_DAY, null), ne(FIELD_AGENT, null))),
                Aggregates.group(new Document("a", "$" + FIELD_AGENT).append("d", "$" + FIELD_DAY)), Aggregates.limit(Math.max(1, limit)));
        try (MongoCursor<Document> cursor = turns.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                Document key = cursor.next().get("_id", Document.class);
                consumer.accept(key.getString("a"), LocalDate.parse(key.getString("d")));
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the days of recent health turns", e);
        }
    }

    @Override
    public Map<String, Set<LocalDate>> agentDaysOfUser(String userId, int limit) {
        return agentDays(eq(FIELD_USER, userId), limit);
    }

    @Override
    public Map<String, Set<LocalDate>> agentDaysOfConversation(String conversationId) {
        return agentDays(eq(FIELD_CONVERSATION, conversationId), Integer.MAX_VALUE);
    }

    @Override
    public HealthDeletedRows deleteConversationReturningDays(String conversationId) {
        // MongoDB has no delete that returns the deleted documents in bulk: read the
        // pairs first, then delete — and delete even when the read fails.
        Map<String, Set<LocalDate>> days = daysOrNull(eq(FIELD_CONVERSATION, conversationId));
        return new HealthDeletedRows(deleteByConversationId(conversationId), days);
    }

    @Override
    public HealthDeletedRows deleteConversationIfDaysKnown(String conversationId) {
        Map<String, Set<LocalDate>> days = daysOrNull(eq(FIELD_CONVERSATION, conversationId));
        if (days == null) {
            return null; // kept for the orphan sweep
        }
        return new HealthDeletedRows(deleteByConversationId(conversationId), days);
    }

    @Override
    public HealthDeletedRows deleteByUserIdReturningDays(String userId) {
        // Read first, then delete; the days are null when the read fails.
        Map<String, Set<LocalDate>> days = daysOrNull(eq(FIELD_USER, userId));
        return new HealthDeletedRows(deleteByUserId(userId), days);
    }

    /**
     * The (agent, day)s of the matching rows, or {@code null} when they cannot be
     * read.
     */
    private Map<String, Set<LocalDate>> daysOrNull(Bson filter) {
        try {
            return agentDays(filter, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void forEachConversationAgentUpdatedSince(Instant since, int limit, BiConsumer<String, String> consumer) {
        Date at = date(since);
        List<Bson> pipeline = List.of(Aggregates.match(or(gte(FIELD_UPDATED, at), gte(FIELD_DISCARDED_AT, at))),
                Aggregates.group(new Document("c", "$" + FIELD_CONVERSATION).append("a", "$" + FIELD_AGENT)), Aggregates.limit(Math.max(1, limit)));
        try (MongoCursor<Document> cursor = turns.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                Document key = cursor.next().get("_id", Document.class);
                if (key.get("c") != null) {
                    consumer.accept(key.get("c").toString(), key.getString("a"));
                }
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the conversations of recent health turns", e);
        }
    }

    private Map<String, Set<LocalDate>> agentDays(Bson filter, int limit) {
        List<Bson> pipeline = List.of(Aggregates.match(and(filter, ne(FIELD_DAY, null), ne(FIELD_AGENT, null))),
                Aggregates.group(new Document("a", "$" + FIELD_AGENT).append("d", "$" + FIELD_DAY)), Aggregates.limit(Math.max(1, limit)));
        Map<String, Set<LocalDate>> days = new LinkedHashMap<>();
        try (MongoCursor<Document> cursor = turns.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                Document key = cursor.next().get("_id", Document.class);
                days.computeIfAbsent(key.getString("a"), k -> new TreeSet<>()).add(LocalDate.parse(key.getString("d")));
            }
            return days;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the days of health turns", e);
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
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
    public void forEachConversationUpdatedSince(Instant since, int limit, Consumer<String> consumer) {
        // A $group, not distinct(): distinct answers in one document (16 MB) and
        // takes no limit. allowDiskUse lets a large window group past the in-memory
        // stage limit.
        try (MongoCursor<Document> cursor = turns.aggregate(updatedSincePipeline(since, limit)).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                Object conversationId = cursor.next().get("_id");
                if (conversationId != null) {
                    consumer.accept(conversationId.toString());
                }
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the conversations of recent health turns", e);
        }
    }

    /**
     * The distinct conversations of rows written since {@code since}, and of
     * markers written since then, at most {@code limit}.
     */
    static List<Bson> updatedSincePipeline(Instant since, int limit) {
        Date at = date(since);
        return List.of(Aggregates.match(or(gte(FIELD_UPDATED, at), gte(FIELD_DISCARDED_AT, at))), Aggregates.group("$" + FIELD_CONVERSATION),
                Aggregates.limit(Math.max(1, limit)));
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
