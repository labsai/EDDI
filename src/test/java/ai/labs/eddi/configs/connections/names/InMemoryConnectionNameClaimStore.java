/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A name-claim store that models what the database guarantees: one claim per
 * {@code (tenant, name)}, and every write a compare-and-set under one monitor.
 * <p>
 * There is no clock. A claim with no connection id is "older than the stale
 * bound" only once a test says so with {@link #markStale}, which is what lets
 * the stale and the in-flight cases be driven deterministically.
 */
public class InMemoryConnectionNameClaimStore implements IConnectionNameClaimStore {

    private final Map<String, NameClaim> claims = new HashMap<>();
    private final Set<String> stale = new HashSet<>();

    private static String key(String tenantId, String name) {
        return tenantId + "/" + name;
    }

    @Override
    public synchronized boolean claim(String tenantId, String name, String token) {
        if (claims.containsKey(key(tenantId, name))) {
            return false;
        }
        claims.put(key(tenantId, name), new NameClaim(tenantId, name, token, null));
        stale.remove(key(tenantId, name));
        return true;
    }

    @Override
    public synchronized Optional<NameClaim> find(String tenantId, String name) {
        return Optional.ofNullable(claims.get(key(tenantId, name)));
    }

    @Override
    public synchronized boolean takeOver(NameClaim expected, String newToken, Duration staleAfter) {
        String key = key(expected.tenantId(), expected.name());
        NameClaim current = claims.get(key);
        if (current == null || !current.token().equals(expected.token())) {
            return false;
        }
        if (expected.connectionId() == null
                ? current.connectionId() != null || !stale.contains(key)
                : !expected.connectionId().equals(current.connectionId())) {
            return false;
        }
        claims.put(key, new NameClaim(expected.tenantId(), expected.name(), newToken, null));
        stale.remove(key);
        return true;
    }

    @Override
    public synchronized boolean recordConnection(String tenantId, String name, String token, String connectionId) {
        NameClaim current = claims.get(key(tenantId, name));
        if (current == null || !current.token().equals(token)) {
            return false;
        }
        claims.put(key(tenantId, name), new NameClaim(tenantId, name, token, connectionId));
        return true;
    }

    @Override
    public synchronized boolean release(String tenantId, String name, String token) {
        NameClaim current = claims.get(key(tenantId, name));
        if (current == null || !current.token().equals(token)) {
            return false;
        }
        claims.remove(key(tenantId, name));
        return true;
    }

    @Override
    public synchronized boolean releaseConnection(String tenantId, String name, String connectionId) {
        NameClaim current = claims.get(key(tenantId, name));
        if (current == null || current.connectionId() == null || !current.connectionId().equals(connectionId)) {
            return false;
        }
        claims.remove(key(tenantId, name));
        return true;
    }

    /** Places a claim directly, for arranging a test. */
    public synchronized void seed(String tenantId, String name, String token, String connectionId) {
        claims.put(key(tenantId, name), new NameClaim(tenantId, name, token, connectionId));
    }

    /** Declares an unrecorded claim older than any stale bound. */
    public synchronized void markStale(String tenantId, String name) {
        stale.add(key(tenantId, name));
    }
}
