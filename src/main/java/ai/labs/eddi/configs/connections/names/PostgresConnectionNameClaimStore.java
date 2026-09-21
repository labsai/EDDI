/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IConnectionNameClaimStore}.
 * <p>
 * The unique constraint on {@code (tenant_id, connection_name)} is the
 * guarantee, and every write is one statement, so each compare-and-set is
 * evaluated and applied under one row lock.
 * <p>
 * {@code claimed_at} is {@code TIMESTAMPTZ} and is written and compared only
 * with {@code CURRENT_TIMESTAMP}: one clock (the database's) and one kind of
 * timestamp, so neither replica clock skew nor a session time zone can move the
 * stale bound.
 */
@ApplicationScoped
@DefaultBean
public class PostgresConnectionNameClaimStore implements IConnectionNameClaimStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresConnectionNameClaimStore.class);

    /** Postgres SQLSTATE for a unique violation. */
    static final String UNIQUE_VIOLATION = "23505";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS connection_name_claims (
                tenant_id VARCHAR(255) NOT NULL,
                connection_name VARCHAR(255) NOT NULL,
                claim_token VARCHAR(64) NOT NULL,
                connection_id VARCHAR(64),
                claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_connection_name_claims_tenant_name UNIQUE (tenant_id, connection_name)
            )
            """;

    static final String INSERT_CLAIM = """
            INSERT INTO connection_name_claims (tenant_id, connection_name, claim_token, connection_id, claimed_at)
            VALUES (?, ?, ?, NULL, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, connection_name) DO NOTHING
            """;

    static final String SELECT_CLAIM = """
            SELECT tenant_id, connection_name, claim_token, connection_id FROM connection_name_claims
             WHERE tenant_id = ? AND connection_name = ?
            """;

    static final String TAKE_OVER_UNRECORDED = """
            UPDATE connection_name_claims SET claim_token = ?, connection_id = NULL, claimed_at = CURRENT_TIMESTAMP
             WHERE tenant_id = ? AND connection_name = ? AND claim_token = ? AND connection_id IS NULL
               AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
            """;

    static final String TAKE_OVER_RECORDED = """
            UPDATE connection_name_claims SET claim_token = ?, connection_id = NULL, claimed_at = CURRENT_TIMESTAMP
             WHERE tenant_id = ? AND connection_name = ? AND claim_token = ? AND connection_id = ?
            """;

    static final String RECORD_CONNECTION = """
            UPDATE connection_name_claims SET connection_id = ?
             WHERE tenant_id = ? AND connection_name = ? AND claim_token = ?
            """;

    static final String RELEASE = "DELETE FROM connection_name_claims WHERE tenant_id = ? AND connection_name = ? AND claim_token = ?";

    static final String RELEASE_CONNECTION = "DELETE FROM connection_name_claims WHERE tenant_id = ? AND connection_name = ? AND connection_id = ?";

    /**
     * Resolved lazily, never at construction: on a MongoDB deployment the
     * datasource bean is inactive, and resolving it while the startup event fires
     * aborts the boot. The same reason every other Postgres store here takes
     * {@code Instance<DataSource>}.
     */
    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresConnectionNameClaimStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the connection_name_claims schema");
        }
    }

    @Override
    public boolean claim(String tenantId, String name, String token) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT_CLAIM)) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            statement.setString(3, token);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            // ON CONFLICT DO NOTHING reports a held name as zero rows. A unique
            // violation is the same answer reached another way, and is mapped to it
            // rather than surfacing as a store failure the caller would retry.
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("Failed to claim a connection name", e);
        }
    }

    @Override
    public Optional<NameClaim> find(String tenantId, String name) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_CLAIM)) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NameClaim(rows.getString("tenant_id"), rows.getString("connection_name"), rows.getString("claim_token"),
                        rows.getString("connection_id")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a connection name claim", e);
        }
    }

    @Override
    public boolean takeOver(NameClaim expected, String newToken, Duration staleAfter) {
        createSchema();
        boolean unrecorded = expected.connectionId() == null;
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(unrecorded ? TAKE_OVER_UNRECORDED : TAKE_OVER_RECORDED)) {
            statement.setString(1, newToken);
            statement.setString(2, expected.tenantId());
            statement.setString(3, expected.name());
            statement.setString(4, expected.token());
            if (unrecorded) {
                statement.setLong(5, staleAfter.toMillis());
            } else {
                statement.setString(5, expected.connectionId());
            }
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to take over a connection name claim", e);
        }
    }

    @Override
    public boolean recordConnection(String tenantId, String name, String token, String connectionId) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(RECORD_CONNECTION)) {
            statement.setString(1, connectionId);
            statement.setString(2, tenantId);
            statement.setString(3, name);
            statement.setString(4, token);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the connection holding a name claim", e);
        }
    }

    @Override
    public boolean release(String tenantId, String name, String token) {
        return delete(RELEASE, tenantId, name, token);
    }

    @Override
    public boolean releaseConnection(String tenantId, String name, String connectionId) {
        return delete(RELEASE_CONNECTION, tenantId, name, connectionId);
    }

    private boolean delete(String sql, String tenantId, String name, String holder) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            statement.setString(3, holder);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release a connection name claim", e);
        }
    }
}
