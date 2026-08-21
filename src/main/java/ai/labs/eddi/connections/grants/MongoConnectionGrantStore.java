/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IConnectionGrantStore}.
 * <p>
 * The two conditional updates below are the ones that matter; everything else
 * is ordinary document mapping.
 */
@ApplicationScoped
@DefaultBean
public class MongoConnectionGrantStore implements IConnectionGrantStore {

    static final String COLLECTION = "connection_grants";

    private static final String FIELD_TENANT = "tenantId";
    private static final String FIELD_CONNECTION = "connectionName";
    private static final String FIELD_PRINCIPAL = "principal";
    private static final String FIELD_ACCESS = "encryptedAccessToken";
    private static final String FIELD_ACCESS_IV = "accessTokenIv";
    private static final String FIELD_REFRESH = "encryptedRefreshToken";
    private static final String FIELD_REFRESH_IV = "refreshTokenIv";
    private static final String FIELD_DEK = "dekId";
    private static final String FIELD_EXPIRES = "expiresAt";
    private static final String FIELD_SCOPES = "scopes";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_CREATED = "createdAt";
    private static final String FIELD_UPDATED = "updatedAt";
    private static final String FIELD_LAST_REFRESH = "lastRefreshAt";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_REFRESH_IN_PROGRESS = "refreshInProgress";
    private static final String FIELD_LEASE_EXPIRES = "refreshLeaseExpiresAt";

    private final MongoCollection<Document> grants;

