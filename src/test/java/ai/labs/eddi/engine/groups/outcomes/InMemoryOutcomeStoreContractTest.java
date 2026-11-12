/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

/**
 * Runs the store contract against the in-memory double the service tests use,
 * so the contract is checked without Docker (the real stores run it in their
 * {@code *IT}s).
 */
class InMemoryOutcomeStoreContractTest implements OutcomeStoreContract {

    private final IOutcomeEventStore store = new OutcomeEventServiceTest.MemoryStore();

    @Override
    public IOutcomeEventStore outcomes() {
        return store;
    }
}
