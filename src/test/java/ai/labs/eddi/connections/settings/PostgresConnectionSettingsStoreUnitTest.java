/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import ai.labs.eddi.connections.settings.IConnectionSettingsStore.StoredConnectionSettings;
import jakarta.enterprise.inject.Instance;
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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PostgresConnectionSettingsStore} against a mocked JDBC stack. */
@SuppressWarnings("unchecked")
class PostgresConnectionSettingsStoreUnitTest {

    private Connection connection;
    private Statement ddl;
    private PreparedStatement statement;
    private ResultSet row;
    private PostgresConnectionSettingsStore store;

    @BeforeEach
    void setUp() throws SQLException {
        Instance<DataSource> dataSources = mock(Instance.class);
        var dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        ddl = mock(Statement.class);
        statement = mock(PreparedStatement.class);
        row = mock(ResultSet.class);
        when(dataSources.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(row);
        store = new PostgresConnectionSettingsStore(dataSources);
    }

    @Test
    @DisplayName("the table is created once, not on every call")
    void schemaIsCreatedOnce() throws SQLException {
        when(row.next()).thenReturn(false);

        store.read("default");
        store.read("default");

        verify(ddl, times(1)).execute(PostgresConnectionSettingsStore.CREATE_TABLE);
    }

    @Test
    @DisplayName("no row reads as empty")
    void noRowReadsAsEmpty() throws SQLException {
        when(row.next()).thenReturn(false);

        assertTrue(store.read("default").isEmpty());
        verify(statement).setString(1, "default");
    }

    @Test
    @DisplayName("a NULL column reads as unset, and a SQL boolean false is still false")
    void nullColumnsReadAsUnset() throws SQLException {
        var array = mock(Array.class);
        when(row.next()).thenReturn(true);
        when(row.getBoolean("enabled")).thenReturn(false);
        when(row.getBoolean("allow_plaintext_remote_origins")).thenReturn(false);
        when(row.wasNull()).thenReturn(false, true);
        when(row.getString("public_base_url")).thenReturn(null);
        when(row.getArray("credential_endpoint_allowlist")).thenReturn(array);
        when(array.getArray()).thenReturn(new String[]{"https://auth.atlassian.com"});
        when(row.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-14T10:00:00Z")));
        when(row.getString("updated_by")).thenReturn("alice");

        StoredConnectionSettings stored = store.read("default").orElseThrow();

        assertEquals(false, stored.settings().getEnabled(), "false read with wasNull()=false is a stored false");
        assertNull(stored.settings().getAllowPlaintextRemoteOrigins(), "wasNull()=true is unset");
        assertNull(stored.settings().getPublicBaseUrl());
        assertEquals(List.of("https://auth.atlassian.com"), stored.settings().getCredentialEndpointAllowlist());
        assertEquals("alice", stored.updatedBy());
    }

    @Test
    @DisplayName("a write binds unset fields as SQL NULL and the allowlist as a text array")
    void writeBindsNullsAndArrays() throws SQLException {
        var array = mock(Array.class);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(array);

        store.write("default", new StoredConnectionSettings(new ConnectionSettings(null, "https://eddi.example.com", List.of("https://a.example.com"),
                true), Instant.parse("2026-09-14T10:00:00Z"), "alice"));

        verify(connection).prepareStatement(PostgresConnectionSettingsStore.UPSERT);
        verify(statement).setString(1, "default");
        verify(statement).setNull(2, Types.BOOLEAN);
        verify(statement).setString(3, "https://eddi.example.com");
        verify(statement).setArray(4, array);
        verify(statement).setBoolean(5, true);
        verify(statement).setString(7, "alice");
        verify(statement).executeUpdate();
    }

    @Test
    @DisplayName("an unset allowlist is SQL NULL, not an empty array")
    void unsetAllowlistIsNull() throws SQLException {
        store.write("default", new StoredConnectionSettings(new ConnectionSettings(), null, null));

        verify(statement).setNull(4, Types.ARRAY);
        verify(statement).setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    @DisplayName("a JDBC failure surfaces as an unchecked exception naming the tenant")
    void sqlFailureSurfaces() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("connection reset"));

        var failure = assertThrows(IllegalStateException.class, () -> store.read("default"));

        assertTrue(failure.getMessage().contains("default"), failure.getMessage());
    }
}
