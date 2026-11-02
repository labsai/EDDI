/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import org.junit.jupiter.api.BeforeAll;

import javax.sql.DataSource;

/**
 * {@link EmailMailboxStateStoreContract} against PostgreSQL (Testcontainers).
 */
class PostgresEmailMailboxStateStoreIT extends PostgresTestBase implements EmailMailboxStateStoreContract {

    private static PostgresEmailMailboxStateStore store;
    private static DataSource dataSource;

    @BeforeAll
    static void createStore() {
        dataSource = createDataSource();
        store = new PostgresEmailMailboxStateStore(createDataSourceInstance());
        store.read("schema"); // creates the schema
    }

    @Override
    public IEmailMailboxStateStore store() {
        return store;
    }

    @Override
    public void clear() throws Exception {
        truncateTables(dataSource, PostgresEmailMailboxStateStore.TABLE, PostgresEmailMailboxStateStore.NOTICES);
    }
}
