/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.engine.attachments.AttachmentGrantBackfill;
import ai.labs.eddi.engine.attachments.AttachmentStoreContract;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
}
