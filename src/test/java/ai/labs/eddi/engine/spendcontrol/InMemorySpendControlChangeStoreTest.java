/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * The {@link SpendControlChangeStoreContract} against the in-memory double the
 * service tests use, so the contract is checked without Docker.
 */
@DisplayName("InMemorySpendControlChangeStore")
class InMemorySpendControlChangeStoreTest implements SpendControlChangeStoreContract {

    private ISpendControlChangeStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySpendControlChangeStore();
    }

    @Override
    public ISpendControlChangeStore store() {
        return store;
    }
}
