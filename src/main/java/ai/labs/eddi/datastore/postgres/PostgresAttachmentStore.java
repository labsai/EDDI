/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.AttachmentLockOrder;
import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.DerivedBlobs;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.LibraryEntry;
import ai.labs.eddi.engine.attachments.LibraryMeta;
import ai.labs.eddi.engine.attachments.LibraryStoreSupport;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.attachments.OwnerScope;
import ai.labs.eddi.engine.attachments.QuotaMode;
import ai.labs.eddi.engine.attachments.QuotaReservation;
import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobBackendSelector;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import ai.labs.eddi.engine.attachments.blob.BlobLocator;
import ai.labs.eddi.engine.attachments.blob.BlobMetrics;
import ai.labs.eddi.engine.attachments.blob.BlobStreams;
import ai.labs.eddi.engine.attachments.blob.StreamedUpload;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>
 * <b>Reservations</b> ({@code attachment_reservations}) hold quota for writes
 * in flight. Usage is the bucket's rows plus its live reservations, read in one
 * statement; expiry is compared with the database's {@code NOW()}. A
 * reservation is written under the same advisory locks as an upload, and
 * deleted with its conversation.
 * <p>
 * <b>Large files.</b> A streamed upload above
 * {@code eddi.attachments.max-size-bytes} keeps its bytes in a blob backend
 * ({@code pg-chunks}); its row has no {@code data}, and
 * {@code blob_backend}/{@code blob_key} name where they are. Every delete of
 * such a row records a tombstone in {@code attachment_blob_tombstones} in its
 * transaction and then deletes the object; an upload records an intent
 * tombstone before its first byte. See {@link #sweepTombstones}.
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
            "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_dedup ON attachments (owner_key, root_ref, sha256) WHERE root_ref IS NOT NULL",
            // The tombstone sweep asks whether a blob still references an object.
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_blob_key ON attachments (blob_key) WHERE blob_key IS NOT NULL");
    /**
     * Where a blob's bytes live when they are not in {@code data}: the backend and
     * the key of a {@link BlobLocator}, both null for every inline row. And the
     * {@link QuotaMode} a streamed upload was stored under, null for
     * {@link QuotaMode#STANDARD}. {@code data} stops being NOT NULL, as a located
     * row has none; dropping the constraint touches only the catalog.
     */
    private static final List<String> ADD_BLOB_COLUMNS = List.of(
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS blob_backend TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS blob_key TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS quota_mode TEXT",
            "ALTER TABLE attachments ALTER COLUMN data DROP NOT NULL",
            "ALTER TABLE attachment_reservations ADD COLUMN IF NOT EXISTS quota_mode TEXT");
    /**
     * Backend objects waiting to be removed: of a deleted blob whose backend could
     * not delete its bytes (due at once), or of an upload in flight (due once its
     * reservation could no longer be converted). See {@link #sweepTombstones}.
     */
    private static final String CREATE_TOMBSTONES_TABLE = """
            CREATE TABLE IF NOT EXISTS attachment_blob_tombstones (
                backend TEXT NOT NULL,
                blob_key TEXT NOT NULL,
                owner_key TEXT,
                not_before TIMESTAMPTZ NOT NULL,
                attempts INT NOT NULL DEFAULT 0,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (backend, blob_key)
            )
            """;
    /**
     * A library document's own fields, on its root row: null on every
     * conversation-owned row. {@code last_accessed_at} (a lineage column) is its
     * last access, from which retention counts.
     */
    private static final List<String> ADD_LIBRARY_COLUMNS = List.of(
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS library_title TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS library_slug TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS agent_scope TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS contributed_by TEXT",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS saved_at TIMESTAMPTZ",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ",
            "ALTER TABLE attachments ADD COLUMN IF NOT EXISTS retention_days INT");
    /**
     * The library listings, the reaper's scans and the slug check, for library
     * roots only; built CONCURRENTLY with the lineage indexes.
     */
    private static final List<String> CREATE_LIBRARY_INDEXES = List.of(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_library_access ON attachments (owner_key, last_accessed_at) "
                    + "WHERE owner_scope <> 'CONVERSATION' AND root_ref IS NULL",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_library_removed ON attachments (owner_key, removed_at) "
                    + "WHERE owner_scope <> 'CONVERSATION' AND root_ref IS NULL",
            "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_attach_library_slug ON attachments (owner_key, library_slug) "
                    + "WHERE library_slug IS NOT NULL");
    /** A library root: owned by a library, not a conversation. */
    private static final String LIBRARY_ROOT = "owner_scope <> 'CONVERSATION' AND root_ref IS NULL";
    private static final String ENTRY_COLUMNS = "storage_ref, library_title, library_slug, owner_scope, owner_key, agent_scope, "
            + "contributed_by, filename, mime_type, size_bytes, page_count, saved_at, last_accessed_at, removed_at, retention_days";
    private static final String LIBRARY_DELETE_DENIED = "Delete denied: this is a library document; library documents are removed through the library";
    /**
     * How often a derived write starts over when its root changed owner meanwhile.
     */
    static final int OWNER_CHANGE_ATTEMPTS = 3;
    private static final String CREATE_INDEX_TOMBSTONES_DUE = "CREATE INDEX IF NOT EXISTS idx_attach_tomb_due ON attachment_blob_tombstones (not_before)";
    private static final String CREATE_INDEX_TOMBSTONES_OWNER = "CREATE INDEX IF NOT EXISTS idx_attach_tomb_owner ON attachment_blob_tombstones (owner_key)";
    /** How long past its reservation an upload's intent tombstone waits. */
    static final Duration INTENT_GRACE = Duration.ofMinutes(1);
    /** The tombstones a delete or a streamed upload retries on its way out. */
    static final int TOMBSTONE_SWEEP_BATCH = 5;
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
     * Quota held by writes in flight. The columns a bucket is selected by have the
     * names they have in {@code attachments}, so one {@code WHERE} serves both
     * tables in {@link #usage}.
     */
    private static final String CREATE_RESERVATIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS attachment_reservations (
                id TEXT PRIMARY KEY,
                owner_key TEXT NOT NULL,
                conversation_id TEXT,
                user_id TEXT,
                bytes BIGINT NOT NULL,
                count INT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """;
    private static final String CREATE_INDEX_RESERVATIONS_CONV = "CREATE INDEX IF NOT EXISTS idx_attach_resv_conv ON attachment_reservations (conversation_id)";
    private static final String CREATE_INDEX_RESERVATIONS_USER = "CREATE INDEX IF NOT EXISTS idx_attach_resv_user ON attachment_reservations (user_id)";
    private static final String CREATE_INDEX_RESERVATIONS_EXPIRY = "CREATE INDEX IF NOT EXISTS idx_attach_resv_expiry ON attachment_reservations (expires_at)";
    /** The most expired reservations one reserve deletes. */
    static final int SWEEP_BATCH = 100;
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

    @ConfigProperty(name = "eddi.documents.library.max-per-user", defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_PER_USER)
    long libraryMaxPerUser = LibraryStoreSupport.DEFAULT_MAX_PER_USER;

    @ConfigProperty(name = "eddi.documents.library.max-total-bytes-per-user",
                    defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_TOTAL_BYTES_PER_USER)
    long libraryMaxTotalBytesPerUser = LibraryStoreSupport.DEFAULT_MAX_TOTAL_BYTES_PER_USER;

    @ConfigProperty(name = "eddi.documents.library.retention-days", defaultValue = "" + LibraryStoreSupport.DEFAULT_RETENTION_DAYS)
    int libraryRetentionDays = LibraryStoreSupport.DEFAULT_RETENTION_DAYS;

    @ConfigProperty(name = "eddi.documents.library.max-grants-per-document",
                    defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_GRANTS_PER_DOCUMENT)
    int libraryMaxGrantsPerDocument = LibraryStoreSupport.DEFAULT_MAX_GRANTS_PER_DOCUMENT;

    /**
     * The longest a streamed upload may take: the reservation the store takes for
     * one lasts this long.
     */
    @ConfigProperty(name = "eddi.attachments.blob.upload-ttl-seconds", defaultValue = "3600")
    long uploadTtlSeconds = 3600;

    /**
     * Where large blobs go; {@code null} outside CDI (tests), which stores none.
     */
    @Inject
    BlobBackendSelector blobBackends;

    /** Announces deleted roots; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentRootDeleted> rootDeletedEvent;

    /** Announces roots that changed owner; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentOwnerChanged> ownerChangedEvent;

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
            stmt.execute(CREATE_RESERVATIONS_TABLE);
            stmt.execute(CREATE_INDEX_RESERVATIONS_CONV);
            stmt.execute(CREATE_INDEX_RESERVATIONS_USER);
            stmt.execute(CREATE_INDEX_RESERVATIONS_EXPIRY);
            for (String column : ADD_BLOB_COLUMNS) {
                stmt.execute(column);
            }
            for (String column : ADD_LIBRARY_COLUMNS) {
                stmt.execute(column);
            }
            stmt.execute(CREATE_TOMBSTONES_TABLE);
            stmt.execute(CREATE_INDEX_TOMBSTONES_DUE);
            stmt.execute(CREATE_INDEX_TOMBSTONES_OWNER);
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

    // ---------------------------------------------------------------- streaming

    @Override
    public Attachment store(InputStream in, long declaredLength, String declaredMime, String filename, String conversationId,
                            String tenantId, String userId, String reservationId, QuotaMode quotaMode)
            throws AttachmentStoreException {
        if (quotaMode == null) {
            throw new IllegalArgumentException("A streamed upload needs a quota mode");
        }
        if (quotaMode == QuotaMode.LARGE_PER_USER && userId == null) {
            throw new IllegalArgumentException("A large upload counts against its user, so it needs one");
        }
        if (in == null || declaredLength < 1) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        boolean inline = declaredLength <= maxSizeBytes;
        if (!inline && quotaMode == QuotaMode.STANDARD) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(declaredLength, maxSizeBytes));
        }
        if (!inline && blobBackends == null) {
            throw new AttachmentStoreException("No blob backend is configured for large attachments");
        }
        ensureSchema();
        // The quota is held from before the first byte until the blob is recorded.
        QuotaReservation reservation = reservationId != null
                ? requireCovering(reservationId, conversationId, userId, declaredLength, quotaMode)
                : reserve(new OwnerRef(OwnerScope.CONVERSATION, conversationId, userId), conversationId, userId, declaredLength, 1,
                        uploadTtl(), quotaMode);
        boolean stored = false;
        try {
            StreamedUpload upload = StreamedUpload.begin(in, declaredLength, declaredMime);
            Attachment attachment = inline
                    ? storeStreamedInline(upload, filename, conversationId, tenantId, userId, reservation, quotaMode)
                    : storeStreamedInBackend(upload, filename, conversationId, tenantId, userId, reservation, quotaMode);
            stored = true;
            LOGGER.debugf("Stored streamed attachment '%s' (%s, %d bytes, %s) for conversation '%s'", sanitize(filename),
                    attachment.mimeType(), declaredLength, inline ? "inline" : "in its blob backend", sanitize(conversationId));
            return attachment;
        } finally {
            if (!stored) {
                // Used up either way: converted above, or given back here.
                release(reservation.id());
            }
            sweepTombstonesQuietly();
        }
    }

    private Duration uploadTtl() {
        return Duration.ofSeconds(Math.max(1, Math.min(uploadTtlSeconds, QuotaReservation.MAX_TTL.toSeconds())));
    }

    /**
     * A caller's reservation, if it can cover this upload: live, of this
     * conversation, user and quota mode, and holding at least its bytes and one
     * file. Checked before the first byte is read.
     */
    private QuotaReservation requireCovering(String reservationId, String conversationId, String userId, long bytes, QuotaMode mode)
            throws AttachmentStoreException {
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT owner_key, bytes, count, expires_at FROM attachment_reservations "
                        + "WHERE id = ? AND expires_at > NOW() AND conversation_id = ? AND user_id IS NOT DISTINCT FROM ? "
                        + "AND quota_mode IS NOT DISTINCT FROM ?")) {
            ps.setString(1, reservationId);
            ps.setString(2, conversationId);
            ps.setString(3, userId);
            ps.setString(4, mode.stored());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                            "No live quota reservation of this conversation, user and quota mode covers this upload. Please retry.");
                }
                if (rs.getLong(2) < bytes || rs.getInt(3) < 1) {
                    throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                            "The upload (%d bytes) is larger than the %d bytes reserved for it".formatted(bytes, rs.getLong(2)));
                }
                return new QuotaReservation(reservationId, rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getTimestamp(4).toInstant());
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to read an attachment quota reservation", e);
        }
    }

    /**
     * Small enough for the {@code data} column: read whole (bounded by the memory
     * guard, as every upload always was), inserted uncharged and converted in one
     * transaction.
     */
    private Attachment storeStreamedInline(StreamedUpload upload, String filename, String conversationId, String tenantId, String userId,
                                           QuotaReservation reservation, QuotaMode mode)
            throws AttachmentStoreException {
        byte[] bytes = upload.readFully();
        String sha256 = upload.sha256Hex();
        String storageRef = UUID.randomUUID().toString();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                insertStreamed(conn, storageRef, conversationId, tenantId, userId, filename, upload.resolvedMime(), bytes.length, bytes, null,
                        sha256, mode);
                convert(conn, reservation.id(), storageRef);
                return null;
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to store attachment", e);
        }
        return new Attachment(storageRef, filename, upload.resolvedMime(), bytes.length, conversationId);
    }

    /**
     * Too large for the {@code data} column: streamed to the blob backend outside
     * any lock or transaction, then recorded and converted in one transaction.
     * <p>
     * An <em>intent tombstone</em> is written before the first byte and removed in
     * that transaction, due only once the reservation can no longer be converted: a
     * writer that dies mid-upload leaves its object to the sweep. The transaction
     * must remove exactly that tombstone — if the sweep took it first, the object
     * may be gone, and the upload is refused rather than recorded without bytes.
     */
    private Attachment storeStreamedInBackend(StreamedUpload upload, String filename, String conversationId, String tenantId,
                                              String userId, QuotaReservation reservation, QuotaMode mode)
            throws AttachmentStoreException {
        BlobBackend backend = blobBackends.forWrites();
        BlobLocator locator = new BlobLocator(backend.name(), BlobKeys.random());
        long length = upload.declaredLength();
        // Due once the reservation can no longer be converted, by the database's clock.
        writeTombstone(locator, conversationId, reservation.expiresAt().plus(INTENT_GRACE));
        try {
            backend.put(locator.key(), upload.stream(), length, length);
        } catch (IOException e) {
            if (StreamedUpload.keyTaken(e)) {
                removeTombstone(locator);
            } else {
                discard(locator, conversationId);
            }
            throw StreamedUpload.putFailed(e);
        } catch (RuntimeException e) {
            discard(locator, conversationId);
            throw e;
        }
        BlobMetrics.recordWritten(meterRegistry, backend.name(), length);
        String sha256 = upload.sha256Hex();
        String storageRef = UUID.randomUUID().toString();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                if (removeTombstone(conn, locator) != 1) {
                    throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                            "This upload took longer than its quota reservation lasts. Please retry.");
                }
                insertStreamed(conn, storageRef, conversationId, tenantId, userId, filename, upload.resolvedMime(), length, null, locator,
                        sha256, mode);
                convert(conn, reservation.id(), storageRef);
                return null;
            });
        } catch (SQLException e) {
            // The outcome is unknown: a commit can fail after the server applied it. So
            // the object is not deleted here but its tombstone made due; the sweep keeps
            // it if a row turns out to reference it.
            dueNow(locator, conversationId);
            throw new AttachmentStoreException("Failed to record a streamed attachment", e);
        } catch (AttachmentStoreException | RuntimeException e) {
            discard(locator, conversationId);
            throw e;
        }
        return new Attachment(storageRef, filename, upload.resolvedMime(), length, conversationId);
    }

    private static void insertStreamed(Connection conn, String storageRef, String conversationId, String tenantId, String userId,
                                       String filename, String mime, long size, byte[] bytes, BlobLocator locator, String sha256,
                                       QuotaMode mode)
            throws SQLException {
        String sql = "INSERT INTO attachments (storage_ref, conversation_id, tenant_id, user_id, filename, mime_type, size_bytes, data, "
                + "owner_key, sha256, blob_backend, blob_key, quota_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, tenantId);
            ps.setString(4, userId);
            ps.setString(5, filename);
            ps.setString(6, mime);
            ps.setLong(7, size);
            if (bytes != null) {
                ps.setBytes(8, bytes);
            } else {
                ps.setNull(8, Types.BINARY);
            }
            ps.setString(9, conversationId);
            ps.setString(10, sha256);
            ps.setString(11, locator != null ? locator.backend() : null);
            ps.setString(12, locator != null ? locator.key() : null);
            ps.setString(13, mode.stored());
            ps.executeUpdate();
        }
    }

    // --------------------------------------------------------------- tombstones

    /**
     * Writes a tombstone for {@code locator}, due at {@code notBefore} (a database
     * time) or, for {@code null}, at once — or makes an existing one due sooner.
     */
    private void writeTombstone(BlobLocator locator, String ownerKey, Instant notBefore) throws AttachmentStoreException {
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            writeTombstone(conn, locator, ownerKey, notBefore);
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to record an attachment blob tombstone", e);
        }
    }

    private static void writeTombstone(Connection conn, BlobLocator locator, String ownerKey, Instant notBefore) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO attachment_blob_tombstones (backend, blob_key, owner_key, not_before) "
                + "VALUES (?, ?, ?, COALESCE(?, NOW())) ON CONFLICT (backend, blob_key) "
                + "DO UPDATE SET not_before = LEAST(attachment_blob_tombstones.not_before, EXCLUDED.not_before)")) {
            ps.setString(1, locator.backend());
            ps.setString(2, locator.key());
            ps.setString(3, ownerKey);
            ps.setTimestamp(4, notBefore != null ? Timestamp.from(notBefore) : null);
            ps.executeUpdate();
        }
    }

    private static int removeTombstone(Connection conn, BlobLocator locator) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_blob_tombstones WHERE backend = ? AND blob_key = ?")) {
            ps.setString(1, locator.backend());
            ps.setString(2, locator.key());
            return ps.executeUpdate();
        }
    }

    private void removeTombstone(BlobLocator locator) {
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            removeTombstone(conn, locator);
        } catch (SQLException e) {
            LOGGER.debugf("Could not remove an attachment blob tombstone; the sweep will: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * Deletes an object no row references any more, and its tombstone. If the
     * backend cannot delete it now, the tombstone stays (due at once) and a later
     * sweep retries — for every reader the blob is gone already.
     */
    private void discard(BlobLocator locator, String ownerKey) {
        try {
            backendFor(locator).delete(locator.key());
        } catch (IOException | RuntimeException e) {
            BlobMetrics.recordTombstone(meterRegistry, locator.backend());
            LOGGER.warnf("Could not delete an attachment's bytes from blob backend '%s'; left for the tombstone sweep: %s", locator.backend(),
                    sanitize(e.getMessage()));
            try {
                writeTombstone(locator, ownerKey, null);
            } catch (AttachmentStoreException recordFailure) {
                LOGGER.errorf("Could not record a tombstone for an undeleted attachment blob in backend '%s': %s", locator.backend(),
                        sanitize(recordFailure.getMessage()));
            }
            return;
        }
        removeTombstone(locator);
    }

    /**
     * Makes the object's tombstone due at once — or writes one, if a committed
     * record removed it — so the sweep decides by whether a row references it.
     */
    private void dueNow(BlobLocator locator, String ownerKey) {
        try {
            writeTombstone(locator, ownerKey, null);
        } catch (AttachmentStoreException e) {
            // The intent tombstone is still there if the record rolled back, due later.
            LOGGER.warnf("Could not make an attachment blob tombstone due in backend '%s'; it falls due with its reservation: %s",
                    locator.backend(), sanitize(e.getMessage()));
        }
    }

    private void sweepTombstonesQuietly() {
        try {
            sweepTombstones(TOMBSTONE_SWEEP_BATCH);
        } catch (RuntimeException e) {
            LOGGER.debugf("Attachment blob tombstone sweep failed; the next one retries: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * Each due tombstone in a transaction of its own, locked {@code FOR UPDATE SKIP
     * LOCKED}: a writer recording its upload holds its intent tombstone, so the
     * sweep skips it; a writer that comes after the sweep took it finds it gone and
     * refuses its upload. An object a row references is kept and only the tombstone
     * goes. A failed delete is retried later, backing off by the minute.
     */
    @Override
    public int sweepTombstones(int max) {
        if (max < 1) {
            return 0;
        }
        ensureSchema();
        List<BlobLocator> due = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT backend, blob_key FROM attachment_blob_tombstones WHERE not_before <= NOW() ORDER BY not_before LIMIT ?")) {
            ps.setInt(1, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new BlobLocator(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read attachment blob tombstones", e);
        }
        int cleared = 0;
        for (BlobLocator locator : due) {
            try (Connection conn = dataSourceInstance.get().getConnection()) {
                if (inTransaction(conn, () -> sweepOne(conn, locator))) {
                    cleared++;
                }
            } catch (SQLException | AttachmentStoreException e) {
                LOGGER.debugf("Could not sweep an attachment blob tombstone: %s", sanitize(e.getMessage()));
            }
        }
        return cleared;
    }

    private boolean sweepOne(Connection conn, BlobLocator locator) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM attachment_blob_tombstones WHERE backend = ? AND blob_key = ? "
                + "AND not_before <= NOW() FOR UPDATE SKIP LOCKED")) {
            ps.setString(1, locator.backend());
            ps.setString(2, locator.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
            }
        }
        boolean referenced;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM attachments WHERE blob_key = ? AND blob_backend = ? LIMIT 1")) {
            ps.setString(1, locator.key());
            ps.setString(2, locator.backend());
            try (ResultSet rs = ps.executeQuery()) {
                referenced = rs.next();
            }
        }
        if (!referenced) {
            try {
                backendFor(locator).delete(locator.key());
            } catch (IOException | RuntimeException e) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE attachment_blob_tombstones SET attempts = attempts + 1, "
                        + "not_before = NOW() + LEAST(attempts + 1, 60) * INTERVAL '1 minute' WHERE backend = ? AND blob_key = ?")) {
                    ps.setString(1, locator.backend());
                    ps.setString(2, locator.key());
                    ps.executeUpdate();
                }
                return false;
            }
        }
        removeTombstone(conn, locator);
        return true;
    }

    @Override
    public long pendingTombstones(String ownerKeyOrNull) {
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM attachment_blob_tombstones" + (ownerKeyOrNull != null ? " WHERE owner_key = ?" : "");
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (ownerKeyOrNull != null) {
                ps.setString(1, ownerKeyOrNull);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count attachment blob tombstones", e);
        }
    }

    /**
     * A located row a delete removed: its object and the owner it was stored for.
     */
    private record Removed(BlobLocator locator, String ownerKey) {
    }

    /**
     * Reads {@code blob_backend, blob_key} at columns {@code backendColumn} and the
     * next from a {@code DELETE … RETURNING} row; inline rows add nothing.
     */
    private static void collectLocated(ResultSet rs, int backendColumn, String ownerKey, List<Removed> into) throws SQLException {
        String key = rs.getString(backendColumn + 1);
        if (key != null) {
            into.add(new Removed(new BlobLocator(rs.getString(backendColumn), key), ownerKey));
        }
    }

    /**
     * Tombstones (due at once) for the located rows a delete removed, in its
     * transaction.
     */
    private static void tombstoneAll(Connection conn, List<Removed> removed) throws SQLException {
        for (Removed row : removed) {
            writeTombstone(conn, row.locator(), row.ownerKey(), null);
        }
    }

    /** After the delete committed: the objects of the rows it removed. */
    private void discardAll(List<Removed> removed) {
        for (Removed row : removed) {
            discard(row.locator(), row.ownerKey());
        }
        if (!removed.isEmpty()) {
            sweepTombstonesQuietly();
        }
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Located located = locate(storageRef, requestingConversationId, false);
        // On size, not on where the bytes are: an inline row above a guard lowered
        // later is refused too, and a located row within one raised later loads.
        if (located.sizeBytes() > maxSizeBytes) {
            throw new AttachmentTooLargeForMemoryException(located.sizeBytes(), maxSizeBytes);
        }
        if (located.locator() == null) {
            return located.data();
        }
        try (InputStream in = openLocated(located.locator(), 0, -1)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to read the attachment's bytes from its blob backend", e);
        }
    }

    @Override
    public InputStream open(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Located located = locate(storageRef, requestingConversationId, true);
        if (located.locator() == null) {
            return new ByteArrayInputStream(located.data());
        }
        try {
            return openLocated(located.locator(), 0, -1);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to open the attachment's bytes in its blob backend", e);
        }
    }

    @Override
    public InputStream openRange(String storageRef, String requestingConversationId, long offset, long length)
            throws AttachmentStoreException {
        BlobStreams.requireValidRange(offset, length);
        Located located = locate(storageRef, requestingConversationId, true);
        if (located.locator() == null) {
            byte[] data = located.data();
            int from = (int) Math.min(offset, data.length);
            int count = (int) Math.min(length, data.length - from);
            return new ByteArrayInputStream(data, from, count);
        }
        try {
            return openLocated(located.locator(), offset, length);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to open the attachment's bytes in its blob backend", e);
        }
    }

    /**
     * A located blob's bytes from its backend — all of them for a negative
     * {@code length}. A backend that has no object under a key some row still names
     * has lost data: a store failure, never "not found".
     */
    private InputStream openLocated(BlobLocator locator, long offset, long length) throws IOException {
        BlobBackend backend = backendFor(locator);
        try {
            InputStream in = length < 0 ? backend.get(locator.key()) : backend.getRange(locator.key(), offset, length);
            return BlobMetrics.countingReads(meterRegistry, backend.name(), in);
        } catch (NoSuchFileException e) {
            throw new IOException("The blob backend has no object for this attachment", e);
        }
    }

    private BlobBackend backendFor(BlobLocator locator) throws IOException {
        if (blobBackends == null) {
            throw new IOException("No blob backend is configured on this node");
        }
        return blobBackends.forLocator(locator);
    }

    /**
     * What a read needs of a blob the requester may read: its size, and its bytes
     * when they are inline ({@code null} when located elsewhere or larger than the
     * memory guard — those are never fetched).
     */
    private record Located(long sizeBytes, byte[] data, BlobLocator locator) {
    }

    /**
     * Authorizes like {@link #load} always has, then returns where the bytes are
     * ({@code forStream}: also an inline row above the memory guard, which
     * {@link #load} refuses unread). A slice is touched: slices are a
     * least-recently-used cache; nothing else is, so an ordinary read stays a read.
     */
    private Located locate(String storageRef, String requestingConversationId, boolean forStream) throws AttachmentStoreException {
        ensureSchema();
        String sql = "SELECT CASE WHEN a.blob_key IS NULL AND a.size_bytes <= ? THEN a.data END AS data, "
                + "a.blob_backend, a.blob_key, " + LINEAGE_COLUMNS + LINEAGE_FROM;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, maxSizeBytes);
            ps.setString(2, requestingConversationId);
            ps.setString(3, storageRef);
            Located located;
            boolean slice;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                authorize(rs, storageRef, requestingConversationId);
                long size = rs.getLong("size_bytes");
                String key = rs.getString("blob_key");
                BlobLocator locator = key != null ? new BlobLocator(rs.getString("blob_backend"), key) : null;
                byte[] data = rs.getBytes("data");
                if (locator == null && data == null && size <= maxSizeBytes) {
                    throw new AttachmentStoreException("Attachment " + storageRef + " has no bytes");
                }
                located = new Located(size, data, locator);
                slice = Kind.PDF_SLICE.name().equals(rs.getString("kind"));
            }
            if (slice) {
                touch(conn, storageRef);
            }
            if (forStream && located.locator() == null && located.data() == null) {
                // An inline row above a guard lowered since it was stored: a stream may
                // have it; load() refuses it on its size without reading it.
                located = new Located(located.sizeBytes(), readInline(conn, storageRef), null);
            }
            return located;
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to load attachment", e);
        }
    }

    private static byte[] readInline(Connection conn, String storageRef) throws SQLException, AttachmentStoreException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM attachments WHERE storage_ref = ?")) {
            ps.setString(1, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getBytes(1) == null) {
                    throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                }
                return rs.getBytes(1);
            }
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
                + "WHERE storage_ref = COALESCE((SELECT root_ref FROM attachments WHERE storage_ref = ?), ?) AND root_ref IS NULL "
                + "RETURNING storage_ref, owner_scope";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                String rootRef;
                OwnerScope scope;
                try (PreparedStatement ps = conn.prepareStatement(legacy)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, conversationId);
                    ps.setString(3, storageRef);
                    ps.setString(4, storageRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            // The same subtype the MongoDB store throws, so a caller telling "gone"
                            // from "the store failed" gets the same answer on both backends.
                            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                        }
                        rootRef = rs.getString(1);
                        scope = OwnerScope.fromStored(rs.getString(2));
                    }
                }
                boolean added;
                try (PreparedStatement ps = conn.prepareStatement(INSERT_GRANT_ON_ROOT)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, storageRef);
                    added = ps.executeUpdate() > 0;
                }
                if (scope != OwnerScope.CONVERSATION) {
                    // A library document is opened by any number of conversations: past the
                    // cap the oldest grant goes, never the one just written. The legacy
                    // UPDATE above holds the root's row, so evictions of one document run
                    // one at a time.
                    evictGrants(conn, rootRef, conversationId);
                }
                return added;
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
        for (String index : CREATE_LIBRARY_INDEXES) {
            try {
                executeUpdate(index);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment library index; library lookups fall back to a scan: %s", sanitize(e.getMessage()));
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

    /**
     * Evicts the grants of a library document beyond
     * {@code eddi.documents.library.max-grants-per-document}, oldest first, in both
     * shapes, keeping {@code keep}'s; inside the caller's transaction, which holds
     * the root's row.
     */
    private void evictGrants(Connection conn, String rootRef, String keep) throws SQLException {
        if (libraryMaxGrantsPerDocument <= 0) {
            return;
        }
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT conversation_id FROM attachment_grants WHERE storage_ref = ? ORDER BY created_at, conversation_id")) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
        }
        List<String> legacy = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT grants FROM attachments WHERE storage_ref = ?")) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getArray(1) != null && rs.getArray(1).getArray() instanceof Object[] elements) {
                    for (Object element : elements) {
                        if (element != null) {
                            legacy.add(String.valueOf(element));
                        }
                    }
                }
            }
        }
        List<String> evict = LibraryStoreSupport.grantsToEvict(legacy, rows, keep, libraryMaxGrantsPerDocument);
        if (evict.isEmpty()) {
            return;
        }
        Array evicted = conn.createArrayOf("text", evict.toArray());
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_grants WHERE storage_ref = ? AND conversation_id = ANY(?)")) {
            ps.setString(1, rootRef);
            ps.setArray(2, evicted);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE attachments SET grants = ARRAY(SELECT g FROM unnest(grants) AS g WHERE NOT (g = ANY(?))) WHERE storage_ref = ?")) {
            ps.setArray(1, evicted);
            ps.setString(2, rootRef);
            ps.executeUpdate();
        }
        LOGGER.debugf("Evicted %d grants of library document %s (cap %d)", (Object) evict.size(), rootRef, (Object) libraryMaxGrantsPerDocument);
    }

    @Override
    public boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        ensureSchema();
        boolean deleted;
        AttachmentRootDeleted announced = null;
        List<Removed> located = new ArrayList<>();
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
            // Whichever conversation asks — the one it came from included.
            if (OwnerScope.fromStored(scope) != OwnerScope.CONVERSATION) {
                throw new AttachmentAccessDeniedException(LIBRARY_DELETE_DENIED);
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
                // Lock the root's row first — a root FOR UPDATE, a view's root FOR SHARE.
                // A storeDerived holding the root FOR SHARE has its view committed before
                // this returns, and the DELETE below, a new statement, sees that view; one
                // starting now finds the root gone. Without it the DELETE would wait on the
                // root row and then work from its old snapshot, leaving a view inserted
                // meanwhile behind. Under the lock the owner is read again: a promote that
                // committed meanwhile made it a library document, which this may not delete.
                try (PreparedStatement ps = conn.prepareStatement(rootRef == null
                        ? "SELECT owner_scope FROM attachments WHERE storage_ref = ? FOR UPDATE"
                        : "SELECT owner_scope FROM attachments WHERE storage_ref = ? AND root_ref IS NULL FOR SHARE")) {
                    ps.setString(1, rootRef == null ? storageRef : rootRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && OwnerScope.fromStored(rs.getString(1)) != OwnerScope.CONVERSATION) {
                            throw new AttachmentAccessDeniedException(LIBRARY_DELETE_DENIED);
                        }
                    }
                }
                boolean removed = false;
                located.clear();
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachments WHERE storage_ref = ? OR root_ref = ? "
                        + "RETURNING blob_backend, blob_key, COALESCE(owner_key, conversation_id)")) {
                    ps.setString(1, storageRef);
                    ps.setString(2, storageRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            removed = true;
                            collectLocated(rs, 1, rs.getString(3), located);
                        }
                    }
                }
                // Bytes elsewhere are marked for removal in the same commit as their row,
                // so no object outlives its blob unnoticed if this node stops next.
                tombstoneAll(conn, located);
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
        discardAll(located);
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
        List<Removed> located = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            List<String> refs = new ArrayList<>();
            int held = inTransaction(conn, () -> {
                // The quota this conversation's writes in flight held, before the blobs.
                // Deleted after them, a blob committed after the blob DELETE's snapshot
                // could still be converted, and would survive the erasure. Deleted
                // first, every later convert is refused and its writer deletes the
                // blob; a convert that already holds the row commits before this
                // statement returns, and the blob DELETE below, a new statement, sees
                // its blob.
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_reservations WHERE conversation_id = ?")) {
                    ps.setString(1, conversationId);
                    ps.executeUpdate();
                }
                // A library document that came from this conversation keeps its
                // conversation id as origin; it is the library's, and stays.
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM attachments WHERE conversation_id = ? AND owner_scope = 'CONVERSATION' "
                                + "RETURNING storage_ref, root_ref, owner_scope, owner_key, user_id, blob_backend, blob_key")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String ref = rs.getString(1);
                            refs.add(ref);
                            if (rs.getString(2) == null) {
                                roots.add(rootDeleted(ref, rs.getString(3), rs.getString(4), conversationId, rs.getString(5)));
                            }
                            collectLocated(rs, 6, rs.getString(4) != null ? rs.getString(4) : conversationId, located);
                        }
                    }
                }
                if (!roots.isEmpty()) {
                    // The lineage once more, as a new statement: the DELETE above may have
                    // waited on a root a storeDerived held FOR SHARE, and then worked from
                    // a snapshot without the view that storeDerived committed.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM attachments WHERE root_ref = ANY(?) RETURNING blob_backend, blob_key, COALESCE(owner_key, conversation_id)")) {
                        ps.setArray(1, conn.createArrayOf("text", roots.stream().map(AttachmentRootDeleted::rootRef).toArray()));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                collectLocated(rs, 1, rs.getString(3), located);
                            }
                        }
                    }
                }
                tombstoneAll(conn, located);
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
        discardAll(located);
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
        // A library document keeps its origin conversation's id, but is not its own.
        return listWhere("root_ref IS NULL AND conversation_id = ? AND owner_scope = 'CONVERSATION'", conversationId);
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Owned, OR granted in the legacy shape, OR granted in the new one — one
        // query over the roots, so a blob granted in both shapes is listed once and a
        // grant row whose blob is gone lists nothing. A library document is listed
        // through a grant only, and not while it is soft-removed.
        return listWhere("root_ref IS NULL AND removed_at IS NULL AND ((conversation_id = ? AND owner_scope = 'CONVERSATION') "
                + "OR grants @> ARRAY[?]::text[] OR storage_ref IN (SELECT storage_ref FROM attachment_grants WHERE conversation_id = ?))",
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
            // The owner is the root's at write time: when it changes between the read
            // and the lock (a promote), the write starts over with the new owner — its
            // buckets and locks — rather than failing.
            for (int attempt = 1;; attempt++) {
                Lineage from = resolveSource(conn, derivation, requestingConversationId);
                try {
                    return storeDerivedOnce(conn, from, bytes, resolvedMime, filename, derivation, sha256);
                } catch (OwnerChangedException e) {
                    if (attempt >= OWNER_CHANGE_ATTEMPTS) {
                        throw new AttachmentStoreException("The owner of this file changed while a view of it was being stored. Please retry.");
                    }
                }
            }
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to store a derived attachment", e);
        }
    }

    /** A root's owner changed between reading it and locking it. */
    private static final class OwnerChangedException extends AttachmentStoreException {
        OwnerChangedException() {
            super("The owner of this file changed while a view of it was being stored");
        }
    }

    private Attachment storeDerivedOnce(Connection conn, Lineage from, byte[] bytes, String resolvedMime, String filename, Derivation derivation,
                                        String sha256)
            throws SQLException, AttachmentStoreException {
        Kind kind = derivation.kind();
        String rootRef = from.lineageRoot();
        String parentRef = from.derived() ? from.storageRef() : null;
        boolean media = DerivedCaps.countsTowardOwnerQuota(kind);
        OwnerScope scope = OwnerScope.fromStored(from.ownerScope());
        boolean library = scope != OwnerScope.CONVERSATION;
        boolean conversationQuota = media && !library && maxTotalBytesPerConversation > 0;
        boolean userQuota = media && !library && from.userId() != null && maxTotalBytesPerUser > 0;
        boolean libraryQuota = media && library && libraryMaxTotalBytesPerUser > 0;
        String[] stored = new String[1];
        Attachment result = inTransaction(conn, () -> {
            // Lock order: conversation → user → team → source, as every writer takes
            // them; a library's bucket locks in its owner's place.
            List<Lock> locks = new ArrayList<>();
            if (conversationQuota) {
                locks.add(new Lock(AttachmentLockOrder.Scope.CONVERSATION, from.conversationId()));
            }
            if (userQuota) {
                locks.add(new Lock(AttachmentLockOrder.Scope.USER, from.userId()));
            }
            if (libraryQuota) {
                locks.add(LibraryStoreSupport.bucketLock(scope, from.ownerKey()));
            }
            locks.add(new Lock(AttachmentLockOrder.Scope.SOURCE, rootRef));
            lockAll(conn, locks);
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
            if (libraryQuota) {
                LibraryStoreSupport.checkQuota(0, libraryUsage(conn, scope, ownerKey)[1], 0, bytes.length, libraryLimits());
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
     * key; a root that is gone is not found, and one whose owner changed since it
     * was read makes the write start over ({@link OwnerChangedException}).
     */
    private static String lockRoot(Connection conn, String rootRef, Lineage expected) throws SQLException, AttachmentStoreException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT conversation_id, COALESCE(owner_key, conversation_id), owner_scope "
                + "FROM attachments WHERE storage_ref = ? AND root_ref IS NULL FOR SHARE")) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("Attachment not found: " + rootRef);
                }
                String ownerKey = rs.getString(2);
                if (!rs.getString(1).equals(expected.conversationId()) || !ownerKey.equals(expected.ownerKey())
                        || OwnerScope.fromStored(rs.getString(3)) != OwnerScope.fromStored(expected.ownerScope())) {
                    throw new OwnerChangedException();
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
            List<Lock> locks = new ArrayList<>();
            if (conversationQuota) {
                locks.add(new Lock(AttachmentLockOrder.Scope.CONVERSATION, conversationId));
            }
            if (userQuota) {
                locks.add(new Lock(AttachmentLockOrder.Scope.USER, userId));
            }
            lockAll(conn, locks);
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
     * Takes the transaction-scoped advisory locks {@code wanted} needs, in the one
     * order every writer uses ({@link AttachmentLockOrder}). The keys are the ones
     * this store has always used, so a node of an older release serializes on the
     * same locks.
     */
    private static void lockAll(Connection conn, List<Lock> wanted) throws SQLException {
        for (Lock lock : AttachmentLockOrder.ordered(wanted)) {
            advisoryLock(conn, lockKey(lock));
        }
    }

    static String lockKey(Lock lock) {
        return lock.scope() == AttachmentLockOrder.Scope.SOURCE
                ? SOURCE_LOCK_KEY_PREFIX + lock.id()
                : LOCK_KEY_PREFIX + lock.scope().name().toLowerCase(Locale.ROOT) + ":" + lock.id();
    }

    /**
     * {count, totalBytes} of a bucket — the rows matching {@code where} plus the
     * live reservations matching it, read in one statement so that a reservation
     * converted meanwhile is seen either as itself or as its blob. The count is of
     * uploads (roots) only, the bytes are the uploads' plus their media views' —
     * the only derived bytes that count toward an owner's quota. A reservation
     * counts until it expires by the database's clock. In the conversation's bucket
     * the bytes of {@link QuotaMode#LARGE_PER_USER} uploads and reservations do not
     * count — they count in the user's — though their files do.
     *
     * @param where
     *            {@code conversation_id = ?} or {@code user_id = ?}: the columns
     *            have the same name in both tables
     */
    private static long[] usage(Connection conn, String where, String value) throws SQLException {
        String largeExempt = where.startsWith("conversation_id")
                ? " AND quota_mode IS DISTINCT FROM '" + QuotaMode.LARGE_PER_USER.name() + "'"
                : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.files + r.files, a.bytes + r.bytes FROM "
                        + "(SELECT COUNT(*) FILTER (WHERE root_ref IS NULL) AS files, "
                        + "COALESCE(SUM(size_bytes) FILTER (WHERE (root_ref IS NULL OR kind IN " + MEDIA_KINDS_SQL + ")" + largeExempt
                        // A library document and its views count in the library's bucket only.
                        + "), 0) AS bytes FROM attachments WHERE " + where + " AND owner_scope = 'CONVERSATION') a, "

                        + "(SELECT COALESCE(SUM(count), 0) AS files, COALESCE(SUM(bytes) FILTER (WHERE TRUE" + largeExempt + "), 0) AS bytes "
                        + "FROM attachment_reservations WHERE " + where + " AND expires_at > NOW()) r")) {
            ps.setString(1, value);
            ps.setString(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    // ------------------------------------------------------------------ library

    private LibraryStoreSupport.Limits libraryLimits() {
        return new LibraryStoreSupport.Limits(libraryMaxPerUser, libraryMaxTotalBytesPerUser, libraryRetentionDays, libraryMaxGrantsPerDocument);
    }

    /**
     * {count, totalBytes} of a library's bucket: its documents (soft-removed ones
     * included) and the bytes of them and their media views.
     */
    private static long[] libraryUsage(Connection conn, OwnerScope scope, String ownerKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE root_ref IS NULL), "
                + "COALESCE(SUM(size_bytes) FILTER (WHERE root_ref IS NULL OR kind IN " + MEDIA_KINDS_SQL + "), 0) "
                + "FROM attachments WHERE owner_key = ? AND owner_scope = ?")) {
            ps.setString(1, ownerKey);
            ps.setString(2, scope.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    /** The bytes of a root's media views, which move with it into a library. */
    private static long mediaBytesOfLineage(Connection conn, String rootRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(size_bytes), 0) FROM attachments WHERE root_ref = ? AND kind IN " + MEDIA_KINDS_SQL)) {
            ps.setString(1, rootRef);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static LibraryStoreSupport.SlugLookup slugLookup(Connection conn, String ownerKey) {
        return candidate -> {
            try {
                return slugTaken(conn, ownerKey, candidate);
            } catch (SQLException e) {
                throw new AttachmentStoreException("Failed to check a library slug", e);
            }
        };
    }

    private static boolean slugTaken(Connection conn, String ownerKey, String slug) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM attachments WHERE owner_key = ? AND " + LIBRARY_ROOT + " AND library_slug = ? LIMIT 1")) {
            ps.setString(1, ownerKey);
            ps.setString(2, slug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Moves a root's lineage to the new owner, as a statement after the root's
     * update: the root is locked FOR UPDATE by then, so a view committed before it
     * is seen here and one written after it reads the new owner.
     */
    private static void rekeyLineage(Connection conn, String rootRef, OwnerRef newOwner) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET owner_scope = ?, owner_key = ? WHERE root_ref = ?")) {
            ps.setString(1, newOwner.scope().name());
            ps.setString(2, newOwner.ownerKey());
            ps.setString(3, rootRef);
            ps.executeUpdate();
        }
    }

    @Override
    public LibraryEntry promote(String storageRef, String owningConversationId, OwnerRef newOwner, LibraryMeta meta)
            throws AttachmentStoreException {
        LibraryStoreSupport.requireLibraryOwner(newOwner);
        if (meta == null) {
            throw new IllegalArgumentException("A library document needs its title and slug");
        }
        ensureSchema();
        String[] oldOwnerKey = new String[1];
        LibraryEntry entry;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            entry = inTransaction(conn, () -> {
                // Lock order: the library's bucket, then the root's source lock — after it
                // no view of the old owner can be stored, before it none was half-stored.
                lockAll(conn, List.of(LibraryStoreSupport.bucketLock(newOwner.scope(), newOwner.ownerKey()),
                        new Lock(AttachmentLockOrder.Scope.SOURCE, storageRef)));
                long size;
                try (PreparedStatement ps = conn.prepareStatement("SELECT root_ref, owner_scope, conversation_id, "
                        + "COALESCE(owner_key, conversation_id), size_bytes FROM attachments WHERE storage_ref = ? FOR UPDATE")) {
                    ps.setString(1, storageRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
                        }
                        if (rs.getString(1) != null) {
                            throw new AttachmentStoreException("Only an uploaded file can be put into a library, not a view of one");
                        }
                        if (OwnerScope.fromStored(rs.getString(2)) != OwnerScope.CONVERSATION) {
                            throw new AttachmentAccessDeniedException("This file is in a library already");
                        }
                        if (!rs.getString(3).equals(owningConversationId)) {
                            throw new AttachmentAccessDeniedException("Only the conversation a file was uploaded to can put it into a library");
                        }
                        oldOwnerKey[0] = rs.getString(4);
                        size = rs.getLong(5);
                    }
                }
                long[] usage = libraryUsage(conn, newOwner.scope(), newOwner.ownerKey());
                LibraryStoreSupport.checkQuota(usage[0], usage[1], 1, size + mediaBytesOfLineage(conn, storageRef), libraryLimits());
                String slug = LibraryStoreSupport.uniqueSlug(meta.slug(), slugLookup(conn, newOwner.ownerKey()));
                try (PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET owner_scope = ?, owner_key = ?, library_title = ?, "
                        + "library_slug = ?, agent_scope = ?, contributed_by = ?, retention_days = ?, saved_at = NOW(), last_accessed_at = NOW(), "
                        + "removed_at = NULL WHERE storage_ref = ?")) {
                    ps.setString(1, newOwner.scope().name());
                    ps.setString(2, newOwner.ownerKey());
                    ps.setString(3, meta.title());
                    ps.setString(4, slug);
                    ps.setString(5, meta.agentScope());
                    ps.setString(6, newOwner.ownerUserId());
                    ps.setObject(7, meta.retentionDays(), Types.INTEGER);
                    ps.setString(8, storageRef);
                    ps.executeUpdate();
                }
                rekeyLineage(conn, storageRef, newOwner);
                // The promoting conversation reads it through a grant from now on, in both
                // shapes, like any grantee.
                try (PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET grants = CASE WHEN ? = ANY(COALESCE(grants, '{}')) "
                        + "THEN grants ELSE array_append(COALESCE(grants, '{}'), ?) END WHERE storage_ref = ?")) {
                    ps.setString(1, owningConversationId);
                    ps.setString(2, owningConversationId);
                    ps.setString(3, storageRef);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO attachment_grants (storage_ref, conversation_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, storageRef);
                    ps.setString(2, owningConversationId);
                    ps.executeUpdate();
                }
                evictGrants(conn, storageRef, owningConversationId);
                return readEntry(conn, newOwner.ownerKey(), storageRef)
                        .orElseThrow(() -> new AttachmentStoreException("A promoted library document could not be read back"));
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to put an attachment into a library", e);
        }
        DerivedBlobs.fireOwnerChanged(ownerChangedEvent,
                new AttachmentOwnerChanged(storageRef, oldOwnerKey[0], newOwner.ownerKey(), newOwner.scope()));
        LOGGER.debugf("Put attachment %s into a %s library", storageRef, newOwner.scope());
        return entry;
    }

    @Override
    public LibraryEntry moveOwner(String storageRef, String oldOwnerKey, OwnerRef newOwner) throws AttachmentStoreException {
        LibraryStoreSupport.requireLibraryOwner(newOwner);
        if (newOwner.ownerKey().equals(oldOwnerKey)) {
            throw new IllegalArgumentException("A document moves to another owner, not to its own");
        }
        ensureSchema();
        LibraryEntry entry;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            entry = inTransaction(conn, () -> {
                lockAll(conn, List.of(LibraryStoreSupport.bucketLock(newOwner.scope(), newOwner.ownerKey()),
                        new Lock(AttachmentLockOrder.Scope.SOURCE, storageRef)));
                long size;
                String wantedSlug;
                try (PreparedStatement ps = conn.prepareStatement("SELECT size_bytes, library_slug FROM attachments WHERE storage_ref = ? "
                        + "AND owner_key = ? AND " + LIBRARY_ROOT + " FOR UPDATE")) {
                    ps.setString(1, storageRef);
                    ps.setString(2, oldOwnerKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new AttachmentNotFoundException("Library document not found: " + storageRef);
                        }
                        size = rs.getLong(1);
                        wantedSlug = rs.getString(2);
                    }
                }
                long[] usage = libraryUsage(conn, newOwner.scope(), newOwner.ownerKey());
                LibraryStoreSupport.checkQuota(usage[0], usage[1], 1, size + mediaBytesOfLineage(conn, storageRef), libraryLimits());
                String slug = LibraryStoreSupport.uniqueSlug(wantedSlug, slugLookup(conn, newOwner.ownerKey()));
                try (PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET owner_scope = ?, owner_key = ?, library_slug = ?, "
                        + "saved_at = NOW() WHERE storage_ref = ?")) {
                    ps.setString(1, newOwner.scope().name());
                    ps.setString(2, newOwner.ownerKey());
                    ps.setString(3, slug);
                    ps.setString(4, storageRef);
                    ps.executeUpdate();
                }
                rekeyLineage(conn, storageRef, newOwner);
                return readEntry(conn, newOwner.ownerKey(), storageRef)
                        .orElseThrow(() -> new AttachmentStoreException("A moved library document could not be read back"));
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to move a library document", e);
        }
        DerivedBlobs.fireOwnerChanged(ownerChangedEvent, new AttachmentOwnerChanged(storageRef, oldOwnerKey, newOwner.ownerKey(), newOwner.scope()));
        return entry;
    }

    private static Optional<LibraryEntry> readEntry(Connection conn, String ownerKey, String storageRef) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + ENTRY_COLUMNS + " FROM attachments WHERE storage_ref = ? AND owner_key = ? AND " + LIBRARY_ROOT)) {
            ps.setString(1, storageRef);
            ps.setString(2, ownerKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(entry(rs)) : Optional.empty();
            }
        }
    }

    private static LibraryEntry entry(ResultSet rs) throws SQLException {
        return new LibraryEntry(rs.getString("storage_ref"), rs.getString("library_title"), rs.getString("library_slug"),
                OwnerScope.fromStored(rs.getString("owner_scope")), rs.getString("owner_key"), rs.getString("agent_scope"),
                rs.getString("contributed_by"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                (Integer) rs.getObject("page_count"), instant(rs, "saved_at"), instant(rs, "last_accessed_at"), instant(rs, "removed_at"),
                (Integer) rs.getObject("retention_days"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value != null ? value.toInstant() : null;
    }

    /** Library entries matching {@code where} (after {@link #LIBRARY_ROOT}). */
    private List<LibraryEntry> entriesWhere(String where, String orderBy, int limit, int offset, Object... params) {
        ensureSchema();
        String sql = "SELECT " + ENTRY_COLUMNS + " FROM attachments WHERE " + LIBRARY_ROOT + where + " ORDER BY " + orderBy
                + " LIMIT ? OFFSET ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object param : params) {
                if (param instanceof Instant when) {
                    ps.setTimestamp(i++, Timestamp.from(when));
                } else if (param instanceof Integer number) {
                    ps.setInt(i++, number);
                } else {
                    ps.setString(i++, (String) param);
                }
            }
            ps.setInt(i++, LibraryStoreSupport.pageLimit(limit));
            ps.setInt(i, LibraryStoreSupport.pageOffset(offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<LibraryEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(entry(rs));
                }
                return entries;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list library documents", e);
        }
    }

    @Override
    public List<LibraryEntry> listByOwner(String ownerKey, String agentScopeOrNull, boolean includeRemoved, int offset, int limit) {
        if (ownerKey == null) {
            return List.of();
        }
        String where = " AND owner_key = ?" + (agentScopeOrNull != null ? " AND (agent_scope = ? OR agent_scope IS NULL)" : "")
                + (includeRemoved ? "" : " AND removed_at IS NULL");
        Object[] params = agentScopeOrNull != null ? new Object[]{ownerKey, agentScopeOrNull} : new Object[]{ownerKey};
        return entriesWhere(where, "COALESCE(last_accessed_at, saved_at) DESC, storage_ref", limit, offset, params);
    }

    @Override
    public Optional<LibraryEntry> getLibraryEntry(String ownerKey, String storageRef) {
        if (ownerKey == null || storageRef == null) {
            return Optional.empty();
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            return readEntry(conn, ownerKey, storageRef);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a library document", e);
        }
    }

    @Override
    public void touchAccess(String storageRef) {
        ensureSchema();
        executeUpdate("UPDATE attachments SET last_accessed_at = NOW() WHERE storage_ref = ? AND " + LIBRARY_ROOT, storageRef);
    }

    @Override
    public boolean softRemove(String ownerKey, String storageRef, Instant when) {
        if (when == null) {
            throw new IllegalArgumentException("A soft removal needs its time");
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET removed_at = ? WHERE storage_ref = ? AND owner_key = ? AND "
                        + LIBRARY_ROOT + " AND removed_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setString(2, storageRef);
            ps.setString(3, ownerKey);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove a library document", e);
        }
    }

    @Override
    public boolean restore(String ownerKey, String storageRef) {
        ensureSchema();
        return executeUpdate("UPDATE attachments SET removed_at = NULL WHERE storage_ref = ? AND owner_key = ? AND " + LIBRARY_ROOT
                + " AND removed_at IS NOT NULL", storageRef, ownerKey) > 0;
    }

    @Override
    public long deleteByOwner(String ownerKey) {
        return deleteLibraryRoots(ownerKey, null);
    }

    @Override
    public boolean deleteLibraryDocument(String ownerKey, String storageRef) {
        return storageRef != null && deleteLibraryRoots(ownerKey, storageRef) > 0;
    }

    /**
     * Deletes the library roots of {@code ownerKey} — one, or all of them with
     * {@code storageRef} {@code null} (and then the owner's reservations too) —
     * with their lineage and the grants on them, in one transaction; then their
     * located bytes and one announcement per root. The roots are locked FOR UPDATE
     * first, so the lineage DELETE, a later statement, sees a view a concurrent
     * {@code storeDerived} committed while it waited.
     */
    private long deleteLibraryRoots(String ownerKey, String storageRef) {
        if (ownerKey == null) {
            return 0;
        }
        ensureSchema();
        List<AttachmentRootDeleted> roots = new ArrayList<>();
        List<Removed> located = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                roots.clear();
                located.clear();
                try (PreparedStatement ps = conn.prepareStatement("SELECT storage_ref, owner_scope, conversation_id, user_id FROM attachments "
                        + "WHERE owner_key = ? AND " + LIBRARY_ROOT + (storageRef != null ? " AND storage_ref = ?" : "") + " FOR UPDATE")) {
                    ps.setString(1, ownerKey);
                    if (storageRef != null) {
                        ps.setString(2, storageRef);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            roots.add(new AttachmentRootDeleted(rs.getString(1), OwnerScope.fromStored(rs.getString(2)), ownerKey, rs.getString(4)));
                        }
                    }
                }
                if (storageRef == null) {
                    // The library's own reservations; a conversation's (whose owner key is
                    // its id) are never the library's, whatever key was passed.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM attachment_reservations WHERE owner_key = ? AND conversation_id IS DISTINCT FROM owner_key")) {
                        ps.setString(1, ownerKey);
                        ps.executeUpdate();
                    }
                }
                if (roots.isEmpty()) {
                    return null;
                }
                Array refs = conn.createArrayOf("text", roots.stream().map(AttachmentRootDeleted::rootRef).toArray());
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachments WHERE storage_ref = ANY(?) OR root_ref = ANY(?) "
                        + "RETURNING blob_backend, blob_key, COALESCE(owner_key, conversation_id)")) {
                    ps.setArray(1, refs);
                    ps.setArray(2, refs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            collectLocated(rs, 1, rs.getString(3), located);
                        }
                    }
                }
                tombstoneAll(conn, located);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_grants WHERE storage_ref = ANY(?)")) {
                    ps.setArray(1, refs);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException | AttachmentStoreException e) {
            throw new RuntimeException("Failed to delete library documents", e);
        }
        discardAll(located);
        DerivedBlobs.fireRootDeleted(rootDeletedEvent, roots);
        if (!roots.isEmpty()) {
            LOGGER.debugf("Deleted %d library documents", roots.size());
        }
        return roots.size();
    }

    @Override
    public List<LibraryEntry> expired(Instant now, int limit) {
        if (now == null) {
            throw new IllegalArgumentException("Expiry is decided at a given time");
        }
        // Retention counts from the last access; a document without its own retention
        // has the deployment's, and zero (its own or the default) never runs out.
        int fallback = Math.max(0, libraryRetentionDays);
        return entriesWhere(" AND COALESCE(retention_days, ?) > 0 "
                + "AND COALESCE(last_accessed_at, saved_at) + COALESCE(retention_days, ?) * INTERVAL '1 day' <= ?",
                "COALESCE(last_accessed_at, saved_at), storage_ref", limit, 0, fallback, fallback, now);
    }

    @Override
    public List<LibraryEntry> removedBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new IllegalArgumentException("A purge needs its cutoff");
        }
        return entriesWhere(" AND removed_at IS NOT NULL AND removed_at < ?", "removed_at, storage_ref", limit, 0, cutoff);
    }

    // ------------------------------------------------------- quota reservations

    @Override
    public QuotaReservation reserve(
                                    OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl,
                                    QuotaMode mode)
            throws AttachmentStoreException {
        QuotaReservation.validateRequest(owner, conversationId, userId, bytes, count, ttl, mode);
        // A large upload's bytes are the user's to carry, not the conversation's.
        long conversationBytes = mode == QuotaMode.LARGE_PER_USER ? 0 : bytes;
        ensureSchema();
        boolean conversationQuota = maxPerConversation > 0 || maxTotalBytesPerConversation > 0;
        boolean userQuota = userId != null && (maxPerUser > 0 || maxTotalBytesPerUser > 0);
        String id = UUID.randomUUID().toString();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            return inTransaction(conn, () -> {
                List<Lock> locks = new ArrayList<>();
                if (conversationQuota) {
                    locks.add(new Lock(AttachmentLockOrder.Scope.CONVERSATION, conversationId));
                }
                if (userQuota) {
                    locks.add(new Lock(AttachmentLockOrder.Scope.USER, userId));
                }
                lockAll(conn, locks);
                sweepExpired(conn);
                if (conversationQuota) {
                    long[] usage = usage(conn, "conversation_id = ?", conversationId);
                    AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_CONVERSATION, usage[0], usage[1], count, conversationBytes,
                            maxPerConversation, maxTotalBytesPerConversation);
                }
                if (userQuota) {
                    long[] usage = usage(conn, "user_id = ?", userId);
                    AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_USER, usage[0], usage[1], count, bytes, maxPerUser,
                            maxTotalBytesPerUser);
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO attachment_reservations "
                        + "(id, owner_key, conversation_id, user_id, bytes, count, expires_at, quota_mode) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + ? * INTERVAL '1 millisecond', ?) RETURNING expires_at")) {
                    ps.setString(1, id);
                    ps.setString(2, owner.ownerKey());
                    ps.setString(3, conversationId);
                    ps.setString(4, userId);
                    ps.setLong(5, bytes);
                    ps.setInt(6, count);
                    ps.setLong(7, ttl.toMillis());
                    ps.setString(8, mode.stored());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return new QuotaReservation(id, owner.ownerKey(), bytes, count, rs.getTimestamp(1).toInstant());
                    }
                }
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to reserve attachment quota", e);
        }
    }

    /**
     * Deletes up to {@value #SWEEP_BATCH} expired reservations of any bucket. An
     * expired row no longer counts, so this only keeps the table from growing:
     * every reserve sweeps, whether quotas are on or not, so the rows of writers
     * that crashed are removed by the next writer anywhere. {@code SKIP LOCKED}
     * leaves rows a concurrent sweep or {@code convert} holds to them, so two
     * sweeps never wait on each other.
     */
    private static void sweepExpired(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_reservations WHERE id IN "
                + "(SELECT id FROM attachment_reservations WHERE expires_at <= NOW() LIMIT " + SWEEP_BATCH
                + " FOR UPDATE SKIP LOCKED)")) {
            ps.executeUpdate();
        }
    }

    @Override
    public void release(String reservationId) {
        ensureSchema();
        executeUpdate("DELETE FROM attachment_reservations WHERE id = ?", reservationId);
    }

    @Override
    public void convert(String reservationId, String storageRef) throws AttachmentStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            inTransaction(conn, () -> {
                convert(conn, reservationId, storageRef);
                return null;
            });
        } catch (SQLException e) {
            throw new AttachmentStoreException("Failed to convert an attachment quota reservation", e);
        }
    }

    /**
     * The conversion, inside the caller's transaction on {@code conn} — which a
     * write against a reservation shares with its insert, so the blob and the
     * reservation trade places in one commit. Takes the reservation's bucket locks
     * (conversation → user), then deletes the reservation once the blob is in its
     * buckets and fits.
     */
    private void convert(Connection conn, String reservationId, String storageRef) throws SQLException, AttachmentStoreException {
        String conversationId;
        String userId;
        try (PreparedStatement ps = conn.prepareStatement("SELECT conversation_id, user_id FROM attachment_reservations WHERE id = ?")) {
            ps.setString(1, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw reservationGone();
                }
                conversationId = rs.getString(1);
                userId = rs.getString(2);
            }
        }
        List<Lock> locks = new ArrayList<>();
        locks.add(new Lock(AttachmentLockOrder.Scope.CONVERSATION, conversationId));
        locks.add(new Lock(AttachmentLockOrder.Scope.USER, userId));
        lockAll(conn, locks);
        long reserved;
        try (PreparedStatement ps = conn.prepareStatement(
                // clock_timestamp(), not NOW(): NOW() is when the transaction began, and
                // the locks above may have been waited on for longer than the
                // reservation had left.
                "SELECT bytes FROM attachment_reservations WHERE id = ? AND expires_at > clock_timestamp() FOR UPDATE")) {
            ps.setString(1, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw reservationGone();
                }
                reserved = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT size_bytes FROM attachments WHERE storage_ref = ? AND root_ref IS NULL "
                + "AND conversation_id = ? AND user_id IS NOT DISTINCT FROM ?")) {
            ps.setString(1, storageRef);
            ps.setString(2, conversationId);
            ps.setString(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AttachmentNotFoundException("No upload " + storageRef + " in the buckets of this quota reservation");
                }
                if (rs.getLong(1) > reserved) {
                    throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                            "The upload (%d bytes) is larger than the %d bytes reserved for it".formatted(rs.getLong(1), reserved));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment_reservations WHERE id = ?")) {
            ps.setString(1, reservationId);
            ps.executeUpdate();
        }
    }

    private static AttachmentQuotaExceededException reservationGone() {
        return new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                "The quota reservation for this upload expired or was released before the upload completed. Please retry.");
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
        if (OwnerScope.fromStored(rs.getString("root_owner_scope")) != OwnerScope.CONVERSATION) {
            throw new AttachmentAccessDeniedException("Access denied: this library document has not been opened in this conversation");
        }
        throw new AttachmentAccessDeniedException(
                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(rs.getString("conversation_id"), requester));
    }

    private static boolean mayRead(ResultSet rs, String requester) throws SQLException {
        // Only a conversation-owned root is its conversation's own: a library
        // document is read through a grant, even by the conversation it came from.
        String owner = rs.getString("conversation_id");
        if (owner != null && owner.equals(requester) && OwnerScope.fromStored(rs.getString("root_owner_scope")) == OwnerScope.CONVERSATION) {
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
