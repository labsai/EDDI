/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresGlobalVariableStore} — JDBC adapter. Uses
 * mocked {@link DataSource} / {@link Connection} / {@link PreparedStatement} to
 * verify SQL operations without a running PostgreSQL instance.
 */
@SuppressWarnings("unchecked")
class PostgresGlobalVariableStoreTest {

    private Connection connection;
    private PostgresGlobalVariableStore store;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);

        // Schema creation statement
        Statement stmt = mock(Statement.class);
        when(connection.createStatement()).thenReturn(stmt);

        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);

        store = new PostgresGlobalVariableStore(instance);
    }

    // ==================== getAll ====================

    @Test
    @DisplayName("getAll — returns empty map when no rows")
    void getAllEmpty() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("SELECT key, value FROM"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Map<String, String> result = store.getAll();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAll — returns key-value pairs")
    void getAllWithRows() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("SELECT key, value FROM"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("key")).thenReturn("model", "temp");
        when(rs.getString("value")).thenReturn("gpt-4.1", "0.7");

        Map<String, String> result = store.getAll();

        assertEquals(2, result.size());
        assertEquals("gpt-4.1", result.get("model"));
        assertEquals("0.7", result.get("temp"));
    }

    // ==================== get ====================

    @Test
    @DisplayName("get — returns GlobalVariable when found")
    void getFound() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE key = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("key")).thenReturn("model");
        when(rs.getString("value")).thenReturn("gpt-4.1");
        when(rs.getString("description")).thenReturn("Default model");
        when(rs.getBoolean("exportable")).thenReturn(true);

        GlobalVariable result = store.get("model");

        assertNotNull(result);
        assertEquals("model", result.key());
        assertEquals("gpt-4.1", result.value());
        assertEquals("Default model", result.description());
        assertTrue(result.exportable());
        verify(ps).setString(1, "model");
    }

    @Test
    @DisplayName("get — returns null when not found")
    void getNotFound() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE key = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(store.get("missing"));
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — executes INSERT ON CONFLICT with correct params")
    void upsert() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("ON CONFLICT"))).thenReturn(ps);

        store.upsert(new GlobalVariable("model", "gpt-4.1", "Default", true));

        verify(ps).setString(1, "model");
        verify(ps).setString(2, "gpt-4.1");
        verify(ps).setString(3, "Default");
        verify(ps).setBoolean(4, true);
        verify(ps).executeUpdate();
    }

    @Test
    @DisplayName("upsert — handles null description")
    void upsertNullDescription() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("ON CONFLICT"))).thenReturn(ps);

        store.upsert(new GlobalVariable("key", "val", null, false));

        verify(ps).setString(3, null);
        verify(ps).setBoolean(4, false);
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — executes DELETE with correct key")
    void delete() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("DELETE FROM"))).thenReturn(ps);

        store.delete("model");

        verify(ps).setString(1, "model");
        verify(ps).executeUpdate();
    }

    // ==================== listAll ====================

    @Test
    @DisplayName("listAll — returns empty list when no rows")
    void listAllEmpty() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("SELECT key, value, description"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        List<GlobalVariable> result = store.listAll();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listAll — returns GlobalVariable objects")
    void listAllWithRows() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("SELECT key, value, description"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("key")).thenReturn("a", "b");
        when(rs.getString("value")).thenReturn("1", "2");
        when(rs.getString("description")).thenReturn("desc-a", null);
        when(rs.getBoolean("exportable")).thenReturn(true, false);

        List<GlobalVariable> result = store.listAll();

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).key());
        assertEquals("1", result.get(0).value());
        assertTrue(result.get(0).exportable());
        assertEquals("b", result.get(1).key());
        assertFalse(result.get(1).exportable());
    }

    // ==================== error handling ====================

    @Test
    @DisplayName("getAll — returns empty map on SQLException")
    void getAllSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("test error"));

        Map<String, String> result = store.getAll();

        assertTrue(result.isEmpty()); // graceful degradation, not exception
    }

    @Test
    @DisplayName("get — returns null on SQLException")
    void getSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("test error"));

        assertNull(store.get("key")); // graceful degradation
    }

    @Test
    @DisplayName("upsert — swallows SQLException gracefully")
    void upsertSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("test error"));

        // Should not throw
        assertDoesNotThrow(() -> store.upsert(new GlobalVariable("k", "v")));
    }

    @Test
    @DisplayName("delete — swallows SQLException gracefully")
    void deleteSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("test error"));

        assertDoesNotThrow(() -> store.delete("key"));
    }

    @Test
    @DisplayName("listAll — returns empty list on SQLException")
    void listAllSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("test error"));

        List<GlobalVariable> result = store.listAll();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("ensureSchema — only creates table once (idempotent)")
    void ensureSchemaIdempotent() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // Call getAll twice — ensureSchema should only execute CREATE TABLE once
        store.getAll();
        store.getAll();

        // createStatement() is called once in setUp's ensureSchema,
        // so verify it's not called again
        verify(connection, atMost(1)).createStatement();
    }
}
