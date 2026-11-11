/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthOutcomeStore;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;

/**
 * MongoDB implementation of {@link IHealthOutcomeStore}: collection
 * {@code healthoutcomes}, one document per session, {@code _id}
 * {@code <conversationId>|<sessionNo>}, written by {@code replaceOne(upsert)}.
 * {@code day} is an ISO {@code yyyy-MM-dd} string, as in {@code healthturns}.
 * The incremental feed's watermark lives in {@code healthrollupstate} (the
 * rollup's own state collection) under {@value #WATERMARK_ID}.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthOutcomeStore implements IHealthOutcomeStore {

    static final String COLLECTION = "healthoutcomes";
    static final String STATE_COLLECTION = "healthrollupstate";
    static final String WATERMARK_ID = "outcomes";

    static final String FIELD_CONVERSATION = "conversationId";
    static final String FIELD_SESSION = "sessionNo";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_AGENT_VERSION = "agentVersion";
    static final String FIELD_USER = "userId";
    static final String FIELD_ORIGIN = "origin";
    static final String FIELD_ENGAGED = "engaged";
    static final String FIELD_STARTED = "startedAt";
    static final String FIELD_ENDED = "endedAt";
    static final String FIELD_TURNS = "turns";
    static final String FIELD_COST = "costUsd";
    static final String FIELD_FAILURES = "failures";
    static final String FIELD_OUTCOME = "outcome";
    static final String FIELD_EVIDENCE = "evidence";
    static final String FIELD_PROVISIONAL = "provisional";
    static final String FIELD_DAY = "day";
    static final String FIELD_DUE = "dueAt";
    static final String FIELD_FIRST_OUTPUT = "firstOutputStep";
    static final String FIELD_LAST_ACTIONS = "lastActions";
    static final String FIELD_EVALUATED = "evaluatedAt";
    static final String FIELD_AT = "at";

    private final MongoCollection<Document> outcomes;
    private final MongoCollection<Document> state;

    @Inject
    public MongoHealthOutcomeStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION), database.getCollection(STATE_COLLECTION));
        // Reports and drill-down read an agent's days; erasure finds rows by user;
        // evaluation reads a conversation's rows and the due ones; retention deletes
        // by day; the orphan sweep reads recently evaluated rows.
        outcomes.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_outcomes_agent_day").background(true));
        outcomes.createIndex(Indexes.ascending(FIELD_CONVERSATION, FIELD_SESSION),
                new IndexOptions().name("idx_health_outcomes_conversation").background(true));
        outcomes.createIndex(Indexes.ascending(FIELD_USER), new IndexOptions().name("idx_health_outcomes_user").background(true));
        outcomes.createIndex(Indexes.ascending(FIELD_DAY), new IndexOptions().name("idx_health_outcomes_day").background(true));
        outcomes.createIndex(Indexes.ascending(FIELD_DUE), new IndexOptions().name("idx_health_outcomes_due").sparse(true).background(true));
        outcomes.createIndex(Indexes.ascending(FIELD_EVALUATED), new IndexOptions().name("idx_health_outcomes_evaluated").background(true));
    }

    /** For tests: collections without index creation. */
    MongoHealthOutcomeStore(MongoCollection<Document> outcomes, MongoCollection<Document> state) {
        this.outcomes = outcomes;
        this.state = state;
    }

    static String id(String conversationId, int sessionNo) {
        return conversationId + "|" + sessionNo;
    }

    @Override
    public void upsert(Collection<HealthOutcome> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try {
            List<WriteModel<Document>> writes = new ArrayList<>(rows.size());
            for (HealthOutcome row : rows) {
                writes.add(new UpdateOneModel<>(eq("_id", id(row.conversationId(), row.sessionNo())), replacePipeline(row),
                        new UpdateOptions().upsert(true)));
            }
            outcomes.bulkWrite(writes);
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store health outcomes", e);
        }
    }

    /**
     * Every value {@code $set} as a literal (an action named {@code "$x"} stays a
     * string), and {@code dueAt} kept when a mark newer than this evaluation is
     * stored — removed, not nulled, when there is none (the index is sparse).
     */
    static List<Bson> replacePipeline(HealthOutcome row) {
        Document set = new Document();
        toDocument(row).forEach((field, value) -> {
            if (!FIELD_DUE.equals(field)) {
                set.put(field, new Document("$literal", value));
            }
        });
        Date evaluated = date(row.evaluatedAt() == null ? Instant.EPOCH : row.evaluatedAt());
        Object fresh = row.dueAt() == null ? "$$REMOVE" : date(row.dueAt());
        Object kept = row.dueAt() == null ? "$" + FIELD_DUE : new Document("$min", List.of("$" + FIELD_DUE, date(row.dueAt())));
        Document markedSince = new Document("$and", List.of(
                new Document("$ne", Arrays.asList(new Document("$ifNull", Arrays.asList("$" + FIELD_DUE, null)), null)),
                new Document("$gte", List.of("$" + FIELD_DUE, evaluated)),
                new Document("$lte", List.of("$" + FIELD_DUE, new Date(evaluated.getTime() + MARK_WINDOW_MS)))));
        set.put(FIELD_DUE, new Document("$cond", Arrays.asList(markedSince, kept, fresh)));
        return List.of(new Document("$set", set));
    }

    @Override
    public List<HealthOutcome> findByConversation(String conversationId) {
        return find(eq(FIELD_CONVERSATION, conversationId), Sorts.ascending(FIELD_SESSION), 0, Integer.MAX_VALUE,
                "Could not read the health outcomes of a conversation");
    }

    @Override
    public long deleteSessions(String conversationId, Collection<Integer> sessionNos) {
        if (conversationId == null || sessionNos == null || sessionNos.isEmpty()) {
            return 0L;
        }
        try {
            List<String> ids = new LinkedHashSet<>(sessionNos).stream().map(n -> id(conversationId, n)).toList();
            return outcomes.deleteMany(in("_id", ids)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete health outcomes", e);
        }
    }

    @Override
    public List<HealthOutcome> findDue(Instant now, int limit) {
        return find(lte(FIELD_DUE, Date.from(now)), Sorts.ascending(FIELD_DUE), 0, limit, "Could not read the due health outcomes");
    }

    @Override
    public long markDue(String conversationId, Collection<Integer> sessionNos, Instant at) {
        if (sessionNos != null && sessionNos.isEmpty()) {
            return 0L;
        }
        try {
            Date when = Date.from(at);
            List<Bson> filters = new ArrayList<>(List.of(eq(FIELD_CONVERSATION, conversationId), or(eq(FIELD_DUE, null), gt(FIELD_DUE, when))));
            if (sessionNos != null) {
                filters.add(in(FIELD_SESSION, sessionNos));
            }
            return outcomes.updateMany(and(filters), Updates.set(FIELD_DUE, when)).getModifiedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not mark health outcomes due", e);
        }
    }

    /** The rows a count reads: one agent, its days, origins and version. */
    static Bson scope(HealthUsageQuery query) {
        List<Bson> filters = new ArrayList<>();
        filters.add(eq(FIELD_AGENT, query.agentId()));
        filters.add(gte(FIELD_DAY, query.from().toString()));
        filters.add(lte(FIELD_DAY, query.to().toString()));
        filters.add(in(FIELD_ORIGIN, query.origins()));
        if (query.version() != null) {
            filters.add(eq(FIELD_AGENT_VERSION, query.version()));
        }
        return and(filters);
    }

    @Override
    public List<HealthOutcomeCount> counts(HealthUsageQuery query, boolean perDay, String groupBy) {
        if (query.origins().isEmpty()) {
            return List.of();
        }
        Object group = switch (groupBy == null ? "none" : groupBy) {
            case "version" -> "$" + FIELD_AGENT_VERSION;
            case "origin" -> "$" + FIELD_ORIGIN;
            default -> null;
        };
        Document key = new Document("o", "$" + FIELD_OUTCOME).append("e", "$" + FIELD_EVIDENCE).append("en", "$" + FIELD_ENGAGED)
                .append("p", "$" + FIELD_PROVISIONAL).append("d", perDay ? "$" + FIELD_DAY : null).append("g", group);
        List<Document> pipeline = List.of(new Document("$match", scope(query)),
                new Document("$group", new Document("_id", key).append("sessions", new Document("$sum", 1)).append("turns",
                        new Document("$sum", "$" + FIELD_TURNS))));
        try {
            List<HealthOutcomeCount> counts = new ArrayList<>();
            for (Document d : outcomes.aggregate(pipeline).allowDiskUse(true)) {
                Document k = d.get("_id", Document.class);
                Object g = k.get("g");
                String day = k.getString("d");
                counts.add(new HealthOutcomeCount(day == null ? null : LocalDate.parse(day), g == null ? null : String.valueOf(g), k.getString("o"),
                        k.getString("e"), Boolean.TRUE.equals(k.get("en")), Boolean.TRUE.equals(k.get("p")), number(d.get("sessions")),
                        number(d.get("turns"))));
            }
            return counts;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the health outcomes of an agent", e);
        }
    }

    @Override
    public List<HealthOutcome> sessions(HealthSessionQuery query) {
        if (query.origins().isEmpty() || query.versions().isEmpty()) {
            return List.of();
        }
        List<Bson> filters = new ArrayList<>();
        filters.add(eq(FIELD_AGENT, query.agentId()));
        filters.add(gte(FIELD_DAY, query.from().toString()));
        filters.add(lte(FIELD_DAY, query.to().toString()));
        filters.add(in(FIELD_AGENT_VERSION, query.versions()));
        filters.add(in(FIELD_ORIGIN, query.origins()));
        if (!query.outcomes().isEmpty()) {
            filters.add(in(FIELD_OUTCOME, query.outcomes()));
        }
        if (!query.evidence().isEmpty()) {
            filters.add(in(FIELD_EVIDENCE, query.evidence()));
        }
        return find(and(filters), Sorts.orderBy(Sorts.descending(FIELD_ENDED), Sorts.ascending(FIELD_CONVERSATION, FIELD_SESSION)),
                Math.max(0, query.offset()), query.limit(), "Could not read the health sessions of an agent");
    }

    @Override
    public Map<String, Long> endingActions(HealthUsageQuery query) {
        if (query.origins().isEmpty()) {
            return Map.of();
        }
        List<Document> pipeline = List.of(
                new Document("$match", and(scope(query), eq(FIELD_ENGAGED, true), eq(FIELD_PROVISIONAL, false), ne(FIELD_OUTCOME, null))),
                new Document("$unwind", "$" + FIELD_LAST_ACTIONS),
                // An action listed twice on one turn still ends that session once.
                new Document("$group", new Document("_id", new Document("a", "$" + FIELD_LAST_ACTIONS).append("s", "$_id"))),
                new Document("$group", new Document("_id", "$_id.a").append("n", new Document("$sum", 1))));
        try {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Document d : outcomes.aggregate(pipeline).allowDiskUse(true)) {
                Object action = d.get("_id");
                if (action != null) {
                    counts.put(String.valueOf(action), number(d.get("n")));
                }
            }
            return counts;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the ending actions of an agent", e);
        }
    }

    @Override
    public Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom,
                                                                  LocalDate to, Collection<String> interactive, Collection<String> origins) {
        if (agentIds == null || agentIds.isEmpty() || origins == null || origins.isEmpty()) {
            return Map.of();
        }
        Document inWindow = new Document("$gte", List.of("$" + FIELD_DAY, windowFrom.toString()));
        Document interactiveOrigin = new Document("$in", List.of("$" + FIELD_ORIGIN, List.copyOf(interactive == null ? List.of() : interactive)));
        List<Document> pipeline = List.of(
                new Document("$match", and(in(FIELD_AGENT, agentIds), gte(FIELD_DAY, baselineFrom.toString()), lte(FIELD_DAY, to.toString()),
                        in(FIELD_ORIGIN, origins))),
                new Document("$group", new Document("_id", "$" + FIELD_AGENT)
                        .append("iNow", sumIf(new Document("$and", List.of(inWindow, interactiveOrigin))))
                        .append("aNow", sumIf(inWindow))
                        .append("iBefore", sumIf(new Document("$and", List.of(new Document("$not", List.of(inWindow)), interactiveOrigin))))
                        .append("aBefore", sumIf(new Document("$not", List.of(inWindow))))));
        try {
            Map<String, HealthPortfolioSessions> sessions = new LinkedHashMap<>();
            for (Document d : outcomes.aggregate(pipeline).allowDiskUse(true)) {
                sessions.put(d.getString("_id"), new HealthPortfolioSessions(number(d.get("iNow")), number(d.get("aNow")), number(d.get("iBefore")),
                        number(d.get("aBefore"))));
            }
            return sessions;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not count the sessions of agents", e);
        }
    }

    private static Document sumIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    @Override
    public List<HealthOutcome> findByUserId(String userId, int limit) {
        return find(eq(FIELD_USER, userId), Sorts.descending(FIELD_ENDED), 0, limit, "Could not read a user's health outcomes");
    }

    @Override
    public long deleteByUserId(String userId) {
        try {
            return outcomes.deleteMany(eq(FIELD_USER, userId)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete a user's health outcomes", e);
        }
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        try {
            return outcomes.deleteMany(eq(FIELD_CONVERSATION, conversationId)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete a conversation's health outcomes", e);
        }
    }

    @Override
    public void forEachConversationEvaluatedSince(Instant since, int limit, Consumer<String> consumer) {
        List<Bson> pipeline = List.of(Aggregates.match(gte(FIELD_EVALUATED, Date.from(since))), Aggregates.group("$" + FIELD_CONVERSATION),
                Aggregates.limit(Math.max(1, limit)));
        try (MongoCursor<Document> cursor = outcomes.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                Object conversationId = cursor.next().get("_id");
                if (conversationId != null) {
                    consumer.accept(conversationId.toString());
                }
            }
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the conversations of recent health outcomes", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        try {
            return outcomes.deleteMany(lt(FIELD_DAY, day.toString())).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete expired health outcomes", e);
        }
    }

    @Override
    public Optional<Instant> watermark() {
        try {
            Document d = state.find(eq("_id", WATERMARK_ID)).first();
            return d == null || d.getDate(FIELD_AT) == null ? Optional.empty() : Optional.of(d.getDate(FIELD_AT).toInstant());
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the health outcome watermark", e);
        }
    }

    @Override
    public void setWatermark(Instant at) {
        try {
            state.replaceOne(eq("_id", WATERMARK_ID), new Document("_id", WATERMARK_ID).append(FIELD_AT, Date.from(at)),
                    new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store the health outcome watermark", e);
        }
    }

    private List<HealthOutcome> find(Bson filter, Bson sort, int skip, int limit, String failure) {
        try {
            List<HealthOutcome> found = new ArrayList<>();
            var cursor = outcomes.find(filter).sort(sort).skip(skip);
            if (limit < Integer.MAX_VALUE) {
                cursor = cursor.limit(Math.max(1, limit));
            }
            for (Document document : cursor) {
                found.add(fromDocument(document));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    static Document toDocument(HealthOutcome o) {
        Document d = new Document();
        d.put(FIELD_CONVERSATION, o.conversationId());
        d.put(FIELD_SESSION, o.sessionNo());
        d.put(FIELD_AGENT, o.agentId());
        d.put(FIELD_AGENT_VERSION, o.agentVersion());
        d.put(FIELD_USER, o.userId());
        d.put(FIELD_ORIGIN, o.origin());
        d.put(FIELD_ENGAGED, o.engaged());
        d.put(FIELD_STARTED, date(o.startedAt()));
        d.put(FIELD_ENDED, date(o.endedAt()));
        d.put(FIELD_TURNS, o.turns());
        d.put(FIELD_COST, o.costUsd());
        d.put(FIELD_FAILURES, o.failures());
        d.put(FIELD_OUTCOME, o.outcome());
        d.put(FIELD_EVIDENCE, o.evidence());
        d.put(FIELD_PROVISIONAL, o.provisional());
        d.put(FIELD_DAY, o.day() == null ? null : o.day().toString());
        if (o.dueAt() != null) {
            d.put(FIELD_DUE, date(o.dueAt()));
        }
        d.put(FIELD_FIRST_OUTPUT, o.firstOutputStep());
        d.put(FIELD_LAST_ACTIONS, o.lastActions());
        d.put(FIELD_EVALUATED, date(o.evaluatedAt()));
        return d;
    }

    static HealthOutcome fromDocument(Document d) {
        List<String> actions = new ArrayList<>();
        if (d.get(FIELD_LAST_ACTIONS) instanceof List<?> list) {
            for (Object action : list) {
                if (action != null) {
                    actions.add(String.valueOf(action));
                }
            }
        }
        String day = d.getString(FIELD_DAY);
        return new HealthOutcome(d.getString(FIELD_CONVERSATION), intValue(d.get(FIELD_SESSION)), d.getString(FIELD_AGENT),
                integer(d.get(FIELD_AGENT_VERSION)), d.getString(FIELD_USER), d.getString(FIELD_ORIGIN), Boolean.TRUE.equals(d.get(FIELD_ENGAGED)),
                instant(d.getDate(FIELD_STARTED)), instant(d.getDate(FIELD_ENDED)), intValue(d.get(FIELD_TURNS)),
                d.get(FIELD_COST) instanceof Number n ? n.doubleValue() : 0.0, intValue(d.get(FIELD_FAILURES)), d.getString(FIELD_OUTCOME),
                d.getString(FIELD_EVIDENCE), Boolean.TRUE.equals(d.get(FIELD_PROVISIONAL)), day == null ? null : LocalDate.parse(day),
                instant(d.getDate(FIELD_DUE)), integer(d.get(FIELD_FIRST_OUTPUT)), actions, instant(d.getDate(FIELD_EVALUATED)));
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