    @Inject
    public MongoConnectionGrantStore(MongoDatabase database) {
        this.grants = database.getCollection(COLLECTION);
        this.grants.createIndex(
                Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT), Indexes.ascending(FIELD_CONNECTION), Indexes.ascending(FIELD_PRINCIPAL)),
                new IndexOptions().name("idx_grant_tenant_connection_principal").unique(true).background(true));
        this.grants.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT), Indexes.ascending(FIELD_PRINCIPAL)),
                new IndexOptions().name("idx_grant_tenant_principal").background(true));
    }

    private static Bson key(String tenantId, String connectionName, String principal) {
        return Filters.and(Filters.eq(FIELD_TENANT, tenantId), Filters.eq(FIELD_CONNECTION, connectionName),
                Filters.eq(FIELD_PRINCIPAL, principal));
    }

    @Override
    public Optional<ConnectionGrant> find(String tenantId, String connectionName, String principal) {
        Document document = grants.find(key(tenantId, connectionName, principal)).first();
        return Optional.ofNullable(document).map(MongoConnectionGrantStore::toGrant);
    }

    @Override
    public void upsert(ConnectionGrant grant) {
        Instant now = Instant.now();
        Bson update = Updates.combine(Updates.set(FIELD_ACCESS, grant.getEncryptedAccessToken()),
                Updates.set(FIELD_ACCESS_IV, grant.getAccessTokenIv()), Updates.set(FIELD_REFRESH, grant.getEncryptedRefreshToken()),
                Updates.set(FIELD_REFRESH_IV, grant.getRefreshTokenIv()), Updates.set(FIELD_DEK, grant.getDekId()),
                Updates.set(FIELD_EXPIRES, toDate(grant.getExpiresAt())), Updates.set(FIELD_SCOPES, grant.getScopes()),
                Updates.set(FIELD_STATUS, grant.getStatus() == null ? ConnectionGrant.Status.ACTIVE.name() : grant.getStatus().name()),
                Updates.set(FIELD_UPDATED, Date.from(now)), Updates.setOnInsert(FIELD_CREATED, Date.from(now)),
                Updates.set(FIELD_LAST_REFRESH, toDate(grant.getLastRefreshAt())),
                // The lease is cleared on any upsert: a fresh grant written by the
                // authorization-code callback must not inherit a stale claim from a
                // refresh that failed before it.
                Updates.unset(FIELD_REFRESH_IN_PROGRESS), Updates.unset(FIELD_LEASE_EXPIRES), Updates.inc(FIELD_VERSION, 1L));
        grants.updateOne(key(grant.getTenantId(), grant.getConnectionName(), grant.getPrincipal()), update, new UpdateOptions().upsert(true));
    }

    @Override
    public boolean claimRefresh(String tenantId, String connectionName, String principal, String claimantId, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        Bson free = Filters.or(Filters.exists(FIELD_REFRESH_IN_PROGRESS, false), Filters.eq(FIELD_REFRESH_IN_PROGRESS, null),
                Filters.lt(FIELD_LEASE_EXPIRES, Date.from(now)));
        Bson filter = Filters.and(key(tenantId, connectionName, principal), free);
        Bson update = Updates.combine(Updates.set(FIELD_REFRESH_IN_PROGRESS, claimantId),
                Updates.set(FIELD_LEASE_EXPIRES, Date.from(leaseExpiresAt)));
        // Deliberately NOT an upsert: claiming a refresh on a grant that does not
        // exist would create an empty row and make "not connected" look like
        // "connected but unusable".
        //
        // matchedCount, not modifiedCount: Mongo reports zero MODIFIED when an update
        // would write the values already there, which is what a re-claim by the same
        // claimant within the same millisecond does. That reads as a lost claim, and
        // the caller then waits for a refresh only it was going to perform. Matching
        // the filter IS winning the claim — the filter is what encodes "the lease was
        // free".
        return grants.updateOne(filter, update).getMatchedCount() == 1;
    }

    @Override
    public boolean completeRefresh(ConnectionGrant grant, long expectedVersion) {
        Instant now = Instant.now();
        Bson filter = Filters.and(key(grant.getTenantId(), grant.getConnectionName(), grant.getPrincipal()),
                Filters.eq(FIELD_VERSION, expectedVersion));
        Bson update = Updates.combine(Updates.set(FIELD_ACCESS, grant.getEncryptedAccessToken()),
                Updates.set(FIELD_ACCESS_IV, grant.getAccessTokenIv()), Updates.set(FIELD_REFRESH, grant.getEncryptedRefreshToken()),
                Updates.set(FIELD_REFRESH_IV, grant.getRefreshTokenIv()), Updates.set(FIELD_DEK, grant.getDekId()),
                Updates.set(FIELD_EXPIRES, toDate(grant.getExpiresAt())), Updates.set(FIELD_SCOPES, grant.getScopes()),
                Updates.set(FIELD_STATUS, grant.getStatus().name()), Updates.set(FIELD_UPDATED, Date.from(now)),
                Updates.set(FIELD_LAST_REFRESH, Date.from(now)), Updates.unset(FIELD_REFRESH_IN_PROGRESS), Updates.unset(FIELD_LEASE_EXPIRES),
                Updates.inc(FIELD_VERSION, 1L));
        // matchedCount for the same reason as the claim above: a CAS that rewrites
        // identical values still won its version, and reporting a loss would send the
        // caller down the "another writer was ahead" path when nobody was.
        return grants.updateOne(filter, update).getMatchedCount() == 1;
    }

    @Override
    public void releaseRefresh(String tenantId, String connectionName, String principal, String claimantId) {
        // Scoped to THIS claimant: releasing unconditionally would let a claimant
        // whose lease already expired clear somebody else's live claim on its way
        // out, reopening the double-refresh window it was supposed to close.
        Bson filter = Filters.and(key(tenantId, connectionName, principal), Filters.eq(FIELD_REFRESH_IN_PROGRESS, claimantId));
        grants.updateOne(filter, Updates.combine(Updates.unset(FIELD_REFRESH_IN_PROGRESS), Updates.unset(FIELD_LEASE_EXPIRES)));
    }

    @Override
    public boolean delete(String tenantId, String connectionName, String principal) {
        return grants.deleteOne(key(tenantId, connectionName, principal)).getDeletedCount() > 0;
    }

    @Override
    public int deleteByConnection(String tenantId, String connectionName) {
        return (int) grants.deleteMany(Filters.and(Filters.eq(FIELD_TENANT, tenantId), Filters.eq(FIELD_CONNECTION, connectionName)))
                .getDeletedCount();
    }

    @Override
    public List<ConnectionGrant> findByPrincipal(String tenantId, String principal) {
        var results = new ArrayList<ConnectionGrant>();
        for (Document document : grants.find(Filters.and(Filters.eq(FIELD_TENANT, tenantId), Filters.eq(FIELD_PRINCIPAL, principal)))) {
            results.add(toGrant(document));
        }
        return results;
    }

    @Override
    public long countByStatus(String tenantId, ConnectionGrant.Status status) {
        return grants.countDocuments(Filters.and(Filters.eq(FIELD_TENANT, tenantId), Filters.eq(FIELD_STATUS, status.name())));
    }

    private static ConnectionGrant toGrant(Document document) {
        var grant = new ConnectionGrant();
        grant.setId(document.getObjectId("_id") == null ? null : document.getObjectId("_id").toHexString());
        grant.setTenantId(document.getString(FIELD_TENANT));
        grant.setConnectionName(document.getString(FIELD_CONNECTION));
        grant.setPrincipal(document.getString(FIELD_PRINCIPAL));
        grant.setEncryptedAccessToken(document.getString(FIELD_ACCESS));
        grant.setAccessTokenIv(document.getString(FIELD_ACCESS_IV));
        grant.setEncryptedRefreshToken(document.getString(FIELD_REFRESH));
        grant.setRefreshTokenIv(document.getString(FIELD_REFRESH_IV));
        grant.setDekId(document.getString(FIELD_DEK));
        grant.setExpiresAt(toInstant(document.getDate(FIELD_EXPIRES)));
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) document.get(FIELD_SCOPES);
        grant.setScopes(scopes);
        String status = document.getString(FIELD_STATUS);
        grant.setStatus(status == null ? ConnectionGrant.Status.ACTIVE : ConnectionGrant.Status.valueOf(status));
        grant.setCreatedAt(toInstant(document.getDate(FIELD_CREATED)));
        grant.setUpdatedAt(toInstant(document.getDate(FIELD_UPDATED)));
        grant.setLastRefreshAt(toInstant(document.getDate(FIELD_LAST_REFRESH)));
        Object version = document.get(FIELD_VERSION);
        grant.setVersion(version instanceof Number number ? number.longValue() : 0L);
        grant.setRefreshInProgress(document.getString(FIELD_REFRESH_IN_PROGRESS));
        grant.setRefreshLeaseExpiresAt(toInstant(document.getDate(FIELD_LEASE_EXPIRES)));
        return grant;
    }

    private static Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
