/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL implementation of {@link IAttachmentStore}.
 * <p>
 * Stores attachment data in an {@code attachments} table using {@code BYTEA}
 * columns. The {@code storage_ref} is a random UUID (unguessable).
 * <p>
 * <b>Grants</b> live in their own table, {@code attachment_grants}
 * {@code (storage_ref, conversation_id, created_at)}. For one release they are
 * also written to the legacy {@code attachments.grants TEXT[]} column and read
 * from both, so a node of the previous release in a rolling upgrade still
 * authorizes a grant made here, and the reverse;
 * {@code AttachmentGrantBackfill} copies the legacy column over. Deleting a
 * blob deletes the grants on it, in the same transaction; deleting a
 * conversation's attachments also deletes the grants that conversation
 * <em>held</em> on other blobs, in both shapes.
 * <p>
 * Quotas are checked and the row inserted in one transaction holding a
 * transaction-scoped advisory lock per quota scope (conversation, then user),
 * so two concurrent uploads cannot both pass the check.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAttachmentStore implements IAttachmentStore, IAttachmentGrantMigration {

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
    /**
     * Lets pruning find the legacy grants a deleted conversation held. Built
     * CONCURRENTLY by {@link #ensureGrantIndexes()} on the backfill's thread, not
     * in {@link #ensureSchema()}: a plain build holds a lock that blocks every
     * upload and grant on a large table, on whichever request touched it first.
     */
    private static final String CREATE_INDEX_LEGACY_GRANTS = "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_grants ON attachments USING GIN (grants)";
    private static final String CREATE_BACKFILL_TABLE = "CREATE TABLE IF NOT EXISTS attachment_grants_backfill (name TEXT PRIMARY KEY, cursor_value TEXT)";
    private static final String BACKFILL_CURSOR = "cursor";
    private static final String CREATE_GRANTS_TABLE = """
            CREATE TABLE IF NOT EXISTS attachment_grants (
                storage_ref TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (storage_ref, conversation_id)
            )
            """;
    private static final String CREATE_INDEX_GRANTS_CONV = "CREATE INDEX IF NOT EXISTS idx_attach_grants_conv ON attachment_grants (conversation_id)";
    /**
     * The requester holds a grant in the new shape — one column of the same read.
     */
    private static final String GRANTED = "EXISTS (SELECT 1 FROM attachment_grants g WHERE g.storage_ref = attachments.storage_ref "
            + "AND g.conversation_id = ?) AS granted";
    private static final String INSERT_GRANT = "INSERT INTO attachment_grants (storage_ref, conversation_id) VALUES (?, ?) "
            + "ON CONFLICT DO NOTHING";
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

    @ConfigProperty(name = "eddi.attachments.max-per-user", defaultValue = "500")
    long maxPerUser;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-user", defaultValue = "1073741824")
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
            stmt.execute(CREATE_GRANTS_TABLE);
            stmt.execute(CREATE_INDEX_GRANTS_CONV);
            stmt.execute(CREATE_BACKFILL_TABLE);
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
        String sql = "SELECT data, conversation_id, grants, " + GRANTED + " FROM attachments WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestingConversationId);
            ps.setString(2, storageRef);
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
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id, grants, " + GRANTED
                + " FROM attachments WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestingConversationId);
            ps.setString(2, storageRef);
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
        // One transaction, both shapes. The legacy UPDATE goes first: it locks the
        // blob's row, so a concurrent delete either finished before (no row: not
        // found) or waits for this commit and then deletes the grant row with it.
        String legacy = "UPDATE attachments SET grants = "
                + "CASE WHEN ? = ANY(COALESCE(grants, '{}')) THEN grants "
                + "ELSE array_append(COALESCE(grants, '{}'), ?) END "
                + "WHERE storage_ref = ?";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(legacy)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, conversationId);
                    ps.setString(3, storageRef);
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    // The same subtype the MongoDB store throws, so a caller telling "gone"
                    // from "the store failed" gets the same answer on both backends.
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                return insertGrant(conn, storageRef, conversationId);
            });
            LOGGER.debugf("Granted conversation '%s' access to attachment %s",
                    sanitize(conversationId), storageRef);
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to grant attachment access", e);
        }
    }

    private static boolean insertGrant(Connection conn, String storageRef, String conversationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_GRANT)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public Page readLegacyGrants(String afterCursor, int limit) {
        ensureSchema();
        String sql = "SELECT storage_ref, grants FROM attachments WHERE cardinality(grants) > 0"
                + (afterCursor != null ? " AND storage_ref > ?" : "") + " ORDER BY storage_ref LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (afterCursor != null) {
                ps.setString(i++, afterCursor);
            }
            ps.setInt(i, limit);
            List<LegacyGrants> blobs = new ArrayList<>();
            String last = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    last = rs.getString("storage_ref");
                    List<String> grantees = new ArrayList<>();
                    Array arr = rs.getArray("grants");
                    if (arr != null && arr.getArray() instanceof Object[] elements) {
                        for (Object element : elements) {
                            if (element != null) {
                                grantees.add(String.valueOf(element));
                            }
                        }
                    }
                    blobs.add(new LegacyGrants(last, grantees));
                }
            }
            return new Page(blobs, blobs.size() < limit ? null : last);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read legacy attachment grants", e);
        }
    }

    /**
     * Inserts only while the blob row exists, and locks it (FOR SHARE) for the
     * statement: a concurrent delete either ran first (nothing inserted) or waits
     * and then deletes the new row with the blob.
     */
    @Override
    public boolean copyGrant(String storageRef, String conversationId) {
        ensureSchema();
        String sql = "INSERT INTO attachment_grants (storage_ref, conversation_id) "
                + "SELECT storage_ref, ? FROM attachments WHERE storage_ref = ? FOR SHARE ON CONFLICT DO NOTHING";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, storageRef);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to copy an attachment grant", e);
        }
    }

    @Override
    public Map<String, Set<String>> existingGrants(Collection<String> storageRefs) {
        Map<String, Set<String>> existing = new HashMap<>();
        if (storageRefs.isEmpty()) {
            return existing;
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT storage_ref, conversation_id FROM attachment_grants WHERE storage_ref = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", storageRefs.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                }
            }
            return existing;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read attachment grant rows", e);
        }
    }

    @Override
    public void removeGrant(String storageRef, String conversationId) {
        ensureSchema();
        executeUpdate("DELETE FROM attachment_grants WHERE storage_ref = ? AND conversation_id = ?", storageRef, conversationId);
    }

    @Override
    public long pruneGrantsWithoutBlob() {
        ensureSchema();
        return executeUpdate("DELETE FROM attachment_grants g WHERE NOT EXISTS (SELECT 1 FROM attachments a WHERE a.storage_ref = g.storage_ref)");
    }

    @Override
    public void ensureGrantIndexes() {
        ensureSchema();
        // Outside a transaction (auto-commit), as CONCURRENTLY requires. A build that
        // failed leaves an invalid index IF NOT EXISTS then skips; pruning still
        // works without it, by a scan.
        executeUpdate(CREATE_INDEX_LEGACY_GRANTS);
    }

    @Override
    public String readBackfillCursor() {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT cursor_value FROM attachment_grants_backfill WHERE name = ?")) {
            ps.setString(1, BACKFILL_CURSOR);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the attachment grant backfill cursor", e);
        }
    }

    @Override
    public void writeBackfillCursor(String cursor) {
        ensureSchema();
        if (cursor == null) {
            executeUpdate("DELETE FROM attachment_grants_backfill WHERE name = ?", BACKFILL_CURSOR);
        } else {
            executeUpdate("INSERT INTO attachment_grants_backfill (name, cursor_value) VALUES (?, ?) "
                    + "ON CONFLICT (name) DO UPDATE SET cursor_value = EXCLUDED.cursor_value", BACKFILL_CURSOR, cursor);
        }
    }

    private int executeUpdate(String sql, String... params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Attachment grant statement failed", e);
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
            // A row that records no owner stays deletable, though the read path refuses
            // it: deleting discloses nothing, and refusing would strand personal data.
            if (owner != null && !owner.equals(requestingConversationId)) {
                throw new AttachmentAccessDeniedException(
                        "Delete denied: attachment belongs to '%s', requested from '%s'"
                                .formatted(owner, requestingConversationId));
            }
            // The blob and the grants on it in one transaction: a grant never outlives
            // its blob.
            return inTransaction(conn, () -> {
                boolean deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachments WHERE storage_ref = ?")) {
                    ps.setString(1, storageRef);
                    deleted = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachment_grants WHERE storage_ref = ?")) {
                    ps.setString(1, storageRef);
                    ps.executeUpdate();
                }
                return deleted;
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to delete attachment", e);
        }
    }

    /**
     * Deletes the conversation's blobs, the grant rows on them and the grant rows
     * the conversation <em>held</em> on other blobs in one transaction, then the
     * conversation from the legacy grant lists of other blobs. The blobs go first:
     * their DELETE waits for a concurrent {@link #grantAccess} on one of them to
     * commit, so the grant delete that follows sees its row.
     * <p>
     * The legacy prune runs after the commit, as its own statement: it locks
     * <em>other</em> conversations' blob rows, and holding this conversation's
     * deleted rows at the same time let two such deletes wait on each other in
     * opposite order (A's blob granted to B, B's blob granted to A).
     *
     * @return the number of blobs deleted
     */
    @Override
    public long deleteByConversation(String conversationId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            List<String> refs = new ArrayList<>();
            int held = inTransaction(conn, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachments WHERE conversation_id = ? RETURNING storage_ref")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            refs.add(rs.getString(1));
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachment_grants WHERE conversation_id = ? OR storage_ref = ANY(?)")) {
                    ps.setString(1, conversationId);
                    ps.setArray(2, conn.createArrayOf("text", refs.toArray()));
                    return ps.executeUpdate();
                }
            });
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE attachments SET grants = array_remove(grants, ?) WHERE grants @> ARRAY[?]::text[]")) {
                ps.setString(1, conversationId);
                ps.setString(2, conversationId);
                ps.executeUpdate();
            }
            if (!refs.isEmpty() || held > 0) {
                LOGGER.debugf("Deleted %d attachments and %d grant rows for conversation '%s'", refs.size(), held, sanitize(conversationId));
            }
            return refs.size();
        } catch (SQLException | AttachmentStoreException e) {
            throw new RuntimeException("Failed to delete attachments", e);
        }
    }

    /** Work run by {@link #inTransaction}. */
    @FunctionalInterface
    private interface TransactionWork<T> {
        T run() throws SQLException, AttachmentStoreException;
    }

    /**
     * Runs {@code work} in one transaction on {@code conn}: commits when it
     * returns, rolls back when it throws, and restores the connection's auto-commit
     * either way.
     */
    private static <T> T inTransaction(Connection conn, TransactionWork<T> work) throws SQLException, AttachmentStoreException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException | AttachmentStoreException | RuntimeException e) {
            // A rollback that fails too (the connection broke) must not replace the
            // error that caused it.
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(autoCommit);
            } catch (SQLException resetFailure) {
                LOGGER.debugf("Could not restore auto-commit on an attachment store connection: %s", resetFailure.getMessage());
            }
        }
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        return listWhere("conversation_id = ?", conversationId);
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Owned, OR granted in the legacy shape, OR granted in the new one — one
        // query over the blobs, so a blob granted in both shapes is listed once and a
        // grant row whose blob is gone lists nothing.
        return listWhere("conversation_id = ? OR grants @> ARRAY[?]::text[] "
                + "OR storage_ref IN (SELECT storage_ref FROM attachment_grants WHERE conversation_id = ?)",
                conversationId, conversationId, conversationId);
    }

    private List<Attachment> listWhere(String whereClause, String... params) {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id "
                + "FROM attachments WHERE " + whereClause + " ORDER BY created_at";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
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
        inTransaction(conn, () -> {
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
            return null;
        });
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
        if (rs.getBoolean("granted")) {
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
