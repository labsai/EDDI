/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * The shared {@link IngestionStateStoreContract} against the in-memory test
 * double.
 *
 * <p>
 * Runs the same 23 cases as the MongoDB and PostgreSQL backends, so the double
 * the pipeline tests rely on cannot quietly behave differently from production.
 */
@DisplayName("InMemoryIngestionStateStore (test double)")
class InMemoryIngestionStateStoreTest implements IngestionStateStoreContract {

    private InMemoryIngestionStateStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIngestionStateStore();
    }

    @Override
    public IIngestionStateStore store() {
        return store;
    }

    @Override
    public void forceDocumentOwner(String sourceId, String documentId, String runId) {
        store.forceDocumentOwner(sourceId, documentId, runId);
    }
}
