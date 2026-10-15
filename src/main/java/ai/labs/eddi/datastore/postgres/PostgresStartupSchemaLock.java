/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Serialises the startup of replicas that share one PostgreSQL database.
 * <p>
 * Every PostgreSQL store creates its tables with {@code CREATE TABLE IF NOT
 * EXISTS}, which is not safe against itself: two sessions creating the same
 * table at once both pass the existence check and the loser fails with
 * {@code duplicate key value violates unique constraint
 * "pg_type_typname_nsp_index"}. A single node never races itself, but replicas
 * started together against an empty database do — which is exactly what a
 * Kubernetes Deployment of several replicas does on its first install — and the
 * losing replica failed its boot.
 * <p>
 * The first startup observer takes a session-level advisory lock and the last
 * one releases it, so the startup phases (where the stores create their schemas
 * and the migrations run) of replicas booting together run one after the other.
 * The wait is bounded by {@code eddi.datastore.postgres.startup-lock-timeout};
 * on expiry the node starts without the lock rather than not at all. A no-op on
 * MongoDB.
 */
@ApplicationScoped
public class PostgresStartupSchemaLock {

    private static final Logger LOGGER = Logger.getLogger(PostgresStartupSchemaLock.class);

    /**
     * Arbitrary, fixed advisory-lock key shared by every EDDI node ("EDDISCHM").
     */
    static final long LOCK_KEY = 0x4544444953434847L;

    private final Instance<DataSource> dataSource;
    private final String datastoreType;
    private final int timeoutSeconds;
    private volatile Connection held;

    @Inject
    public PostgresStartupSchemaLock(Instance<DataSource> dataSource,
            @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb") String datastoreType,
            @ConfigProperty(name = "eddi.datastore.postgres.startup-lock-timeout", defaultValue = "180") int timeoutSeconds) {
        this.dataSource = dataSource;
        this.datastoreType = datastoreType;
        this.timeoutSeconds = timeoutSeconds;
    }

    void acquire(@Observes
    @Priority(1) StartupEvent event) {
        acquire();
    }

    void release(@Observes
    @Priority(Integer.MAX_VALUE) StartupEvent event) {
        release();
    }

    void acquire() {
        if (!"postgres".equals(datastoreType)) {
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.get().getConnection();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '" + Math.max(1, timeoutSeconds) + "s'");
                statement.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");
                statement.execute("SET lock_timeout = 0");
            }
            held = connection;
            LOGGER.debug("Holding the PostgreSQL startup lock");
        } catch (SQLException | RuntimeException e) {
            LOGGER.warnf("Starting without the PostgreSQL startup lock (%s): a replica booting at the same moment against an "
                    + "empty database may race this one creating tables", e.getMessage());
            closeQuietly(connection);
        }
    }

    void release() {
        Connection connection = held;
        held = null;
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (SQLException | RuntimeException e) {
            LOGGER.debugf("PostgreSQL startup lock release failed (closing the session releases it): %s", e.getMessage());
        } finally {
            closeQuietly(connection);
        }
    }

    @PreDestroy
    void close() {
        release();
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing releases the session lock anyway
        }
    }
}
