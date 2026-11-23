/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreContract;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.EnqueueOutcome;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkCounts;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkRequest;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueueContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared {@link IngestionStateStoreContract} against PostgreSQL.
 *
 * <p>
 * Running the identical cases on both backends is the point: the stores this
 * replaces had drifted apart in ways neither suite could see.
 */
@DisplayName("PostgresIngestionStateStore")
class PostgresIngestionStateStoreTest extends PostgresTestBase implements IngestionStateStoreContract, IngestionWorkQueueContract {

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
        truncateTables(dataSource, "rag_ingestion_documents", "rag_ingestion_runs", "rag_ingestion_sources");
    }

    @Override
    public IIngestionStateStore store() {
        return store;
    }

    @Test
    @DisplayName("a table written before the work table gains its columns, and its rows read as having no work")
    void migratesATableWrittenBeforeTheWorkTable() throws Exception {
        // The shape rag_ingestion_documents had before phase Q, with a row in it.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS rag_ingestion_documents");
            statement.execute("""
                    CREATE TABLE rag_ingestion_documents (
                        source_id TEXT NOT NULL, document_id TEXT NOT NULL, content_hash TEXT, etag TEXT,
                        last_modified TEXT, first_ingested_at TIMESTAMP, last_ingested_at TIMESTAMP,
                        last_run_id VARCHAR(64), missed_runs INTEGER NOT NULL DEFAULT 0,
                        tombstoned BOOLEAN NOT NULL DEFAULT FALSE, fencing_run_id VARCHAR(64),
                        fencing_generation BIGINT, citation_hash TEXT, PRIMARY KEY (source_id, document_id))
                    """);
            statement.execute("INSERT INTO rag_ingestion_documents (source_id, document_id, content_hash, "
                    + "last_ingested_at) VALUES ('legacy', 'doc', 'hash', now())");
        }
        PostgresIngestionStateStore migrated = new PostgresIngestionStateStore(createDataSourceInstance());
        try {
            migrated.ensureSchema();

            assertEquals("hash", migrated.lookup("legacy", "doc").orElseThrow().contentHash(),
                    "the existing row is untouched");
            assertEquals(WorkCounts.NONE, migrated.workCounts("legacy", Instant.now()),
                    "and has no work: nothing pending, nothing poisoned");
            String run = migrated.startRun("legacy").orElseThrow();
            assertEquals(EnqueueOutcome.QUEUED, migrated.enqueueWork("legacy", run,
                    new WorkRequest("doc", "v2", "{}"), Instant.now()), "an old row takes work");
            assertEquals(1, migrated.claimWork("legacy", run, 10, Instant.now(),
                    Instant.now().plusSeconds(60)).size());
        } finally {
            // Back to the current schema for the other tests.
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS rag_ingestion_documents");
            }
            new PostgresIngestionStateStore(createDataSourceInstance()).ensureSchema();
        }
    }

    @Override
    public void forceClaimedBy(String sourceId, String documentId, String claimer) {
        String sql = "UPDATE rag_ingestion_documents SET claimed_by = ? WHERE source_id = ? AND document_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, claimer);
            statement.setString(2, sourceId);
            statement.setString(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not set the claimer", e);
        }
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
