/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IConnectionGrantStore}.
 * <p>
 * The claim and the CAS are single {@code UPDATE … WHERE} statements, which is
 * what makes them atomic without a transaction: Postgres evaluates the
 * predicate and applies the update under one row lock, so two replicas racing
 * produce one winner and one {@code 0 rows updated}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresConnectionGrantStore implements IConnectionGrantStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresConnectionGrantStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS connection_grants (
                id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text,
                tenant_id VARCHAR(255) NOT NULL,
                connection_name VARCHAR(255) NOT NULL,
                principal VARCHAR(255) NOT NULL,
                encrypted_access_token TEXT,
                access_token_iv VARCHAR(255),
                encrypted_refresh_token TEXT,
                refresh_token_iv VARCHAR(255),
                dek_id VARCHAR(255),
                expires_at TIMESTAMP,
                scopes JSONB,
                status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_refresh_at TIMESTAMP,
                version BIGINT NOT NULL DEFAULT 0,
                refresh_in_progress VARCHAR(128),
                refresh_lease_expires_at TIMESTAMP,
                UNIQUE (tenant_id, connection_name, principal)
            )
            """;

    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_cg_tenant_principal ON connection_grants (tenant_id, principal)";

    private static final String SELECT_COLUMNS = """
            id, tenant_id, connection_name, principal, encrypted_access_token, access_token_iv,
            encrypted_refresh_token, refresh_token_iv, dek_id, expires_at, scopes, status,
            created_at, updated_at, last_refresh_at, version, refresh_in_progress, refresh_lease_expires_at
            """;

    private final DataSource dataSource;

    @Inject
    public PostgresConnectionGrantStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void createSchema() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the connection_grants schema");
        }
    }

    @Override
    public Optional<ConnectionGrant> find(String tenantId, String connectionName, String principal) {
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM connection_grants WHERE tenant_id = ? AND connection_name = ? AND principal = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, connectionName);
            statement.setString(3, principal);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toGrant(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read connection grant", e);
        }
    }

    @Override
    public void upsert(ConnectionGrant grant) {
        String sql = """
                INSERT INTO connection_grants (tenant_id, connection_name, principal, encrypted_access_token, access_token_iv,
                    encrypted_refresh_token, refresh_token_iv, dek_id, expires_at, scopes, status, updated_at, last_refresh_at,
                    version, refresh_in_progress, refresh_lease_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP, ?, 1, NULL, NULL)
                ON CONFLICT (tenant_id, connection_name, principal) DO UPDATE SET
                    encrypted_access_token = EXCLUDED.encrypted_access_token,
                    access_token_iv = EXCLUDED.access_token_iv,
                    encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                    refresh_token_iv = EXCLUDED.refresh_token_iv,
                    dek_id = EXCLUDED.dek_id,
                    expires_at = EXCLUDED.expires_at,
                    scopes = EXCLUDED.scopes,
                    status = EXCLUDED.status,
                    updated_at = CURRENT_TIMESTAMP,
                    last_refresh_at = EXCLUDED.last_refresh_at,
                    version = connection_grants.version + 1,
                    refresh_in_progress = NULL,
                    refresh_lease_expires_at = NULL
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, grant.getTenantId());
            statement.setString(2, grant.getConnectionName());
            statement.setString(3, grant.getPrincipal());
            statement.setString(4, grant.getEncryptedAccessToken());
            statement.setString(5, grant.getAccessTokenIv());
            statement.setString(6, grant.getEncryptedRefreshToken());
            statement.setString(7, grant.getRefreshTokenIv());
            statement.setString(8, grant.getDekId());
            statement.setTimestamp(9, toTimestamp(grant.getExpiresAt()));
            statement.setString(10, writeScopes(grant.getScopes()));
            statement.setString(11, (grant.getStatus() == null ? ConnectionGrant.Status.ACTIVE : grant.getStatus()).name());
            statement.setTimestamp(12, toTimestamp(grant.getLastRefreshAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write connection grant", e);
        }
    }

    @Override
    public boolean claimRefresh(String tenantId, String connectionName, String principal, String claimantId, Instant leaseExpiresAt) {
        // One statement, so the predicate and the write happen under one row lock.
        // A SELECT followed by an UPDATE lets two replicas both see the lease free.
        String sql = """
                UPDATE connection_grants
                   SET refresh_in_progress = ?, refresh_lease_expires_at = ?
                 WHERE tenant_id = ? AND connection_name = ? AND principal = ?
                   AND (refresh_in_progress IS NULL OR refresh_lease_expires_at < CURRENT_TIMESTAMP)
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, claimantId);
            statement.setTimestamp(2, toTimestamp(leaseExpiresAt));
            statement.setString(3, tenantId);
            statement.setString(4, connectionName);
            statement.setString(5, principal);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim a refresh lease", e);
        }
    }

    @Override
    public boolean completeRefresh(ConnectionGrant grant, long expectedVersion) {
        String sql = """
                UPDATE connection_grants
                   SET encrypted_access_token = ?, access_token_iv = ?, encrypted_refresh_token = ?, refresh_token_iv = ?,
                       dek_id = ?, expires_at = ?, scopes = ?::jsonb, status = ?, updated_at = CURRENT_TIMESTAMP,
                       last_refresh_at = CURRENT_TIMESTAMP, version = version + 1,
                       refresh_in_progress = NULL, refresh_lease_expires_at = NULL
                 WHERE tenant_id = ? AND connection_name = ? AND principal = ? AND version = ?
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, grant.getEncryptedAccessToken());
            statement.setString(2, grant.getAccessTokenIv());
            statement.setString(3, grant.getEncryptedRefreshToken());
            statement.setString(4, grant.getRefreshTokenIv());
            statement.setString(5, grant.getDekId());
            statement.setTimestamp(6, toTimestamp(grant.getExpiresAt()));
            statement.setString(7, writeScopes(grant.getScopes()));
            statement.setString(8, grant.getStatus().name());
            statement.setString(9, grant.getTenantId());
            statement.setString(10, grant.getConnectionName());
            statement.setString(11, grant.getPrincipal());
            statement.setLong(12, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to complete a refresh", e);
        }
    }

    @Override
    public void releaseRefresh(String tenantId, String connectionName, String principal, String claimantId) {
        String sql = """
                UPDATE connection_grants SET refresh_in_progress = NULL, refresh_lease_expires_at = NULL
                 WHERE tenant_id = ? AND connection_name = ? AND principal = ? AND refresh_in_progress = ?
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, connectionName);
            statement.setString(3, principal);
            statement.setString(4, claimantId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warnf(e, "Failed to release a refresh lease");
        }
    }

    @Override
    public boolean delete(String tenantId, String connectionName, String principal) {
        String sql = "DELETE FROM connection_grants WHERE tenant_id = ? AND connection_name = ? AND principal = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, connectionName);
            statement.setString(3, principal);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete a connection grant", e);
        }
    }

    @Override
    public int deleteByConnection(String tenantId, String connectionName) {
        String sql = "DELETE FROM connection_grants WHERE tenant_id = ? AND connection_name = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, connectionName);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete connection grants", e);
        }
    }

    @Override
    public List<ConnectionGrant> findByPrincipal(String tenantId, String principal) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM connection_grants WHERE tenant_id = ? AND principal = ?";
        var results = new ArrayList<ConnectionGrant>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, principal);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(toGrant(rows));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list connection grants", e);
        }
    }

    @Override
    public List<ConnectionGrant> findByTenant(String tenantId) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM connection_grants WHERE tenant_id = ?";
        var results = new ArrayList<ConnectionGrant>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(toGrant(rows));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list connection grants for tenant", e);
        }
    }

    @Override
    public boolean updateSealedTokens(ConnectionGrant grant, long expectedVersion) {
        // Neither version nor the lease columns appear in the SET clause: a re-seal
        // must be invisible to a refresh that is mid-flight, or it turns a rotation
        // into a lost token.
        String sql = "UPDATE connection_grants SET encrypted_access_token = ?, access_token_iv = ?, encrypted_refresh_token = ?, "
                + "refresh_token_iv = ?, dek_id = ? WHERE tenant_id = ? AND connection_name = ? AND principal = ? AND version = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, grant.getEncryptedAccessToken());
            statement.setString(2, grant.getAccessTokenIv());
            statement.setString(3, grant.getEncryptedRefreshToken());
            statement.setString(4, grant.getRefreshTokenIv());
            statement.setString(5, grant.getDekId());
            statement.setString(6, grant.getTenantId());
            statement.setString(7, grant.getConnectionName());
            statement.setString(8, grant.getPrincipal());
            statement.setLong(9, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write re-sealed connection grant tokens", e);
        }
    }

    @Override
    public long countByStatus(String tenantId, ConnectionGrant.Status status) {
        String sql = "SELECT COUNT(*) FROM connection_grants WHERE tenant_id = ? AND status = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, status.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count connection grants", e);
        }
    }

    private static ConnectionGrant toGrant(ResultSet rows) throws SQLException {
        var grant = new ConnectionGrant();
        grant.setId(rows.getString("id"));
        grant.setTenantId(rows.getString("tenant_id"));
        grant.setConnectionName(rows.getString("connection_name"));
        grant.setPrincipal(rows.getString("principal"));
        grant.setEncryptedAccessToken(rows.getString("encrypted_access_token"));
        grant.setAccessTokenIv(rows.getString("access_token_iv"));
        grant.setEncryptedRefreshToken(rows.getString("encrypted_refresh_token"));
        grant.setRefreshTokenIv(rows.getString("refresh_token_iv"));
        grant.setDekId(rows.getString("dek_id"));
        grant.setExpiresAt(toInstant(rows.getTimestamp("expires_at")));
        grant.setScopes(readScopes(rows.getString("scopes")));
        String status = rows.getString("status");
        grant.setStatus(status == null ? ConnectionGrant.Status.ACTIVE : ConnectionGrant.Status.valueOf(status));
        grant.setCreatedAt(toInstant(rows.getTimestamp("created_at")));
        grant.setUpdatedAt(toInstant(rows.getTimestamp("updated_at")));
        grant.setLastRefreshAt(toInstant(rows.getTimestamp("last_refresh_at")));
        grant.setVersion(rows.getLong("version"));
        grant.setRefreshInProgress(rows.getString("refresh_in_progress"));
        grant.setRefreshLeaseExpiresAt(toInstant(rows.getTimestamp("refresh_lease_expires_at")));
        return grant;
    }

    private static String writeScopes(List<String> scopes) {
        try {
            return scopes == null ? null : MAPPER.writeValueAsString(scopes);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readScopes(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
