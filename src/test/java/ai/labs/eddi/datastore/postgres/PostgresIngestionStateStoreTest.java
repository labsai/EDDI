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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

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

    @Override
    public void forceDocumentOwner(String sourceId, String documentId, String runId) {
        String sql = "UPDATE rag_ingestion_documents SET fencing_run_id = ? WHERE source_id = ? AND document_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sourceId);
            statement.setString(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not set the document owner", e);
        }
    }
}
