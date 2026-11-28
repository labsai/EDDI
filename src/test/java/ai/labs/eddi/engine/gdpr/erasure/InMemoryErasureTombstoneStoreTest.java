/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * The in-memory double the unit tests of the erasure guarantee run on, held to
 * the same contract as the MongoDB and PostgreSQL stores — a double that
 * behaved differently would let those tests pass over a bug the real stores
 * expose.
 */
@DisplayName("InMemoryErasureTombstoneStore — the store contract")
class InMemoryErasureTombstoneStoreTest implements ErasureTombstoneStoreContract {

    private final InMemoryErasureTombstoneStore store = new InMemoryErasureTombstoneStore();

    @Override
    public IErasureTombstoneStore tombstoneStore() {
        return store;
    }

    /** The double has no database clock of its own unless a test gives it one. */
    @Test
    @Override
    public void aTombstoneFromASlowClockCountsFromTheDatabaseWriteTime() {
        store.databaseNow = Instant.now();
        ErasureTombstoneStoreContract.super.aTombstoneFromASlowClockCountsFromTheDatabaseWriteTime();
    }
}
