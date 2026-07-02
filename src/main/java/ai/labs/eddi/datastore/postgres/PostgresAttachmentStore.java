/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
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

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL implementation of {@link IAttachmentStore}.
 * <p>
 * Stores attachment data in an {@code attachments} table using {@code BYTEA}
 * columns. The {@code storage_ref} is a random UUID (unguessable). Access
 * grants are held in a {@code grants TEXT[]} column and die with the row.
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
                tenant_id TEXT,
                filename TEXT,
                mime_type TEXT NOT NULL,
                size_bytes BIGINT NOT NULL,
                data BYTEA NOT NULL,
                grants TEXT[] NOT NULL DEFAULT '{}',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """;
    private static final String ADD_GRANTS_COLUMN = "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS grants TEXT[] NOT NULL DEFAULT '{}'";
    private static final String CREATE_INDEX_CONV = "CREATE INDEX IF NOT EXISTS idx_attach_conv ON attachments (conversation_id)";
    private static final String CREATE_INDEX_TENANT = "CREATE INDEX IF NOT EXISTS idx_attach_tenant ON attachments (tenant_id)";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @ConfigProperty(name = "eddi.attachments.max-per-conversation", defaultValue = "50")
    long maxPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-conversation", defaultValue = "104857600") // 100 MB
    long maxTotalBytesPerConversation;

    @Inject
    public PostgresAttachmentStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(ADD_GRANTS_COLUMN);
            stmt.execute(CREATE_INDEX_CONV);
            stmt.execute(CREATE_INDEX_TENANT);
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

        String resolvedMime = MimeValidator.normalize(declaredMime != null ? declaredMime : detectedMime);
        String storageRef = UUID.randomUUID().toString();

        ensureSchema();
        enforceQuota(conversationId, bytes.length);

        String sql = "INSERT INTO attachments "
                + "(storage_ref, conversation_id, tenant_id, filename, mime_type, size_bytes, data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, tenantId);
            ps.setString(4, filename);
            ps.setString(5, resolvedMime);
            ps.setLong(6, bytes.length);
            ps.setBytes(7, bytes);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to store attachment", e);
        }

        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s'",
                sanitize(filename), resolvedMime, bytes.length, sanitize(conversationId));

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        String sql = "SELECT data, conversation_id, grants FROM attachments WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentStoreException("Attachment not found: " + storageRef);
                }
                authorize(rs.getString("conversation_id"), rs, requestingConversationId);
                return rs.getBytes("data");
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to load attachment", e);
        }
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id, grants "
                + "FROM attachments WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentStoreException("Attachment not found: " + storageRef);
                }
                authorize(rs.getString("conversation_id"), rs, requestingConversationId);
                return new Attachment(
                        rs.getString("storage_ref"),
                        rs.getString("filename"),
                        rs.getString("mime_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("conversation_id"));
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to read attachment metadata", e);
        }
    }

    @Override
    public void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        ensureSchema();
        String sql = "UPDATE attachments SET grants = "
                + "CASE WHEN ? = ANY(COALESCE(grants, '{}')) THEN grants "
                + "ELSE array_append(COALESCE(grants, '{}'), ?) END "
                + "WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, conversationId);
            ps.setString(3, storageRef);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new AttachmentStoreException("Attachment not found: " + storageRef);
            }
            LOGGER.debugf("Granted conversation '%s' access to attachment %s",
                    sanitize(conversationId), storageRef);
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to grant attachment access", e);
        }
    }

    @Override
    public boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            String owner;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT conversation_id FROM attachments WHERE storage_ref = ?")) {
                ps.setString(1, storageRef);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    owner = rs.getString("conversation_id");
                }
            }
            if (owner != null && !owner.equals(requestingConversationId)) {
                throw new AttachmentStoreException(
                        "Delete denied: attachment belongs to '%s', requested from '%s'"
                                .formatted(owner, requestingConversationId));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM attachments WHERE storage_ref = ?")) {
                ps.setString(1, storageRef);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to delete attachment", e);
        }
    }

    @Override
    public long deleteByConversation(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM attachments WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d attachments for conversation '%s'", (Object) deleted, sanitize(conversationId));
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete attachments", e);
        }
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id "
                + "FROM attachments WHERE conversation_id = ? ORDER BY created_at";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
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

    private void enforceQuota(String conversationId, long incomingBytes) throws AttachmentStoreException {
        if (maxPerConversation <= 0 && maxTotalBytesPerConversation <= 0) {
            return;
        }
        String sql = "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM attachments WHERE conversation_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                long totalBytes = rs.getLong(2);
                if (maxPerConversation > 0 && count >= maxPerConversation) {
                    throw new AttachmentStoreException(
                            "Attachment quota exceeded for conversation: %d/%d files. Delete some attachments first."
                                    .formatted(count, maxPerConversation));
                }
                if (maxTotalBytesPerConversation > 0 && totalBytes + incomingBytes > maxTotalBytesPerConversation) {
                    throw new AttachmentStoreException(
                            "Attachment storage quota exceeded for conversation: %d + %d bytes exceeds limit of %d. Delete some attachments first."
                                    .formatted(totalBytes, incomingBytes, maxTotalBytesPerConversation));
                }
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to check attachment quota", e);
        }
    }

    private void authorize(String owner, ResultSet rs, String requester) throws SQLException, AttachmentStoreException {
        if (owner != null && owner.equals(requester)) {
            return;
        }
        if (grantsContain(rs, requester)) {
            return;
        }
        throw new AttachmentStoreException(
                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(owner, requester));
    }

    private static boolean grantsContain(ResultSet rs, String value) throws SQLException {
        Array arr = rs.getArray("grants");
        if (arr == null) {
            return false;
        }
        Object raw = arr.getArray();
        if (raw instanceof Object[] elements) {
            for (Object element : elements) {
                if (value != null && value.equals(String.valueOf(element))) {
                    return true;
                }
            }
        }
        return false;
    }
}
