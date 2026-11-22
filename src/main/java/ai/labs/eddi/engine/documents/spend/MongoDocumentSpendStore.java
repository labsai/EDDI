/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
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
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;

/**
 * MongoDB {@link IDocumentSpendStore}: one document per (kind, subject, day)
 * with {@code _id = <kind>:<day>:<subject>} (neither the kind nor the day
 * contains a colon, so the id is unambiguous).
 * <p>
 * A charge is one {@code findOneAndUpdate} with {@code $inc} and
 * {@code upsert}; the duplicate-key error two callers can get when both create
 * the first bucket of a day is retried once, as in
 * {@code MongoTeamBudgetStore}. The indexes are created on first use, the whole
 * pass bounded by {@link #INDEX_TIMEOUT_SECONDS}; a failure is logged and
 * lookups still work.
 */
@ApplicationScoped
@DefaultBean
public class MongoDocumentSpendStore implements IDocumentSpendStore {

    private static final Logger LOGGER = Logger.getLogger(MongoDocumentSpendStore.class);

    static final String COLLECTION = "document_spend_days";
    static final long INDEX_TIMEOUT_SECONDS = 10;

    private static final String F_KIND = "kind";
    private static final String F_SUBJECT = "subject";
    private static final String F_DAY = "day";
    private static final String F_SPENT = "spentUsd";
    private static final String F_CALLS = "calls";
    private static final String F_EXPIRES = "expiresAt";

    private final MongoCollection<Document> days;
    private volatile boolean indexesAttempted;
    private final AtomicLong nextSweepAtMillis = new AtomicLong();

    @Inject
    public MongoDocumentSpendStore(MongoDatabase database) {
        this.days = database.getCollection(COLLECTION);
    }

    static String id(SubjectKind kind, String subject, long epochDay) {
        return kind.name() + ":" + epochDay + ":" + subject;
    }

    /** Creates the indexes once, bounded in time; never throws. */
    void ensureIndexes() {
        if (indexesAttempted) {
            return;
        }
        synchronized (this) {
            if (indexesAttempted) {
                return;
            }
            indexesAttempted = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS);
            try {
                days.withTimeout(remainingMillis(deadline), TimeUnit.MILLISECONDS).createIndex(Indexes.ascending(F_KIND, F_SUBJECT, F_DAY),
                        new IndexOptions().name("idx_document_spend_subject"));
                days.withTimeout(remainingMillis(deadline), TimeUnit.MILLISECONDS).createIndex(Indexes.ascending(F_DAY),
                        new IndexOptions().name("idx_document_spend_day"));
                days.withTimeout(remainingMillis(deadline), TimeUnit.MILLISECONDS).createIndex(Indexes.ascending(F_EXPIRES),
                        new IndexOptions().name("idx_document_spend_ttl").expireAfter(0L, TimeUnit.SECONDS));
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create the %s indexes; lookups fall back to a scan: %s", COLLECTION, sanitize(e.getMessage()));
            }
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    @Override
    public DaySpend add(SubjectKind kind, String subject, long epochDay, double usd, long oldestRetainedDay) {
        ensureIndexes();
        double amount = Double.isFinite(usd) && usd > 0.0 ? usd : 0.0;
        long retainedDays = Math.max(1L, epochDay - oldestRetainedDay + 1);
        Date expiresAt = Date.from(LocalDate.ofEpochDay(epochDay + retainedDays).atStartOfDay(ZoneOffset.UTC).toInstant());
        Bson filter = eq("_id", id(kind, subject, epochDay));
        Bson update = Updates.combine(Updates.inc(F_SPENT, amount), Updates.inc(F_CALLS, 1L), Updates.setOnInsert(F_KIND, kind.name()),
                Updates.setOnInsert(F_SUBJECT, subject), Updates.setOnInsert(F_DAY, epochDay), Updates.setOnInsert(F_EXPIRES, expiresAt));
        FindOneAndUpdateOptions upsert = new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER);
        Document after;
        try {
            try {
                after = days.findOneAndUpdate(filter, update, upsert);
            } catch (MongoException e) {
                if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                // Two callers created the first bucket of the day at once; the document
                // exists now, so the same update applies to it.
                after = days.findOneAndUpdate(filter, update, upsert);
            }
            days.deleteMany(and(eq(F_KIND, kind.name()), eq(F_SUBJECT, subject), lt(F_DAY, oldestRetainedDay)));
        } catch (MongoException e) {
            throw new DocumentSpendStoreException("Could not record document spend", e);
        }
        sweepIfDue(oldestRetainedDay);
        return toDaySpend(after);
    }

    private void sweepIfDue(long oldestRetainedDay) {
        long now = System.currentTimeMillis();
        long due = nextSweepAtMillis.get();
        if (now >= due && nextSweepAtMillis.compareAndSet(due, now + SWEEP_INTERVAL_MILLIS)) {
            try {
                deleteBefore(oldestRetainedDay);
            } catch (DocumentSpendStoreException e) {
                LOGGER.debugf("Document spend sweep failed; retried later: %s", sanitize(e.getMessage()));
            }
        }
    }

    @Override
    public double spent(SubjectKind kind, String subject, long epochDay) {
        ensureIndexes();
        try {
            Document found = days.find(eq("_id", id(kind, subject, epochDay))).first();
            return found == null ? 0.0 : number(found, F_SPENT);
        } catch (MongoException e) {
            throw new DocumentSpendStoreException("Could not read document spend", e);
        }
    }

    @Override
    public long deleteBySubject(SubjectKind kind, String subject) {
        ensureIndexes();
        try {
            return days.deleteMany(and(eq(F_KIND, kind.name()), eq(F_SUBJECT, subject))).getDeletedCount();
        } catch (MongoException e) {
            throw new DocumentSpendStoreException("Could not delete document spend", e);
        }
    }

    @Override
    public List<DaySpend> findBySubject(SubjectKind kind, String subject, int max) {
        ensureIndexes();
        try {
            List<DaySpend> found = new ArrayList<>();
            for (Document document : days.find(and(eq(F_KIND, kind.name()), eq(F_SUBJECT, subject))).sort(Sorts.descending(F_DAY))
                    .limit(Math.max(0, max))) {
                found.add(toDaySpend(document));
            }
            return found;
        } catch (MongoException e) {
            throw new DocumentSpendStoreException("Could not read document spend", e);
        }
    }

    @Override
    public long deleteBefore(long oldestRetainedDay) {
        ensureIndexes();
        try {
            return days.deleteMany(lt(F_DAY, oldestRetainedDay)).getDeletedCount();
        } catch (MongoException e) {
            throw new DocumentSpendStoreException("Could not delete expired document spend", e);
        }
    }

    private static DaySpend toDaySpend(Document document) {
        return new DaySpend(SubjectKind.valueOf(document.getString(F_KIND)), document.getString(F_SUBJECT),
                document.get(F_DAY, Number.class).longValue(), number(document, F_SPENT),
                document.get(F_CALLS) instanceof Number n ? n.longValue() : 0L);
    }

    private static double number(Document document, String field) {
        return document != null && document.get(field) instanceof Number n ? n.doubleValue() : 0.0;
    }
}
