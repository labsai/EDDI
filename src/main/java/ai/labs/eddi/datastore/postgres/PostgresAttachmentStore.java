/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.DerivedBlobs;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.attachments.OwnerScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <b>Lineage.</b> Derived blobs are rows of the same table with
 * {@code root_ref} set; their {@code conversation_id}, {@code user_id},
 * {@code tenant_id}, {@code owner_scope} and {@code owner_key} are copied from
 * the root when they are written, so the conversation cascade reaches them and
 * a grantee's erasure does not. Every read joins the row to its root and
 * authorizes on the root. {@code owner_key} null (every row stored before the
 * column existed) reads as {@code conversation_id}.
 * <p>
 * Quotas are checked and the row inserted in one transaction holding a
 * transaction-scoped advisory lock per quota scope (conversation, then user),
 * so two concurrent uploads cannot both pass the check. A derived write takes
 * the root's source lock after them.
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
     * The owner and lineage columns. Each is additive with a constant default (or
     * none), so adding it does not rewrite the table.
     */
    private static final List<String> ADD_LINEAGE_COLUMNS = List.of(
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS owner_scope TEXT NOT NULL DEFAULT 'CONVERSATION'",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS owner_key TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS root_ref TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS parent_ref TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS kind TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS unit_id TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS page INT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS sha256 TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS page_count INT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ");
    /**
     * Lets pruning find the legacy grants a deleted conversation held. Built
     * CONCURRENTLY by {@link #ensureGrantIndexes()} on the backfill's thread, not
     * in {@link #ensureSchema()}: a plain build holds a lock that blocks every
     * upload and grant on a large table, on whichever request touched it first.
     */
    private static final String CREATE_INDEX_LEGACY_GRANTS = "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_grants ON attachments USING GIN (grants)";
    /**
     * The lineage lookups ({@code findDerived}, {@code listDerived}, the cascade)
     * and the dedup backstop. Partial, so they hold derived rows only; built
     * CONCURRENTLY with the legacy-grant index, for the same reason. Dedup itself
     * runs under the source lock and does not depend on the unique index.
     */
    private static final List<String> CREATE_LINEAGE_INDEXES = List.of(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_lineage ON attachments (root_ref, kind, unit_id) WHERE root_ref IS NOT NULL",
            "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_dedup ON attachments (owner_key, root_ref, sha256) WHERE root_ref IS NOT NULL");
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
     * A blob joined to the root of its lineage ({@code a} = the blob, {@code r} =
     * its root; the same row for a root), with whether the requester holds a
     * new-shape grant on the root. A blob whose root is gone joins nothing and
     * reads as not found. The first parameter is the requester, the second the
     * storage reference.
     */
    private static final String LINEAGE_COLUMNS = "a.storage_ref, a.filename, a.mime_type, a.size_bytes, a.root_ref, a.parent_ref, a.kind, "
            + "a.unit_id, a.page, a.sha256, r.storage_ref AS lineage_root, r.conversation_id, r.tenant_id AS root_tenant_id, "
            + "r.user_id AS root_user_id, r.owner_scope AS root_owner_scope, COALESCE(r.owner_key, r.conversation_id) AS root_owner_key, "
            + "r.page_count AS root_page_count, r.grants, "
            + "EXISTS (SELECT 1 FROM attachment_grants g WHERE g.storage_ref = r.storage_ref AND g.conversation_id = ?) AS granted";
    private static final String LINEAGE_FROM = " FROM attachments a JOIN attachments r "
            + "ON r.storage_ref = COALESCE(a.root_ref, a.storage_ref) AND r.root_ref IS NULL WHERE a.storage_ref = ?";
    private static final String INSERT_GRANT_ON_ROOT = "INSERT INTO attachment_grants (storage_ref, conversation_id) "
            + "SELECT COALESCE(root_ref, storage_ref), ? FROM attachments WHERE storage_ref = ? ON CONFLICT DO NOTHING";
    /** The kinds whose bytes count toward the owner's byte quota, as SQL. */
    private static final String MEDIA_KINDS_SQL = Arrays.stream(Kind.values()).filter(DerivedCaps::countsTowardOwnerQuota)
            .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", ", "(", ")"));
    private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String LOCK_KEY_PREFIX = "eddi-attachments-quota:";
    private static final String SOURCE_LOCK_KEY_PREFIX = "eddi-attachments-source:";

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

    @ConfigProperty(name = "eddi.attachments.derived.max-per-source", defaultValue = "" + DerivedCaps.DEFAULT_MAX_PER_SOURCE)
    long derivedMaxPerSource = DerivedCaps.DEFAULT_MAX_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-total-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE)
    long derivedMaxTotalBytesPerSource = DerivedCaps.DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-slices-per-source", defaultValue = "" + DerivedCaps.DEFAULT_MAX_SLICES_PER_SOURCE)
    long derivedMaxSlicesPerSource = DerivedCaps.DEFAULT_MAX_SLICES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-slice-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_SLICE_BYTES_PER_SOURCE)
    long derivedMaxSliceBytesPerSource = DerivedCaps.DEFAULT_MAX_SLICE_BYTES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-text-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_TEXT_BYTES_PER_SOURCE)
    long derivedMaxTextBytesPerSource = DerivedCaps.DEFAULT_MAX_TEXT_BYTES_PER_SOURCE;

    /** Announces deleted roots; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentRootDeleted> rootDeletedEvent;

    /** {@code null} outside CDI (tests). */
    @Inject
    MeterRegistry meterRegistry;

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
            for (String column : ADD_LINEAGE_COLUMNS) {
                stmt.execute(column);
            }
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
        String sql = "SELECT a.data, " + LINEAGE_COLUMNS + LINEAGE_FROM;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestingConversationId);
            ps.setString(2, storageRef);
            byte[] data;
            boolean slice;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                authorize(rs, storageRef, requestingConversationId);
                data = rs.getBytes("data");
                slice = Kind.PDF_SLICE.name().equals(rs.getString("kind"));
            }
            if (slice) {
                // Slices are a least-recently-used cache; nothing else is touched, so an
                // ordinary load stays a read.
                touch(conn, storageRef);
            }
            return data;
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to load attachment", e);
        }
    }

    private static void touch(Connection conn, String storageRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET last_accessed_at = NOW() WHERE storage_ref = ?")) {
            ps.setString(1, storageRef);
            ps.executeUpdate();
        }
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        String sql = "SELECT " + LINEAGE_COLUMNS + LINEAGE_FROM;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestingConversationId);
            ps.setString(2, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                authorize(rs, storageRef, requestingConversationId);
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
        // One transaction, both shapes, always on the root (a derived ref grants its
        // root). The legacy UPDATE goes first: it locks the root's row, so a
        // concurrent delete either finished before (no row: not found) or waits for
        // this commit and then deletes the grant row with it.
        String legacy = "UPDATE attachments SET grants = "
                + "CASE WHEN ? = ANY(COALESCE(grants, '{}')) THEN grants "
                + "ELSE array_append(COALESCE(grants, '{}'), ?) END "
                + "WHERE storage_ref = COALESCE((SELECT root_ref FROM attachments WHERE storage_ref = ?), ?) AND root_ref IS NULL";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(legacy)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, conversationId);
                    ps.setString(3, storageRef);
                    ps.setString(4, storageRef);
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    // The same subtype the MongoDB store throws, so a caller telling "gone"
                    // from "the store failed" gets the same answer on both backends.
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT_GRANT_ON_ROOT)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, storageRef);
                    return ps.executeUpdate() > 0;
                }
            });
            LOGGER.debugf("Granted conversation '%s' access to attachment %s",
                    sanitize(conversationId), storageRef);
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to grant attachment access", e);
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

    /**
     * The legacy-grant index and the two lineage indexes, CONCURRENTLY, on the
     * backfill's thread.
     */
    @Override
    public void ensureGrantIndexes() {
        ensureSchema();
        // Outside a transaction (auto-commit), as CONCURRENTLY requires. A build that
        // failed leaves an invalid index IF NOT EXISTS then skips; every query still
        // works without it, by a scan.
        executeUpdate(CREATE_INDEX_LEGACY_GRANTS);
        for (String index : CREATE_LINEAGE_INDEXES) {
            try {
                executeUpdate(index);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment lineage index; lineage lookups fall back to a scan: %s", sanitize(e.getMessage()));
            }
        }
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
        boolean deleted;
        AttachmentRootDeleted announced = null;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            String owner;
            String rootRef;
            String scope;
            String ownerKey;
            String userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT conversation_id, root_ref, owner_scope, owner_key, user_id FROM attachments WHERE storage_ref = ?")) {
                ps.setString(1, storageRef);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    owner = rs.getString("conversation_id");
                    rootRef = rs.getString("root_ref");
                    scope = rs.getString("owner_scope");
                    ownerKey = rs.getString("owner_key");
                    userId = rs.getString("user_id");
                }
            }
            // A row that records no owner stays deletable, though the read path refuses
            // it: deleting discloses nothing, and refusing would strand personal data.
            if (owner != null && !owner.equals(requestingConversationId)) {
                throw new AttachmentAccessDeniedException(
                        "Delete denied: attachment belongs to '%s', requested from '%s'"
                                .formatted(owner, requestingConversationId));
            }
            // The blob, its lineage (a root's; a derived blob has none of its own) and
            // the grants on it in one transaction: neither outlives the root.
            deleted = inTransaction(conn, () -> {
                if (rootRef == null) {
                    // A root: lock its row first. A storeDerived holding it FOR SHARE has
                    // its view committed before this returns, and the DELETE below, a new
                    // statement, sees that view; one starting now finds the root gone.
                    // Without it the DELETE would wait on the root row and then work from
                    // its old snapshot, leaving a view inserted meanwhile behind.
                    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM attachments WHERE storage_ref = ? FOR UPDATE")) {
                        ps.setString(1, storageRef);
                        ps.executeQuery().close();
                    }
                }
                boolean removed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachments WHERE storage_ref = ? OR root_ref = ?")) {
                    ps.setString(1, storageRef);
                    ps.setString(2, storageRef);
                    removed = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachment_grants WHERE storage_ref = ?")) {
                    ps.setString(1, storageRef);
                    ps.executeUpdate();
                }
                return removed;
            });
            if (deleted && rootRef == null) {
                announced = rootDeleted(storageRef, scope, ownerKey, owner, userId);
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to delete attachment", e);
        }
        if (announced != null) {
            DerivedBlobs.fireRootDeleted(rootDeletedEvent, List.of(announced));
        }
        return deleted;
    }

    private static AttachmentRootDeleted rootDeleted(String rootRef, String scope, String ownerKey, String conversationId, String userId) {
        return new AttachmentRootDeleted(rootRef, OwnerScope.fromStored(scope), ownerKey != null ? ownerKey : conversationId, userId);
    }

    /**
     * Deletes the conversation's blobs — roots and the lineage stored under them —
     * the grant rows on them and the grant rows the conversation <em>held</em> on
     * other blobs in one transaction, then the conversation from the legacy grant
     * lists of other blobs. The blobs go first: their DELETE waits for a concurrent
     * {@link #grantAccess} on one of them to commit, so the grant delete that
     * follows sees its row.
     * <p>
     * The legacy prune runs after the commit, as its own statement: it locks
     * <em>other</em> conversations' blob rows, and holding this conversation's
     * deleted rows at the same time let two such deletes wait on each other in
     * opposite order (A's blob granted to B, B's blob granted to A).
     *
     * @return the number of roots deleted
     */
    @Override
    public long deleteByConversation(String conversationId) {
        ensureSchema();
        List<AttachmentRootDeleted> roots = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            List<String> refs = new ArrayList<>();
            int held = inTransaction(conn, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachments WHERE conversation_id = ? RETURNING storage_ref, root_ref, owner_scope, owner_key, user_id")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String ref = rs.getString(1);
                            refs.add(ref);
                            if (rs.getString(2) == null) {
                                roots.add(rootDeleted(ref, rs.getString(3), rs.getString(4), conversationId, rs.getString(5)));
                            }
                        }
                    }
                }
                if (!roots.isEmpty()) {
                    // The lineage once more, as a new statement: the DELETE above may have
                    // waited on a root a storeDerived held FOR SHARE, and then worked from
                    // a snapshot without the view that storeDerived committed.
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachments WHERE root_ref = ANY(?)")) {
                        ps.setArray(1, conn.createArrayOf("text", roots.stream().map(AttachmentRootDeleted::rootRef).toArray()));
                        ps.executeUpdate();
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
                LOGGER.debugf("Deleted %d attachments (%d roots) and %d grant rows for conversation '%s'", refs.size(), roots.size(), held,
                        sanitize(conversationId));
            }
        } catch (SQLException | AttachmentStoreException e) {
            throw new RuntimeException("Failed to delete attachments", e);
        }
        DerivedBlobs.fireRootDeleted(rootDeletedEvent, roots);
        return roots.size();
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
        return listWhere("root_ref IS NULL AND conversation_id = ?", conversationId);
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Owned, OR granted in the legacy shape, OR granted in the new one — one
        // query over the roots, so a blob granted in both shapes is listed once and a
        // grant row whose blob is gone lists nothing.
        return listWhere("root_ref IS NULL AND (conversation_id = ? OR grants @> ARRAY[?]::text[] "
                + "OR storage_ref IN (SELECT storage_ref FROM attachment_grants WHERE conversation_id = ?))",
                conversationId, conversationId, conversationId);
    }

    private List<Attachment> listWhere(String whereClause, String... params) {
        ensureSchema();
        String sql = "SELECT storage_ref, filename, mime_type, size_bytes, conversation_id "
                + "FROM attachments WHERE " + whereClause + " ORDER BY created_at, storage_ref";
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

    // ------------------------------------------------------------------ lineage

    /** A blob joined to its root, as {@link #readLineage} found it. */
    private record Lineage(String storageRef, String filename, String mimeType, long sizeBytes, String rootRef, String parentRef,
            String kind, String unitId, Integer page, String sha256, String lineageRoot, String conversationId, String tenantId,
            String userId, String ownerScope, String ownerKey, Integer pageCount) {

        boolean derived() {
            return rootRef != null;
        }

        Attachment attachment() {
            return new Attachment(storageRef, filename, mimeType, sizeBytes, conversationId);
        }

        Optional<Derivation> derivation() {
            if (!derived()) {
                return Optional.empty();
            }
            return Optional.of(new Derivation(rootRef, parentRef, Kind.fromStored(kind), unitId, page, sha256));
        }
    }

    /**
     * The blob and its root, readable by the requester. A blob that does not exist,
     * whose root is gone, or that the requester cannot read is "not found" — the
     * same answer, so it is no oracle.
     */
    private Lineage readLineage(Connection conn, String storageRef, String requester) throws SQLException, AttachmentStoreException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + LINEAGE_COLUMNS + LINEAGE_FROM)) {
            ps.setString(1, requester);
            ps.setString(2, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !mayRead(rs, requester)) {
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                return new Lineage(rs.getString("storage_ref"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                        rs.getString("root_ref"), rs.getString("parent_ref"), rs.getString("kind"), rs.getString("unit_id"),
                        (Integer) rs.getObject("page"), rs.getString("sha256"), rs.getString("lineage_root"), rs.getString("conversation_id"),
                        rs.getString("root_tenant_id"), rs.getString("root_user_id"), rs.getString("root_owner_scope"),
                        rs.getString("root_owner_key"), (Integer) rs.getObject("root_page_count"));
            }
        }
    }

    private Lineage readLineage(String storageRef, String requester) throws AttachmentStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            return readLineage(conn, storageRef, requester);
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to read attachment lineage", e);
        }
    }

    @Override
    public Attachment storeDerived(byte[] bytes, String mimeType, String filename, Derivation derivation,
                                   String requestingConversationId)
            throws AttachmentStoreException {
        if (derivation == null) {
            throw new AttachmentStoreException("A derived blob needs its derivation");
        }
        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(bytes.length, maxSizeBytes));
        }
        if (!MimeValidator.isCompatibleContent(mimeType, bytes)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(mimeType, MimeValidator.detectMime(bytes)));
        }
        String resolvedMime = MimeValidator.normalize(mimeType != null ? mimeType : MimeValidator.detectMime(bytes));
        String sha256 = DerivedBlobs.sha256Hex(bytes);
        Kind kind = derivation.kind();
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            Lineage from = resolveSource(conn, derivation, requestingConversationId);
            String rootRef = from.lineageRoot();
            String parentRef = from.derived() ? from.storageRef() : null;
            boolean media = DerivedCaps.countsTowardOwnerQuota(kind);
            boolean conversationQuota = media && maxTotalBytesPerConversation > 0;
            boolean userQuota = media && from.userId() != null && maxTotalBytesPerUser > 0;
            String[] stored = new String[1];
            Attachment result = inTransaction(conn, () -> {
                // Lock order: conversation → user → source, as every writer takes them.
                if (conversationQuota) {
                    advisoryLock(conn, LOCK_KEY_PREFIX + "conversation:" + from.conversationId());
                }
                if (userQuota) {
                    advisoryLock(conn, LOCK_KEY_PREFIX + "user:" + from.userId());
                }
                advisoryLock(conn, SOURCE_LOCK_KEY_PREFIX + rootRef);
                // The root again, under the lock and held FOR SHARE: a delete that
                // committed meanwhile reads as gone, one that starts now waits for this
                // insert and then deletes it with the lineage (delete() locks the root
                // FOR UPDATE before it deletes; deleteByConversation() deletes the
                // lineage again in a statement after its wait).
                String ownerKey = lockRoot(conn, rootRef, from);
                Attachment existing = findByContent(conn, ownerKey, rootRef, sha256);
                if (existing != null) {
                    return existing;
                }
                List<String> evict = DerivedCaps.check(kind, bytes.length, lineageOf(conn, rootRef), derivedLimits());
                if (conversationQuota) {
                    AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_CONVERSATION, 0,
                            usage(conn, "conversation_id = ?", from.conversationId())[1], bytes.length, 0, maxTotalBytesPerConversation);
                }
                if (userQuota) {
                    AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_USER, 0, usage(conn, "user_id = ?", from.userId())[1],
                            bytes.length, 0, maxTotalBytesPerUser);
                }
                if (!evict.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachments WHERE root_ref = ? AND storage_ref = ANY(?)")) {
                        ps.setString(1, rootRef);
                        ps.setArray(2, conn.createArrayOf("text", evict.toArray()));
                        ps.executeUpdate();
                    }
                }
                String storageRef = UUID.randomUUID().toString();
                insertDerived(conn, storageRef, from, ownerKey, filename, resolvedMime, bytes,
                        derivation.resolved(rootRef, parentRef, sha256));
                stored[0] = storageRef;
                return new Attachment(storageRef, filename, resolvedMime, bytes.length, from.conversationId());
            });
            if (stored[0] != null) {
                DerivedBlobs.recordStored(meterRegistry, kind, bytes.length);
                LOGGER.debugf("Stored derived %s (%d bytes) of attachment %s", kind, bytes.length, rootRef);
            }
            return result;
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to store a derived attachment", e);
        }
    }

    /**
     * What the new view is taken from: its parent when one is named, else the named
     * root; either may itself be derived. A root and a parent of different lineages
     * are refused.
     */
    private Lineage resolveSource(Connection conn, Derivation derivation, String requester) throws SQLException, AttachmentStoreException {
        String named = derivation.parentRef() != null ? derivation.parentRef() : derivation.rootRef();
        if (named == null) {
            throw new AttachmentStoreException("A derived blob needs its root");
        }
        Lineage from = readLineage(conn, named, requester);
        if (derivation.parentRef() != null && derivation.rootRef() != null
                && !from.lineageRoot().equals(readLineage(conn, derivation.rootRef(), requester).lineageRoot())) {
            throw new AttachmentStoreException("The parent of a derived blob belongs to another root");
        }
        return from;
    }

    /**
     * Locks the root's row for the rest of the transaction and returns its owner
     * key; a root that is gone, or whose owner changed since it was read, is
     * refused.
     */
    private static String lockRoot(Connection conn, String rootRef, Lineage expected) throws SQLException, AttachmentStoreException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT conversation_id, COALESCE(owner_key, conversation_id) FROM attachments WHERE storage_ref = ? AND root_ref IS NULL FOR SHARE")) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("Attachment not found: " + rootRef);
                }
                String ownerKey = rs.getString(2);
                if (!rs.getString(1).equals(expected.conversationId()) || !ownerKey.equals(expected.ownerKey())) {
                    throw new AttachmentStoreException("The owner of this file changed while a view of it was being stored. Please retry.");
                }
                return ownerKey;
            }
        }
    }

    private static Attachment findByContent(Connection conn, String ownerKey, String rootRef, String sha256) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT storage_ref, filename, mime_type, size_bytes, conversation_id FROM attachments "
                + "WHERE owner_key = ? AND root_ref = ? AND sha256 = ? ORDER BY created_at LIMIT 1")) {
            ps.setString(1, ownerKey);
            ps.setString(2, rootRef);
            ps.setString(3, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Attachment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5))
                        : null;
            }
        }
    }

    private static List<DerivedCaps.Existing> lineageOf(Connection conn, String rootRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT storage_ref, kind, size_bytes, last_accessed_at FROM attachments WHERE root_ref = ?")) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                List<DerivedCaps.Existing> lineage = new ArrayList<>();
                while (rs.next()) {
                    Timestamp accessed = rs.getTimestamp(4);
                    lineage.add(new DerivedCaps.Existing(rs.getString(1), Kind.fromStored(rs.getString(2)), rs.getLong(3),
                            accessed != null ? accessed.toInstant() : null));
                }
                return lineage;
            }
        }
    }

    private DerivedCaps.Limits derivedLimits() {
        return new DerivedCaps.Limits(derivedMaxPerSource, derivedMaxTotalBytesPerSource, derivedMaxSlicesPerSource, derivedMaxSliceBytesPerSource,
                derivedMaxTextBytesPerSource);
    }

    private static void insertDerived(Connection conn, String storageRef, Lineage root, String ownerKey, String filename, String mime,
                                      byte[] bytes, Derivation derivation)
            throws SQLException {
        String sql = "INSERT INTO attachments (storage_ref, conversation_id, tenant_id, user_id, filename, mime_type, size_bytes, data, "
                + "owner_scope, owner_key, root_ref, parent_ref, kind, unit_id, page, sha256, last_accessed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, root.conversationId());
            ps.setString(3, root.tenantId());
            ps.setString(4, root.userId());
            ps.setString(5, filename);
            ps.setString(6, mime);
            ps.setLong(7, bytes.length);
            ps.setBytes(8, bytes);
            ps.setString(9, OwnerScope.fromStored(root.ownerScope()).name());
            ps.setString(10, ownerKey);
            ps.setString(11, derivation.rootRef());
            ps.setString(12, derivation.parentRef());
            ps.setString(13, derivation.kind().name());
            ps.setString(14, derivation.unitId());
            ps.setObject(15, derivation.page(), Types.INTEGER);
            ps.setString(16, derivation.sha256());
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        return readLineage(storageRef, requestingConversationId).derivation();
    }

    @Override
    public List<Attachment> listDerived(String rootRef, Kind kind, String requestingConversationId) throws AttachmentStoreException {
        String root = readLineage(rootRef, requestingConversationId).lineageRoot();
        return kind == null
                ? listWhere("root_ref = ?", root)
                : listWhere("root_ref = ? AND kind = ?", root, kind.name());
    }

    @Override
    public Optional<Attachment> findDerived(String rootRef, Kind kind, String unitId, String requestingConversationId)
            throws AttachmentStoreException {
        String root = readLineage(rootRef, requestingConversationId).lineageRoot();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT storage_ref, filename, mime_type, size_bytes, conversation_id FROM attachments "
                        + "WHERE root_ref = ? AND kind = ? AND unit_id IS NOT DISTINCT FROM ? ORDER BY created_at DESC, storage_ref DESC LIMIT 1")) {
            ps.setString(1, root);
            ps.setString(2, kind.name());
            ps.setString(3, unitId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Attachment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to look up a derived attachment", e);
        }
    }

    @Override
    public String rootOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        return readLineage(storageRef, requestingConversationId).lineageRoot();
    }

    @Override
    public OwnerRef ownerOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Lineage lineage = readLineage(storageRef, requestingConversationId);
        return new OwnerRef(OwnerScope.fromStored(lineage.ownerScope()), lineage.ownerKey(), lineage.userId());
    }

    @Override
    public Integer pageCount(String rootRef, String requestingConversationId) throws AttachmentStoreException {
        return readLineage(rootRef, requestingConversationId).pageCount();
    }

    @Override
    public void setPageCount(String rootRef, int pages) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET page_count = ? WHERE storage_ref = ? AND root_ref IS NULL")) {
            ps.setInt(1, pages);
            ps.setString(2, rootRef);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record an attachment's page count", e);
        }
    }

    private static void insert(Connection conn, String storageRef, String conversationId, String tenantId, String userId,
                               String filename, String mime, byte[] bytes)
            throws SQLException {
        String sql = "INSERT INTO attachments "
                + "(storage_ref, conversation_id, tenant_id, user_id, filename, mime_type, size_bytes, data, owner_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, tenantId);
            ps.setString(4, userId);
            ps.setString(5, filename);
            ps.setString(6, mime);
            ps.setLong(7, bytes.length);
            ps.setBytes(8, bytes);
            ps.setString(9, conversationId);
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
                advisoryLock(conn, LOCK_KEY_PREFIX + "conversation:" + conversationId);
            }
            if (userQuota) {
                advisoryLock(conn, LOCK_KEY_PREFIX + "user:" + userId);
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
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    /**
     * {count, totalBytes} of the rows matching {@code where}: the count is of
     * uploads (roots) only, the bytes are the uploads' plus their media views' —
     * the only derived bytes that count toward an owner's quota.
     */
    private static long[] usage(Connection conn, String where, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE root_ref IS NULL), "
                        + "COALESCE(SUM(size_bytes) FILTER (WHERE root_ref IS NULL OR kind IN " + MEDIA_KINDS_SQL + "), 0) "
                        + "FROM attachments WHERE " + where)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    /**
     * Owner-or-grant on the root, from a row of {@link #LINEAGE_COLUMNS}. A
     * stranger asking for a root is denied (the established answer); one asking for
     * a derived blob is told it does not exist, so a view's reference is no oracle.
     */
    private static void authorize(ResultSet rs, String storageRef, String requester) throws SQLException, AttachmentStoreException {
        if (mayRead(rs, requester)) {
            return;
        }
        if (rs.getString("root_ref") != null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        throw new AttachmentAccessDeniedException(
                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(rs.getString("conversation_id"), requester));
    }

    private static boolean mayRead(ResultSet rs, String requester) throws SQLException {
        String owner = rs.getString("conversation_id");
        if (owner != null && owner.equals(requester)) {
            return true;
        }
        if (grantsContain(rs, requester)) {
            return true;
        }
        return rs.getBoolean("granted");
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
