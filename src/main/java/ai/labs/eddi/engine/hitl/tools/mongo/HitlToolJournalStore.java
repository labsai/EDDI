/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools.mongo;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.conversions.Bson;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
    private static final int INDEX_OPTIONS_CONFLICT_ERROR_CODE = 85;

    private static final String KEY_INDEX_NAME = "idx_journal_key";
    private static final String LEGACY_TTL_INDEX_NAME = "idx_journal_ttl";
    private static final String TTL_INDEX_NAME = "idx_journal_ttl_claimed";

    private static final String CONVERSATION_ID = "conversationId";
    private static final String PAUSE_EPOCH = "pauseEpoch";
    private static final String CALL_ID = "callId";
    private static final String TOOL_NAME = "toolName";
    private static final String STATUS = "status";
    private static final String RESULT_CAPPED = "resultCapped";
    private static final String EXECUTED_AT = "executedAt";
    private static final String CLAIMED_AT = "claimedAt";
    private static final String DECIDED_BY = "decidedBy";

    private final MongoCollection<Document> collection;

    @Inject
    public HitlToolJournalStore(MongoDatabase database,
            @ConfigProperty(name = "eddi.hitl.tool.journal-retention", defaultValue = "30d") Duration journalRetention) {
        this.collection = database.getCollection(COLLECTION_JOURNAL);

        collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending(CONVERSATION_ID), Indexes.ascending(PAUSE_EPOCH), Indexes.ascending(CALL_ID)),
                new IndexOptions().name(KEY_INDEX_NAME).unique(true));

        long retentionSeconds = journalRetention == null || journalRetention.isNegative() || journalRetention.isZero()
                ? Duration.ofDays(30).toSeconds()
                : journalRetention.toSeconds();

        // Drop the legacy TTL index that anchored on executedAt (stored as int64,
        // which the TTL monitor ignores, and which crash-orphaned EXECUTING claims
        // never even got). Absent on fresh deployments, so ignore a missing index.
        dropIndexIfPresent(LEGACY_TTL_INDEX_NAME);

        // Anchor the retention TTL on claimedAt (a BSON Date set on every entry in
        // tryClaim), so BOTH completed entries and crash-orphaned EXECUTING claims
        // expire after the retention window.
        createTtlIndexWithConflictRetry(retentionSeconds);
    }

    /**
     * Create the claimedAt TTL index, recreating it if the retention value changed
     * since last startup. Mongo raises IndexOptionsConflict (error code 85) when an
     * index with the same name/key already exists but with different options (here,
     * a different {@code expireAfterSeconds}); in that case drop and recreate with
     * the new retention.
     */
    private void createTtlIndexWithConflictRetry(long retentionSeconds) {
        Bson ttlKey = Indexes.ascending(CLAIMED_AT);
        IndexOptions ttlOptions = new IndexOptions().name(TTL_INDEX_NAME).expireAfter(retentionSeconds, TimeUnit.SECONDS);
        try {
            collection.createIndex(ttlKey, ttlOptions);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == INDEX_OPTIONS_CONFLICT_ERROR_CODE) {
                LOGGER.infof("HITL tool journal: TTL retention changed — recreating %s with expireAfterSeconds=%d",
                        TTL_INDEX_NAME, retentionSeconds);
                dropIndexIfPresent(TTL_INDEX_NAME);
                collection.createIndex(ttlKey, ttlOptions);
            } else {
                throw e;
            }
        }
    }

    private void dropIndexIfPresent(String indexName) {
        try {
            collection.dropIndex(indexName);
        } catch (RuntimeException e) {
            // Index absent (fresh deployment) — nothing to drop.
            LOGGER.debugf("HITL tool journal: index %s not present to drop (%s)", indexName, e.getMessage());
        }
    }

    @Override
    public boolean tryClaim(String conversationId, String pauseEpoch, String callId, String toolName, String decidedBy) {
        Document doc = new Document()
                .append(CONVERSATION_ID, conversationId)
                .append(PAUSE_EPOCH, pauseEpoch)
                .append(CALL_ID, callId)
                .append(TOOL_NAME, toolName)
                .append(STATUS, Status.EXECUTING.name())
                // BSON Date so the TTL monitor honors it. Set on EVERY entry (not
                // just executed ones) so crash-orphaned EXECUTING claims also expire.
                .append(CLAIMED_AT, Date.from(Instant.now()))
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
                // BSON Date (not epoch millis) so it reads back as a real timestamp.
                set(EXECUTED_AT, Date.from(Instant.now())));

        var result = collection.updateOne(filter, update);
        if (result.getMatchedCount() == 0) {
            LOGGER.warnf("HITL tool journal: markExecuted found no claimed entry for conversationId=%s pauseEpoch=%s callId=%s",
                    conversationId, pauseEpoch, callId);
        }
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return collection.deleteMany(eq(CONVERSATION_ID, conversationId)).getDeletedCount();
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
        if (val instanceof Date date) {
            return date.toInstant();
        }
        // Backward-compat: pre-existing rows stored executedAt as int64 epoch millis.
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
