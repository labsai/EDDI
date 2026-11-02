/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.branding.BrandingStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

/** {@link PostgresBrandingStore} against a real PostgreSQL (Testcontainers). */
class BrandingStorePostgresIT extends PostgresTestBase {

    private PostgresBrandingStore store;

    @BeforeEach
    void setUp() throws SQLException {
        store = new PostgresBrandingStore(createDataSourceInstance());
        // The first call creates the tables; truncate after it so each test starts
        // empty.
        store.read();
        DataSource ds = createDataSource();
        truncateTables(ds, "branding", "branding_assets");
    }

    @Test
    void emptyStoreHasNoBrand() {
        BrandingStoreContract.emptyStoreHasNoBrand(store);
    }

    @Test
    void documentRoundTrips() {
        BrandingStoreContract.documentRoundTrips(store);
    }

    @Test
    void compareAndSetRefusesAStaleRevision() {
        BrandingStoreContract.compareAndSetRefusesAStaleRevision(store);
    }

    @Test
    void assetsAreStoredPerSlot() {
        BrandingStoreContract.assetsAreStoredPerSlot(store);
    }
}
