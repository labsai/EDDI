/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * The shared {@link IngestedFileStoreContract} against the in-memory double.
 *
 * <p>
 * Held to the same contract as the real stores so that a unit test using the
 * double is testing the behaviour they are required to have, rather than a
 * convenient approximation of it.
 */
@DisplayName("InMemoryIngestedFileStore (test double)")
class InMemoryIngestedFileStoreTest implements IngestedFileStoreContract {

    private InMemoryIngestedFileStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIngestedFileStore();
    }

    @Override
    public IIngestedFileStore store() {
        return store;
    }
}
