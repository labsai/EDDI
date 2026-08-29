/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A grant store that models what the database guarantees, and nothing more.
 * <p>
 * The point of this double is the three conditional writes. Both real stores
 * make {@code claimRefresh}, {@code completeRefresh} and
 * {@code updateSealedTokens} atomic — Mongo with a single {@code updateOne}
 * under a document lock, Postgres with a single {@code UPDATE … WHERE} under a
 * row lock — so this one holds the map's monitor for the whole
 * read-decide-write, which is the same guarantee expressed in the only way a
 * map can express it. A double that merely reads and then writes would let the
 * concurrency tests pass while the property under test was absent.
 * <p>
 * Everything else here is deliberately naive: this is not a persistence test.
 */
public class InMemoryConnectionGrantStore implements IConnectionGrantStore {

    private final Map<String, ConnectionGrant> grants = new ConcurrentHashMap<>();

    private static String key(String tenantId, String connectionName, String principal) {
        return tenantId + "|" + connectionName + "|" + principal;
    }

    private static ConnectionGrant copy(ConnectionGrant source) {
        var copy = new ConnectionGrant();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setConnectionName(source.getConnectionName());
        copy.setPrincipal(source.getPrincipal());
        copy.setEncryptedAccessToken(source.getEncryptedAccessToken());
        copy.setAccessTokenIv(source.getAccessTokenIv());
        copy.setEncryptedRefreshToken(source.getEncryptedRefreshToken());
        copy.setRefreshTokenIv(source.getRefreshTokenIv());
        copy.setDekId(source.getDekId());
        copy.setExpiresAt(source.getExpiresAt());
        copy.setScopes(source.getScopes());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setLastRefreshAt(source.getLastRefreshAt());
        copy.setVersion(source.getVersion());
        copy.setRefreshInProgress(source.getRefreshInProgress());
        copy.setRefreshLeaseExpiresAt(source.getRefreshLeaseExpiresAt());
        return copy;
    }

    @Override
    public synchronized Optional<ConnectionGrant> find(String tenantId, String connectionName, String principal) {
        // A copy, because the real stores hand back a detached document and a caller
        // that mutated a shared instance would pass here and fail in production.
        return Optional.ofNullable(grants.get(key(tenantId, connectionName, principal))).map(InMemoryConnectionGrantStore::copy);
    }

    @Override
    public synchronized void upsert(ConnectionGrant grant) {
        String mapKey = key(grant.getTenantId(), grant.getConnectionName(), grant.getPrincipal());
        ConnectionGrant existing = grants.get(mapKey);
        ConnectionGrant stored = copy(grant);
        stored.setVersion(existing == null ? 1 : existing.getVersion() + 1);
        stored.setCreatedAt(existing == null ? Instant.now() : existing.getCreatedAt());
        stored.setUpdatedAt(Instant.now());
        // Both real stores write ACTIVE for an unset status rather than storing null.
        stored.setStatus(grant.getStatus() == null ? ConnectionGrant.Status.ACTIVE : grant.getStatus());
        stored.setRefreshInProgress(null);
        stored.setRefreshLeaseExpiresAt(null);
        grants.put(mapKey, stored);
    }

    @Override
    public synchronized boolean claimRefresh(String tenantId, String connectionName, String principal, String claimantId, Instant leaseExpiresAt) {
        ConnectionGrant grant = grants.get(key(tenantId, connectionName, principal));
        if (grant == null) {
            return false;
        }
        boolean free = grant.getRefreshInProgress() == null
                || (grant.getRefreshLeaseExpiresAt() != null && grant.getRefreshLeaseExpiresAt().isBefore(Instant.now()));
        if (!free) {
            return false;
        }
        grant.setRefreshInProgress(claimantId);
        grant.setRefreshLeaseExpiresAt(leaseExpiresAt);
        return true;
    }

    @Override
    public synchronized boolean completeRefresh(ConnectionGrant grant, long expectedVersion) {
        String mapKey = key(grant.getTenantId(), grant.getConnectionName(), grant.getPrincipal());
        ConnectionGrant existing = grants.get(mapKey);
        if (existing == null || existing.getVersion() != expectedVersion) {
            return false;
        }
        ConnectionGrant stored = copy(grant);
        stored.setVersion(expectedVersion + 1);
        stored.setCreatedAt(existing.getCreatedAt());
        stored.setUpdatedAt(Instant.now());
        stored.setLastRefreshAt(Instant.now());
        stored.setRefreshInProgress(null);
        stored.setRefreshLeaseExpiresAt(null);
        grants.put(mapKey, stored);
        return true;
    }

    @Override
    public synchronized void releaseRefresh(String tenantId, String connectionName, String principal, String claimantId) {
        ConnectionGrant grant = grants.get(key(tenantId, connectionName, principal));
        // Scoped to the claimant, exactly like the real stores: an expired claimant
        // must not clear its successor's live claim.
        if (grant != null && claimantId.equals(grant.getRefreshInProgress())) {
            grant.setRefreshInProgress(null);
            grant.setRefreshLeaseExpiresAt(null);
        }
    }

    @Override
    public synchronized boolean delete(String tenantId, String connectionName, String principal) {
        return grants.remove(key(tenantId, connectionName, principal)) != null;
    }

    @Override
    public synchronized int deleteByConnection(String tenantId, String connectionName) {
        String prefix = tenantId + "|" + connectionName + "|";
        var toRemove = grants.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        toRemove.forEach(grants::remove);
        return toRemove.size();
    }

    @Override
    public synchronized List<ConnectionGrant> findByTenant(String tenantId) {
        var results = new ArrayList<ConnectionGrant>();
        for (ConnectionGrant grant : grants.values()) {
            if (tenantId.equals(grant.getTenantId())) {
                results.add(copy(grant));
            }
        }
        return results;
    }

    @Override
    public synchronized boolean updateSealedTokens(ConnectionGrant grant, long expectedVersion) {
        ConnectionGrant stored = grants.get(key(grant.getTenantId(), grant.getConnectionName(), grant.getPrincipal()));
        // The version belongs in the condition, exactly as it is in the real stores'
        // filter and WHERE clause. A double that wrote unconditionally would make
        // every re-seal succeed, and the CAS tests would then assert nothing.
        if (stored == null || stored.getVersion() != expectedVersion) {
            return false;
        }
        stored.setEncryptedAccessToken(grant.getEncryptedAccessToken());
        stored.setAccessTokenIv(grant.getAccessTokenIv());
        stored.setEncryptedRefreshToken(grant.getEncryptedRefreshToken());
        stored.setRefreshTokenIv(grant.getRefreshTokenIv());
        stored.setDekId(grant.getDekId());
        // No version bump, no updatedAt, no lease field: neither real store touches
        // them, and a re-seal that moved any of them would be visible to a refresh
        // that is mid-flight.
        return true;
    }

    @Override
    public synchronized List<ConnectionGrant> findByPrincipal(String tenantId, String principal) {
        var results = new ArrayList<ConnectionGrant>();
        for (ConnectionGrant grant : grants.values()) {
            if (tenantId.equals(grant.getTenantId()) && principal.equals(grant.getPrincipal())) {
                results.add(copy(grant));
            }
        }
        return results;
    }

    @Override
    public synchronized long countByStatus(String tenantId, ConnectionGrant.Status status) {
        return grants.values().stream().filter(g -> tenantId.equals(g.getTenantId()) && g.getStatus() == status).count();
    }

    /** Places a grant directly, for arranging a test. */
    public synchronized void seed(ConnectionGrant grant) {
        upsert(grant);
    }
}
