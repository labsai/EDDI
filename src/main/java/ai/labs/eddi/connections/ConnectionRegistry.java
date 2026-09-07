/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Name → connection lookup, cached.
 * <p>
 * A connection is resolved on every outbound request that references one, and
 * the store has to scan the descriptor index to answer by name. Caching the
 * <em>document</em> is safe and caching anything else would not be: the
 * document carries only references, so it is the same for every caller, while
 * the credential it produces is per-caller and per-moment and is therefore
 * never cached here.
 * <p>
 * Invalidation is explicit on every write, not merely a TTL. A deleted
 * connection that keeps resolving for another five minutes is a revocation that
 * did not revoke; the TTL is the backstop for a write that happened on another
 * replica, not the mechanism.
 */
@ApplicationScoped
public class ConnectionRegistry {

    private static final Logger LOGGER = Logger.getLogger(ConnectionRegistry.class);

    /** Backstop for writes made on another replica. */
    static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final IConnectionStore connectionStore;

    /**
     * {@code tenantId + "/" + name} → the document, or an empty Optional when the
     * name does not exist.
     * <p>
     * Negative results are cached deliberately. Without that, a config that
     * references a connection nobody created re-scans the descriptor index on every
     * single request, which turns a typo into a load problem.
     */
    private final Cache<String, Optional<ConnectionConfiguration>> cache = Caffeine.newBuilder().maximumSize(512).expireAfterWrite(CACHE_TTL).build();

    @Inject
    public ConnectionRegistry(IConnectionStore connectionStore) {
        this.connectionStore = connectionStore;
    }

    /**
     * Looks a connection up by reference.
     *
     * @return the connection, or empty when no such name exists in that tenant
     */
    public Optional<ConnectionConfiguration> find(ConnectionReference reference) {
        if (reference == null) {
            return Optional.empty();
        }
        return cache.get(key(reference), ignored -> load(reference));
    }

    /**
     * Looks a connection up, or throws with a message an agent designer can act on.
     */
    public ConnectionConfiguration require(ConnectionReference reference) {
        return find(reference).orElseThrow(() -> new ConnectionException(ConnectionException.Reason.NOT_FOUND,
                "No connection named '" + reference.name() + "' exists in tenant '" + reference.tenantId()
                        + "'. Create it under /connectionstore/connections before referencing it."));
    }

    /** Drops every cached document. Called by the REST layer on any write. */
    public void invalidate() {
        cache.invalidateAll();
        LOGGER.debug("Connection registry cache invalidated");
    }

    private Optional<ConnectionConfiguration> load(ConnectionReference reference) {
        try {
            return Optional.ofNullable(connectionStore.readByName(reference.tenantId(), reference.name()));
        } catch (IResourceStore.ResourceStoreException e) {
            // Not cached as a negative result: a store blip must not pin "this
            // connection does not exist" for the whole TTL, which would turn a
            // transient database problem into a minute of failed outbound calls
            // even after the database recovered.
            throw new ConnectionException(ConnectionException.Reason.NOT_FOUND,
                    "Could not read connection '" + reference.name() + "': " + e.getClass().getSimpleName(), e);
        }
    }

    private static String key(ConnectionReference reference) {
        return reference.tenantId() + "/" + reference.name();
    }
}
