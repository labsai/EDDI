/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools.mongo;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IHitlToolJournalStore}. Annotated
 * {@code @DefaultBean} so PostgreSQL can override.
 * <p>
 * {@code tryClaim} relies on the unique compound index on
 * {@code (conversationId, pauseEpoch, callId)} for atomicity: a plain
 * {@code insertOne} either succeeds (this call won the claim) or fails with a
 * duplicate-key error (another attempt already claimed or completed this
 * execution). Only the duplicate-key error (Mongo error code 11000) is caught
 * here and translated to a {@code false} return. Any other write or
 * connectivity failure is NOT a duplicate and must not be conflated with one —
 * it is propagated as an unchecked exception so the caller fails cleanly (and
 * can retry) instead of silently skipping a human-approved tool.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class HitlToolJournalStore implements IHitlToolJournalStore {

    private static final Logger LOGGER = Logger.getLogger(HitlToolJournalStore.class);

    private static final String COLLECTION_JOURNAL = "hitltoolexecutionjournal";

    private static final int RESULT_CAPPED_MAX_BYTES = 32 * 1024;
    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    private static final String CONVERSATION_ID = "conversationId";
    private static final String PAUSE_EPOCH = "pauseEpoch";
    private static final String CALL_ID = "callId";
    private static final String TOOL_NAME = "toolName";
    private static final String STATUS = "status";
    private static final String RESULT_CAPPED = "resultCapped";
    private static final String EXECUTED_AT = "executedAt";
    private static final String DECIDED_BY = "decidedBy";

    private final MongoCollection<Document> collection;

    @Inject
    public HitlToolJournalStore(MongoDatabase database,
            @ConfigProperty(name = "eddi.hitl.tool.journal-retention", defaultValue = "30d") Duration journalRetention) {
        this.collection = database.getCollection(COLLECTION_JOURNAL);

        collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending(CONVERSATION_ID), Indexes.ascending(PAUSE_EPOCH), Indexes.ascending(CALL_ID)),
                new IndexOptions().name("idx_journal_key").unique(true));

        long retentionSeconds = journalRetention == null || journalRetention.isNegative() || journalRetention.isZero()
                ? Duration.ofDays(30).toSeconds()
                : journalRetention.toSeconds();
        collection.createIndex(Indexes.ascending(EXECUTED_AT),
                new IndexOptions().name("idx_journal_ttl").expireAfter(retentionSeconds, TimeUnit.SECONDS));
    }

    @Override
    public boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy) {
        Document doc = new Document()
                .append(CONVERSATION_ID, conversationId)
                .append(PAUSE_EPOCH, pauseEpoch)
                .append(CALL_ID, callId)
                .append(TOOL_NAME, toolName)
                .append(STATUS, Status.EXECUTING.name())
                .append(DECIDED_BY, decidedBy);

        try {
            collection.insertOne(doc);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_ERROR_CODE) {
                LOGGER.infof("HITL tool journal: claim already exists for conversationId=%s pauseEpoch=%s callId=%s — not re-executing",
                        conversationId, pauseEpoch, callId);
                return false;
            }
            // Not a duplicate — a transient write failure must never be conflated
            // with "already claimed". Propagate so the caller fails cleanly and can
            // retry, rather than silently skipping a human-approved tool.
            LOGGER.errorf(e, "HITL tool journal: unexpected write error claiming conversationId=%s pauseEpoch=%s callId=%s",
                    conversationId, pauseEpoch, callId);
            throw e;
        } catch (RuntimeException e) {
            // Any other unexpected failure (e.g. MongoException for connectivity/
            // timeout issues) is likewise not a duplicate and must propagate.
            LOGGER.errorf(e, "HITL tool journal: failed to claim conversationId=%s pauseEpoch=%s callId=%s",
                    conversationId, pauseEpoch, callId);
            throw e;
        }
    }

    @Override
    public void markExecuted(String conversationId, String pauseEpoch, String callId, String resultCapped) {
        String capped = capUtf8(resultCapped, RESULT_CAPPED_MAX_BYTES);
        var filter = and(eq(CONVERSATION_ID, conversationId), eq(PAUSE_EPOCH, pauseEpoch), eq(CALL_ID, callId));
        var update = combine(
                set(STATUS, Status.EXECUTED.name()),
                set(RESULT_CAPPED, capped),
                set(EXECUTED_AT, Instant.now().toEpochMilli()));

        var result = collection.updateOne(filter, update);
        if (result.getMatchedCount() == 0) {
            LOGGER.warnf("HITL tool journal: markExecuted found no claimed entry for conversationId=%s pauseEpoch=%s callId=%s",
                    conversationId, pauseEpoch, callId);
        }
    }

    @Override
    public Optional<JournalEntry> find(String conversationId, String pauseEpoch, String callId) {
        var filter = and(eq(CONVERSATION_ID, conversationId), eq(PAUSE_EPOCH, pauseEpoch), eq(CALL_ID, callId));
        Document doc = collection.find(filter).first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(toEntry(doc));
    }

    private static JournalEntry toEntry(Document doc) {
        String statusRaw = doc.getString(STATUS);
        Status status = statusRaw != null ? Status.valueOf(statusRaw) : Status.EXECUTING;
        Instant executedAt = readEpochMillis(doc, EXECUTED_AT);
        return new JournalEntry(
                doc.getString(CONVERSATION_ID),
                doc.getString(PAUSE_EPOCH),
                doc.getString(CALL_ID),
                doc.getString(TOOL_NAME),
                status,
                doc.getString(RESULT_CAPPED),
                executedAt,
                doc.getString(DECIDED_BY));
    }

    private static Instant readEpochMillis(Document doc, String field) {
        Object val = doc.get(field);
        return val instanceof Number num ? Instant.ofEpochMilli(num.longValue()) : null;
    }

    private static String capUtf8(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        // Truncate on a byte boundary, then trim any trailing partial UTF-8
        // sequence so the result decodes cleanly.
        int end = maxBytes;
        // UTF-8 continuation bytes have the high bits 10xxxxxx (0x80-0xBF).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
