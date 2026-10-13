/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.AttachmentQuotas;
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
 * <p>
 * Quotas are checked and the row inserted in one transaction holding a
 * transaction-scoped advisory lock per quota scope (conversation, then user),
 * so two concurrent uploads cannot both pass the check.
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
    private static final String ADD_USER_COLUMN = "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS user_id TEXT";
    private static final String CREATE_INDEX_USER = "CREATE INDEX IF NOT EXISTS idx_attach_user ON attachments (user_id)";
    private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String LOCK_KEY_PREFIX = "eddi-attachments-quota:";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @ConfigProperty(name = "eddi.attachments.max-per-conversation", defaultValue = "50")
    long maxPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-conversation", defaultValue = "104857600") // 100 MB
    long maxTotalBytesPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-per-user", defaultValue = "0")
    long maxPerUser;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-user", defaultValue = "0")
    long maxTotalBytesPerUser;

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
            stmt.execute(ADD_USER_COLUMN);
            stmt.execute(CREATE_INDEX_USER);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize attachments table", e);
        }
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename,
                            String conversationId, String tenantId, String userId)
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
        if (!MimeValidator.isCompatibleContent(declaredMime, bytes)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(declaredMime, detectedMime));
        }

        String resolvedMime = MimeValidator.normalize(declaredMime != null ? declaredMime : detectedMime);
        String storageRef = UUID.randomUUID().toString();

        ensureSchema();
        boolean conversationQuota = maxPerConversation > 0 || maxTotalBytesPerConversation > 0;
        boolean userQuota = userId != null && (maxPerUser > 0 || maxTotalBytesPerUser > 0);

        try (Connection conn = dataSourceInstance.get().getConnection()) {
            if (!conversationQuota && !userQuota) {
                insert(conn, storageRef, conversationId, tenantId, userId, filename, resolvedMime, bytes);
            } else {
                insertWithinQuota(conn, conversationQuota, userQuota, storageRef, conversationId, tenantId, userId, filename,
                        resolvedMime, bytes);
            }
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
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
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
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
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
                throw new AttachmentAccessDeniedException(
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
        return listWhere("conversation_id = ?", conversationId, null);
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Owned by the conversation OR granted to it.
        return listWhere("conversation_id = ? OR ? = ANY(COALESCE(grants, '{}'))", conversationId, conversationId);
    }

    private List<Attachment> listWhere(String whereClause, String param1, String param2) {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id "
                + "FROM attachments WHERE " + whereClause + " ORDER BY created_at";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            if (param2 != null) {
                ps.setString(2, param2);
            }
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

    private static void insert(Connection conn, String storageRef, String conversationId, String tenantId, String userId,
                               String filename, String mime, byte[] bytes)
            throws SQLException {
        String sql = "INSERT INTO attachments "
                + "(storage_ref, conversation_id, tenant_id, user_id, filename, mime_type, size_bytes, data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, tenantId);
            ps.setString(4, userId);
            ps.setString(5, filename);
            ps.setString(6, mime);
            ps.setLong(7, bytes.length);
            ps.setBytes(8, bytes);
            ps.executeUpdate();
        }
    }

    /**
     * Check the quotas and insert in one transaction, serialised per quota scope by
     * {@code pg_advisory_xact_lock}. The check used to run on its own connection
     * before the insert, so N concurrent uploads at {@code limit - 1} all saw room
     * and all inserted. The locks are always taken conversation first, then user,
     * so two uploads can never wait on each other in a cycle; they are released by
     * the commit or rollback.
     */
    private void insertWithinQuota(Connection conn, boolean conversationQuota, boolean userQuota, String storageRef,
                                   String conversationId, String tenantId, String userId, String filename, String mime,
                                   byte[] bytes)
            throws SQLException, AttachmentStoreException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (conversationQuota) {
                advisoryLock(conn, "conversation:" + conversationId);
            }
            if (userQuota) {
                advisoryLock(conn, "user:" + userId);
            }
            if (conversationQuota) {
                long[] usage = usage(conn, "conversation_id = ?", conversationId);
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_CONVERSATION, usage, bytes.length, maxPerConversation,
                        maxTotalBytesPerConversation);
            }
            if (userQuota) {
                long[] usage = usage(conn, "user_id = ?", userId);
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_USER, usage, bytes.length, maxPerUser, maxTotalBytesPerUser);
            }
            insert(conn, storageRef, conversationId, tenantId, userId, filename, mime, bytes);
            conn.commit();
        } catch (SQLException | AttachmentStoreException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private static void advisoryLock(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ADVISORY_LOCK)) {
            ps.setString(1, LOCK_KEY_PREFIX + key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    /** {count, totalBytes} of the rows matching {@code where}. */
    private static long[] usage(Connection conn, String where, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM attachments WHERE " + where)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    private void authorize(String owner, ResultSet rs, String requester) throws SQLException, AttachmentStoreException {
        if (owner != null && owner.equals(requester)) {
            return;
        }
        if (grantsContain(rs, requester)) {
            return;
        }
        throw new AttachmentAccessDeniedException(
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
