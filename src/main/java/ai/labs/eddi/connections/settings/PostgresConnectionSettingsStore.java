/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IConnectionSettingsStore}: one row per
 * tenant in {@code connection_settings}. Every settings column is nullable,
 * because null is how the model says "not set".
 * <p>
 * Schema is auto-created on first access, following
 * {@code PostgresGlobalVariableStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresConnectionSettingsStore implements IConnectionSettingsStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresConnectionSettingsStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS connection_settings (
                tenant_id VARCHAR(255) PRIMARY KEY,
                enabled BOOLEAN,
                public_base_url TEXT,
                credential_endpoint_allowlist TEXT[],
                allow_plaintext_remote_origins BOOLEAN,
                updated_at TIMESTAMPTZ,
                updated_by TEXT
            )
            """;

    static final String SELECT = """
            SELECT enabled, public_base_url, credential_endpoint_allowlist, allow_plaintext_remote_origins, updated_at, updated_by
            FROM connection_settings WHERE tenant_id = ?
            """;

    static final String UPSERT = """
            INSERT INTO connection_settings
                (tenant_id, enabled, public_base_url, credential_endpoint_allowlist, allow_plaintext_remote_origins, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                public_base_url = EXCLUDED.public_base_url,
                credential_endpoint_allowlist = EXCLUDED.credential_endpoint_allowlist,
                allow_plaintext_remote_origins = EXCLUDED.allow_plaintext_remote_origins,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresConnectionSettingsStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        synchronized (this) {
            if (schemaInitialized) {
                return;
            }
            try (Connection connection = dataSourceInstance.get().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                schemaInitialized = true;
                LOGGER.info("PostgresConnectionSettingsStore initialized (table=connection_settings)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize the connection_settings table", e);
            }
        }
    }

    @Override
    public Optional<StoredConnectionSettings> read(String tenantId) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, tenantId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                var model = new ConnectionSettings(nullableBoolean(row, "enabled"), row.getString("public_base_url"),
                        stringList(row.getArray("credential_endpoint_allowlist")), nullableBoolean(row, "allow_plaintext_remote_origins"));
                Timestamp updatedAt = row.getTimestamp("updated_at");
                return Optional.of(new StoredConnectionSettings(model, updatedAt == null ? null : updatedAt.toInstant(),
                        row.getString("updated_by")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read connection settings for tenant " + tenantId, e);
        }
    }

    @Override
    public void write(String tenantId, StoredConnectionSettings stored) {
        ensureSchema();
        ConnectionSettings model = stored.settings();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, tenantId);
            setNullableBoolean(statement, 2, model.getEnabled());
            statement.setString(3, model.getPublicBaseUrl());
            if (model.getCredentialEndpointAllowlist() == null) {
                statement.setNull(4, Types.ARRAY);
            } else {
                statement.setArray(4, connection.createArrayOf("text", model.getCredentialEndpointAllowlist().toArray(new String[0])));
            }
            setNullableBoolean(statement, 5, model.getAllowPlaintextRemoteOrigins());
            if (stored.updatedAt() == null) {
                statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setTimestamp(6, Timestamp.from(stored.updatedAt()));
            }
            statement.setString(7, stored.updatedBy());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write connection settings for tenant " + tenantId, e);
        }
    }

    private static Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
    }

    private static List<String> stringList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object values = array.getArray();
            if (values instanceof String[] strings) {
                return List.copyOf(Arrays.asList(strings));
            }
            if (values instanceof Object[] objects) {
                return Arrays.stream(objects).map(String::valueOf).toList();
            }
            return List.of();
        } finally {
            array.free();
        }
    }
}
