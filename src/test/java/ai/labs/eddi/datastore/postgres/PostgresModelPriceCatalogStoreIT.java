/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * {@link PostgresModelPriceCatalogStore} against a real PostgreSQL
 * (Testcontainers).
 */
class PostgresModelPriceCatalogStoreIT extends PostgresTestBase {

    private PostgresModelPriceCatalogStore store;

    @BeforeEach
    void setUp() throws SQLException {
        store = new PostgresModelPriceCatalogStore(createDataSourceInstance());
        // The first call creates the table; truncate after it so each test starts
        // empty.
        store.read();
        DataSource ds = createDataSource();
        truncateTables(ds, PostgresModelPriceCatalogStore.TABLE);
    }

    @Test
    void emptyStoreHasNoCatalog() {
        ModelPriceCatalogStoreContract.emptyStoreHasNoCatalog(store);
    }

    @Test
    void documentRoundTrips() {
        ModelPriceCatalogStoreContract.documentRoundTrips(store);
    }

    @Test
    void compareAndSetRefusesAStaleRevision() {
        ModelPriceCatalogStoreContract.compareAndSetRefusesAStaleRevision(store);
    }
}
