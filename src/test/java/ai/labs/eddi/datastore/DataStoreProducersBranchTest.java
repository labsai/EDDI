/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.mongo.MongoDeploymentStorage;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.MigrationLogStore;
import ai.labs.eddi.configs.migration.MigrationManager;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.mongo.MongoUserMemoryStore;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.mongo.GlobalVariableStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.*;
import ai.labs.eddi.engine.audit.AuditStore;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.DatabaseLogs;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.mongo.MongoScheduleStore;
import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.mongo.AgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.mongo.UserConversationStore;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.MongoSecretPersistence;
import ai.labs.eddi.secrets.persistence.PostgresSecretPersistence;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for all producer methods in {@link DataStoreProducers} — covers both
 * the postgres and mongodb branches for each of the 15 producer methods.
 */
@SuppressWarnings("unchecked")
@DisplayName("DataStoreProducers — Full Branch Coverage")
class DataStoreProducersBranchTest {

    private DataStoreProducers producers;

    @BeforeEach
    void setUp() {
        producers = new DataStoreProducers();
    }

    // ==================== conversationMemoryStore ====================

    @Test
    @DisplayName("conversationMemoryStore — mongo when datastoreType=mongodb")
    void conversationMemoryStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<ConversationMemoryStore> mongo = mock(Instance.class);
        Instance<PostgresConversationMemoryStore> pg = mock(Instance.class);
        var mongoStore = mock(ConversationMemoryStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IConversationMemoryStore result = producers.conversationMemoryStore(mongo, pg);
        assertSame(mongoStore, result);
        verify(pg, never()).get();
    }

