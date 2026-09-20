/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileStoreContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;

/** The shared {@link IngestedFileStoreContract} against PostgreSQL. */
@DisplayName("PostgresIngestedFileStore")
class PostgresIngestedFileStoreTest extends PostgresTestBase implements IngestedFileStoreContract {

    private static PostgresIngestedFileStore store;
    private static DataSource dataSource;

    @BeforeAll
    static void init() {
        dataSource = createDataSource();
        store = new PostgresIngestedFileStore(createDataSourceInstance());
        store.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        truncateTables(dataSource, "rag_ingested_files");
    }

    @Override
    public IIngestedFileStore store() {
        return store;
    }
}
