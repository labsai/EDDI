/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.mongo;

import ai.labs.eddi.engine.alerts.AlertJson;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
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
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;
import static com.mongodb.client.model.Updates.unset;

/**
 * MongoDB implementation of {@link IAlertEventStore}: events in
 * {@code alertevents}, throttle counters in {@code alertwindows} (a TTL index
 * removes expired windows).
 * <p>
 * The dedupe is the partial unique index over {@code dedupeKey}: a second
 * insert with the same key fails with a duplicate-key error on whichever
 * replica runs it, and is turned into an increment of {@code duplicates}.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@DefaultBean
public class AlertEventStore implements IAlertEventStore {

    /** Internal: the send claim (never read into an event). */
    static final String SEND_CLAIM_TOKEN = "sendClaimToken";
    static final String SEND_CLAIM_UNTIL = "sendClaimUntil";

    static final String COLLECTION_EVENTS = "alertevents";
    static final String COLLECTION_WINDOWS = "alertwindows";

    private static final TypeReference<List<Delivery>> DELIVERIES = new TypeReference<>() {
    };

    private final MongoCollection<Document> events;
    private final MongoCollection<Document> windows;

    @Inject
    public AlertEventStore(MongoDatabase database) {
        this.events = database.getCollection(COLLECTION_EVENTS);
        this.windows = database.getCollection(COLLECTION_WINDOWS);
        events.createIndex(Indexes.ascending("dedupeKey"),
                new IndexOptions().name("idx_alertevent_dedupe").unique(true).partialFilterExpression(exists("dedupeKey", true)));
        events.createIndex(Indexes.compoundIndex(Indexes.ascending("agentId"), Indexes.descending("firedAt")),
                new IndexOptions().name("idx_alertevent_agent_fired"));
        events.createIndex(Indexes.descending("firedAt"), new IndexOptions().name("idx_alertevent_fired"));
        events.createIndex(Indexes.ascending("nextAttemptAt"),
                new IndexOptions().name("idx_alertevent_next_attempt").partialFilterExpression(exists("nextAttemptAt", true)));
        events.createIndex(Indexes.compoundIndex(Indexes.ascending("ruleId"), Indexes.ascending("state"), Indexes.ascending("firedAt")),
                new IndexOptions().name("idx_alertevent_rule_state"));
        events.createIndex(Indexes.compoundIndex(Indexes.ascending("acknowledgedAt"), Indexes.ascending("agentId")),
                new IndexOptions().name("idx_alertevent_unacknowledged"));
        events.createIndex(Indexes.ascending("subjectUserId"),
                new IndexOptions().name("idx_alertevent_subject").partialFilterExpression(exists("subjectUserId", true)));
        events.createIndex(Indexes.ascending("match.ratingId"),
                new IndexOptions().name("idx_alertevent_rating").partialFilterExpression(exists("match.ratingId", true)));
        windows.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().name("idx_alertwindow_ttl").expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public Optional<AlertEvent> insert(AlertEvent event) {
        AlertEvent stored = event.withId(new ObjectId().toHexString()).withVersion(0);
        try {
            events.insertOne(toDocument(stored));
            return Optional.of(stored);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY || event.dedupeKey() == null) {
                throw e;
            }
            events.updateOne(eq("dedupeKey", event.dedupeKey()), inc("duplicates", 1));
            return Optional.empty();
        }
    }

    @Override
    public long incrementWindow(String ruleId, Instant windowStart, Instant expiresAt) {
        String key = ruleId + "|" + windowStart.toEpochMilli();
        Bson update = combine(inc("count", 1L), setOnInsert("expiresAt", Date.from(expiresAt)));
        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER);
        Document counter;
        try {
            counter = windows.findOneAndUpdate(eq("_id", key), update, options);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            // Two first increments raced to insert; the loser finds the winner's row now.
            counter = windows.findOneAndUpdate(eq("_id", key), update, options);
        }
        Number count = counter == null ? null : counter.get("count", Number.class);
        return count == null ? 1L : count.longValue();
    }

    @Override
    public Optional<AlertEvent> read(String id) {
        return Optional.ofNullable(events.find(eq("_id", id)).first()).map(AlertEventStore::fromDocument);
    }

    @Override
    public Optional<AlertEvent> updateDelivery(AlertEvent event, long expectedVersion) {
        Bson update = combine(set("deliveries", deliveries(event.deliveries())), set("state", event.state().name()),
                set("nextAttemptAt", date(event.nextAttemptAt())), inc("version", 1L));
        Document updated = events.findOneAndUpdate(and(eq("_id", event.id()), eq("version", expectedVersion)), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(updated).map(AlertEventStore::fromDocument);
    }

    @Override
    public List<AlertEvent> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<AlertEvent> claimed = new ArrayList<>();
        List<Document> due = new ArrayList<>();
        events.find(lte("nextAttemptAt", Date.from(now))).sort(Sorts.ascending("nextAttemptAt")).limit(limit).forEach(due::add);
        for (Document doc : due) {
            Number version = doc.get("version", Number.class);
            Document won = events.findOneAndUpdate(
                    and(eq("_id", doc.get("_id")), eq("version", version == null ? 0L : version.longValue()),
                            lte("nextAttemptAt", Date.from(now))),
                    combine(set("nextAttemptAt", Date.from(leaseUntil)), inc("version", 1L)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (won != null) {
                claimed.add(fromDocument(won));
            }
        }
        return claimed;
    }

    @Override
    public List<AlertEvent> list(AlertEventFilter filter, int offset, int limit) {
        List<AlertEvent> page = new ArrayList<>();
        events.find(filter(filter)).sort(Sorts.descending("firedAt", "_id")).skip(Math.max(0, offset)).limit(limit)
                .forEach(doc -> page.add(fromDocument(doc)));
        return page;
    }

    @Override
    public long count(AlertEventFilter filter) {
        return events.countDocuments(filter(filter));
    }

    @Override
    public boolean acknowledge(String id, String by, Instant at) {
        return events.updateOne(and(eq("_id", id), eq("acknowledgedAt", null)), acknowledgement(by, at)).getModifiedCount() > 0;
    }

    @Override
    public long acknowledgeMatching(AlertEventFilter filter, String by, Instant at) {
        return events.updateMany(and(filter(filter), eq("acknowledgedAt", null)), acknowledgement(by, at)).getModifiedCount();
    }

    @Override
    public long acknowledgeIds(Collection<String> ids, String by, Instant at) {
        if (ids.isEmpty()) {
            return 0;
        }
        return events.updateMany(and(in("_id", ids), eq("acknowledgedAt", null)), acknowledgement(by, at)).getModifiedCount();
    }

    @Override
    public List<AlertEvent> findDigestPending(String ruleId, int limit) {
        List<AlertEvent> pending = new ArrayList<>();
        events.find(and(eq("ruleId", ruleId), eq("state", AlertEvent.State.DIGEST_PENDING.name()))).sort(Sorts.ascending("firedAt"))
                .limit(limit).forEach(doc -> pending.add(fromDocument(doc)));
        return pending;
    }

    @Override
    public long markDigested(Collection<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return events.updateMany(and(in("_id", ids), eq("state", AlertEvent.State.DIGEST_PENDING.name())),
                combine(set("state", AlertEvent.State.DIGESTED.name()), inc("version", 1L))).getModifiedCount();
    }

    @Override
    public long deleteFiredBefore(Instant cutoff) {
        return events.deleteMany(lt("firedAt", Date.from(cutoff))).getDeletedCount();
    }

    @Override
    public long redactRatingComments(Collection<String> ratingIds, Instant now) {
        if (ratingIds.isEmpty()) {
            return 0;
        }
        return events.updateMany(and(in("match.ratingId", ratingIds), ne("match.commentExcerpt", null), unclaimed(now)),
                set("match.commentExcerpt", null)).getModifiedCount();
    }

    @Override
    public long countRatingComments(Collection<String> ratingIds) {
        if (ratingIds.isEmpty()) {
            return 0;
        }
        return events.countDocuments(and(in("match.ratingId", ratingIds), ne("match.commentExcerpt", null)));
    }

    /** No send claim, or an expired one. */
    private static Bson unclaimed(Instant now) {
        return or(eq(SEND_CLAIM_UNTIL, null), lte(SEND_CLAIM_UNTIL, Date.from(now)));
    }

    @Override
    public boolean claimSend(String eventId, String token, Instant now, Instant until) {
        return events.updateOne(and(eq("_id", eventId), unclaimed(now)),
                combine(set(SEND_CLAIM_TOKEN, token), set(SEND_CLAIM_UNTIL, Date.from(until)))).getModifiedCount() == 1;
    }

    @Override
    public boolean releaseSend(String eventId, String token) {
        return events.updateOne(and(eq("_id", eventId), eq(SEND_CLAIM_TOKEN, token)), combine(unset(SEND_CLAIM_TOKEN), unset(SEND_CLAIM_UNTIL)))
                .getModifiedCount() == 1;
    }

    @Override
    public long clearAcknowledger(String userId) {
        return events.updateMany(eq("acknowledgedBy", userId), set("acknowledgedBy", null)).getModifiedCount();
    }

    @Override
    public List<AlertEvent> findAcknowledgedBy(String userId, int limit) {
        List<AlertEvent> acknowledged = new ArrayList<>();
        events.find(eq("acknowledgedBy", userId)).sort(Sorts.descending("acknowledgedAt", "_id")).limit(limit)
                .forEach(doc -> acknowledged.add(fromDocument(doc)));
        return acknowledged;
    }

    @Override
    public Optional<AlertEvent> findByDedupeKey(String dedupeKey) {
        return Optional.ofNullable(events.find(eq("dedupeKey", dedupeKey)).first()).map(AlertEventStore::fromDocument);
    }

    @Override
    public long redactOutputValues(String userId, Instant now) {
        return events.updateMany(and(eq("subjectUserId", userId), unclaimed(now)), combine(unset("match.value"), unset("subjectUserId")))
                .getModifiedCount();
    }

    @Override
    public long countOutputValues(String userId) {
        return events.countDocuments(eq("subjectUserId", userId));
    }

    @Override
    public List<AlertEvent> findOutputValuesBySubject(String userId, int limit) {
        List<AlertEvent> found = new ArrayList<>();
        events.find(eq("subjectUserId", userId)).sort(Sorts.descending("firedAt", "_id")).limit(limit)
                .forEach(doc -> found.add(fromDocument(doc)));
        return found;
    }

    @Override
    public long redactRatingComment(String eventId, Instant now) {
        return events.updateOne(and(eq("_id", eventId), exists("match.commentExcerpt", true), unclaimed(now)), unset("match.commentExcerpt"))
                .getModifiedCount();
    }

    private static Bson acknowledgement(String by, Instant at) {
        return combine(set("acknowledgedBy", by), set("acknowledgedAt", Date.from(at)));
    }

    static Bson filter(AlertEventFilter f) {
        List<Bson> clauses = new ArrayList<>();
        if (f != null) {
            if (f.agentId() != null) {
                clauses.add(eq("agentId", f.agentId()));
            }
            if (f.ruleId() != null) {
                clauses.add(eq("ruleId", f.ruleId()));
            }
            if (f.source() != null) {
                clauses.add(eq("source", f.source().name()));
            }
            if (f.acknowledged() != null) {
                clauses.add(f.acknowledged() ? ne("acknowledgedAt", null) : eq("acknowledgedAt", null));
            }
            if (f.state() != null) {
                clauses.add(eq("state", f.state().name()));
            }
            if (f.conversationId() != null) {
                clauses.add(eq("conversationId", f.conversationId()));
            }
            if (f.from() != null) {
                clauses.add(gte("firedAt", Date.from(f.from())));
            }
            if (f.to() != null) {
                clauses.add(lt("firedAt", Date.from(f.to())));
            }
        }
        return clauses.isEmpty() ? new Document() : and(clauses);
    }

    static Document toDocument(AlertEvent event) {
        Document doc = new Document("_id", event.id());
        doc.put("ruleId", event.ruleId());
        doc.put("ruleName", event.ruleName());
        doc.put("source", event.source() == null ? null : event.source().name());
        doc.put("kind", event.kind().name());
        doc.put("agentId", event.agentId());
        doc.put("agentVersion", event.agentVersion());
        doc.put("conversationId", event.conversationId());
        doc.put("firedAt", date(event.firedAt()));
        doc.put("summary", event.summary());
        Map<String, Object> match = AlertJson.toMap(event.match());
        doc.put("match", match == null ? null : new Document(match));
        doc.put("deliveries", deliveries(event.deliveries()));
        doc.put("state", event.state().name());
        doc.put("nextAttemptAt", date(event.nextAttemptAt()));
        if (event.dedupeKey() != null) {
            // Absent rather than null: the unique index is partial on existence.
            doc.put("dedupeKey", event.dedupeKey());
        }
        doc.put("duplicates", event.duplicates());
        doc.put("digestCount", event.digestCount());
        doc.put("digestItems", event.digestItems());
        doc.put("acknowledgedBy", event.acknowledgedBy());
        doc.put("acknowledgedAt", date(event.acknowledgedAt()));
        doc.put("version", event.version());
        if (event.subjectUserId() != null) {
            // Absent unless set: the index is partial on existence, and most events have
            // none.
            doc.put("subjectUserId", event.subjectUserId());
        }
        if (event.digestEventIds() != null) {
            doc.put("digestEventIds", event.digestEventIds());
        }
        return doc;
    }

    static AlertEvent fromDocument(Document doc) {
        String source = doc.getString("source");
        Number agentVersion = doc.get("agentVersion", Number.class);
        Number duplicates = doc.get("duplicates", Number.class);
        Number digestCount = doc.get("digestCount", Number.class);
        Number version = doc.get("version", Number.class);
        return new AlertEvent(doc.getString("_id"), doc.getString("ruleId"), doc.getString("ruleName"),
                source == null ? null : AlertSource.valueOf(source), AlertEvent.Kind.valueOf(doc.getString("kind")), doc.getString("agentId"),
                agentVersion == null ? null : agentVersion.intValue(), doc.getString("conversationId"), instant(doc.getDate("firedAt")),
                doc.getString("summary"), AlertJson.convert(doc.get("match"), AlertMatch.class), AlertJson.convert(doc.get("deliveries"), DELIVERIES),
                AlertEvent.State.valueOf(doc.getString("state")), instant(doc.getDate("nextAttemptAt")), doc.getString("dedupeKey"),
                duplicates == null ? 0 : duplicates.intValue(), digestCount == null ? null : digestCount.intValue(),
                doc.getList("digestItems", String.class), doc.getString("acknowledgedBy"), instant(doc.getDate("acknowledgedAt")),
                version == null ? 0L : version.longValue(), null, doc.getList("digestEventIds", String.class));
    }

    private static List<Document> deliveries(List<Delivery> deliveries) {
        List<Document> docs = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            docs.add(new Document(AlertJson.toMap(delivery)));
        }
        return docs;
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