    @Test
    @DisplayName("conversationMemoryStore — postgres when datastoreType=postgres")
    void conversationMemoryStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<ConversationMemoryStore> mongo = mock(Instance.class);
        Instance<PostgresConversationMemoryStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresConversationMemoryStore.class);
        when(pg.get()).thenReturn(pgStore);

        IConversationMemoryStore result = producers.conversationMemoryStore(mongo, pg);
        assertSame(pgStore, result);
        verify(mongo, never()).get();
    }

    // ==================== databaseLogs ====================

    @Test
    @DisplayName("databaseLogs — mongo")
    void databaseLogs_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<DatabaseLogs> mongo = mock(Instance.class);
        Instance<PostgresDatabaseLogs> pg = mock(Instance.class);
        var mongoStore = mock(DatabaseLogs.class);
        when(mongo.get()).thenReturn(mongoStore);

        IDatabaseLogs result = producers.databaseLogs(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("databaseLogs — postgres")
    void databaseLogs_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<DatabaseLogs> mongo = mock(Instance.class);
        Instance<PostgresDatabaseLogs> pg = mock(Instance.class);
        var pgStore = mock(PostgresDatabaseLogs.class);
        when(pg.get()).thenReturn(pgStore);

        IDatabaseLogs result = producers.databaseLogs(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== userMemoryStore ====================

    @Test
    @DisplayName("userMemoryStore — mongo")
    void userMemoryStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MongoUserMemoryStore> mongo = mock(Instance.class);
        Instance<PostgresUserMemoryStore> pg = mock(Instance.class);
        var mongoStore = mock(MongoUserMemoryStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IUserMemoryStore result = producers.userMemoryStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("userMemoryStore — postgres")
    void userMemoryStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MongoUserMemoryStore> mongo = mock(Instance.class);
        Instance<PostgresUserMemoryStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresUserMemoryStore.class);
        when(pg.get()).thenReturn(pgStore);

        IUserMemoryStore result = producers.userMemoryStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== resourceStorageFactory ====================

    @Test
    @DisplayName("resourceStorageFactory — mongo")
    void resourceStorageFactory_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MongoResourceStorageFactory> mongo = mock(Instance.class);
        Instance<PostgresResourceStorageFactory> pg = mock(Instance.class);
        var mongoStore = mock(MongoResourceStorageFactory.class);
        when(mongo.get()).thenReturn(mongoStore);

        IResourceStorageFactory result = producers.resourceStorageFactory(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("resourceStorageFactory — postgres")
    void resourceStorageFactory_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MongoResourceStorageFactory> mongo = mock(Instance.class);
        Instance<PostgresResourceStorageFactory> pg = mock(Instance.class);
        var pgStore = mock(PostgresResourceStorageFactory.class);
        when(pg.get()).thenReturn(pgStore);

        IResourceStorageFactory result = producers.resourceStorageFactory(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== auditStore ====================

    @Test
    @DisplayName("auditStore — mongo")
    void auditStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<AuditStore> mongo = mock(Instance.class);
        Instance<PostgresAuditStore> pg = mock(Instance.class);
        var mongoStore = mock(AuditStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IAuditStore result = producers.auditStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("auditStore — postgres")
    void auditStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<AuditStore> mongo = mock(Instance.class);
        Instance<PostgresAuditStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresAuditStore.class);
        when(pg.get()).thenReturn(pgStore);

        IAuditStore result = producers.auditStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== secretPersistence ====================

    @Test
    @DisplayName("secretPersistence — mongo")
    void secretPersistence_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MongoSecretPersistence> mongo = mock(Instance.class);
        Instance<PostgresSecretPersistence> pg = mock(Instance.class);
        var mongoStore = mock(MongoSecretPersistence.class);
        when(mongo.get()).thenReturn(mongoStore);

        ISecretPersistence result = producers.secretPersistence(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("secretPersistence — postgres")
    void secretPersistence_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MongoSecretPersistence> mongo = mock(Instance.class);
        Instance<PostgresSecretPersistence> pg = mock(Instance.class);
        var pgStore = mock(PostgresSecretPersistence.class);
        when(pg.get()).thenReturn(pgStore);

        ISecretPersistence result = producers.secretPersistence(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== userConversationStore ====================

    @Test
    @DisplayName("userConversationStore — mongo")
    void userConversationStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<UserConversationStore> mongo = mock(Instance.class);
        Instance<PostgresUserConversationStore> pg = mock(Instance.class);
        var mongoStore = mock(UserConversationStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IUserConversationStore result = producers.userConversationStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("userConversationStore — postgres")
    void userConversationStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<UserConversationStore> mongo = mock(Instance.class);
        Instance<PostgresUserConversationStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresUserConversationStore.class);
        when(pg.get()).thenReturn(pgStore);

        IUserConversationStore result = producers.userConversationStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== agentTriggerStore ====================

    @Test
    @DisplayName("agentTriggerStore — mongo")
    void agentTriggerStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<AgentTriggerStore> mongo = mock(Instance.class);
        Instance<PostgresAgentTriggerStore> pg = mock(Instance.class);
        var mongoStore = mock(AgentTriggerStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IAgentTriggerStore result = producers.agentTriggerStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("agentTriggerStore — postgres")
    void agentTriggerStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<AgentTriggerStore> mongo = mock(Instance.class);
        Instance<PostgresAgentTriggerStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresAgentTriggerStore.class);
        when(pg.get()).thenReturn(pgStore);

        IAgentTriggerStore result = producers.agentTriggerStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== deploymentStorage ====================

    @Test
    @DisplayName("deploymentStorage — mongo")
    void deploymentStorage_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MongoDeploymentStorage> mongo = mock(Instance.class);
        Instance<PostgresDeploymentStorage> pg = mock(Instance.class);
        var mongoStore = mock(MongoDeploymentStorage.class);
        when(mongo.get()).thenReturn(mongoStore);

        IDeploymentStorage result = producers.deploymentStorage(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("deploymentStorage — postgres")
    void deploymentStorage_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MongoDeploymentStorage> mongo = mock(Instance.class);
        Instance<PostgresDeploymentStorage> pg = mock(Instance.class);
        var pgStore = mock(PostgresDeploymentStorage.class);
        when(pg.get()).thenReturn(pgStore);

        IDeploymentStorage result = producers.deploymentStorage(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== migrationLogStore ====================

    @Test
    @DisplayName("migrationLogStore — mongo")
    void migrationLogStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MigrationLogStore> mongo = mock(Instance.class);
        Instance<PostgresMigrationLogStore> pg = mock(Instance.class);
        var mongoStore = mock(MigrationLogStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IMigrationLogStore result = producers.migrationLogStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("migrationLogStore — postgres")
    void migrationLogStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MigrationLogStore> mongo = mock(Instance.class);
        Instance<PostgresMigrationLogStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresMigrationLogStore.class);
        when(pg.get()).thenReturn(pgStore);

        IMigrationLogStore result = producers.migrationLogStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== migrationManager ====================

    @Test
    @DisplayName("migrationManager — mongo")
    void migrationManager_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<MigrationManager> mongo = mock(Instance.class);
        Instance<PostgresMigrationManager> pg = mock(Instance.class);
        var mongoStore = mock(MigrationManager.class);
        when(mongo.get()).thenReturn(mongoStore);

        IMigrationManager result = producers.migrationManager(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("migrationManager — postgres")
    void migrationManager_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<MigrationManager> mongo = mock(Instance.class);
        Instance<PostgresMigrationManager> pg = mock(Instance.class);
        var pgStore = mock(PostgresMigrationManager.class);
        when(pg.get()).thenReturn(pgStore);

        IMigrationManager result = producers.migrationManager(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== globalVariableStore ====================

    @Test
    @DisplayName("globalVariableStore — mongo")
    void globalVariableStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<GlobalVariableStore> mongo = mock(Instance.class);
        Instance<PostgresGlobalVariableStore> pg = mock(Instance.class);
        var mongoStore = mock(GlobalVariableStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IGlobalVariableStore result = producers.globalVariableStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("globalVariableStore — postgres")
    void globalVariableStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<GlobalVariableStore> mongo = mock(Instance.class);
        Instance<PostgresGlobalVariableStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresGlobalVariableStore.class);
        when(pg.get()).thenReturn(pgStore);

        IGlobalVariableStore result = producers.globalVariableStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== conversationCheckpointStore ====================

    @Test
    @DisplayName("conversationCheckpointStore — mongo")
    void conversationCheckpointStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<ai.labs.eddi.engine.memory.MongoConversationCheckpointStore> mongo = mock(Instance.class);
        Instance<PostgresConversationCheckpointStore> pg = mock(Instance.class);
        var mongoStore = mock(ai.labs.eddi.engine.memory.MongoConversationCheckpointStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IConversationCheckpointStore result = producers.conversationCheckpointStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("conversationCheckpointStore — postgres")
    void conversationCheckpointStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<ai.labs.eddi.engine.memory.MongoConversationCheckpointStore> mongo = mock(Instance.class);
        Instance<PostgresConversationCheckpointStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresConversationCheckpointStore.class);
        when(pg.get()).thenReturn(pgStore);

        IConversationCheckpointStore result = producers.conversationCheckpointStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== attachmentStore ====================

    @Test
    @DisplayName("attachmentStore — mongo")
    void attachmentStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<ai.labs.eddi.datastore.mongo.GridFsAttachmentStore> mongo = mock(Instance.class);
        Instance<PostgresAttachmentStore> pg = mock(Instance.class);
        var mongoStore = mock(ai.labs.eddi.datastore.mongo.GridFsAttachmentStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IAttachmentStore result = producers.attachmentStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("attachmentStore — postgres")
    void attachmentStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<ai.labs.eddi.datastore.mongo.GridFsAttachmentStore> mongo = mock(Instance.class);
        Instance<PostgresAttachmentStore> pg = mock(Instance.class);
        var pgStore = mock(PostgresAttachmentStore.class);
        when(pg.get()).thenReturn(pgStore);

        IAttachmentStore result = producers.attachmentStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== tenantQuotaStore ====================

    @Test
    @DisplayName("tenantQuotaStore — mongo")
    void tenantQuotaStore_mongo() throws Exception {
        producers.datastoreType = "mongodb";
        Instance<ai.labs.eddi.engine.tenancy.MongoTenantQuotaStore> mongo = mock(Instance.class);
        Instance<ai.labs.eddi.engine.tenancy.PostgresTenantQuotaStore> pg = mock(Instance.class);
        var mongoStore = mock(ai.labs.eddi.engine.tenancy.MongoTenantQuotaStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        var result = producers.tenantQuotaStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("tenantQuotaStore — postgres")
    void tenantQuotaStore_postgres() throws Exception {
        producers.datastoreType = "postgres";
        Instance<ai.labs.eddi.engine.tenancy.MongoTenantQuotaStore> mongo = mock(Instance.class);
        Instance<ai.labs.eddi.engine.tenancy.PostgresTenantQuotaStore> pg = mock(Instance.class);
        var pgStore = mock(ai.labs.eddi.engine.tenancy.PostgresTenantQuotaStore.class);
        when(pg.get()).thenReturn(pgStore);

        var result = producers.tenantQuotaStore(mongo, pg);
        assertSame(pgStore, result);
    }

    // ==================== isPostgres edge case ====================

    @Test
    @DisplayName("isPostgres returns false for null datastoreType")
    void isPostgres_null() throws Exception {
        producers.datastoreType = null;
        Instance<MongoScheduleStore> mongo = mock(Instance.class);
        Instance<PostgresScheduleStore> pg = mock(Instance.class);
        var mongoStore = mock(MongoScheduleStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IScheduleStore result = producers.scheduleStore(mongo, pg);
        assertSame(mongoStore, result);
    }

    @Test
    @DisplayName("isPostgres returns false for unknown type")
    void isPostgres_unknownType() throws Exception {
        producers.datastoreType = "redis";
        Instance<MongoScheduleStore> mongo = mock(Instance.class);
        Instance<PostgresScheduleStore> pg = mock(Instance.class);
        var mongoStore = mock(MongoScheduleStore.class);
        when(mongo.get()).thenReturn(mongoStore);

        IScheduleStore result = producers.scheduleStore(mongo, pg);
        assertSame(mongoStore, result);
    }
}
