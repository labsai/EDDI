/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples.mongo;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IAuditSampleStore}: collection
 * {@code audit_samples}. Status, version, user and reviewer are top-level
 * fields (they are what queries, compare-and-set and erasure touch); the rest
 * is JSON in {@code data}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class MongoAuditSampleStore implements IAuditSampleStore {

    static final String COLLECTION = "audit_samples";

    static final String ID = "_id";
    static final String AGENT_ID = "agentId";
    static final String CATEGORY = "category";
    static final String STATUS = "status";
    static final String SAMPLED_AT = "sampledAt";
    static final String REVIEW_DUE_AT = "reviewDueAt";
    static final String USER_ID = "userId";
    static final String REVIEWED_BY = "reviewedBy";
    static final String VERSION = "version";
    static final String DATA = "data";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoAuditSampleStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(STATUS), Indexes.ascending(REVIEW_DUE_AT)),
                new IndexOptions().name("idx_sample_status_due"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.ascending(CATEGORY), Indexes.descending(SAMPLED_AT)),
                new IndexOptions().name("idx_sample_agent_category"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.ascending(STATUS), Indexes.descending(SAMPLED_AT)),
                new IndexOptions().name("idx_sample_agent_status"));
        collection.createIndex(Indexes.ascending(USER_ID), new IndexOptions().name("idx_sample_user").sparse(true));
        collection.createIndex(Indexes.ascending(REVIEWED_BY), new IndexOptions().name("idx_sample_reviewer").sparse(true));
    }

    @Override
    public void insert(AuditSample sample) {
        collection.insertOne(toDocument(sample));
    }

    @Override
    public Optional<AuditSample> read(String id) {
        Document doc = collection.find(eq(ID, id)).first();
        return Optional.ofNullable(doc).map(MongoAuditSampleStore::fromDocument);
    }

    @Override
    public List<AuditSample> query(SampleFilter filter, int skip, int limit) {
        Bson sort = filter.status() == AuditSample.Status.PENDING
                ? Sorts.orderBy(Sorts.ascending(REVIEW_DUE_AT), Sorts.ascending(ID))
                : Sorts.orderBy(Sorts.descending(SAMPLED_AT), Sorts.descending(ID));
        List<AuditSample> rows = new ArrayList<>();
        for (Document doc : collection.find(toBson(filter)).sort(sort).skip(Math.max(0, skip)).limit(Math.max(0, limit))) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public long count(SampleFilter filter) {
        return collection.countDocuments(toBson(filter));
    }

    @Override
    public Optional<AuditSample> review(AuditSample reviewed, long expectedVersion) {
        Bson filter = and(eq(ID, reviewed.id()), eq(VERSION, expectedVersion), eq(STATUS, AuditSample.Status.PENDING.name()));
        Bson update = combine(set(STATUS, reviewed.status().name()), inc(VERSION, 1L), set(REVIEWED_BY, reviewed.reviewedBy()),
                set(DATA, OversightJson.write(reviewed.payload())));
        Document after = collection.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(after).map(MongoAuditSampleStore::fromDocument);
    }

    @Override
    public long expireOverdue(Instant now) {
        return collection.updateMany(and(eq(STATUS, AuditSample.Status.PENDING.name()), lt(REVIEW_DUE_AT, now.toEpochMilli())),
                combine(set(STATUS, AuditSample.Status.EXPIRED.name()), inc(VERSION, 1L))).getModifiedCount();
    }

    @Override
    public long deleteSampledBefore(Instant cutoff) {
        return collection.deleteMany(lt(SAMPLED_AT, cutoff.toEpochMilli())).getDeletedCount();
    }

    @Override
    public List<AuditSample> readByUser(String userId, int limit) {
        List<AuditSample> rows = new ArrayList<>();
        for (Document doc : collection.find(eq(USER_ID, userId)).limit(Math.max(0, limit))) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public long deleteByUser(String userId) {
        return collection.deleteMany(eq(USER_ID, userId)).getDeletedCount();
    }

    @Override
    public long pseudonymizeReviewer(String userId, String pseudonym) {
        return collection.updateMany(eq(REVIEWED_BY, userId), set(REVIEWED_BY, pseudonym)).getModifiedCount();
    }

    public static Document toDocument(AuditSample s) {
        Document doc = new Document(ID, s.id());
        doc.put(AGENT_ID, s.agentId());
        doc.put(CATEGORY, s.category());
        doc.put(STATUS, s.status().name());
        doc.put(SAMPLED_AT, s.sampledAt().toEpochMilli());
        doc.put(REVIEW_DUE_AT, s.reviewDueAt().toEpochMilli());
        doc.put(USER_ID, s.userId());
        doc.put(REVIEWED_BY, s.reviewedBy());
        doc.put(VERSION, s.version());
        doc.put(DATA, OversightJson.write(s.payload()));
        return doc;
    }

    public static AuditSample fromDocument(Document doc) {
        AuditSample payload = OversightJson.read(doc.getString(DATA), AuditSample.class);
        Number version = doc.get(VERSION, Number.class);
        return payload.withColumns(doc.getString(USER_ID), doc.getString(REVIEWED_BY), AuditSample.Status.valueOf(doc.getString(STATUS)),
                version != null ? version.longValue() : 0L);
    }

    static Bson toBson(SampleFilter f) {
        List<Bson> parts = new ArrayList<>();
        if (f.agentId() != null) {
            parts.add(eq(AGENT_ID, f.agentId()));
        }
        if (f.category() != null) {
            parts.add(eq(CATEGORY, f.category()));
        }
        if (f.status() != null) {
            parts.add(eq(STATUS, f.status().name()));
        }
        if (f.from() != null) {
            parts.add(gte(SAMPLED_AT, f.from().toEpochMilli()));
        }
        if (f.to() != null) {
            parts.add(lt(SAMPLED_AT, f.to().toEpochMilli()));
        }
        return parts.isEmpty() ? new Document() : and(parts);
    }
}
