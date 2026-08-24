/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import java.util.Optional;

/**
 * Persistence for in-flight authorization-code flows.
 */
public interface IOAuthStateStore {

    /** Stores a freshly issued state. */
    void create(OAuthState state);

    /**
     * Atomically redeems a state, returning it only if this caller won.
     * <p>
     * One conditional update — {@code SET consumedAt = now WHERE state = ? AND
     * consumedAt IS NULL AND expiresAt > now} — proceeding only if it changed
     * exactly one row. This must be the FIRST thing the callback does. Validating
     * the row and then marking it consumed is a read-then-write: two concurrent
     * callbacks both observe it unconsumed, both proceed, and both redeem the
     * authorization code.
     *
     * @return the state, or empty when it does not exist, has expired, or has
     *         already been redeemed — the caller must not distinguish these to the
     *         browser, since doing so is a state-guessing oracle
     */
    Optional<OAuthState> claim(String state);

    /** Removes expired rows. */
    int deleteExpired();
}
