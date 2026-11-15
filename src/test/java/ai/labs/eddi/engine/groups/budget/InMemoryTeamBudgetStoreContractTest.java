/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

/**
 * Runs the store contract against the in-memory double, so the contract is
 * checked without Docker (the real stores run it in their {@code *IT}s).
 */
class InMemoryTeamBudgetStoreContractTest implements TeamBudgetStoreContract {

    private final ITeamBudgetStore store = new InMemoryTeamBudgetStore();

    @Override
    public ITeamBudgetStore store() {
        return store;
    }
}
