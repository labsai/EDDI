/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.memory.IAttachmentStore;
import ai.labs.eddi.engine.memory.MimeValidator;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IAttachmentStore}.
 * <p>
 * Stores attachment data in a {@code attachments} table using {@code BYTEA}
 * columns. For large files, PostgreSQL large objects could be used, but BYTEA
 * is simpler and sufficient for the 20MB cap.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAttachmentStore implements IAttachmentStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAttachmentStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS attachments (
                storage_ref TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                filename TEXT,
                mime_type TEXT NOT NULL,
                size_bytes BIGINT NOT NULL,
                data BYTEA NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """;
    private static final String CREATE_INDEX_CONV = "CREATE INDEX IF NOT EXISTS idx_attach_conv ON attachments (conversation_id)";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @Inject
    public PostgresAttachmentStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX_CONV);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize attachments table", e);
        }
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename,
                            String conversationId, String tenantId)
            throws AttachmentStoreException {

        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(bytes.length, maxSizeBytes));
        }

        // MIME validation
        String detectedMime = MimeValidator.detectMime(bytes);
        if (!MimeValidator.isCompatible(declaredMime, detectedMime)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(declaredMime, detectedMime));
        }

        String resolvedMime = declaredMime != null ? declaredMime : detectedMime;
        String storageRef = UUID.randomUUID().toString();

        ensureSchema();
        String sql = "INSERT INTO attachments (storage_ref, conversation_id, filename, mime_type, size_bytes, data) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, filename);
            ps.setString(4, resolvedMime);
            ps.setLong(5, bytes.length);
            ps.setBytes(6, bytes);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to store attachment", e);
        }

        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s'",
                filename, resolvedMime, bytes.length, conversationId);

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        String sql = "SELECT data, conversation_id FROM attachments WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentStoreException("Attachment not found: " + storageRef);
                }
                String ownerConv = rs.getString("conversation_id");
                if (!ownerConv.equals(requestingConversationId)) {
                    throw new AttachmentStoreException(
                            "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                                    .formatted(ownerConv, requestingConversationId));
                }
                return rs.getBytes("data");
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to load attachment", e);
        }
    }

    @Override
    public long deleteByConversation(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM attachments WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            long deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d attachments for conversation '%s'", deleted, conversationId);
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete attachments", e);
        }
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id FROM attachments WHERE conversation_id = ? ORDER BY created_at";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Attachment> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new Attachment(
                            rs.getString("storage_ref"),
                            rs.getString("filename"),
                            rs.getString("mime_type"),
                            rs.getLong("size_bytes"),
                            rs.getString("conversation_id")));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list attachments", e);
        }
    }
}
