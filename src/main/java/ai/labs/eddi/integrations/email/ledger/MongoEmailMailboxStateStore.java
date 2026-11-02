/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IEmailMailboxStateStore}: collection
 * {@code email_mailbox_state} (one document per integration, {@code _id} the
 * integration id) and {@code email_mailbox_notices} ({@code _id}
 * {@code <integrationId>|<noticeKey>}, so a claim is an insert that loses on a
 * duplicate key).
 */
@ApplicationScoped
@DefaultBean
public class MongoEmailMailboxStateStore implements IEmailMailboxStateStore {

    static final String COLLECTION = "email_mailbox_state";
    static final String NOTICES = "email_mailbox_notices";

    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    private final MongoCollection<Document> states;
    private final MongoCollection<Document> notices;

    @Inject
    public MongoEmailMailboxStateStore(MongoDatabase database) {
        this.states = database.getCollection(COLLECTION);
        this.notices = database.getCollection(NOTICES);
        notices.createIndex(Indexes.ascending("integrationId"), new IndexOptions().name("idx_email_notices_integration"));
    }

    @Override
    public Optional<MailboxState> read(String integrationId) {
        Document document = states.find(Filters.eq("_id", integrationId)).first();
        if (document == null) {
            return Optional.empty();
        }
        return Optional.of(new MailboxState(integrationId, document.getString("folder"), longOf(document.get("uidValidity")),
                longOf(document.get("lastUid")), instant(document.getDate("since")), (int) longOf(document.get("failures")),
                instant(document.getDate("backoffUntil")), document.getString("lastError"), document.getString("budgetDay"),
                document.get("spentUsd") instanceof Number n ? n.doubleValue() : 0.0, instant(document.getDate("updatedAt"))));
    }

    @Override
    public void saveCursor(String integrationId, String folder, long uidValidity, long lastUid, Instant since, Instant now) {
        states.updateOne(Filters.eq("_id", integrationId),
                Updates.combine(Updates.set("folder", folder), Updates.set("uidValidity", uidValidity), Updates.set("lastUid", lastUid),
                        Updates.set("since", since == null ? null : Date.from(since)), Updates.set("failures", 0), Updates.set("backoffUntil", null),
                        Updates.set("lastError", null), Updates.set("updatedAt", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    @Override
    public void recordFailure(String integrationId, int failures, Instant backoffUntil, String lastError, Instant now) {
        states.updateOne(Filters.eq("_id", integrationId),
                Updates.combine(Updates.set("failures", failures), Updates.set("backoffUntil", backoffUntil == null ? null : Date.from(backoffUntil)),
                        Updates.set("lastError", lastError), Updates.set("updatedAt", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    @Override
    public double addSpend(String integrationId, String day, double amountUsd, Instant now) {
        // A pipeline update, so "same day: add; another day: start over" is one atomic
        // document write — two completions on two nodes cannot both read the old total.
        List<Bson> pipeline = List.of(new Document("$set",
                new Document("spentUsd",
                        new Document("$cond",
                                List.of(new Document("$eq", List.of("$budgetDay", day)),
                                        new Document("$add", List.of(new Document("$ifNull", List.of("$spentUsd", 0.0)), amountUsd)), amountUsd)))
                        .append("budgetDay", day).append("updatedAt", Date.from(now))));
        Document after = states.findOneAndUpdate(Filters.eq("_id", integrationId), pipeline,
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return after != null && after.get("spentUsd") instanceof Number n ? n.doubleValue() : amountUsd;
    }

    @Override
    public boolean claimNotice(String integrationId, String noticeKey, Instant now) {
        try {
            notices.insertOne(new Document("_id", integrationId + "|" + noticeKey).append("integrationId", integrationId).append("createdAt",
                    Date.from(now)));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_ERROR_CODE) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void delete(String integrationId) {
        states.deleteOne(Filters.eq("_id", integrationId));
        notices.deleteMany(Filters.eq("integrationId", integrationId));
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
