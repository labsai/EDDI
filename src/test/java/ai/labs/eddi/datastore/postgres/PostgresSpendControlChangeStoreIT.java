/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.spendcontrol.ISpendControlChangeStore;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeStoreContract;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;

/**
 * {@link PostgresSpendControlChangeStore} against a real PostgreSQL
 * (Testcontainers): the shared {@link SpendControlChangeStoreContract} — round
 * trip, status filter and order, compare-and-set claims under contention,
 * retention.
 */
@DisplayName("PostgresSpendControlChangeStore")
class PostgresSpendControlChangeStoreIT extends PostgresTestBase implements SpendControlChangeStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private ISpendControlChangeStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresSpendControlChangeStore(createDataSourceInstance());
        store.count(Status.PENDING); // creates the schema
        truncateTables(DATA_SOURCE, PostgresSpendControlChangeStore.TABLE);
    }

    @Override
    public ISpendControlChangeStore store() {
        return store;
    }
}
