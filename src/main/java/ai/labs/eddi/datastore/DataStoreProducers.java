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
import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.PostgresAuditStore;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresDatabaseLogs;
import ai.labs.eddi.datastore.postgres.PostgresDeploymentStorage;
import ai.labs.eddi.datastore.postgres.PostgresMigrationLogStore;
import ai.labs.eddi.datastore.postgres.PostgresMigrationManager;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.PostgresScheduleStore;
import ai.labs.eddi.datastore.postgres.PostgresUserConversationStore;
import ai.labs.eddi.datastore.postgres.PostgresUserMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresAgentTriggerStore;
import ai.labs.eddi.engine.audit.AuditStore;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer that selects the correct datastore implementation at
 * <b>runtime</b> based on the {@code eddi.datastore} configuration property.
 * <p>
 * Both MongoDB and PostgreSQL store implementations are compiled into the same
 * JAR. All stores are annotated {@code @DefaultBean}, so these non-default
 * {@code @Produces} methods take priority. Each method uses {@link Instance}
 * for lazy resolution — only the selected store is ever instantiated.
 * <p>
 * <b>Design rationale:</b> {@code @IfBuildProfile} is build-time only (requires
 * separate JARs per database). This producer enables a single Docker image that
 * supports both MongoDB and PostgreSQL, controlled by the
 * {@code EDDI_DATASTORE} environment variable.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class DataStoreProducers {

    @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb")
    String datastoreType;

    private boolean isPostgres() {
        return "postgres".equals(datastoreType);
    }

    @Produces
    @ApplicationScoped
    public IScheduleStore scheduleStore(Instance<MongoScheduleStore> mongo, Instance<PostgresScheduleStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IConversationMemoryStore conversationMemoryStore(Instance<ConversationMemoryStore> mongo,
                                                            Instance<PostgresConversationMemoryStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IDatabaseLogs databaseLogs(Instance<DatabaseLogs> mongo, Instance<PostgresDatabaseLogs> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IUserMemoryStore userMemoryStore(Instance<MongoUserMemoryStore> mongo, Instance<PostgresUserMemoryStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IResourceStorageFactory resourceStorageFactory(Instance<MongoResourceStorageFactory> mongo,
                                                          Instance<PostgresResourceStorageFactory> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IAuditStore auditStore(Instance<AuditStore> mongo, Instance<PostgresAuditStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public ISecretPersistence secretPersistence(Instance<MongoSecretPersistence> mongo, Instance<PostgresSecretPersistence> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IUserConversationStore userConversationStore(Instance<UserConversationStore> mongo, Instance<PostgresUserConversationStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IAgentTriggerStore agentTriggerStore(Instance<AgentTriggerStore> mongo, Instance<PostgresAgentTriggerStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IDeploymentStorage deploymentStorage(Instance<MongoDeploymentStorage> mongo, Instance<PostgresDeploymentStorage> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IMigrationLogStore migrationLogStore(Instance<MigrationLogStore> mongo, Instance<PostgresMigrationLogStore> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }

    @Produces
    @ApplicationScoped
    public IMigrationManager migrationManager(Instance<MigrationManager> mongo, Instance<PostgresMigrationManager> postgres) {
        return isPostgres() ? postgres.get() : mongo.get();
    }
}
