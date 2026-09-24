/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.ContentHashes;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileIds;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IIngestedFileStore}.
 *
 * <p>
 * {@code bytea} rather than large objects: a bytea column is transactional,
 * dumps and restores with the row, and is deleted when the row is — large
 * objects need their own unlink and leak silently when one is missed. The
 * primary key on {@code (source_key, file_id)} is what makes a re-upload a
 * replacement rather than a second copy, in one statement, without a race.
 */
@ApplicationScoped
@DefaultBean
public class PostgresIngestedFileStore implements IIngestedFileStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingested_files (
                source_key TEXT NOT NULL,
                file_id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                mime_type TEXT,
                content_hash TEXT,
                size_bytes BIGINT NOT NULL,
                uploaded_at TIMESTAMP NOT NULL,
                content BYTEA NOT NULL,
                PRIMARY KEY (source_key, file_id)
            )
            """;

    private static final String UPSERT = """
            INSERT INTO rag_ingested_files
                (source_key, file_id, file_name, mime_type, content_hash, size_bytes, uploaded_at, content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_key, file_id) DO UPDATE SET
                file_name = EXCLUDED.file_name,
                mime_type = EXCLUDED.mime_type,
                content_hash = EXCLUDED.content_hash,
                size_bytes = EXCLUDED.size_bytes,
                uploaded_at = EXCLUDED.uploaded_at,
                content = EXCLUDED.content
            """;

    /** Never selects {@code content}: listing a source must not read its bytes. */
    private static final String SELECT_METADATA = """
            SELECT source_key, file_id, file_name, mime_type, content_hash, size_bytes, uploaded_at
            FROM rag_ingested_files
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresIngestedFileStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /**
     * Resolved lazily, never at construction: on a MongoDB deployment the
     * datasource bean is INACTIVE and resolving it during startup would abort the
     * boot of a deployment that never touches Postgres.
     */
    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not create the uploaded-file table", e);
        }
    }

    @Override
    public StoredFile store(String sourceKey, String fileName, String mimeType, byte[] content) {
        String fileId = IngestedFileIds.forFileName(fileName);
        String hash = ContentHashes.sha256Bytes(content);
        Instant uploadedAt = Instant.now();

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, sourceKey);
            statement.setString(2, fileId);
            statement.setString(3, fileName);
            statement.setString(4, mimeType);
            statement.setString(5, hash);
            statement.setLong(6, content.length);
            statement.setTimestamp(7, Timestamp.from(uploadedAt));
            statement.setBytes(8, content);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not store uploaded file '" + fileName + "'", e);
        }
        return new StoredFile(fileId, sourceKey, fileName, mimeType, content.length, hash, uploadedAt);
    }

    @Override
    public List<StoredFile> list(String sourceKey) {
        List<StoredFile> files = new ArrayList<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection
                        .prepareStatement(SELECT_METADATA + " WHERE source_key = ? ORDER BY uploaded_at ASC")) {
            statement.setString(1, sourceKey);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    files.add(toStoredFile(results));
                }
            }
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not list uploaded files of source " + sourceKey, e);
        }
        return files;
    }

    @Override
    public Optional<StoredFile> find(String sourceKey, String fileId) {
        try (Connection connection = connection();
                PreparedStatement statement = connection
                        .prepareStatement(SELECT_METADATA + " WHERE source_key = ? AND file_id = ?")) {
            statement.setString(1, sourceKey);
            statement.setString(2, fileId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(toStoredFile(results)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not read uploaded file " + fileId, e);
        }
    }

    @Override
    public Optional<byte[]> load(String sourceKey, String fileId) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT content FROM rag_ingested_files WHERE source_key = ? AND file_id = ?")) {
            statement.setString(1, sourceKey);
            statement.setString(2, fileId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.ofNullable(results.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not read uploaded file " + fileId, e);
        }
    }

    @Override
    public boolean delete(String sourceKey, String fileId) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM rag_ingested_files WHERE source_key = ? AND file_id = ?")) {
            statement.setString(1, sourceKey);
            statement.setString(2, fileId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not delete uploaded file " + fileId, e);
        }
    }

    @Override
    public long deleteAll(String sourceKey) {
        try (Connection connection = connection();
                PreparedStatement statement = connection
                        .prepareStatement("DELETE FROM rag_ingested_files WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not delete the uploaded files of source " + sourceKey, e);
        }
    }

    @Override
    public Usage usage(String sourceKey) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM rag_ingested_files WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? new Usage(results.getInt(1), results.getLong(2))
                        : Usage.empty();
            }
        } catch (SQLException e) {
            throw new IngestedFileStoreException("Could not measure the uploaded files of source " + sourceKey, e);
        }
    }

    private static StoredFile toStoredFile(ResultSet results) throws SQLException {
        Timestamp uploadedAt = results.getTimestamp("uploaded_at");
        return new StoredFile(
                results.getString("file_id"),
                results.getString("source_key"),
                results.getString("file_name"),
                results.getString("mime_type"),
                results.getLong("size_bytes"),
                results.getString("content_hash"),
                uploadedAt == null ? Instant.EPOCH : uploadedAt.toInstant());
    }
}
