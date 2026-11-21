/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.engine.attachments.AttachmentGrantBackfill;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.AttachmentStoreContract;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.RecordingEvent;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresAttachmentStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link AttachmentStoreContract}, plus the grant table's schema.
 */
@DisplayName("Attachment store (PostgreSQL)")
class PostgresAttachmentStoreIT extends PostgresTestBase implements AttachmentStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresAttachmentStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresAttachmentStore(createDataSourceInstance());
        store.maxSizeBytes = 1_048_576;
        setQuotas(0, 0, 0, 0);
        rootDeleted = new RecordingEvent<>();
        store.rootDeletedEvent = rootDeleted;
        // The first calls create the schemas; then start from empty tables.
        store.listByConversation(OWNER);
        store.ensureGrantIndexes();
        migrationLog = new PostgresMigrationLogStore(createDataSourceInstance());
        migrationLog.readMigrationLog("schema");
        truncateTables(DATA_SOURCE, "attachments", "attachment_grants", "attachment_grants_backfill", "migration_log");
        json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        groupConversations = new GroupConversationStore(new PostgresResourceStorageFactory(createDataSourceInstance(), json), null);
    }

    private PostgresMigrationLogStore migrationLog;
    private JsonSerialization json;
    private GroupConversationStore groupConversations;
    private RecordingEvent<AttachmentRootDeleted> rootDeleted;

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
}
