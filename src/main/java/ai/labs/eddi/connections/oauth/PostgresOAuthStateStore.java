/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IOAuthStateStore}.
 * <p>
 * {@code UPDATE … RETURNING} does the claim and the read in one statement,
 * which is what makes it atomic: the predicate is evaluated and the row updated
 * under one lock, so a second concurrent callback sees zero rows.
 */
@ApplicationScoped
@DefaultBean
public class PostgresOAuthStateStore implements IOAuthStateStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresOAuthStateStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS connection_oauth_states (
                state VARCHAR(128) PRIMARY KEY,
                tenant_id VARCHAR(255) NOT NULL,
                connection_name VARCHAR(255) NOT NULL,
                principal VARCHAR(255) NOT NULL,
                code_verifier VARCHAR(255) NOT NULL,
                redirect_uri TEXT NOT NULL,
                return_to TEXT,
                nonce_hash VARCHAR(128),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                consumed_at TIMESTAMP
            )
            """;

    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_oauth_state_expires ON connection_oauth_states (expires_at)";

    /**
     * Added after the table shipped, so an existing deployment gets the column
     * rather than an insert that fails on every account link.
     */
    private static final String ADD_NONCE_COLUMN = "ALTER TABLE connection_oauth_states ADD COLUMN IF NOT EXISTS nonce_hash VARCHAR(128)";

    /**
     * Resolved lazily, never at construction.
     * <p>
     * On a MongoDB deployment the datasource bean is INACTIVE, and Quarkus resolves
     * it while firing the startup event — so a constructor that takes a
     * {@code DataSource} directly aborts the whole boot on a deployment that will
     * never touch Postgres. Every other Postgres store here takes
     * {@code Instance<DataSource>} for that reason.
     */
    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresOAuthStateStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(ADD_NONCE_COLUMN);
            statement.execute(CREATE_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the connection_oauth_states schema");
        }
    }

    @Override
    public void create(OAuthState state) {
        createSchema();
        String sql = """
                INSERT INTO connection_oauth_states
                    (state, tenant_id, connection_name, principal, code_verifier, redirect_uri, return_to, nonce_hash,
                     created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.getState());
            statement.setString(2, state.getTenantId());
            statement.setString(3, state.getConnectionName());
            statement.setString(4, state.getPrincipal());
            statement.setString(5, state.getCodeVerifier());
            statement.setString(6, state.getRedirectUri());
            statement.setString(7, state.getReturnTo());
            statement.setString(8, state.getNonceHash());
            statement.setTimestamp(9, Timestamp.from(state.getCreatedAt()));
            statement.setTimestamp(10, Timestamp.from(state.getExpiresAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store an OAuth state", e);
        }
    }

    @Override
    public Optional<OAuthState> claim(String state) {
        createSchema();
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        // The instant is bound, not taken from the database's CURRENT_TIMESTAMP.
        // expires_at was written by this JVM, so comparing it against the DB clock
        // makes app/DB skew shift the ten-minute window in either direction — a state
        // row that expires early, or one that outlives its TTL. The Mongo store
        // already compares JVM-written against JVM-now; one authoritative clock is
        // what makes the two backends agree.
        String sql = """
                UPDATE connection_oauth_states
                   SET consumed_at = ?
                 WHERE state = ? AND consumed_at IS NULL AND expires_at > ?
                RETURNING state, tenant_id, connection_name, principal, code_verifier, redirect_uri, return_to,
                          nonce_hash, created_at, expires_at, consumed_at
                """;
        Instant now = Instant.now();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, state);
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toState(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim an OAuth state", e);
        }
    }

    @Override
    public int deleteExpired() {
        createSchema();
        // Same clock as claim(), for the same reason: a sweep on the DB clock could
        // delete rows claim() still considers live, or leave rows it has stopped
        // accepting.
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM connection_oauth_states WHERE expires_at < ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warnf(e, "Failed to delete expired OAuth states");
            return 0;
        }
    }

    private static OAuthState toState(ResultSet rows) throws SQLException {
        var state = new OAuthState();
        state.setState(rows.getString("state"));
        state.setTenantId(rows.getString("tenant_id"));
        state.setConnectionName(rows.getString("connection_name"));
        state.setPrincipal(rows.getString("principal"));
        state.setCodeVerifier(rows.getString("code_verifier"));
        state.setRedirectUri(rows.getString("redirect_uri"));
        state.setReturnTo(rows.getString("return_to"));
        state.setNonceHash(rows.getString("nonce_hash"));
        state.setCreatedAt(toInstant(rows.getTimestamp("created_at")));
        state.setExpiresAt(toInstant(rows.getTimestamp("expires_at")));
        state.setConsumedAt(toInstant(rows.getTimestamp("consumed_at")));
        return state;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
