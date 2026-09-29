/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB implementation of {@link ISlackApprovalRecordStore}.
 * <p>
 * A unique index on {@code (integrationName, subject, pauseEpoch)} makes
 * {@link #tryRecord} atomic, and a TTL index on {@code expiresAt} is the
 * cleanup backstop. Reads still compare {@code expiresAt} themselves: the TTL
 * monitor runs about once a minute, and "usually deleted by now" is not an
 * authorization property.
 */
@ApplicationScoped
@DefaultBean
public class MongoSlackApprovalRecordStore implements ISlackApprovalRecordStore {

    static final String COLLECTION = "slack_hitl_approval_records";

    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    private static final String FIELD_INTEGRATION = "integrationName";
    private static final String FIELD_SUBJECT = "subject";
    private static final String FIELD_PAUSE = "pauseEpoch";
    private static final String FIELD_CARD = "cardId";
    private static final String FIELD_CHANNEL = "approvalChannelId";
    private static final String FIELD_CREATED = "createdAt";
    private static final String FIELD_EXPIRES = "expiresAt";

    private final MongoCollection<Document> records;
    private final Duration retention;

    @Inject
    public MongoSlackApprovalRecordStore(MongoDatabase database,
            @ConfigProperty(name = "eddi.slack.hitl.approval-record-retention", defaultValue = "30d") Duration retention) {
        this.records = database.getCollection(COLLECTION);
        this.retention = SlackApprovalRecordRetention.sanitize(retention);
        records.createIndex(
                Indexes.compoundIndex(Indexes.ascending(FIELD_INTEGRATION), Indexes.ascending(FIELD_SUBJECT),
                        Indexes.ascending(FIELD_PAUSE)),
                new IndexOptions().name("idx_slack_approval_key").unique(true));
        records.createIndex(Indexes.ascending(FIELD_EXPIRES),
                new IndexOptions().name("idx_slack_approval_ttl").expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public boolean tryRecord(String integrationName, String subject, String pauseEpoch, String cardId,
                             String approvalChannelId) {
        Instant now = Instant.now();
        // Upsert whose filter only matches an EXPIRED record: an expired record is
        // replaced in place (→ true), no record is inserted (→ true), and a LIVE
        // record does not match the filter, so the upsert attempts an insert that
        // the unique index rejects (→ duplicate key → false). One round trip, no
        // read-then-write window.
        var filter = Filters.and(
                Filters.eq(FIELD_INTEGRATION, integrationName),
                Filters.eq(FIELD_SUBJECT, subject),
                Filters.eq(FIELD_PAUSE, pauseEpoch),
                Filters.lte(FIELD_EXPIRES, Date.from(now)));
        var update = Updates.combine(
                Updates.set(FIELD_CARD, cardId),
                Updates.set(FIELD_CHANNEL, approvalChannelId),
                Updates.set(FIELD_CREATED, Date.from(now)),
                Updates.set(FIELD_EXPIRES, Date.from(now.plus(retention))));
        try {
            records.updateOne(filter, update, new UpdateOptions().upsert(true));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_ERROR_CODE) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<SlackApprovalRecord> findBySubject(String integrationName, String subject) {
        var filter = Filters.and(
                Filters.eq(FIELD_INTEGRATION, integrationName),
                Filters.eq(FIELD_SUBJECT, subject),
                Filters.gt(FIELD_EXPIRES, Date.from(Instant.now())));
        List<SlackApprovalRecord> result = new ArrayList<>();
        for (Document document : records.find(filter)) {
            result.add(toRecord(document));
        }
        return result;
    }

    @Override
    public void delete(String integrationName, String subject, String pauseEpoch) {
        records.deleteOne(Filters.and(
                Filters.eq(FIELD_INTEGRATION, integrationName),
                Filters.eq(FIELD_SUBJECT, subject),
                Filters.eq(FIELD_PAUSE, pauseEpoch)));
    }

    private static SlackApprovalRecord toRecord(Document document) {
        return new SlackApprovalRecord(
                document.getString(FIELD_INTEGRATION),
                document.getString(FIELD_SUBJECT),
                document.getString(FIELD_PAUSE),
                document.getString(FIELD_CARD),
                document.getString(FIELD_CHANNEL),
                toInstant(document.getDate(FIELD_CREATED)),
                toInstant(document.getDate(FIELD_EXPIRES)));
    }

    private static Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
