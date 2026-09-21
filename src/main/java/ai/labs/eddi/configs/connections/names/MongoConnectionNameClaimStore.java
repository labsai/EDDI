/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import com.mongodb.ErrorCategory;
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
import org.bson.conversions.Bson;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IConnectionNameClaimStore}.
 * <p>
 * The unique compound index on {@code (tenantId, name)} is the whole guarantee;
 * every method below is a single conditional write against it.
 * <p>
 * Timestamps come from the <b>server</b> clock ({@code $currentDate},
 * {@code $$NOW}), so replicas with skewed clocks agree on when a claim went
 * stale. {@code $$NOW} needs MongoDB 4.2 or later; EDDI supports 6.0+.
 */
@ApplicationScoped
@DefaultBean
public class MongoConnectionNameClaimStore implements IConnectionNameClaimStore {

    static final String COLLECTION = "connection_name_claims";

    private static final String FIELD_TENANT = "tenantId";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_CONNECTION_ID = "connectionId";
    private static final String FIELD_CLAIMED_AT = "claimedAt";

    private final MongoCollection<Document> claims;

    @Inject
    public MongoConnectionNameClaimStore(MongoDatabase database) {
        this.claims = database.getCollection(COLLECTION);
        this.claims.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT), Indexes.ascending(FIELD_NAME)),
                new IndexOptions().name("idx_connection_name_claim_tenant_name").unique(true).background(true));
    }

    private static Bson key(String tenantId, String name) {
        return Filters.and(Filters.eq(FIELD_TENANT, tenantId), Filters.eq(FIELD_NAME, name));
    }

    @Override
    public boolean claim(String tenantId, String name, String token) {
        // An upsert whose filter includes the fresh token can never match an
        // existing claim, so it always inserts — and the unique index turns a second
        // claim on the same name into a duplicate-key error. An upsert rather than
        // insertOne only so that claimedAt can come from the server clock.
        try {
            claims.updateOne(Filters.and(key(tenantId, name), Filters.eq(FIELD_TOKEN, token)),
                    Updates.combine(Updates.setOnInsert(FIELD_CONNECTION_ID, null), Updates.currentDate(FIELD_CLAIMED_AT)),
                    new UpdateOptions().upsert(true));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<NameClaim> find(String tenantId, String name) {
        Document document = claims.find(key(tenantId, name)).first();
        return Optional.ofNullable(document).map(found -> new NameClaim(found.getString(FIELD_TENANT), found.getString(FIELD_NAME),
                found.getString(FIELD_TOKEN), found.getString(FIELD_CONNECTION_ID)));
    }

    @Override
    public boolean takeOver(NameClaim expected, String newToken, Duration staleAfter) {
        Bson holder;
        if (expected.connectionId() == null) {
            // Stale by the server's clock: claimedAt < $$NOW - staleAfter.
            Bson olderThanBound = Filters.expr(
                    new Document("$lt", List.of("$" + FIELD_CLAIMED_AT, new Document("$subtract", List.of("$$NOW", staleAfter.toMillis())))));
            holder = Filters.and(Filters.eq(FIELD_CONNECTION_ID, null), olderThanBound);
        } else {
            holder = Filters.eq(FIELD_CONNECTION_ID, expected.connectionId());
        }
        Bson filter = Filters.and(key(expected.tenantId(), expected.name()), Filters.eq(FIELD_TOKEN, expected.token()), holder);
        Bson update = Updates.combine(Updates.set(FIELD_TOKEN, newToken), Updates.set(FIELD_CONNECTION_ID, null),
                Updates.currentDate(FIELD_CLAIMED_AT));
        // matchedCount: matching the filter is what winning the compare-and-set means.
        return claims.updateOne(filter, update).getMatchedCount() == 1;
    }

    @Override
    public boolean recordConnection(String tenantId, String name, String token, String connectionId) {
        return claims.updateOne(Filters.and(key(tenantId, name), Filters.eq(FIELD_TOKEN, token)), Updates.set(FIELD_CONNECTION_ID, connectionId))
                .getMatchedCount() == 1;
    }

    @Override
    public boolean release(String tenantId, String name, String token) {
        return claims.deleteOne(Filters.and(key(tenantId, name), Filters.eq(FIELD_TOKEN, token))).getDeletedCount() > 0;
    }

    @Override
    public boolean releaseConnection(String tenantId, String name, String connectionId) {
        return claims.deleteOne(Filters.and(key(tenantId, name), Filters.eq(FIELD_CONNECTION_ID, connectionId))).getDeletedCount() > 0;
    }
}
