/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import java.time.Duration;
import java.util.Optional;

/**
 * A durable, atomic claim on a connection's {@code (tenant, name)}.
 * <p>
 * Connection documents live in a versioned document store that cannot carry a
 * unique index on a field inside the document, so the name rule used to be a
 * scan of the descriptor index before and after the write. Two replicas each
 * seeing only their own descriptor both passed it. This store is the arbiter
 * instead: one row per {@code (tenant, name)} under a real unique constraint,
 * so exactly one create can hold a name at a time, whatever replica it runs on.
 * <p>
 * Every write is conditional on what the caller observed — the claim token it
 * holds, or the holder value it read — so a caller that lost a race learns it
 * from the return value and never overwrites the winner.
 */
public interface IConnectionNameClaimStore {

    /**
     * One claim.
     *
     * @param token
     *            random per claim; what every conditional write is keyed on
     * @param connectionId
     *            the connection that holds the name, or null while the create that
     *            took the claim is still in flight (or crashed before recording it)
     */
    record NameClaim(String tenantId, String name, String token, String connectionId) {
    }

    /**
     * Inserts a claim with no connection id, stamped with the database clock.
     *
     * @return true if this caller now holds the name; false if a claim for it
     *         already exists — read it with {@link #find}
     */
    boolean claim(String tenantId, String name, String token);

    /** The current claim on a name, or empty. */
    Optional<NameClaim> find(String tenantId, String name);

    /**
     * Replaces a stale claim with a fresh one for {@code newToken}, only if it is
     * still exactly what the caller observed.
     * <p>
     * When {@code expected.connectionId()} is null the claim must still hold
     * {@code expected.token()}, still have no connection id, and have been taken
     * more than {@code staleAfter} ago <em>by the database clock</em> — a create
     * that crashed between claiming and creating. Otherwise it must still hold both
     * {@code expected.token()} and {@code expected.connectionId()}; the caller has
     * already established that the connection is gone.
     *
     * @return true if this caller now holds the name
     */
    boolean takeOver(NameClaim expected, String newToken, Duration staleAfter);

    /**
     * Records which connection holds the name, only while the claim still holds
     * {@code token}.
     *
     * @return false when the claim was taken over in the meantime
     */
    boolean recordConnection(String tenantId, String name, String token, String connectionId);

    /** Deletes the claim only while it still holds {@code token}. */
    boolean release(String tenantId, String name, String token);

    /** Deletes the claim only if it names {@code connectionId}. */
    boolean releaseConnection(String tenantId, String name, String connectionId);
}
