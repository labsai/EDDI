/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import org.junit.jupiter.api.DisplayName;

/**
 * The in-memory double used by the unit tests honours the same contract as the
 * MongoDB and PostgreSQL stores ({@code MongoDocumentSpendStoreIT},
 * {@code PostgresDocumentSpendStoreIT}).
 */
@DisplayName("InMemoryDocumentSpendStore")
class InMemoryDocumentSpendStoreTest implements DocumentSpendStoreContract {

    private final InMemoryDocumentSpendStore store = new InMemoryDocumentSpendStore();

    @Override
    public IDocumentSpendStore store() {
        return store;
    }
}
