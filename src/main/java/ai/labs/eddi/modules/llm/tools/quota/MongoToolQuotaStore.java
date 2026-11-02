/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lt;

/**
 * MongoDB implementation of {@link IToolQuotaStore}.
 * <p>
 * One document per (agent, counter, subject, window), with a digest of that
 * tuple as {@code _id}. {@link #tryAcquire} is a single
 * {@code findOneAndUpdate} whose filter carries {@code count < limit}: the
 * server evaluates the filter and applies the increment under the document's
 * lock, so concurrent callers — on any node — cannot overshoot.
 * <p>
 * The upsert has one subtlety. When the document exists and is at the limit the
 * filter does not match, so the server tries to INSERT a document with the same
 * {@code _id} and fails with a duplicate-key error. That error is the "at the
 * limit" answer, not a failure — except in the narrow case of two callers
 * creating the first document of a window at the same instant, where the loser
 * gets the same error although the winner's document still has room. The
 * duplicate-key branch therefore retries once as a plain conditional update (no
 * upsert), which settles both cases correctly.
 * <p>
 * A TTL index on {@code expiresAt} removes old windows; it is a backstop for
 * {@link #deleteExpired}, and nothing relies on it for correctness.
 */
@ApplicationScoped
@DefaultBean
public class MongoToolQuotaStore implements IToolQuotaStore {

    static final String COLLECTION = "toolquotacounters";

    private static final String FIELD_AGENT = "agentId";
    private static final String FIELD_COUNTER = "counter";
    private static final String FIELD_SUBJECT = "subject";
    private static final String FIELD_WINDOW = "windowStart";
    private static final String FIELD_COUNT = "count";
    private static final String FIELD_EXPIRES = "expiresAt";

    private final MongoCollection<Document> counters;

    @Inject
    public MongoToolQuotaStore(MongoDatabase database) {
        this.counters = database.getCollection(COLLECTION);
        // Erasure and export look rows up by subject.
        this.counters.createIndex(Indexes.ascending(FIELD_SUBJECT), new IndexOptions().name("idx_tool_quota_subject").background(true));
        this.counters.createIndex(Indexes.ascending(FIELD_EXPIRES),
                new IndexOptions().name("idx_tool_quota_ttl").expireAfter(0L, TimeUnit.SECONDS).background(true));
    }

    @Override
    public Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt) {
        String id = key.digest();
        Bson belowLimit = and(eq("_id", id), lt(FIELD_COUNT, limit));
        try {
            Document after;
            try {
                after = counters.findOneAndUpdate(belowLimit,
                        Updates.combine(Updates.inc(FIELD_COUNT, 1), Updates.setOnInsert(FIELD_AGENT, key.agentId()),
                                Updates.setOnInsert(FIELD_COUNTER, key.counter()), Updates.setOnInsert(FIELD_SUBJECT, key.subject()),
                                Updates.setOnInsert(FIELD_WINDOW, key.windowStart()),
                                Updates.setOnInsert(FIELD_EXPIRES, Date.from(expiresAt))),
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            } catch (MongoException e) {
                if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                // The document exists. Either it is at the limit (the filter excluded it
                // and the upsert collided) or another caller created it between our filter
                // and our insert. Ask again without the upsert.
                after = counters.findOneAndUpdate(belowLimit, Updates.inc(FIELD_COUNT, 1),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            }
            if (after != null) {
                return new Acquisition(true, after.getInteger(FIELD_COUNT));
            }
            return new Acquisition(false, Math.max(limit, used(key)));
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not count a tool call", e);
        }
    }

    @Override
    public void release(ToolQuotaKey key) {
        try {
            counters.updateOne(and(eq("_id", key.digest()), gt(FIELD_COUNT, 0)), Updates.inc(FIELD_COUNT, -1));
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not release a counted tool call", e);
        }
    }

    @Override
    public int used(ToolQuotaKey key) {
        try {
            Document found = counters.find(eq("_id", key.digest())).first();
            return found == null ? 0 : found.getInteger(FIELD_COUNT, 0);
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not read a tool quota counter", e);
        }
    }

    @Override
    public long deleteBySubject(String subject) {
        try {
            return counters.deleteMany(eq(FIELD_SUBJECT, subject)).getDeletedCount();
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not delete tool quota counters", e);
        }
    }

    @Override
    public List<ToolQuotaUsage> findBySubject(String subject, int max) {
        try {
            List<ToolQuotaUsage> found = new ArrayList<>();
            for (Document document : counters.find(eq(FIELD_SUBJECT, subject)).sort(Sorts.descending(FIELD_WINDOW)).limit(max)) {
                found.add(new ToolQuotaUsage(document.getString(FIELD_AGENT), document.getString(FIELD_COUNTER),
                        document.get(FIELD_WINDOW, Number.class).longValue(), document.getInteger(FIELD_COUNT, 0)));
            }
            return found;
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not read tool quota counters", e);
        }
    }

    @Override
    public int deleteExpired(Instant now) {
        try {
            return (int) counters.deleteMany(lt(FIELD_EXPIRES, Date.from(now))).getDeletedCount();
        } catch (MongoException e) {
            throw new ToolQuotaStoreException("Could not delete expired tool quota counters", e);
        }
    }
}
