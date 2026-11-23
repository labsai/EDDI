/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.engine.attachments.AttachmentGrantBackfill;
import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.LibraryStoreContract;
import ai.labs.eddi.engine.attachments.LibraryStoreSupport;
import ai.labs.eddi.engine.attachments.AttachmentStoreContract;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.RecordingEvent;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import ai.labs.eddi.engine.attachments.OwnerScope;
import ai.labs.eddi.engine.attachments.PdfLikeStream;
import ai.labs.eddi.engine.attachments.QuotaMode;
import ai.labs.eddi.engine.attachments.QuotaReservation;
import ai.labs.eddi.engine.attachments.QuotaReservationContract;
import ai.labs.eddi.engine.attachments.StreamingAttachmentStoreContract;
import ai.labs.eddi.engine.attachments.blob.BlobBackendSelector;
import ai.labs.eddi.engine.attachments.blob.FaultInjectingBlobBackend;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresAttachmentStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link AttachmentStoreContract}, plus the grant table's schema.
 */
@DisplayName("Attachment store (PostgreSQL)")
class PostgresAttachmentStoreIT extends PostgresTestBase
        implements
            AttachmentStoreContract,
            QuotaReservationContract,
            StreamingAttachmentStoreContract,
            LibraryStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresAttachmentStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresAttachmentStore(createDataSourceInstance());
        store.maxSizeBytes = 1_048_576;
        setQuotas(0, 0, 0, 0);
        rootDeleted = new RecordingEvent<>();
        store.rootDeletedEvent = rootDeleted;
        ownerChanged = new RecordingEvent<>();
        store.ownerChangedEvent = ownerChanged;
        setLibraryLimits(LibraryStoreSupport.Limits.defaults());
        // The first calls create the schemas; then start from empty tables.
        store.listByConversation(OWNER);
        store.ensureGrantIndexes();
        migrationLog = new PostgresMigrationLogStore(createDataSourceInstance());
        migrationLog.readMigrationLog("schema");
        // The real pg-chunks backend, with 256 KiB chunks so a large test file spans
        // several; its first call creates its table.
        blobBackend = new FaultInjectingBlobBackend(new PgChunksBlobBackend(() -> DATA_SOURCE, 256 * 1024));
        blobBackend.keyCount();
        store.blobBackends = BlobBackendSelector.of(blobBackend);
        truncateTables(DATA_SOURCE, "attachments", "attachment_grants", "attachment_grants_backfill", "attachment_reservations", "migration_log",
                "attachment_blob_tombstones", "attachment_chunks");
        json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        groupConversations = new GroupConversationStore(new PostgresResourceStorageFactory(createDataSourceInstance(), json), null);
    }

    private PostgresMigrationLogStore migrationLog;
    private FaultInjectingBlobBackend blobBackend;

    @Override
    public FaultInjectingBlobBackend blobBackend() {
        return blobBackend;
    }

    @Override
    public void setMaxSizeBytes(long maxSizeBytes) {
        store.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public void makeTombstonesDue() {
        execute("UPDATE attachment_blob_tombstones SET not_before = NOW() - INTERVAL '1 second'");
    }

    @Override
    public void seedDueTombstone(String key, String ownerKey) {
        execute("INSERT INTO attachment_blob_tombstones (backend, blob_key, owner_key, not_before) VALUES (?, ?, ?, NOW() - INTERVAL '1 second')",
                PgChunksBlobBackend.NAME, key, ownerKey);
    }

    @Override
    public List<String> heapProbeArgs() {
        PGSimpleDataSource pg = (PGSimpleDataSource) DATA_SOURCE;
        return List.of("postgres", pg.getUrl(), pg.getUser(), pg.getPassword());
    }

    private JsonSerialization json;
    private GroupConversationStore groupConversations;
    private RecordingEvent<AttachmentRootDeleted> rootDeleted;
    private RecordingEvent<AttachmentOwnerChanged> ownerChanged;

    @Override
    public void setLibraryLimits(LibraryStoreSupport.Limits limits) {
        store.libraryMaxPerUser = limits.maxPerOwner();
        store.libraryMaxTotalBytesPerUser = limits.maxTotalBytesPerOwner();
        store.libraryRetentionDays = limits.defaultRetentionDays();
        store.libraryMaxGrantsPerDocument = limits.maxGrantsPerDocument();
    }

    @Override
    public List<AttachmentOwnerChanged> ownerChanges() {
        return ownerChanged.fired();
    }

    @Override
    public void setLastAccessed(String storageRef, Instant when) {
        try (Connection conn = DATA_SOURCE.getConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE attachments SET last_accessed_at = ? WHERE storage_ref = ?")) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setString(2, storageRef);
            assertEquals(1, ps.executeUpdate(), "no row " + storageRef);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void seedReservation(String ownerKey, String conversationId) {
        execute("INSERT INTO attachment_reservations (id, owner_key, conversation_id, bytes, count, expires_at) "
                + "VALUES (gen_random_uuid()::text, ?, ?, 1, 1, NOW() + INTERVAL '1 hour')", ownerKey, conversationId);
    }

    @Override
    public long reservationsOf(String ownerKey) {
        return count("SELECT COUNT(*) FROM attachment_reservations WHERE owner_key = ?", ownerKey);
    }

    @Override
    public void setDerivedLimits(DerivedCaps.Limits limits) {
        store.derivedMaxPerSource = limits.maxPerSource();
        store.derivedMaxTotalBytesPerSource = limits.maxTotalBytesPerSource();
        store.derivedMaxSlicesPerSource = limits.maxSlicesPerSource();
        store.derivedMaxSliceBytesPerSource = limits.maxSliceBytesPerSource();
        store.derivedMaxTextBytesPerSource = limits.maxTextBytesPerSource();
    }

    @Override
    public List<AttachmentRootDeleted> rootDeletions() {
        return rootDeleted.fired();
    }

    @Override
    public long lineageRows(String rootRef) {
        return count("SELECT COUNT(*) FROM attachments WHERE root_ref = ?", rootRef);
    }

    @Override
    public IMigrationLogStore migrationLog() {
        return migrationLog;
    }

    @Override
    public String createGroupConversation() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setGroupId("g-1");
        gc.setState(GroupConversation.GroupConversationState.CREATED);
        return groupConversations.create(gc);
    }

    @Override
    public AttachmentGrantBackfill backfillWithRealStores() {
        return new AttachmentGrantBackfill(store, migrationLog, new PostgresConversationMemoryStore(createDataSourceInstance(), json),
                groupConversations);
    }

    @Override
    public void seedGrantRow(String storageRef, String conversationId) {
        execute("INSERT INTO attachment_grants (storage_ref, conversation_id) VALUES (?, ?)", storageRef, conversationId);
    }

    @Override
    public IAttachmentStore store() {
        return store;
    }

    @Override
    public IAttachmentGrantMigration migration() {
        return store;
    }

    @Override
    public void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser) {
        store.maxPerConversation = perConversation;
        store.maxTotalBytesPerConversation = bytesPerConversation;
        store.maxPerUser = perUser;
        store.maxTotalBytesPerUser = bytesPerUser;
    }

    @Override
    public void expireReservations() {
        execute("UPDATE attachment_reservations SET expires_at = NOW() - INTERVAL '1 second'");
    }

    @Override
    public Duration storedLifetime(String reservationId) {
        try (Connection conn = DATA_SOURCE.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT (EXTRACT(EPOCH FROM (expires_at - created_at)) * 1000)::bigint FROM attachment_reservations WHERE id = ?")) {
            ps.setString(1, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no reservation " + reservationId);
                return Duration.ofMillis(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long reservationRows() {
        try {
            return countAll("SELECT COUNT(*) FROM attachment_reservations");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String storeUncharged(String conversationId, String userId, byte[] bytes) {
        long[] quotas = {store.maxPerConversation, store.maxTotalBytesPerConversation, store.maxPerUser, store.maxTotalBytesPerUser};
        setQuotas(0, 0, 0, 0);
        try {
            return store.store(bytes, "text/plain", "uncharged.txt", conversationId, "default", userId).storageRef();
        } catch (IAttachmentStore.AttachmentStoreException e) {
            throw new IllegalStateException(e);
        } finally {
            setQuotas(quotas[0], quotas[1], quotas[2], quotas[3]);
        }
    }

    private static long count(String sql, String param) {
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql, String... params) {
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long grantRowsOn(String storageRef) {
        return count("SELECT COUNT(*) FROM attachment_grants WHERE storage_ref = ?", storageRef);
    }

    @Override
    public long grantRowsHeldBy(String conversationId) {
        return count("SELECT COUNT(*) FROM attachment_grants WHERE conversation_id = ?", conversationId);
    }

    @Override
    public List<String> legacyGrantees(String storageRef) {
        try (Connection conn = DATA_SOURCE.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT grants FROM attachments WHERE storage_ref = ?")) {
            ps.setString(1, storageRef);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> grantees = new ArrayList<>();
                if (rs.next()) {
                    Array arr = rs.getArray(1);
                    if (arr != null) {
                        for (Object element : (Object[]) arr.getArray()) {
                            grantees.add(String.valueOf(element));
                        }
                    }
                }
                return grantees;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long legacyGrantsHeldBy(String conversationId) {
        return count("SELECT COUNT(*) FROM attachments WHERE ? = ANY(grants)", conversationId);
    }

    @Override
    public void seedLegacyGrant(String storageRef, String conversationId) {
        execute("UPDATE attachments SET grants = array_append(grants, ?) WHERE storage_ref = ?", conversationId, storageRef);
    }

    @Override
    public void dropGrantRows() {
        execute("DELETE FROM attachment_grants");
    }

    @Test
    void createsTheGrantTableWithItsKeyAndTheGranteeIndex() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename IN ('attachment_grants', 'attachments')")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("attachment_grants_pkey", "idx_attach_grants_conv", "idx_attach_grants")), indexes.toString());
    }

    @Override
    public void makePreUpgradeRoot(String storageRef) {
        // owner_scope is NOT NULL DEFAULT 'CONVERSATION': the ALTER gave every old row
        // that value; owner_key stayed NULL.
        execute("UPDATE attachments SET owner_key = NULL WHERE storage_ref = ?", storageRef);
    }

    /**
     * A storeDerived that holds the root FOR SHARE while a delete starts: the
     * delete waits, and must still remove the view committed meanwhile. Played with
     * a raw connection standing in for storeDerived, so the interleaving is fixed
     * rather than left to timing.
     */
    private void assertADeleteWaitingOnTheRootRemovesAViewCommittedMeanwhile(String root, Runnable delete) throws Exception {
        try (Connection writer = DATA_SOURCE.getConnection()) {
            writer.setAutoCommit(false);
            try (PreparedStatement ps = writer.prepareStatement("SELECT 1 FROM attachments WHERE storage_ref = ? FOR SHARE")) {
                ps.setString(1, root);
                ps.executeQuery().close();
            }
            Thread deleter = Thread.ofVirtual().start(delete);
            awaitALockWait();
            try (PreparedStatement ps = writer.prepareStatement("INSERT INTO attachments (storage_ref, conversation_id, mime_type, size_bytes, "
                    + "data, owner_key, root_ref, kind) VALUES ('8d4f6f1e-0000-4000-8000-0000000000ff', ?, 'text/plain', 1, 'x', ?, ?, 'TEXT_PART')")) {
                ps.setString(1, OWNER);
                ps.setString(2, OWNER);
                ps.setString(3, root);
                ps.executeUpdate();
            }
            writer.commit();
            deleter.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(deleter.isAlive(), "the delete finished");
        }
        assertEquals(0, lineageRows(root), "no view outlives its root");
    }

    /** Waits until some backend waits on a row lock, i.e. the delete is blocked. */
    private static void awaitALockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (countAll("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the delete never waited on the root's row");
    }

    private static long countAll(String sql) throws SQLException {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void deletingARootWaitsForAViewBeingStoredAndRemovesIt() throws Exception {
        String root = store.store("root".getBytes(StandardCharsets.UTF_8), "text/plain", "r.txt", OWNER, "default", null).storageRef();
        assertADeleteWaitingOnTheRootRemovesAViewCommittedMeanwhile(root, () -> {
            try {
                assertTrue(store.delete(root, OWNER));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void deletingAConversationWaitsForAViewBeingStoredAndRemovesIt() throws Exception {
        String root = store.store("root".getBytes(StandardCharsets.UTF_8), "text/plain", "r.txt", OWNER, "default", null).storageRef();
        assertADeleteWaitingOnTheRootRemovesAViewCommittedMeanwhile(root, () -> assertEquals(1, store.deleteByConversation(OWNER)));
    }

    @Test
    void createsThePartialLineageIndexes() throws Exception {
        List<String> definitions = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'attachments' AND indexname IN ('idx_attach_lineage', 'idx_attach_dedup')")) {
            while (rs.next()) {
                definitions.add(rs.getString(1));
            }
        }
        assertTrue(definitions.size() == 2 && definitions.stream().allMatch(d -> d.contains("WHERE (root_ref IS NOT NULL)")), definitions.toString());
        assertTrue(definitions.stream().anyMatch(d -> d.startsWith("CREATE UNIQUE INDEX idx_attach_dedup")), definitions.toString());
    }

    @Test
    @DisplayName("a record whose commit applied but reported failure keeps its object")
    void aCommitThatFailsAfterApplyingKeepsTheObject() throws Exception {
        AtomicBoolean failNextCommit = new AtomicBoolean();
        DataSource flaky = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, DATA_SOURCE, args);
                    if (!(result instanceof Connection conn)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                        Object r = invoke(m, conn, a);
                        if (m.getName().equals("commit") && failNextCommit.getAndSet(false)) {
                            throw new SQLException("connection lost while committing");
                        }
                        return r;
                    });
                });
        PostgresAttachmentStore original = store;
        store = new PostgresAttachmentStore(instanceOf(flaky));
        store.maxSizeBytes = original.maxSizeBytes;
        store.blobBackends = original.blobBackends;
        store.rootDeletedEvent = rootDeleted;
        setQuotas(0, 0, 0, 0);
        QuotaReservation reservation = store.reserve(
                new OwnerRef(OwnerScope.CONVERSATION, StreamingAttachmentStoreContract.CONVERSATION, STREAM_USER),
                StreamingAttachmentStoreContract.CONVERSATION,
                STREAM_USER, LARGE, 1, Duration.ofMinutes(10), QuotaMode.LARGE_PER_USER);
        failNextCommit.set(true);

        assertThrows(IAttachmentStore.AttachmentStoreException.class, () -> store.store(new PdfLikeStream(SEED, LARGE), LARGE,
                "application/pdf", "large.pdf", StreamingAttachmentStoreContract.CONVERSATION, "default", STREAM_USER, reservation.id(),
                QuotaMode.LARGE_PER_USER));
        assertFalse(failNextCommit.get(), "the record's commit was the one that failed");

        String storageRef = storageRefOf(StreamingAttachmentStoreContract.CONVERSATION);
        store.sweepTombstones(10);
        assertEquals(1, blobBackend.keyCount(), "the row the commit applied still has its bytes");
        assertEquals(0, store.pendingTombstones(null), "the sweep found the object referenced and dropped its tombstone");
        try (InputStream in = store.open(storageRef, StreamingAttachmentStoreContract.CONVERSATION)) {
            assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(in));
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String storageRefOf(String conversationId) throws SQLException {
        try (Connection conn = DATA_SOURCE.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT storage_ref FROM attachments WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the commit applied: the row is there");
                return rs.getString(1);
            }
        }
    }
}
