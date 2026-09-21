/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link ConnectionGrant}s, keyed
 * {@code (tenantId, connectionName, principal)}.
 * <p>
 * Three of these methods carry the concurrency design and are not ordinary
 * CRUD: {@link #claimRefresh}, {@link #completeRefresh} and
 * {@link #updateSealedTokens}. Read their comments before changing any of them.
 */
public interface IConnectionGrantStore {

    /** The newest grant for this triple, or empty. */
    Optional<ConnectionGrant> find(String tenantId, String connectionName, String principal);

    /** Creates or replaces a grant. Bumps {@code version} and {@code updatedAt}. */
    void upsert(ConnectionGrant grant);

    /**
     * Atomically takes the refresh lease, and only if it is free.
     * <p>
     * A single conditional update — {@code refreshInProgress IS NULL OR
     * refreshLeaseExpiresAt < now} — because a read-then-write lets two replicas
     * both observe it free. This is the <b>cross-replica gate</b> and it happens
     * <b>before</b> any network call, which is the entire point of the design: an
     * earlier draft relied on a version CAS alone, and a CAS is checked at write
     * time — by then both replicas have already called the token endpoint and a
     * provider with rotating refresh tokens (Google, Atlassian) has already
     * invalidated one of them. The CAS would then dutifully serialise two writes,
     * one carrying a token the provider had already killed, and the user is
     * silently logged out.
     * <p>
     * <b>One clock.</b> "now" is the <em>database's</em> clock, both where the
     * expiry is written ({@code now + lease}) and where it is compared. The caller
     * passes a duration, never an instant. An expiry written from the claimant
     * JVM's clock and compared on a contender JVM's clock frees a live lease early
     * by exactly the skew between the two, and a lease freed early is a second
     * replica refreshing while the first is still in flight.
     *
     * @param lease
     *            how long the claimant owns the refresh. Must be longer than the
     *            token-endpoint timeout, or a slow provider frees the lease while
     *            the claimant is still in flight and reintroduces exactly the
     *            double refresh this prevents. Must also be non-null and positive:
     *            a missing lease is not a shorter one but a permanent one, since
     *            the predicate asks whether the lease has expired and
     *            {@code NULL < CURRENT_TIMESTAMP} is NULL rather than true.
     *            Implementations reject it instead of writing a grant whose refresh
     *            can never be claimed again
     * @return true if this caller now owns the refresh
     */
    boolean claimRefresh(String tenantId, String connectionName, String principal, String claimantId, Duration lease);

    /**
     * Writes a refreshed grant and clears the lease, guarded by the version the
     * claimant read.
     * <p>
     * The claim is the gate; this is the final write guard. Both exist because they
     * fail differently: the claim stops a second token request from being made, and
     * the CAS stops a stale write from landing if a lease expired mid-flight and
     * somebody else finished first.
     *
     * @param expectedVersion
     *            the version observed when the claim was taken
     * @return true if the write landed
     */
    boolean completeRefresh(ConnectionGrant grant, long expectedVersion);

    /**
     * Releases a lease without writing a new token — the claimant failed and the
     * next caller should be free to retry immediately rather than waiting out the
     * lease.
     */
    void releaseRefresh(String tenantId, String connectionName, String principal, String claimantId);

    /** Deletes one grant. This is what "disconnect" means. */
    boolean delete(String tenantId, String connectionName, String principal);

    /**
     * Deletes one grant, but only while the row is still the write whose access
     * token was sealed with {@code accessTokenIv}.
     * <p>
     * For a writer taking back a grant it has just stored. A delete by key alone
     * removes whatever holds the triple by the time it runs — including a later
     * link of the same account that completed in between, which is a grant the user
     * has no reason to think is gone. The IV is fresh random bytes for every seal,
     * so it names one write without {@link #upsert} having to report a version
     * back.
     * <p>
     * A DEK re-seal in the gap changes the IV too, and the delete then misses. That
     * is the safe direction: the grant stays until the user disconnects or the
     * connection is deleted, rather than a live grant being removed.
     *
     * @param accessTokenIv
     *            the IV of the grant the caller wrote; {@code null} matches nothing
     * @return true if that exact grant was removed
     */
    boolean deleteIfSealedWith(String tenantId, String connectionName, String principal, String accessTokenIv);

    /**
     * Deletes every grant belonging to a connection. Called when the connection
     * itself is deleted, so tokens do not outlive the thing that produced them.
     */
    int deleteByConnection(String tenantId, String connectionName);

    /**
     * Every grant a principal holds, for the "your linked accounts" page and for
     * GDPR erasure. Token ciphertext is included in the objects; callers must not
     * serialise them to a client.
     */
    List<ConnectionGrant> findByPrincipal(String tenantId, String principal);

    /**
     * Every grant in a tenant, for DEK rotation.
     * <p>
     * Deliberately not exposed over REST: this returns token ciphertext for the
     * whole tenant. Its one caller re-seals the ciphertext and never decrypts it
     * into anything that outlives the call.
     */
    List<ConnectionGrant> findByTenant(String tenantId);

    /**
     * Writes re-sealed token ciphertext in place, leaving the grant's lifecycle
     * fields alone.
     * <p>
     * Separate from {@link #upsert} because rotation is not a grant update: it must
     * not bump {@code updatedAt}, must not touch a refresh lease it does not own,
     * and must not change what any concurrent refresh sees except the bytes it is
     * about to rewrite anyway. It deliberately does <b>not</b> bump {@code version}
     * either — a re-seal is invisible to anyone holding a version, and invalidating
     * their CAS would fail a refresh that has nothing wrong with it.
     *
     * @param expectedVersion
     *            the version the row was read at. A refresh that landed in between
     *            has already sealed its tokens with the current DEK, so losing this
     *            guard means the work is done, not that it must be forced through
     * @return whether the write landed
     */
    boolean updateSealedTokens(ConnectionGrant grant, long expectedVersion);

    /** Counts by status, for the {@code connection.grant.status} gauge. */
    long countByStatus(String tenantId, ConnectionGrant.Status status);

    /**
     * How many grants a connection holds, across every principal and status.
     * <p>
     * Exists so an update that changes a connection's {@code authType} or
     * {@code binding} can be refused while linked accounts still exist: their
     * refresh tokens would otherwise stay at rest under a name the resolver no
     * longer reads them for. A count rather than a listing, because the caller
     * needs a number for its refusal and must never receive token ciphertext.
     */
    long countByConnection(String tenantId, String connectionName);
}
