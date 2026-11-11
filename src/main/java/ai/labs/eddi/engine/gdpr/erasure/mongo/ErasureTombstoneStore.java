/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure.mongo;

import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.max;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * MongoDB implementation of {@link IErasureTombstoneStore}: tombstones in
 * {@code erasuretombstones} (a TTL index on {@code expiresAt} removes expired
 * ones; {@link #erasedAt} ignores them before that), pending removals in
 * {@code erasurependingremovals}, the salt in {@code erasuremeta}.
 */
@ApplicationScoped
@DefaultBean
public class ErasureTombstoneStore implements IErasureTombstoneStore {

    static final String COLLECTION_TOMBSTONES = "erasuretombstones";
    static final String COLLECTION_PENDING = "erasurependingremovals";
    static final String COLLECTION_META = "erasuremeta";
    private static final String SALT_ID = "tombstone-salt";

    private final MongoCollection<Document> tombstones;
    private final MongoCollection<Document> pending;
    private final MongoCollection<Document> meta;

    @Inject
    public ErasureTombstoneStore(MongoDatabase database) {
        this.tombstones = database.getCollection(COLLECTION_TOMBSTONES);
        this.pending = database.getCollection(COLLECTION_PENDING);
        this.meta = database.getCollection(COLLECTION_META);
        tombstones.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().name("idx_erasuretombstone_ttl").expireAfter(0L, TimeUnit.SECONDS));
        pending.createIndex(Indexes.ascending("userKey"),
                new IndexOptions().name("idx_erasurepending_user").partialFilterExpression(exists("userKey", true)));
        pending.createIndex(Indexes.ascending("conversationId"),
                new IndexOptions().name("idx_erasurepending_conversation").partialFilterExpression(exists("conversationId", true)));
        pending.createIndex(Indexes.ascending("createdAt"), new IndexOptions().name("idx_erasurepending_created"));
    }

    private static String id(Kind kind, String key) {
        return kind.name() + ":" + key;
    }

    @Override
    public void putTombstones(Kind kind, Collection<String> keys, Instant erasedAt, Instant expiresAt) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> writes = new ArrayList<>(keys.size());
        for (String key : keys) {
            Bson update = combine(setOnInsert("kind", kind.name()), max("erasedAt", Date.from(erasedAt)), max("expiresAt", Date.from(expiresAt)));
            writes.add(new UpdateOneModel<>(eq("_id", id(kind, key)), update, new UpdateOptions().upsert(true)));
        }
        tombstones.bulkWrite(writes);
    }

    @Override
    public Optional<Instant> erasedAt(Kind kind, String key, Instant now) {
        Document found = tombstones.find(and(eq("_id", id(kind, key)), gt("expiresAt", Date.from(now)))).first();
        return found == null ? Optional.empty() : Optional.ofNullable(found.getDate("erasedAt")).map(Date::toInstant);
    }

    @Override
    public long deleteExpiredTombstones(Instant now) {
        return tombstones.deleteMany(lte("expiresAt", Date.from(now))).getDeletedCount();
    }

    @Override
    public String salt(String candidate) {
        Document existing = meta.find(eq("_id", SALT_ID)).first();
        if (existing != null) {
            return existing.getString("value");
        }
        try {
            meta.insertOne(new Document("_id", SALT_ID).append("value", candidate));
            return candidate;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            // Another node wrote it first: adopt that one.
            return meta.find(eq("_id", SALT_ID)).first().getString("value");
        }
    }

    @Override
    public void addPendingRemoval(PendingRemoval removal) {
        Document doc = new Document("writer", removal.writer()).append("rowId", removal.rowId()).append("createdAt",
                Date.from(removal.createdAt()));
        if (removal.userKey() != null) {
            doc.append("userKey", removal.userKey());
        }
        if (removal.conversationId() != null) {
            doc.append("conversationId", removal.conversationId());
        }
        Bson update = new Document("$setOnInsert", doc);
        pending.updateOne(eq("_id", removal.writer() + ":" + removal.rowId()), update, new UpdateOptions().upsert(true));
    }

    @Override
    public List<PendingRemoval> pendingRemovals(String writer, String userKey, String conversationId, int limit) {
        List<PendingRemoval> found = new ArrayList<>();
        pending.find(filter(writer, userKey, conversationId)).sort(Sorts.ascending("createdAt", "_id")).limit(limit)
                .forEach(doc -> found.add(new PendingRemoval(doc.getString("writer"), doc.getString("rowId"), doc.getString("userKey"),
                        doc.getString("conversationId"), doc.getDate("createdAt").toInstant())));
        return found;
    }

    @Override
    public long countPendingRemovals(String writer, String userKey, String conversationId) {
        return pending.countDocuments(filter(writer, userKey, conversationId));
    }

    @Override
    public boolean removePendingRemoval(String writer, String rowId) {
        return pending.deleteOne(eq("_id", writer + ":" + rowId)).getDeletedCount() > 0;
    }

    private static Bson filter(String writer, String userKey, String conversationId) {
        List<Bson> scope = new ArrayList<>();
        if (userKey != null) {
            scope.add(eq("userKey", userKey));
        }
        if (conversationId != null) {
            scope.add(eq("conversationId", conversationId));
        }
        List<Bson> clauses = new ArrayList<>();
        if (writer != null) {
            clauses.add(eq("writer", writer));
        }
        if (!scope.isEmpty()) {
            clauses.add(scope.size() == 1 ? scope.get(0) : or(scope));
        }
        return clauses.isEmpty() ? new Document() : and(clauses);
    }
}
