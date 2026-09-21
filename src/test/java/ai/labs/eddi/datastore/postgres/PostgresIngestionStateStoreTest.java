/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;

/**
 * The shared {@link IngestionStateStoreContract} against PostgreSQL.
 *
 * <p>
 * Running the identical cases on both backends is the point: the stores this
 * replaces had drifted apart in ways neither suite could see.
 */
@DisplayName("PostgresIngestionStateStore")
class PostgresIngestionStateStoreTest extends PostgresTestBase implements IngestionStateStoreContract {

    private static PostgresIngestionStateStore store;
    private static DataSource dataSource;

    @BeforeAll
    static void init() {
        dataSource = createDataSource();
        store = new PostgresIngestionStateStore(createDataSourceInstance());
        store.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        truncateTables(dataSource, "rag_ingestion_documents", "rag_ingestion_runs");
    }

    @Override
    public IIngestionStateStore store() {
        return store;
    }
}
