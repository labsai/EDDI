package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link ISecretPersistence}. Stores encrypted
 * secrets and DEKs in two tables: {@code secret_vault_secrets} and
 * {@code secret_vault_deks}.
 * <p>
 * Annotated {@code @DefaultBean} so that the non-default {@code @Produces}
 * method in {@code DataStoreProducers} takes priority. Selected at runtime when
 * {@code eddi.datastore.type=postgres}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresSecretPersistence implements ISecretPersistence {

    private static final Logger LOGGER = Logger.getLogger(PostgresSecretPersistence.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATE_SECRETS_TABLE = """
            CREATE TABLE IF NOT EXISTS secret_vault_secrets (
                id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text,
                tenant_id VARCHAR(255) NOT NULL,
                key_name VARCHAR(255) NOT NULL,
                encrypted_value TEXT NOT NULL,
                iv VARCHAR(255) NOT NULL,
                dek_id VARCHAR(255) NOT NULL,
                checksum VARCHAR(128),
                description TEXT,
                allowed_agents JSONB DEFAULT '["*"]'::jsonb,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_accessed_at TIMESTAMP,
                last_rotated_at TIMESTAMP,
                UNIQUE (tenant_id, key_name)
            )
            """;

    private static final String CREATE_DEKS_TABLE = """
            CREATE TABLE IF NOT EXISTS secret_vault_deks (
                id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text,
                tenant_id VARCHAR(255) NOT NULL UNIQUE,
                encrypted_dek TEXT NOT NULL,
                iv VARCHAR(255) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String CREATE_INDEXES = """
            CREATE INDEX IF NOT EXISTS idx_svs_tenant ON secret_vault_secrets (tenant_id);
            CREATE INDEX IF NOT EXISTS idx_svd_tenant ON secret_vault_deks (tenant_id)
            """;

    private static final String CREATE_META_TABLE = """
            CREATE TABLE IF NOT EXISTS secret_vault_meta (
                key VARCHAR(255) PRIMARY KEY,
                value TEXT NOT NULL
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresSecretPersistence(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_SECRETS_TABLE);
            stmt.execute(CREATE_DEKS_TABLE);
            stmt.execute(CREATE_META_TABLE);
            for (String sql : CREATE_INDEXES.split(";")) {
                sql = sql.trim();
                if (!sql.isEmpty())
                    stmt.execute(sql);
            }
            schemaInitialized = true;
            LOGGER.info("Secrets vault PostgreSQL tables ensured");
        } catch (SQLException e) {
            throw new PersistenceException("Failed to initialize secrets vault tables", e);
        }
    }

    // ─── Secrets ───

    @Override
    public void upsertSecret(EncryptedSecret secret) {
        ensureSchema();
        String sql = """
                INSERT INTO secret_vault_secrets
                    (tenant_id, key_name, encrypted_value, iv, dek_id, checksum,
                     description, allowed_agents, created_at, last_accessed_at, last_rotated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, key_name)
                DO UPDATE SET encrypted_value = EXCLUDED.encrypted_value,
                    iv = EXCLUDED.iv, dek_id = EXCLUDED.dek_id, checksum = EXCLUDED.checksum,
                    description = EXCLUDED.description, allowed_agents = EXCLUDED.allowed_agents,
                    last_accessed_at = EXCLUDED.last_accessed_at, last_rotated_at = EXCLUDED.last_rotated_at
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, secret.getTenantId());
            ps.setString(2, secret.getKeyName());
            ps.setString(3, secret.getEncryptedValue());
            ps.setString(4, secret.getIv());
            ps.setString(5, secret.getDekId());
            ps.setString(6, secret.getChecksum());
            ps.setString(7, secret.getDescription());
            ps.setString(8, MAPPER.writeValueAsString(secret.getAllowedAgents() != null ? secret.getAllowedAgents() : List.of("*")));
            ps.setTimestamp(9, instantToTimestamp(secret.getCreatedAt()));
            ps.setTimestamp(10, instantToTimestamp(secret.getLastAccessedAt()));
            ps.setTimestamp(11, instantToTimestamp(secret.getLastRotatedAt()));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new PersistenceException("Failed to upsert secret " + secret.getTenantId() + "/" + secret.getKeyName(), e);
        }
    }

    @Override
    public Optional<EncryptedSecret> findSecret(String tenantId, String keyName) {
        ensureSchema();
        String sql = "SELECT * FROM secret_vault_secrets WHERE tenant_id = ? AND key_name = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, keyName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return Optional.of(resultSetToSecret(rs));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to find secret " + tenantId + "/" + keyName, e);
        }
        return Optional.empty();
    }

    @Override
    public boolean deleteSecret(String tenantId, String keyName) {
        ensureSchema();
        String sql = "DELETE FROM secret_vault_secrets WHERE tenant_id = ? AND key_name = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, keyName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete secret " + tenantId + "/" + keyName, e);
        }
    }

    @Override
    public List<EncryptedSecret> listSecretsByTenant(String tenantId) {
        ensureSchema();
        String sql = "SELECT * FROM secret_vault_secrets WHERE tenant_id = ? ORDER BY created_at DESC";
        List<EncryptedSecret> secrets = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    secrets.add(resultSetToSecret(rs));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list secrets for tenant " + tenantId, e);
        }
        return secrets;
    }

    // ─── DEKs ───

    @Override
    public void upsertDek(EncryptedDek dek) {
        ensureSchema();
        String sql = """
                INSERT INTO secret_vault_deks (tenant_id, encrypted_dek, iv, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id)
                DO UPDATE SET encrypted_dek = EXCLUDED.encrypted_dek, iv = EXCLUDED.iv
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dek.getTenantId());
            ps.setString(2, dek.getEncryptedDek());
            ps.setString(3, dek.getIv());
            ps.setTimestamp(4, instantToTimestamp(dek.getCreatedAt()));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new PersistenceException("Failed to upsert DEK for tenant " + dek.getTenantId(), e);
        }
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId) {
        ensureSchema();
        String sql = "SELECT * FROM secret_vault_deks WHERE tenant_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp createdTs = rs.getTimestamp("created_at");
                    return Optional.of(new EncryptedDek(rs.getString("id"), rs.getString("tenant_id"), rs.getString("encrypted_dek"),
                            rs.getString("iv"), createdTs != null ? createdTs.toInstant() : null));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to find DEK for tenant " + tenantId, e);
        }
        return Optional.empty();
    }

    @Override
    public void deleteDek(String tenantId) {
        ensureSchema();
        String sql = "DELETE FROM secret_vault_deks WHERE tenant_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete DEK for tenant " + tenantId, e);
        }
    }

    @Override
    public List<EncryptedDek> listAllDeks() {
        ensureSchema();
        String sql = "SELECT * FROM secret_vault_deks ORDER BY tenant_id";
        List<EncryptedDek> deks = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp createdTs = rs.getTimestamp("created_at");
                    deks.add(new EncryptedDek(rs.getString("id"), rs.getString("tenant_id"), rs.getString("encrypted_dek"), rs.getString("iv"),
                            createdTs != null ? createdTs.toInstant() : null));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list all DEKs", e);
        }
        return deks;
    }

    // ─── Metadata ───

    @Override
    public String getMetaValue(String key) {
        ensureSchema();
        String sql = "SELECT value FROM secret_vault_meta WHERE key = ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getString("value");
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read meta value: " + key, e);
        }
        return null;
    }

    @Override
    public void setMetaValue(String key, String value) {
        ensureSchema();
        String sql = """
                INSERT INTO secret_vault_meta (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """;
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to write meta value: " + key, e);
        }
    }

    // ─── Conversion helpers ───

    @SuppressWarnings("unchecked")
    private EncryptedSecret resultSetToSecret(ResultSet rs) throws SQLException {
        var secret = new EncryptedSecret();
        secret.setId(rs.getString("id"));
        secret.setTenantId(rs.getString("tenant_id"));
        secret.setKeyName(rs.getString("key_name"));
        secret.setEncryptedValue(rs.getString("encrypted_value"));
        secret.setIv(rs.getString("iv"));
        secret.setDekId(rs.getString("dek_id"));
        secret.setChecksum(rs.getString("checksum"));
        secret.setDescription(rs.getString("description"));

        String allowedJson = rs.getString("allowed_agents");
        if (allowedJson != null) {
            try {
                secret.setAllowedAgents(MAPPER.readValue(allowedJson, List.class));
            } catch (Exception e) {
                secret.setAllowedAgents(List.of("*"));
            }
        } else {
            secret.setAllowedAgents(List.of("*"));
        }

        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp accessedTs = rs.getTimestamp("last_accessed_at");
        Timestamp rotatedTs = rs.getTimestamp("last_rotated_at");
        secret.setCreatedAt(createdTs != null ? createdTs.toInstant() : null);
        secret.setLastAccessedAt(accessedTs != null ? accessedTs.toInstant() : null);
        secret.setLastRotatedAt(rotatedTs != null ? rotatedTs.toInstant() : null);
        return secret;
    }

    private static Timestamp instantToTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
