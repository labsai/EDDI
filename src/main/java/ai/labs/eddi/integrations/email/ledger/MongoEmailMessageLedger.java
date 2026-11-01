/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB implementation of {@link IEmailMessageLedger}.
 * <p>
 * Two unique indexes carry the exactly-once rules — {@code idempotencyKey}, and
 * {@code (integrationId, direction, messageId)} — so {@link #insertIfAbsent} is
 * a plain insert that loses on a duplicate key. A claim is one
 * {@code findOneAndUpdate} per row whose filter requires the row to be pending
 * and due: the first sweeper's update moves {@code nextAttemptAt} past
 * {@code now}, so the second one's filter no longer matches. A TTL index on
 * {@code expiresAt} backs up the explicit retention sweep.
 */
@ApplicationScoped
@DefaultBean
public class MongoEmailMessageLedger implements IEmailMessageLedger {

    static final String COLLECTION = "email_message_ledger";

    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    static final String F_ID = "_id";
    static final String F_INTEGRATION = "integrationId";
    static final String F_KIND = "kind";
    static final String F_DIRECTION = "direction";
    static final String F_MESSAGE_ID = "messageId";
    static final String F_CONVERSATION = "conversationId";
    static final String F_SOURCE = "sourceRef";
    static final String F_IDEMPOTENCY = "idempotencyKey";
    static final String F_RECIPIENT = "recipient";
    static final String F_STATE = "state";
    static final String F_ATTEMPTS = "attempts";
    static final String F_NEXT = "nextAttemptAt";
    static final String F_CLAIM = "claimToken";
    static final String F_PROVIDER = "providerId";
    static final String F_ERROR = "lastError";
    static final String F_CREATED = "createdAt";
    static final String F_UPDATED = "updatedAt";
    static final String F_EXPIRES = "expiresAt";

    private final MongoCollection<Document> rows;

    @Inject
    public MongoEmailMessageLedger(MongoDatabase database) {
        this.rows = database.getCollection(COLLECTION);
        rows.createIndex(Indexes.ascending(F_IDEMPOTENCY), new IndexOptions().name("idx_email_ledger_idempotency").unique(true));
        rows.createIndex(Indexes.compoundIndex(Indexes.ascending(F_INTEGRATION), Indexes.ascending(F_DIRECTION), Indexes.ascending(F_MESSAGE_ID)),
                new IndexOptions().name("idx_email_ledger_message").unique(true));
        rows.createIndex(Indexes.compoundIndex(Indexes.ascending(F_STATE), Indexes.ascending(F_NEXT)),
                new IndexOptions().name("idx_email_ledger_due"));
        rows.createIndex(Indexes.ascending(F_RECIPIENT), new IndexOptions().name("idx_email_ledger_recipient"));
        rows.createIndex(Indexes.compoundIndex(Indexes.ascending(F_RECIPIENT), Indexes.ascending(F_KIND), Indexes.ascending(F_CREATED)),
                new IndexOptions().name("idx_email_ledger_rate"));
        rows.createIndex(Indexes.ascending(F_EXPIRES), new IndexOptions().name("idx_email_ledger_ttl").expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public boolean insertIfAbsent(LedgerEntry entry) {
        try {
            rows.insertOne(toDocument(entry));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_ERROR_CODE) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<LedgerEntry> findById(String id) {
        return Optional.ofNullable(rows.find(Filters.eq(F_ID, id)).first()).map(MongoEmailMessageLedger::toEntry);
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(rows.find(Filters.eq(F_IDEMPOTENCY, idempotencyKey)).first()).map(MongoEmailMessageLedger::toEntry);
    }

    @Override
    public List<LedgerEntry> claimDue(Kind kind, Instant now, Instant leaseUntil, int limit) {
        List<LedgerEntry> claimed = new ArrayList<>();
        Bson due = Filters.and(Filters.eq(F_STATE, State.PENDING.name()), Filters.eq(F_KIND, kind.name()), Filters.lte(F_NEXT, Date.from(now)));
        var options = new FindOneAndUpdateOptions().sort(Sorts.ascending(F_NEXT)).returnDocument(ReturnDocument.AFTER);
        for (int i = 0; i < limit; i++) {
            Bson claim = Updates.combine(Updates.set(F_CLAIM, UUID.randomUUID().toString()), Updates.set(F_NEXT, Date.from(leaseUntil)),
                    Updates.inc(F_ATTEMPTS, 1), Updates.set(F_UPDATED, Date.from(now)));
            Document document = rows.findOneAndUpdate(due, claim, options);
            if (document == null) {
                break;
            }
            claimed.add(toEntry(document));
        }
        return claimed;
    }

    @Override
    public boolean complete(String id, String claimToken, State terminal, String providerId, String lastError, Instant now) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("complete() needs a terminal state, not " + terminal);
        }
        var result = rows.updateOne(claimed(id, claimToken),
                Updates.combine(Updates.set(F_STATE, terminal.name()), Updates.set(F_PROVIDER, IEmailMessageLedger.cap(providerId, MAX_PROVIDER_ID)),
                        Updates.set(F_ERROR, IEmailMessageLedger.cap(lastError, MAX_LAST_ERROR)),
                        Updates.set(F_CLAIM, null), Updates.set(F_UPDATED, Date.from(now))));
        return result.getModifiedCount() == 1;
    }

    @Override
    public boolean reschedule(String id, String claimToken, Instant nextAttemptAt, String lastError, Instant now) {
        var result = rows.updateOne(claimed(id, claimToken), Updates.combine(Updates.set(F_NEXT, Date.from(nextAttemptAt)),
                Updates.set(F_ERROR, IEmailMessageLedger.cap(lastError, MAX_LAST_ERROR)), Updates.set(F_CLAIM, null),
                Updates.set(F_UPDATED, Date.from(now))));
        return result.getModifiedCount() == 1;
    }

    private static Bson claimed(String id, String claimToken) {
        return Filters.and(Filters.eq(F_ID, id), Filters.eq(F_STATE, State.PENDING.name()), Filters.eq(F_CLAIM, claimToken));
    }

    @Override
    public long countCreatedSince(String recipient, Kind kind, Instant since) {
        return rows.countDocuments(Filters.and(Filters.eq(F_RECIPIENT, recipient), Filters.eq(F_KIND, kind.name()),
                Filters.gte(F_CREATED, Date.from(since)), Filters.ne(F_STATE, State.SUPPRESSED.name())));
    }

    @Override
    public long countPending() {
        return rows.countDocuments(Filters.eq(F_STATE, State.PENDING.name()));
    }

    @Override
    public long deleteExpired(Instant now) {
        return rows.deleteMany(Filters.lte(F_EXPIRES, Date.from(now))).getDeletedCount();
    }

    @Override
    public List<LedgerEntry> findByRecipient(String recipient) {
        List<LedgerEntry> result = new ArrayList<>();
        for (Document document : rows.find(Filters.eq(F_RECIPIENT, recipient))) {
            result.add(toEntry(document));
        }
        return result;
    }

    @Override
    public long deleteByRecipient(String recipient) {
        return rows.deleteMany(Filters.eq(F_RECIPIENT, recipient)).getDeletedCount();
    }

    static Document toDocument(LedgerEntry entry) {
        return new Document(F_ID, entry.id()).append(F_INTEGRATION, entry.integrationId()).append(F_KIND, entry.kind().name())
                .append(F_DIRECTION, entry.direction().name()).append(F_MESSAGE_ID, entry.messageId()).append(F_CONVERSATION, entry.conversationId())
                .append(F_SOURCE, entry.sourceRef()).append(F_IDEMPOTENCY, entry.idempotencyKey()).append(F_RECIPIENT, entry.recipient())
                .append(F_STATE, entry.state().name()).append(F_ATTEMPTS, entry.attempts()).append(F_NEXT, date(entry.nextAttemptAt()))
                .append(F_CLAIM, entry.claimToken()).append(F_PROVIDER, IEmailMessageLedger.cap(entry.providerId(), MAX_PROVIDER_ID))
                .append(F_ERROR, IEmailMessageLedger.cap(entry.lastError(), MAX_LAST_ERROR))
                .append(F_CREATED, date(entry.createdAt())).append(F_UPDATED, date(entry.updatedAt())).append(F_EXPIRES, date(entry.expiresAt()));
    }

    static LedgerEntry toEntry(Document document) {
        Integer attempts = document.getInteger(F_ATTEMPTS);
        return new LedgerEntry(document.getString(F_ID), document.getString(F_INTEGRATION), Kind.valueOf(document.getString(F_KIND)),
                Direction.valueOf(document.getString(F_DIRECTION)), document.getString(F_MESSAGE_ID), document.getString(F_CONVERSATION),
                document.getString(F_SOURCE), document.getString(F_IDEMPOTENCY), document.getString(F_RECIPIENT),
                State.valueOf(document.getString(F_STATE)), attempts == null ? 0 : attempts, instant(document.getDate(F_NEXT)),
                document.getString(F_CLAIM), document.getString(F_PROVIDER), document.getString(F_ERROR), instant(document.getDate(F_CREATED)),
                instant(document.getDate(F_UPDATED)), instant(document.getDate(F_EXPIRES)));
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
