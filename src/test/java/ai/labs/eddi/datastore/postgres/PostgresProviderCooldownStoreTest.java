/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore;
import ai.labs.eddi.modules.llm.impl.ProviderCooldownStoreContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;

/** The shared {@link ProviderCooldownStoreContract} against PostgreSQL. */
@DisplayName("PostgresProviderCooldownStore")
class PostgresProviderCooldownStoreTest extends PostgresTestBase implements ProviderCooldownStoreContract {

    private static PostgresProviderCooldownStore store;
    private static DataSource dataSource;

    @BeforeAll
    static void init() {
        dataSource = createDataSource();
        store = new PostgresProviderCooldownStore(createDataSourceInstance());
        store.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        truncateTables(dataSource, "provider_cooldowns");
    }

    @Override
    public IProviderCooldownStore cooldownStore() {
        return store;
    }
}
