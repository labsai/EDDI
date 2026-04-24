/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.*;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IAttachmentStorage}.
 * <p>
 * Stores binary attachment payloads in a dedicated {@code attachments} table
 * using {@code BYTEA} columns. Each row is linked to a conversation for GDPR
 * cleanup.
 * <p>
 * Activated via {@code @DefaultBean} — yields to MongoDB GridFS when both are
 * available.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAttachmentStorage implements IAttachmentStorage {

    private static final Logger LOGGER = Logger.getLogger(PostgresAttachmentStorage.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS attachments (
                id UUID PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                file_name TEXT,
                mime_type TEXT NOT NULL,
                size_bytes BIGINT NOT NULL DEFAULT 0,
                data BYTEA NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """;

    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_attachments_conversation
                ON attachments (conversation_id)
            """;

    private static final String INSERT = """
            INSERT INTO attachments (id, conversation_id, file_name, mime_type, size_bytes, data)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID = """
            SELECT data FROM attachments WHERE id = ?
            """;

    private static final String DELETE_BY_CONVERSATION = """
            DELETE FROM attachments WHERE conversation_id = ?
            """;

    private final Instance<DataSource> dataSourceInstance;

    @Inject
    public PostgresAttachmentStorage(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    @PostConstruct
    void createTable() {
        if (!dataSourceInstance.isResolvable()) {
            LOGGER.debug("DataSource not available — attachment table creation skipped");
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX);
        } catch (SQLException e) {
            LOGGER.warnf("Failed to create attachments table: %s", e.getMessage());
        }
    }

    @Override
    public String store(String conversationId, String fileName, String mimeType, InputStream data, long sizeBytes) {
        String id = UUID.randomUUID().toString();

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(INSERT)) {

            long storedSizeBytes = Math.max(sizeBytes, 0L);
            ps.setObject(1, UUID.fromString(id));
            ps.setString(2, conversationId);
            ps.setString(3, fileName);
            ps.setString(4, mimeType);
            ps.setLong(5, storedSizeBytes);
            if (sizeBytes > 0) {
                ps.setBinaryStream(6, data, sizeBytes);
            } else {
                ps.setBinaryStream(6, data);
            }
            ps.executeUpdate();

            String storageRef = "pg://" + id;
            LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) → %s",
                    fileName, mimeType, storedSizeBytes, storageRef);
            return storageRef;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to store attachment: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream load(String storageRef) throws AttachmentNotFoundException {
        UUID id = parseStorageRef(storageRef);

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {

            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("data");
                    return new ByteArrayInputStream(data);
                }
                throw new AttachmentNotFoundException("No attachment found for: " + storageRef);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load attachment: " + e.getMessage(), e);
        }
    }

    @Override
    public long deleteByConversation(String conversationId) {
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(DELETE_BY_CONVERSATION)) {

            ps.setString(1, conversationId);
            int deleted = ps.executeUpdate();

            if (deleted > 0) {
                LOGGER.debugf("Deleted %d attachments for conversation '%s'", deleted, conversationId);
            }
            return deleted;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete attachments: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a storage reference back to a UUID.
     *
     * @param storageRef
     *            format: {@code pg://<uuid>}
     */
    private static UUID parseStorageRef(String storageRef) throws AttachmentNotFoundException {
        if (storageRef == null || !storageRef.startsWith("pg://")) {
            throw new AttachmentNotFoundException("Invalid PostgreSQL storage ref: " + storageRef);
        }
        try {
            return UUID.fromString(storageRef.substring("pg://".length()));
        } catch (IllegalArgumentException e) {
            throw new AttachmentNotFoundException("Invalid UUID in storage ref: " + storageRef);
        }
    }
}
